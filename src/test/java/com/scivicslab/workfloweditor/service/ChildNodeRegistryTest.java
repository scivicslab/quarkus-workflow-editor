package com.scivicslab.workfloweditor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.distributed.NodeInfo;

/**
 * Reading the configured child nodes, and turning an actor name written in a workflow into the
 * node it belongs to.
 */
@DisplayName("ChildNodeRegistry — which process an actor name names")
class ChildNodeRegistryTest {

    @Test
    void noConfiguration_meansNoChildNodes() {
        ChildNodeRegistry registry = new ChildNodeRegistry(Optional.empty());

        assertTrue(registry.nodeNames().isEmpty());
        assertNull(registry.resolve("chat-ui:project1/chat-01.chat"));
    }

    @Test
    void readsNameAndAddress() {
        ChildNodeRegistry registry = new ChildNodeRegistry(Optional.of(List.of("chat-ui=127.0.0.1:28116")));

        NodeInfo node = registry.node("chat-ui");

        assertEquals("chat-ui", node.getNodeId());
        assertEquals("127.0.0.1", node.getHost());
        assertEquals(28116, node.getPort());
    }

    /** The part after the first colon is the actor's name in its own process, slashes and all. */
    @Test
    void splitsAtTheFirstColonOnly() {
        ChildNodeRegistry registry = new ChildNodeRegistry(Optional.of(List.of("chat-ui=127.0.0.1:28116")));

        ChildNodeRegistry.Target target = registry.resolve("chat-ui:project1/chat-01.chat");

        assertEquals("chat-ui", target.nodeName());
        assertEquals("project1/chat-01.chat", target.remoteActorName());
    }

    @Test
    void aNameWithNoConfiguredNodeIsNotClaimed() {
        ChildNodeRegistry registry = new ChildNodeRegistry(Optional.of(List.of("chat-ui=127.0.0.1:28116")));

        assertNull(registry.resolve("calc:3"));
        assertNull(registry.resolve("log"));
        assertNull(registry.resolve("otherNode:x"));
    }

    /** A misspelt entry must be refused at startup, not turn into a node nobody can reach. */
    @Test
    void refusesAnEntryItCannotRead() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChildNodeRegistry(Optional.of(List.of("chat-ui=127.0.0.1"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ChildNodeRegistry(Optional.of(List.of("127.0.0.1:28116"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ChildNodeRegistry(Optional.of(List.of("chat-ui=127.0.0.1:not-a-port"))));
    }
}
