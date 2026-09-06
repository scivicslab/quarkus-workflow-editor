package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E tests for S4→S5: SSE Log Display.
 * Verifies that log entries appear in real time, completed/output events are
 * recorded, log level filtering works, and the Clear button empties the log.
 */
public class S45_SseLogDisplayE2E {

    private final Page page;
    private final String url;

    public S45_SseLogDisplayE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S45 SseLogDisplay: start");

        completedEvent_appearsAfterWorkflowFinishes();
        outputEvent_appearsForOutPrint();
        logLevelOff_stepAndInfoSuppressed();
        logLevelFine_fineEventsShown();
        clearLog_logAreaBecomesEmpty();

        System.out.println("S45 SseLogDisplay: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void completedEvent_appearsAfterWorkflowFinishes() {
        String yaml = """
                name: completed-test-wf
                steps:
                - states: ["0", "1"]
                  note: simple step
                  actions:
                  - actor: out
                    method: print
                    arguments: done
                - states: ["1", "end"]
                  note: finish
                  actions:
                  - actor: log
                    method: info
                    arguments: done
                """;
        setupAndRun(yaml, "FINE");

        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry.completed') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        assertTrue("completedEvent: completed entry in log",
                page.locator("#logOutput .log-entry.completed").count() > 0);

        System.out.println("  completedEvent_appearsAfterWorkflowFinishes: PASSED");
    }

    private void outputEvent_appearsForOutPrint() {
        String yaml = """
                name: output-test-wf
                steps:
                - states: ["0", "1"]
                  note: print step
                  actions:
                  - actor: out
                    method: print
                    arguments: hello-e2e
                - states: ["1", "end"]
                  note: finish
                  actions:
                  - actor: log
                    method: info
                    arguments: done
                """;
        setupAndRun(yaml, "FINE");

        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry.output') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        assertTrue("outputEvent: output entry in log",
                page.locator("#logOutput .log-entry.output").count() > 0);

        System.out.println("  outputEvent_appearsForOutPrint: PASSED");
    }

    private void logLevelOff_stepAndInfoSuppressed() {
        String yaml = """
                name: level-off-wf
                steps:
                - states: ["0", "1"]
                  note: step to suppress
                  actions:
                  - actor: out
                    method: print
                    arguments: off-test
                - states: ["1", "end"]
                  note: finish
                  actions:
                  - actor: log
                    method: info
                    arguments: done
                """;
        setupAndRun(yaml, "OFF");

        // completed always passes through even at level OFF
        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry.completed') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        int fineCount = page.locator("#logOutput .log-entry.fine").count();
        int infoCount = page.locator("#logOutput .log-entry.info").count();
        assertEqual("logLevelOff: no fine entries", 0, fineCount);
        assertEqual("logLevelOff: no info entries", 0, infoCount);

        System.out.println("  logLevelOff_stepAndInfoSuppressed: PASSED");
    }

    private void logLevelFine_fineEventsShown() {
        String yaml = """
                name: level-fine-wf
                steps:
                - states: ["0", "1"]
                  note: step to show
                  actions:
                  - actor: out
                    method: print
                    arguments: fine-test
                - states: ["1", "end"]
                  note: finish
                  actions:
                  - actor: log
                    method: info
                    arguments: done
                """;
        setupAndRun(yaml, "FINE");

        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry.fine') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        assertTrue("logLevelFine: fine entries appear",
                page.locator("#logOutput .log-entry.fine").count() > 0);

        System.out.println("  logLevelFine_fineEventsShown: PASSED");
    }

    private void clearLog_logAreaBecomesEmpty() {
        String yaml = """
                name: clear-test-wf
                steps:
                - states: ["0", "1"]
                  note: step
                  actions:
                  - actor: out
                    method: print
                    arguments: clear-me
                - states: ["1", "end"]
                  note: finish
                  actions:
                  - actor: log
                    method: info
                    arguments: done
                """;
        setupAndRun(yaml, "FINE");

        // Wait for at least one log entry to appear
        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry') !== null",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));

        page.click("#clearLogBtn");

        page.waitForFunction(
                "() => document.querySelector('#logOutput .log-entry') === null");

        assertEqual("clearLog: log output is empty", 0,
                page.locator("#logOutput .log-entry").count());

        System.out.println("  clearLog_logAreaBecomesEmpty: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    private void setupAndRun(String yaml, String logLevel) {
        Path tmpFile = writeTempYaml(yaml);
        importYaml(tmpFile);

        page.selectOption("#logLevelSelect", logLevel);
        // Wait for log level to be applied to the server
        page.waitForTimeout(500);

        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");
        page.click("#paramExecute");
    }

    private static Path writeTempYaml(String yaml) {
        try {
            Path tmp = Files.createTempFile("e2e-log-", ".yaml");
            Files.writeString(tmp, yaml);
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException("could not create temp YAML file", e);
        }
    }

    private static void assertEqual(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) {
            throw new AssertionError(label + ": expected true");
        }
    }

    /**
     * Puts one workflow into the editor.
     *
     * <p>Posted to the import endpoint rather than chosen from a file dialog: Import YAML opens
     * the catalog now, so waiting for a file chooser waits for ever. This is the same endpoint
     * the catalog import ends up calling.
     */
    private void importYaml(Path file) {
        try {
            String yaml = Files.readString(file);
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
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");
    }
}
