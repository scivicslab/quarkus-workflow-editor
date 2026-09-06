package com.scivicslab.workfloweditor.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.pojoactor.distributed.RemoteActorIIAR;

/**
 * Whether a workflow can name an actor in a child process.
 *
 * <p>Nothing is sent: resolving a name builds the proxy, and building it opens no connection.
 * That is what makes this a unit test — the child does not have to be running for the editor to
 * know where the name would go.
 */
@DisplayName("WorkflowRunner — naming an actor in a child process")
class WorkflowRunnerChildNodeTest {

    private static WorkflowRunner runnerWith(Optional<List<String>> childNodes) {
        WorkflowRunner runner = new WorkflowRunner();
        runner.childNodes = new ChildNodeRegistry(childNodes);
        runner.init();
        return runner;
    }

    @Test
    void aNameQualifiedByAConfiguredNodeResolvesToAProxy() {
        WorkflowRunner runner = runnerWith(Optional.of(List.of("chat-ui=127.0.0.1:28030")));

        IIActorRef<?> actor = runner.getSystem().getIIActor("chat-ui:project1/chat-01.chat");

        assertNotNull(actor, "the workflow must be able to name it");
        assertTrue(actor instanceof RemoteActorIIAR, actor.getClass().getName());
    }

    /** The editor's own actors keep answering to their own names. */
    @Test
    void localActorsAreUnaffected() {
        WorkflowRunner runner = runnerWith(Optional.of(List.of("chat-ui=127.0.0.1:28030")));

        assertNotNull(runner.getSystem().getIIActor("log"));
        assertNotNull(runner.getSystem().getIIActor("shell"));
        assertNotNull(runner.getSystem().getIIActor("interpreter"));
        assertNull(runner.getSystem().getIIActor("chat-ui"), "a node name is not itself an actor");
    }

    @Test
    void withNoChildNodesConfigured_theNameDoesNotResolve() {
        WorkflowRunner runner = runnerWith(Optional.empty());

        assertNull(runner.getSystem().getIIActor("chat-ui:project1/chat-01.chat"));
    }
}
