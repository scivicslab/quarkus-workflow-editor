package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E regression test: arguments values starting with '[' must execute without
 * YAML parse errors.
 *
 * Bug: toYamlStructured() was emitting bracket-prefixed plain strings without
 * quotes, causing the Turing Workflow runtime to misparse them as YAML sequences.
 *
 * Flow under test:
 *   1. Import YAML containing arguments: "[batch 1/4] ..." (quoted at import)
 *   2. Run the workflow (triggers toYamlStructured() internally)
 *   3. Verify: 'completed' log entry appears, no 'Execution failed' error entry
 */
public class BracketArgsExecutionE2E {

    private final Page page;
    private final String url;

    public BracketArgsExecutionE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("BracketArgsExecution: start");

        bracketPrefixedArgs_workflowCompletesWithoutParseError();
        curlyBracePrefixedArgs_workflowCompletesWithoutParseError();

        System.out.println("BracketArgsExecution: PASSED");
    }

    // ---- scenarios ---------------------------------------------------------

    private void bracketPrefixedArgs_workflowCompletesWithoutParseError() {
        String yaml = """
                name: bracket-args-test
                steps:
                - states: ["0", "1"]
                  actions:
                  - actor: out
                    method: print
                    arguments: "[batch 1/4] Reading tutorial files ..."
                - states: ["1", "2"]
                  actions:
                  - actor: out
                    method: print
                    arguments: "[batch 2/4] Reading more files ..."
                - states: ["2", "end"]
                  actions:
                  - actor: out
                    method: print
                    arguments: done
                - states: ["!end", "end"]
                  actions:
                  - actor: out
                    method: error
                    arguments: Ended unexpectedly
                """;

        setupAndRun(yaml);

        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry.completed') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        assertTrue("bracketArgs: completed entry in log",
                page.locator("#logOutput .log-entry.completed").count() > 0);

        assertNoExecutionError("bracketArgs");

        System.out.println("  bracketPrefixedArgs_workflowCompletesWithoutParseError: PASSED");
    }

    private void curlyBracePrefixedArgs_workflowCompletesWithoutParseError() {
        String yaml = """
                name: curly-args-test
                steps:
                - states: ["0", "1"]
                  actions:
                  - actor: out
                    method: print
                    arguments: "{not-json} some plain text"
                - states: ["1", "end"]
                  actions:
                  - actor: out
                    method: print
                    arguments: done
                - states: ["!end", "end"]
                  actions:
                  - actor: out
                    method: error
                    arguments: Ended unexpectedly
                """;

        setupAndRun(yaml);

        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry.completed') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        assertTrue("curlyArgs: completed entry in log",
                page.locator("#logOutput .log-entry.completed").count() > 0);

        assertNoExecutionError("curlyArgs");

        System.out.println("  curlyBracePrefixedArgs_workflowCompletesWithoutParseError: PASSED");
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Loads one workflow into the editor and starts it.
     *
     * <p>The YAML is posted to the import endpoint rather than chosen from a file dialog:
     * Import YAML opens the catalog now, so waiting for a file chooser waits for ever. The
     * endpoint is the same one that button's catalog import ends up calling, and the same one
     * {@link E2ERunner} uses to put the editor into a known state.
     *
     * <p>There is no #runBtn to press first. The run panel is open from the start, and
     * #paramExecute is its Start button.
     */
    private void setupAndRun(String yaml) {
        importYaml(yaml);
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        page.selectOption("#logLevelSelect", "FINE");
        page.waitForTimeout(300);

        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");
        page.click("#paramExecute");
    }

    private void importYaml(String yaml) {
        try {
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url + "/api/yaml/import"))
                            .header("Content-Type", "text/plain")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(yaml))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200)
                throw new AssertionError("import returned HTTP " + r.statusCode() + ": " + r.body());
        } catch (Exception e) {
            throw new RuntimeException("could not import the workflow", e);
        }
    }

    private void assertNoExecutionError(String label) {
        // Check that no log entry contains "Execution failed" or "while parsing"
        String logText = page.locator("#logOutput").innerText();
        assertFalse(label + ": must not contain 'Execution failed'",
                logText.contains("Execution failed"));
        assertFalse(label + ": must not contain 'while parsing'",
                logText.contains("while parsing"));
    }

    private static Path writeTempYaml(String yaml) {
        try {
            Path tmp = Files.createTempFile("e2e-bracket-", ".yaml");
            Files.writeString(tmp, yaml);
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException("could not create temp YAML file", e);
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) {
            throw new AssertionError(label + ": expected true");
        }
    }

    private static void assertFalse(String label, boolean condition) {
        if (condition) {
            throw new AssertionError(label + ": expected false but was true");
        }
    }
}
