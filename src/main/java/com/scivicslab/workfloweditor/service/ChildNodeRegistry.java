package com.scivicslab.workfloweditor.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.scivicslab.pojoactor.core.distributed.DistributedActorSystem;
import com.scivicslab.pojoactor.core.distributed.NodeInfo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The processes this editor may send work to, and which of them an actor name refers to.
 *
 * <p>A workflow writes {@code chat-ui:project1/chat-01.chat}. The part before the first colon is
 * a child node's name and is configured here; the part after it is the actor's name in that
 * process. Keeping the address out of the workflow is the point: the same workflow can be aimed
 * at a different process by changing configuration alone
 * ({@code RemoteChildActor_260906_oo01}).
 *
 * <p>Configured as {@code workflow-editor.child-nodes=chat-ui=127.0.0.1:28030}, comma-separated.
 * <strong>The port configured is the child's HTTP port</strong> — the one its Web UI answers on
 * and the only one a person sees. The port its actors answer on is derived from that by
 * {@link DistributedActorSystem#publicationPortFor(int)}, the same definition the child uses to
 * decide where to publish, so neither side has to be told the other's number.
 *
 * <p>Unset means no child nodes, and every actor name resolves the way it did before.
 */
@ApplicationScoped
public class ChildNodeRegistry {

    private static final char NODE_SEPARATOR = ':';

    private final Map<String, NodeInfo> nodes = new LinkedHashMap<>();

    /** An actor name split into the process it lives in and its name there. */
    public record Target(String nodeName, String remoteActorName) {}

    @Inject
    public ChildNodeRegistry(
            @ConfigProperty(name = "workflow-editor.child-nodes") Optional<List<String>> configured) {
        for (String entry : configured.orElse(List.of())) {
            String text = entry.strip();
            if (text.isEmpty()) continue;
            nodes.put(nameOf(text), nodeOf(text));
        }
    }

    private static String nameOf(String entry) {
        int equals = entry.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException(
                    "workflow-editor.child-nodes entry must be name=host:port, but was: " + entry);
        }
        return entry.substring(0, equals).strip();
    }

    private static NodeInfo nodeOf(String entry) {
        String address = entry.substring(entry.indexOf('=') + 1).strip();
        int colon = address.lastIndexOf(NODE_SEPARATOR);
        if (colon <= 0) {
            throw new IllegalArgumentException(
                    "workflow-editor.child-nodes entry must be name=host:port, but was: " + entry);
        }
        String host = address.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "workflow-editor.child-nodes port is not a number in: " + entry, e);
        }
        // The configured number is the child's HTTP port; what a caller connects to is derived.
        return new NodeInfo(nameOf(entry), host, DistributedActorSystem.publicationPortFor(port));
    }

    /** @return the configured node names, in the order they were configured */
    public Set<String> nodeNames() {
        return nodes.keySet();
    }

    /** @return the node of that name, or {@code null} if none is configured */
    public NodeInfo node(String nodeName) {
        return nodes.get(nodeName);
    }

    /**
     * Splits an actor name into a configured node and the actor's name there.
     *
     * @param actorName the name as a workflow wrote it
     * @return the split, or {@code null} when no configured node claims this name — including
     *         names with no colon at all, and {@code calc:3} and the like, which mean what they
     *         always meant
     */
    public Target resolve(String actorName) {
        int colon = actorName.indexOf(NODE_SEPARATOR);
        if (colon <= 0 || colon == actorName.length() - 1) {
            return null;
        }
        String nodeName = actorName.substring(0, colon);
        if (!nodes.containsKey(nodeName)) {
            return null;
        }
        return new Target(nodeName, actorName.substring(colon + 1));
    }
}
