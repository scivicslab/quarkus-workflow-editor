package com.scivicslab.workfloweditor.e2e;

import com.microsoft.playwright.*;

/**
 * E2E tests for S6→S7: Actor Action Discovery.
 * Verifies that actions with Javadoc URLs show docs links, that the link
 * format is correct, and that built-in actions without Javadoc show no link.
 */
public class S67_ActorActionDiscoveryE2E {

    private final Page page;
    private final String url;

    public S67_ActorActionDiscoveryE2E(Page page, String url) {
        this.page = page;
        this.url = url;
    }

    public void run() {
        System.out.println("S67 ActorActionDiscovery: start");

        docsLinkExists_forActorWithJavadoc();
        docsLinkFormat_loadJarHref();
        builtinAction_putJson_hasNoDocsLink();

        System.out.println("S67 ActorActionDiscovery: PASSED");
    }

    // ---- scenarios -----------------------------------------------------

    private void docsLinkExists_forActorWithJavadoc() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForSelector("#actorTreeBody .tree-node");

        // loader has Javadoc-backed actions (loadJar, createChild)
        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("loader"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");
        page.waitForSelector("#actorDataContent .actor-action-tag");

        assertTrue("docsLinkExists: at least one docs link in loader panel",
                page.locator("#actorDataContent .actor-action-tag.actor-action-link").count() > 0);

        System.out.println("  docsLinkExists_forActorWithJavadoc: PASSED");
    }

    private void docsLinkFormat_loadJarHref() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForSelector("#actorTreeBody .tree-node");

        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("loader"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");

        page.waitForFunction(
                "() => document.querySelector('#actorDataContent .actor-action-link') !== null");

        Locator loadJarLink = page.locator("#actorDataContent .actor-action-tag.actor-action-link")
                .filter(new Locator.FilterOptions().setHasText("loadJar"));

        String href = loadJarLink.getAttribute("href");
        assertTrue("docsLinkFormat: loadJar href is not null", href != null && !href.isEmpty());
        assertTrue("docsLinkFormat: href starts with https://", href.startsWith("https://"));
        assertTrue("docsLinkFormat: href contains .html#loadJar(", href.contains(".html#loadJar("));

        System.out.println("  docsLinkFormat_loadJarHref: PASSED");
    }

    private void builtinAction_putJson_hasNoDocsLink() {
        page.navigate(url);
        page.click(".side-tab[data-tab='actors']");
        page.waitForSelector("#actorTreeBody .tree-node");

        // interpreter has putJson as a built-in action without Javadoc
        page.locator("#actorTreeBody .tree-node")
                .filter(new Locator.FilterOptions().setHasText("interpreter"))
                .click();

        page.waitForFunction("() => document.getElementById('actorDataPanel').style.display !== 'none'");
        page.waitForSelector("#actorDataContent .actor-action-tag");

        // putJson should appear as a span tag, not an anchor link
        Locator putJsonTag = page.locator("#actorDataContent .actor-action-tag")
                .filter(new Locator.FilterOptions().setHasText("putJson"));
        assertTrue("builtinAction: putJson tag is present", putJsonTag.count() > 0);

        // It must NOT have the actor-action-link class (i.e. it is a span, not an anchor)
        Locator putJsonLink = page.locator("#actorDataContent .actor-action-tag.actor-action-link")
                .filter(new Locator.FilterOptions().setHasText("putJson"));
        assertEqual("builtinAction: putJson has no docs link", 0, putJsonLink.count());

        System.out.println("  builtinAction_putJson_hasNoDocsLink: PASSED");
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
