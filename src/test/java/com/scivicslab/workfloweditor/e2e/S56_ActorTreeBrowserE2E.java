package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

/**
 * E2E tests for S5→S6: Actor Tree Browser.
 * Migrated from e2e/actor-tree.spec.js. Covers Actor Tree and Plugins browser
 * panels. Note: all sidebar buttons are inside the Sidebar dropdown menu.
 */
public class S56_ActorTreeBrowserE2E {

    private final Page page;
    private final String url;

    public S56_ActorTreeBrowserE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S56 ActorTreeBrowser: start");

        // Actor Tree
        sidePanelTabs_areOnScreen();
        panelOpensOnTreeBtnClick();
        fiveStandardActorsShown();
        shellActor_showsExecAction();
        interpreterActor_showsStatusLabel();
        loaderActor_showsLoadJarAndCreateChild();
        panelClosesWithXButton();

        // Plugins Browser
        pluginsPanel_opensAndShowsItems();

        // Side panel tabs
        switchingTabs_changesPanelContent();

        System.out.println("S56 ActorTreeBrowser: PASSED");
    }

    // ---- Actor Tree scenarios ------------------------------------------

    /**
     * The side panel used to be reached through a menu button, #sidebarMenuBtn, whose items
     * included #treeBtn. It is now always on screen, and its views are chosen by the tabs in its
     * header, so both of those ids are gone.
     */
    private void sidePanelTabs_areOnScreen() {
        page.navigate(url);
        page.waitForSelector("#sidePanel");

        assertTrue("sidePanel: the panel is visible", page.locator("#sidePanel").isVisible());
        assertEqual("side-tab: the actors tab is labelled 'Actors'",
                "Actors", page.locator(".side-tab[data-tab='actors']").textContent().trim());
        assertEqual("side-tab: four views to choose from", 4,
                page.locator(".side-tab").count());

        System.out.println("  sidePanelTabs_areOnScreen: PASSED");
    }

    private void panelOpensOnTreeBtnClick() {
        page.navigate(url);

        page.click(".side-tab[data-tab='actors']");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");

        assertTrue("panelOpensOnClick: side panel is visible",
                page.locator("#sidePanel").isVisible());

        System.out.println("  panelOpensOnTreeBtnClick: PASSED");
    }

    private void fiveStandardActorsShown() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");

        page.waitForFunction(
                "() => document.querySelectorAll('#actorTreeBody .tree-node').length === 5",
                null, new Page.WaitForFunctionOptions().setTimeout(10000));

        assertEqual("fiveActors: exactly 5 tree nodes", 5,
                page.locator("#actorTreeBody .tree-node").count());

        System.out.println("  fiveStandardActorsShown: PASSED");
    }

    private void shellActor_showsExecAction() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("shell"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        assertTrue("shellActor: data panel visible",
                page.locator("#actorDataPanel").isVisible());
        assertTrue("shellActor: exec action tag visible",
                page.locator("#actorDataContent .actor-action-tag")
                        .filter(new Locator.FilterOptions().setHasText("exec"))
                        .isVisible());

        System.out.println("  shellActor_showsExecAction: PASSED");
    }

    private void interpreterActor_showsStatusLabel() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("interpreter"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        assertTrue("interpreterActor: Status label visible",
                page.locator("#actorDataContent .actor-section-label")
                        .filter(new Locator.FilterOptions().setHasText("Status"))
                        .isVisible());

        System.out.println("  interpreterActor_showsStatusLabel: PASSED");
    }

    private void loaderActor_showsLoadJarAndCreateChild() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("loader"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        assertTrue("loaderActor: loadJar action tag visible",
                page.locator("#actorDataContent .actor-action-tag")
                        .filter(new Locator.FilterOptions().setHasText("loadJar"))
                        .isVisible());
        assertTrue("loaderActor: createChild action tag visible",
                page.locator("#actorDataContent .actor-action-tag")
                        .filter(new Locator.FilterOptions().setHasText("createChild"))
                        .isVisible());

        System.out.println("  loaderActor_showsLoadJarAndCreateChild: PASSED");
    }

    private void panelClosesWithXButton() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");

        page.click("#sidePanelClose");
        page.waitForFunction("() => document.getElementById('sidePanel').style.display === 'none'");

        assertTrue("panelClosesWithX: side panel hidden",
                !page.locator("#sidePanel").isVisible());

        System.out.println("  panelClosesWithXButton: PASSED");
    }

    // ---- Plugins Browser scenarios -------------------------------------

    private void pluginsPanel_opensAndShowsItems() {
        page.navigate(url);
        page.click(".side-tab[data-tab='plugins']");

        page.waitForFunction("() => document.getElementById('sidePanel').style.display !== 'none'");
        page.waitForFunction("() => document.getElementById('sidePanelPlugins').style.display !== 'none'");

        // Wait for at least one browse item to appear
        page.waitForSelector("#pluginsBody .browse-item",
                new Page.WaitForSelectorOptions().setTimeout(10000));

        assertTrue("pluginsPanel: at least one browse item",
                page.locator("#pluginsBody .browse-item").count() > 0);
        assertTrue("pluginsPanel: Load button visible",
                page.locator("#pluginsBody .browse-item-actions button")
                        .filter(new Locator.FilterOptions().setHasText("Load"))
                        .first().isVisible());

        System.out.println("  pluginsPanel_opensAndShowsItems: PASSED");
    }

    // ---- Side panel tab switching -------------------------------------

    private void switchingTabs_changesPanelContent() {
        page.navigate(url);

        // Open Actors panel via sidebar menu
        page.click(".side-tab[data-tab='actors']");
        page.waitForFunction("() => document.getElementById('sidePanelActors').style.display !== 'none'");

        assertTrue("switchTabs: actors panel visible after the Actors tab",
                page.locator("#sidePanelActors").isVisible());

        // Switch to Plugins tab via side-tab inside the panel
        page.locator(".side-tab[data-tab='plugins']").click();
        page.waitForFunction("() => document.getElementById('sidePanelPlugins').style.display !== 'none'");

        assertTrue("switchTabs: plugins panel visible", page.locator("#sidePanelPlugins").isVisible());
        assertTrue("switchTabs: actors panel hidden", !page.locator("#sidePanelActors").isVisible());

        // Switch to Run tab
        page.locator(".side-tab[data-tab='run']").click();
        page.waitForFunction("() => document.getElementById('sidePanelRun').style.display !== 'none'");

        assertTrue("switchTabs: run panel visible", page.locator("#sidePanelRun").isVisible());
        assertTrue("switchTabs: plugins panel hidden", !page.locator("#sidePanelPlugins").isVisible());

        System.out.println("  switchingTabs_changesPanelContent: PASSED");
    }

    // ---- helpers -------------------------------------------------------

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
