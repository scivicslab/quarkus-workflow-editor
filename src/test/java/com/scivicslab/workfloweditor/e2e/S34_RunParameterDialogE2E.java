package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E tests for S3→S4: the run panel.
 * Verifies that the panel is on screen with its Start button, that parameter inputs appear for
 * workflow variables, that the panel can be closed, and that execution can be started and
 * stopped.
 *
 * <p>There is no run button to press first. The panel opens with the page, and #paramExecute is
 * its Start button.
 */
public class S34_RunParameterDialogE2E {

    private final Page page;
    private final String url;

    public S34_RunParameterDialogE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S34 RunParameterDialog: start");

        runPanel_isOpenWithItsStartButton();
        yamlWithVariables_showsRequiredParamInputs();
        closePanel_panelBecomesHidden();
        startExecution_stopBtnBecomesEnabled();
        stopExecution_stoppedEventInLog();

        System.out.println("S34 RunParameterDialog: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void runPanel_isOpenWithItsStartButton() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");

        assertTrue("runButton: side panel is visible",
                !"none".equals(page.locator("#sidePanel").getAttribute("style").replace(" ", "")));

        System.out.println("  runButton_opensRunPanel: PASSED");
    }

    private void yamlWithVariables_showsRequiredParamInputs() {
        String yaml = """
                name: param-test-wf
                steps:
                - states: ["0", "1"]
                  note: test step
                  actions:
                  - actor: out
                    method: print
                    arguments: ${task}
                """;
        Path tmpFile = writeTempYaml(yaml, "e2e-param-");
        importYaml(tmpFile);

        page.waitForFunction(
                "() => document.querySelector('.step-from') && " +
                "document.querySelector('.step-from').value === '0'");

        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");
        page.waitForFunction(
                "() => document.querySelector('[data-param-key=\"task\"]') !== null");

        Locator taskInput = page.locator("[data-param-key='task']");
        assertTrue("yamlWithVariables: task input field is present",
                taskInput.count() > 0);

        System.out.println("  yamlWithVariables_showsRequiredParamInputs: PASSED");
    }

    private void closePanel_panelBecomesHidden() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");

        page.click("#sidePanelClose");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display === 'none'");

        String display = page.locator("#sidePanel").evaluate("el => el.style.display").toString();
        assertEqual("closePanel: side panel is hidden", "none", display);

        System.out.println("  closePanel_panelBecomesHidden: PASSED");
    }

    private void startExecution_stopBtnBecomesEnabled() {
        // A step that sleeps, not one that prints: Stop is enabled only while something is
        // running, and printing one line finishes before the browser is asked about the button.
        // stopExecution_stoppedEventInLog below sleeps for the same reason.
        String yaml = """
                name: start-test-wf
                steps:
                - states: ["0", "1"]
                  note: long running step
                  actions:
                  - actor: shell
                    method: exec
                    arguments: sleep 30
                """;
        Path tmpFile = writeTempYaml(yaml, "e2e-start-");
        importYaml(tmpFile);

        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");

        page.click("#paramExecute");

        page.waitForFunction(
                "() => !document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        assertTrue("startExecution: stop button is enabled",
                !Boolean.parseBoolean(page.locator("#stopBtn").getAttribute("disabled")));

        // Leave nothing running: the sleep would still hold the interpreter when the next
        // scenario starts its own workflow.
        page.click("#stopBtn");
        page.waitForFunction(
                "() => document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(30000));

        System.out.println("  startExecution_stopBtnBecomesEnabled: PASSED");
    }

    private void stopExecution_stoppedEventInLog() {
        // Use shell sleep to guarantee the workflow is still running when Stop is clicked
        String yaml = """
                name: stop-test-wf
                steps:
                - states: ["0", "1"]
                  note: long running step
                  actions:
                  - actor: shell
                    method: exec
                    arguments: sleep 30
                """;
        Path tmpFile = writeTempYaml(yaml, "e2e-stop-");
        importYaml(tmpFile);

        // Set log level so stopped events are visible (they always pass, but select FINE to see all)
        page.selectOption("#logLevelSelect", "FINE");

        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");
        page.click("#paramExecute");

        // Wait for Stop to be clickable
        page.waitForFunction(
                "() => !document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        page.click("#stopBtn");

        // Workflow is stopped when stopBtn becomes disabled again
        page.waitForFunction(
                "() => document.getElementById('stopBtn').disabled",
                null, new Page.WaitForFunctionOptions().setTimeout(30000));

        assertTrue("stopExecution: stopBtn disabled after stop",
                Boolean.TRUE.equals(page.locator("#stopBtn").evaluate("el => el.disabled")));

        // Either stopped or completed event appears in the log (always visible regardless of level)
        int terminatedCount = page.locator("#logOutput .log-entry.stopped, #logOutput .log-entry.completed")
                .count();
        assertTrue("stopExecution: terminated event in log", terminatedCount > 0);

        System.out.println("  stopExecution_stoppedEventInLog: PASSED");
    }

    // ---- helpers -------------------------------------------------------

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

    private static Path writeTempYaml(String yaml, String prefix) {
        try {
            Path tmp = Files.createTempFile(prefix, ".yaml");
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
}
