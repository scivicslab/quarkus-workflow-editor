package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

import java.nio.file.Files;

/**
 * E2E tests for S1→S2: YAML I/O.
 * Verifies Export (download) and New workflow creation.
 *
 * <p>Importing was here too, as a file chooser: clicking Import YAML used to open one, and the
 * chosen file's contents were expected in the step table. The button now opens the catalog
 * instead, so waiting for a file chooser waits for ever. Bringing a YAML that is on disk into
 * the editor is what {@link S78_CatalogImportE2E} covers, through the catalog the button now
 * opens, so it is not repeated here.
 */
public class S12_YamlIOE2E {

    private final Page page;
    private final String url;

    public S12_YamlIOE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S12 YamlIO: start");
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        export_downloadedFileContainsExpectedYaml();
        newWorkflow_tableResetToTemplate();

        System.out.println("S12 YamlIO: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void export_downloadedFileContainsExpectedYaml() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        // Enter known content into the first step
        Locator firstGroup = page.locator(".step-group").first();
        firstGroup.locator(".step-from").fill("alpha");
        firstGroup.locator(".step-to").fill("beta");
        firstGroup.locator(".step-note-input").fill("export-test-note");

        // Open File menu, then click Export (Save As)
        openFileMenu();
        Download download = page.waitForDownload(() -> page.click("#exportYamlBtn"));

        String filename = download.suggestedFilename();
        assertTrue("export: filename ends with .yaml", filename.endsWith(".yaml"));

        String content;
        try {
            content = Files.readString(download.path());
        } catch (Exception e) {
            throw new RuntimeException("export: could not read downloaded file", e);
        }

        assertTrue("export: YAML contains 'name:'", content.contains("name:"));
        assertTrue("export: YAML contains 'steps:'", content.contains("steps:"));
        assertTrue("export: YAML contains from value 'alpha'", content.contains("alpha"));
        assertTrue("export: YAML contains to value 'beta'", content.contains("beta"));
        assertTrue("export: YAML contains note", content.contains("export-test-note"));

        System.out.println("  export_downloadedFileContainsExpectedYaml: PASSED");
    }

    private void newWorkflow_tableResetToTemplate() {
        page.navigate(url);
        page.waitForSelector("#stepsContainer .step-group");

        // Handle the prompt() dialog (one-shot handler to avoid accumulation across scenarios)
        acceptNextDialog("my-new-workflow");

        // Open File menu, then click New
        openFileMenu();
        page.click("#newWorkflowBtn");

        page.waitForFunction(
                "() => document.querySelector('.step-from') && " +
                "document.querySelector('.step-from').value === '0'");

        Locator firstGroup = page.locator(".step-group").first();
        assertEqual("newWorkflow: from is '0'", "0",
                firstGroup.locator(".step-from").inputValue());
        assertEqual("newWorkflow: to is '1'", "1",
                firstGroup.locator(".step-to").inputValue());

        System.out.println("  newWorkflow_tableResetToTemplate: PASSED");
    }

    // ---- helpers -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void acceptNextDialog(String answer) {
        java.util.function.Consumer<Dialog>[] ref = new java.util.function.Consumer[1];
        ref[0] = dialog -> {
            page.offDialog(ref[0]);
            dialog.accept(answer);
        };
        page.onDialog(ref[0]);
    }

    private void openFileMenu() {
        page.click("#fileMenuBtn");
        page.waitForFunction("() => document.getElementById('fileMenu').style.display !== 'none'");
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
