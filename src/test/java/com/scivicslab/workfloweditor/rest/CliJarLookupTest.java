package com.scivicslab.workfloweditor.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which of the two jars turing-workflow installs is the one a person can run.
 *
 * <p>Since it became both a library and a CLI, the repository holds
 * {@code turing-workflow-3.7.0.jar} (classes only, no Main-Class, no bundled dependencies) beside
 * {@code turing-workflow-3.7.0-shaded.jar} (runnable). Picking by highest name takes the first,
 * because {@code .} sorts after {@code -}, and the command the UI shows would not start.
 */
@DisplayName("CLI jar lookup — picking the runnable jar out of the repository")
class CliJarLookupTest {

    private static void touch(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), "");
    }

    /** Names the plain jar sorts above, which is what the lookup used to return. */
    @Test
    void highestNameIsThePlainJar(@TempDir Path repo) throws Exception {
        Path v = repo.resolve("3.7.0");
        touch(v, "turing-workflow-3.7.0.jar");
        touch(v, "turing-workflow-3.7.0-shaded.jar");

        try (var s = Files.walk(repo, 2)) {
            String highest = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("turing-workflow-") && n.endsWith(".jar"))
                    .max(Comparator.naturalOrder()).orElseThrow();
            assertEquals("turing-workflow-3.7.0.jar", highest);
        }
    }

    @Test
    void lookupReturnsTheShadedJar(@TempDir Path repo) throws Exception {
        Path v = repo.resolve("3.7.0");
        touch(v, "turing-workflow-3.7.0.jar");
        touch(v, "turing-workflow-3.7.0-shaded.jar");
        touch(v, "turing-workflow-3.7.0-sources.jar");
        touch(v, "turing-workflow-3.7.0-javadoc.jar");

        String found = WorkflowApiResource.findRunnableJarIn(repo, "turing-workflow");

        assertEquals(v.resolve("turing-workflow-3.7.0-shaded.jar").toString(), found);
    }

    /** Several versions installed: the newest runnable one. */
    @Test
    void prefersTheNewestVersion(@TempDir Path repo) throws Exception {
        touch(repo.resolve("3.6.1"), "turing-workflow-3.6.1-shaded.jar");
        touch(repo.resolve("3.7.0"), "turing-workflow-3.7.0-shaded.jar");

        String found = WorkflowApiResource.findRunnableJarIn(repo, "turing-workflow");

        assertEquals(repo.resolve("3.7.0").resolve("turing-workflow-3.7.0-shaded.jar").toString(), found);
    }

    /** Nothing runnable installed: say so with a name rather than a path that does not exist. */
    @Test
    void fallsBackWhenNothingRunnableIsInstalled(@TempDir Path repo) throws Exception {
        touch(repo.resolve("3.7.0"), "turing-workflow-3.7.0.jar");

        assertEquals("turing-workflow.jar",
                WorkflowApiResource.findRunnableJarIn(repo, "turing-workflow"));
    }
}
