package com.scivicslab.workfloweditor.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.workfloweditor.rest.WorkflowResource;
import com.scivicslab.workfloweditor.service.WorkflowRunner;
import com.scivicslab.workfloweditor.service.WorkflowState;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MCP tools for the Turing Workflow Editor.
 *
 * <p>Allows external MCP clients (e.g., coder-agent) to manage and execute
 * workflows programmatically.</p>
 */
public class WorkflowTools {

    @Inject
    WorkflowState state;

    @Inject
    WorkflowRunner runner;

    @Inject
    WorkflowResource workflowResource;

    @ConfigProperty(name = "workflow.dir", defaultValue = "/home/devteam/works/workflow")
    String workflowDir;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Run a YAML workflow and return the execution result. "
            + "This is a synchronous call that waits for completion.")
    String runWorkflow(
            @ToolArg(description = "The YAML workflow definition to execute") String yaml,
            @ToolArg(description = "Maximum iterations (default: 100)") int maxIterations
    ) {
        if (runner.isRunning()) {
            return "Error: A workflow is already running.";
        }

        int maxIter = maxIterations > 0 ? maxIterations : 100;
        List<String> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        var sseEmitter = workflowResource.getSseEmitter();

        Thread.startVirtualThread(() -> {
            try {
                runner.runYaml(yaml, maxIter, null, event -> {
                    events.add("[" + event.type() + "] " + event.message());
                    // Also forward to browser UI via SSE
                    sseEmitter.accept(event);
                });
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(5, TimeUnit.MINUTES)) {
                return "Error: Workflow timed out. Events so far:\n" + String.join("\n", events);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted.";
        }

        return String.join("\n", events);
    }

    @Tool(description = "Get the current workflow status (running, name, step count)")
    String getStatus() {
        return String.format("running=%s, paused=%s, name=%s, steps=%d, maxIterations=%d",
                runner.isRunning(),
                runner.isPaused(),
                state.getName(),
                state.size(),
                state.getMaxIterations());
    }

    @Tool(description = "Stop the currently running workflow")
    String stopWorkflow() {
        if (!runner.isRunning()) {
            return "No workflow is currently running.";
        }
        runner.stop();
        return "Stop requested.";
    }

    @Tool(description = "Resume a workflow paused at a breakpoint")
    String resumeWorkflow() {
        if (!runner.isPaused()) {
            return "Workflow is not paused.";
        }
        runner.resume();
        return "Workflow resumed.";
    }

    @Tool(description = "Export the current workflow as YAML")
    String exportYaml() {
        return WorkflowRunner.toYamlStructured(
                state.getName(), state.getDescription(),
                state.getSteps());
    }

    @Tool(description = "Import a YAML workflow into the editor")
    String importYaml(
            @ToolArg(description = "The YAML workflow to import") String yaml
    ) {
        WorkflowRunner.ParsedWorkflow parsed = WorkflowRunner.fromYaml(yaml);
        state.replaceAll(parsed.name(), parsed.steps(), state.getMaxIterations());
        if (parsed.description() != null) {
            state.setDescription(parsed.description());
        }
        if (parsed.params() != null && !parsed.params().isEmpty()) {
            state.setParams(parsed.params());
        }
        // Notify browser UI to reload
        try {
            java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:8091/api/refresh"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
        return "Imported: " + parsed.name() + " (" + parsed.steps().size() + " steps)";
    }

    @Tool(description = "List all registered actors and their available actions with documentation links. "
            + "Use this to discover what actors and actions are available before writing a workflow.")
    String listActors() {
        List<Map<String, Object>> tree = runner.getActorTree();
        StringBuilder sb = new StringBuilder();
        for (var actor : tree) {
            sb.append(actor.get("name"))
              .append(" (").append(actor.get("type")).append("):\n");
            @SuppressWarnings("unchecked")
            var actions = (List<Map<String, Object>>) actor.get("actions");
            if (actions != null) {
                for (var action : actions) {
                    sb.append("  - ").append(action.get("name"));
                    Object url = action.get("javadocUrl");
                    if (url != null) {
                        sb.append("  [docs: ").append(url).append("]");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Tool(description = "Invoke an actor action directly by name")
    String invokeActor(
            @ToolArg(description = "The actor name") String actorName,
            @ToolArg(description = "The action name to invoke") String actionName,
            @ToolArg(description = "Arguments for the action") String args
    ) {
        try {
            var actor = runner.getSystem().getIIActor(actorName);
            if (actor == null) {
                return "Error: Actor not found: " + actorName;
            }
            ActionResult result = actor.callByActionName(actionName, args != null ? args : "");
            return (result.isSuccess() ? "OK" : "FAIL") + ": " + result.getResult();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List available workflow tabs")
    String listTabs() {
        return String.join(", ", state.listTabs());
    }

    @Tool(description = "List available workflow YAML files. "
            + "Returns file paths that can be passed to loadWorkflow or runWorkflowFile.")
    String listWorkflows() {
        try (var stream = Files.walk(Paths.get(workflowDir), 2)) {
            List<String> files = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .filter(p -> !p.toString().contains("environment-parameters"))
                    .map(p -> Paths.get(workflowDir).relativize(p).toString())
                    .sorted()
                    .collect(Collectors.toList());
            return files.isEmpty() ? "No workflows found in " + workflowDir
                    : String.join("\n", files);
        } catch (IOException e) {
            return "Error listing workflows: " + e.getMessage();
        }
    }

    @Tool(description = "Load a workflow YAML file by name. "
            + "Use listWorkflows() to get available names. "
            + "Returns the raw YAML content including parameter placeholders like ${repo}.")
    String loadWorkflow(
            @ToolArg(description = "Workflow file name relative to the workflow directory (e.g. 'publish-to-github.yaml')") String name
    ) {
        try {
            var path = Paths.get(workflowDir, name);
            if (!path.toRealPath().startsWith(Paths.get(workflowDir).toRealPath())) {
                return "Error: Path traversal not allowed.";
            }
            return Files.readString(path);
        } catch (IOException e) {
            return "Error loading workflow '" + name + "': " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseParamsJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, String.valueOf(v)));
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Tool(description = "Load a workflow YAML file and run it with the given parameters. "
            + "Parameters replace ${key} placeholders in the YAML. "
            + "Common parameters: agent (MCP agent name), repo, dir, task. "
            + "Use listWorkflows() to find available workflows, loadWorkflow() to inspect placeholders.")
    String runWorkflowFile(
            @ToolArg(description = "Workflow file name relative to the workflow directory (e.g. 'publish-to-github.yaml')") String name,
            @ToolArg(description = "Parameters as JSON object to substitute ${key} placeholders, e.g. {\"agent\":\"chat-ui-39500\",\"repo\":\"oogasawa/k8s-tree\"}") String parametersJson,
            @ToolArg(description = "Maximum iterations (default: 100)") int maxIterations
    ) {
        if (runner.isRunning()) {
            return "Error: A workflow is already running.";
        }

        String yaml;
        try {
            var path = Paths.get(workflowDir, name);
            if (!path.toRealPath().startsWith(Paths.get(workflowDir).toRealPath())) {
                return "Error: Path traversal not allowed.";
            }
            yaml = Files.readString(path);
        } catch (IOException e) {
            return "Error loading workflow '" + name + "': " + e.getMessage();
        }

        // Parse parameters JSON and apply substitution
        if (parametersJson != null && !parametersJson.isBlank()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, String> params = mapper.readValue(parametersJson, Map.class);
                Map<String, String> stringParams = new java.util.LinkedHashMap<>();
                for (var e : params.entrySet()) {
                    stringParams.put(e.getKey(), String.valueOf(e.getValue()));
                }
                yaml = WorkflowRunner.applyParameters(yaml, stringParams);
            } catch (Exception e) {
                return "Error parsing parameters JSON: " + e.getMessage();
            }
        }

        int maxIter = maxIterations > 0 ? maxIterations : 100;
        List<String> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        var sseEmitter = workflowResource.getSseEmitter();
        final String finalYaml = yaml;

        Thread.startVirtualThread(() -> {
            try {
                runner.runYaml(finalYaml, maxIter, null, event -> {
                    events.add("[" + event.type() + "] " + event.message());
                    sseEmitter.accept(event);
                });
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(10, TimeUnit.MINUTES)) {
                return "Error: Workflow timed out. Events so far:\n" + String.join("\n", events);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted.";
        }

        return String.join("\n", events);
    }
}
