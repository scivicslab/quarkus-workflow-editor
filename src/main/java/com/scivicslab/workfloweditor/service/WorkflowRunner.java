package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.RemoteActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.pojoactor.distributed.RemoteActorIIAR;
import com.scivicslab.workfloweditor.rest.WorkflowResource.WorkflowEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts a matrix of rows into POJO-actor YAML and executes the workflow.
 * A single ActorSystem is shared across the entire application.
 */
@ApplicationScoped
public class WorkflowRunner {

    private static final Logger logger = Logger.getLogger(WorkflowRunner.class.getName());

    private static final String DEFAULT_INTERPRETER_NAME = "interpreter";

    @Inject
    ChildNodeRegistry childNodes;

    private IIActorSystem system;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;
    private volatile Interpreter currentInterpreter = null;

    @PostConstruct
    void init() {
        system = new IIActorSystem("workflow");
        system.addIIActor(new LogActor("log", system));
        system.addIIActor(new ShellActor("shell", system));
        system.addIIActor(new LoaderActor("loader", system));
        system.addIIActor(new MilestoneActor("milestone", system));

        // Create default interpreter actor - the editor UI is bound to this interpreter
        Interpreter defaultInterp = new Interpreter.Builder()
                .loggerName("workflow")
                .team(system)
                .build();
        InterpreterIIAR interpActor = new InterpreterIIAR(DEFAULT_INTERPRETER_NAME, defaultInterp, system);
        system.addIIActor(interpActor);
        defaultInterp.setSelfActorRef(interpActor);
        currentInterpreter = defaultInterp;

        registerChildNodeActors();
    }

    /**
     * Lets a workflow name an actor that lives in one of the configured child processes.
     *
     * <p>Registered as a factory rather than as actors, because the children's actors are made
     * while this editor is already running — a conversation in
     * {@code chat-ui-with-audit-trail} appears when someone opens a tab — so there is nothing to
     * enumerate at startup. The factory is consulted only for names this system does not have and
     * that no built-in claims ({@code RemoteChildActor_260906_oo01}).
     */
    private void registerChildNodeActors() {
        if (childNodes.nodeNames().isEmpty()) {
            return;
        }
        system.addActorFactory(actorName -> {
            ChildNodeRegistry.Target target = childNodes.resolve(actorName);
            if (target == null) {
                return null;
            }
            NodeInfo node = childNodes.node(target.nodeName());
            logger.info("Actor " + actorName + " will be called on " + node.getAddress());
            return new RemoteActorIIAR(
                    actorName, new RemoteActorRef(target.remoteActorName(), node), system);
        });
        logger.info("Child nodes available to workflows: " + childNodes.nodeNames());
    }

    public IIActorSystem getSystem() {
        return system;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void stop() {
        stopRequested = true;
        Interpreter interp = currentInterpreter;
        if (interp != null) {
            interp.requestStop();
            interp.resume(); // Unblock if paused at breakpoint
        }
    }

    public void resume() {
        Interpreter interp = currentInterpreter;
        if (interp != null && interp.isPaused()) {
            interp.resume();
        }
    }

    public boolean isPaused() {
        Interpreter interp = currentInterpreter;
        return interp != null && interp.isPaused();
    }

    /**
     * Returns actor tree info for the ActorSystem.
     * Each entry: { name, type, parent, children, actions }
     */
    public List<Map<String, Object>> getActorTree() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IIActorRef<?> actor : system.getTopLevelActors()) {
            collectActorInfo(actor, result);
        }
        return result;
    }

    private void collectActorInfo(IIActorRef<?> actor, List<Map<String, Object>> result) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", actor.getName());
        info.put("type", actor.getClass().getSimpleName());
        info.put("parent", actor.getParentName());

        // Detect if this actor wraps an Interpreter
        boolean isInterpreter = actor.getClass().getSimpleName().equals("InterpreterIIAR");
        info.put("isInterpreter", isInterpreter);

        if (isInterpreter) {
            Interpreter interp = getInterpreterObject(actor);
            if (interp != null) {
                info.put("currentState", interp.getCurrentState());
                info.put("workflowFile", getInterpreterWorkflowFile(actor));
            }
        }

        // Milestone actor: include latest message and history
        if (actor instanceof MilestoneActor ms) {
            String latest = ms.getLatestMessage();
            if (latest != null) info.put("milestoneMessage", latest);
            info.put("milestoneHistory", ms.getHistory());
        }

        // Determine status — for interpreters, derive from currentState
        if (isInterpreter) {
            String st = (String) info.get("currentState");
            if ("end".equals(st)) info.put("status", "COMPLETED");
            else if (running.get()) info.put("status", "RUNNING");
            else info.put("status", "IDLE");
        } else {
            info.put("status", "IDLE");
        }

        List<String> children = new ArrayList<>(actor.getNamesOfChildren());
        info.put("children", children);

        List<Map<String, String>> actions = discoverActions(actor);
        info.put("actions", actions);

        result.add(info);

        for (String childName : children) {
            if (system.hasIIActor(childName)) {
                collectActorInfo(system.getIIActor(childName), result);
            }
        }
    }

    /**
     * Extracts the wrapped Interpreter from an InterpreterIIAR actor via reflection.
     */
    private Interpreter getInterpreterObject(IIActorRef<?> actor) {
        try {
            var field = actor.getClass().getSuperclass().getSuperclass().getDeclaredField("object");
            field.setAccessible(true);
            Object obj = field.get(actor);
            if (obj instanceof Interpreter interp) {
                return interp;
            }
        } catch (Exception e) {
            logger.fine("Could not get interpreter for " + actor.getName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the current state from an InterpreterIIAR actor via reflection.
     */
    private String getInterpreterState(IIActorRef<?> actor) {
        Interpreter interp = getInterpreterObject(actor);
        return interp != null ? interp.getCurrentState() : null;
    }

    /**
     * Extracts the workflow file name from an InterpreterIIAR actor.
     * Currently returns the actor name as a best-effort identifier since
     * Interpreter does not track the loaded file name.
     * Sub-workflow child interpreters are named "subwf-{baseName}-{timestamp}-{random}".
     */
    private String getInterpreterWorkflowFile(IIActorRef<?> actor) {
        return workflowFileFromActorName(actor.getName());
    }

    // Package-private for testing
    static String workflowFileFromActorName(String name) {
        if (name != null && name.startsWith("subwf-")) {
            // Extract base name from "subwf-{baseName}-{timestamp}-{random}"
            String rest = name.substring(6); // remove "subwf-"
            int dashIdx = rest.lastIndexOf('-');
            if (dashIdx > 0) {
                String beforeRandom = rest.substring(0, dashIdx);
                int dashIdx2 = beforeRandom.lastIndexOf('-');
                if (dashIdx2 > 0) {
                    return beforeRandom.substring(0, dashIdx2) + ".yaml";
                }
            }
            return rest + ".yaml";
        }
        return null;
    }

    /**
     * Determines the status of an actor: RUNNING, COMPLETED, IDLE, or ERROR.
     */
    private String determineActorStatus(IIActorRef<?> actor, boolean isInterpreter) {
        if (!isInterpreter) return "IDLE";
        String state = getInterpreterState(actor);
        if ("end".equals(state)) return "COMPLETED";
        if (running.get()) return "RUNNING";
        return "IDLE";
    }

    /**
     * Emits an actor-tree snapshot as an SSE event.
     * Called after each step execution so the frontend can update the tree in real-time.
     */
    private void emitActorTree(Consumer<WorkflowEvent> emitter) {
        try {
            List<Map<String, Object>> tree = getActorTree();
            emitter.accept(new WorkflowEvent("actor-tree", null, null, null, null, Map.of("actors", tree)));
        } catch (Exception e) {
            logger.fine("Could not emit actor tree: " + e.getMessage());
        }
    }

    private void resetMilestone() {
        for (IIActorRef<?> actor : system.getTopLevelActors()) {
            if (actor instanceof MilestoneActor ms) {
                ms.reset();
            }
        }
    }

    private List<Map<String, String>> discoverActions(IIActorRef<?> actor) {
        List<Map<String, String>> actions = new ArrayList<>();
        String javadocBaseUrl = resolveJavadocBaseUrl(actor.getClass());
        String classPath = actor.getClass().getName().replace('.', '/');

        for (Method method : actor.getClass().getMethods()) {
            Action action = method.getAnnotation(Action.class);
            if (action != null) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", action.value());
                if (javadocBaseUrl != null) {
                    // Build javadoc URL: baseUrl/package/ClassName.html#methodName(params)
                    String params = buildJavadocParams(method);
                    entry.put("javadocUrl", javadocBaseUrl + "/" + classPath + ".html#" + method.getName() + "(" + params + ")");
                }
                actions.add(entry);
            }
        }
        // Built-in JSON state actions available on all IIActorRef
        for (String name : List.of("putJson", "getJson", "hasJson", "clearJson", "printJson")) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", name);
            actions.add(entry);
        }
        return actions;
    }

    private String resolveJavadocBaseUrl(Class<?> clazz) {
        try {
            var url = clazz.getClassLoader().getResource("META-INF/javadoc.properties");
            if (url == null) return null;
            var props = new java.util.Properties();
            try (var in = url.openStream()) {
                props.load(in);
            }
            return props.getProperty("javadoc.baseUrl");
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not load javadoc.properties for " + clazz.getName(), e);
            return null;
        }
    }

    private String buildJavadocParams(Method method) {
        return javadocParams(method);
    }

    // Package-private for testing
    static String javadocParams(Method method) {
        var sb = new StringBuilder();
        for (int i = 0; i < method.getParameterTypes().length; i++) {
            if (i > 0) sb.append(",");
            sb.append(method.getParameterTypes()[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * OutputStream that intercepts line-by-line output and forwards to a callback,
     * while also writing to the original stream.
     */
    private static class OutputInterceptor extends java.io.OutputStream {
        private final java.io.OutputStream original;
        private final Consumer<String> lineCallback;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        OutputInterceptor(java.io.OutputStream original, Consumer<String> lineCallback) {
            this.original = original;
            this.lineCallback = lineCallback;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            original.write(b);
            if (b == '\n') {
                emitLine();
            } else {
                buffer.write(b);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            original.write(buf, off, len);
            for (int i = off; i < off + len; i++) {
                if (buf[i] == '\n') {
                    emitLine();
                } else {
                    buffer.write(buf[i]);
                }
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            original.flush();
            if (buffer.size() > 0) {
                lineCallback.accept(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            }
        }

        private void emitLine() {
            String line = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            if (!line.isEmpty()) {
                lineCallback.accept(line);
            }
        }
    }

    /**
     * Runs a workflow from raw YAML string.
     */
    public void runYaml(String yaml, int maxIterations, Level logLevel, Consumer<WorkflowEvent> emitter) {
        if (!running.compareAndSet(false, true)) {
            emitter.accept(new WorkflowEvent("error", "Workflow already running", null, null));
            return;
        }
        stopRequested = false;
        resetMilestone();

        var workflowLogger     = java.util.logging.Logger.getLogger("workflow");
        var pojoActorLogger    = java.util.logging.Logger.getLogger("com.scivicslab.pojoactor");
        var turingWorkflowLogger = java.util.logging.Logger.getLogger("com.scivicslab.turingworkflow");
        var prevWorkflowLevel     = workflowLogger.getLevel();
        var prevPojoActorLevel    = pojoActorLogger.getLevel();
        var prevTuringWorkflowLevel = turingWorkflowLogger.getLevel();
        if (logLevel != null) {
            workflowLogger.setLevel(logLevel);
            pojoActorLogger.setLevel(logLevel);
            turingWorkflowLogger.setLevel(logLevel);
        }

        // When OFF, filter out step/info events; pass output/completed/error/stopped/paused
        final Consumer<WorkflowEvent> effectiveEmitter;
        if (logLevel == Level.OFF) {
            effectiveEmitter = event -> {
                String type = event.type();
                if (!"step".equals(type) && !"info".equals(type)) {
                    emitter.accept(event);
                }
            };
        } else {
            effectiveEmitter = emitter;
        }

        SseLogHandler sseLogHandler = new SseLogHandler(effectiveEmitter);
        workflowLogger.addHandler(sseLogHandler);
        pojoActorLogger.addHandler(sseLogHandler);
        turingWorkflowLogger.addHandler(sseLogHandler);

        Consumer<String> outputForwarder = msg ->
                effectiveEmitter.accept(new WorkflowEvent("output", msg, null, null));
        setOutputListeners(outputForwarder);

        var origOut = System.out;
        var origErr = System.err;
        System.setOut(new java.io.PrintStream(new OutputInterceptor(origOut, line ->
                forwardOrSubworkflowEvent(line, effectiveEmitter)), true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new OutputInterceptor(origErr, line ->
                effectiveEmitter.accept(new WorkflowEvent("output", "[stderr] " + line, null, null))), true, StandardCharsets.UTF_8));

        try {
            yaml = expandEnvVars(yaml);
            logger.fine("Running YAML directly:\n" + yaml);
            effectiveEmitter.accept(new WorkflowEvent("info", "Workflow started", null, null));

            // Reuse the default interpreter actor
            Interpreter interpreter = currentInterpreter;
            interpreter.reset();

            interpreter.setBreakpointListener((transition, state) ->
                    effectiveEmitter.accept(new WorkflowEvent("paused",
                            "Breakpoint at state: " + state, state, null)));

            interpreter.setActionFailureListener((transition, state, result) -> {
                workflowLogger.finest("Action failed at state '" + state + "' transition "
                        + transition.getStates() + ": " + result.getResult());
            });

            interpreter.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            emitActorTree(effectiveEmitter);

            workflowLogger.info("Initial state: '" + interpreter.getCurrentState() + "'");
            if (interpreter.hasCodeLoaded()) {
                var debugTransitions = interpreter.getCode().getTransitions();
                workflowLogger.fine("Transition count: " + debugTransitions.size());
                for (int t = 0; t < debugTransitions.size(); t++) {
                    var tr = debugTransitions.get(t);
                    workflowLogger.fine("  [" + t + "] states=" + tr.getStates() + " label=" + tr.getLabel());
                }
            } else {
                effectiveEmitter.accept(new WorkflowEvent("error", "No code loaded!", null, null));
            }

            int iteration = 0;
            while (iteration < maxIterations && !stopRequested) {
                ActionResult result = interpreter.execCode();
                String currentState = interpreter.getCurrentState();
                emitActorTree(effectiveEmitter);
                workflowLogger.fine("execCode result: success=" + (result != null && result.isSuccess())
                        + " state=" + currentState
                        + " msg=" + (result != null ? result.getResult() : "null"));

                if (result == null || !result.isSuccess()) {
                    if ("end".equals(currentState)) {
                        effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", currentState, null));
                    } else if (interpreter.isStopRequested()) {
                        effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", currentState, null));
                    } else {
                        String msg = result != null ? result.getResult() : "No matching transition from state: " + currentState;
                        effectiveEmitter.accept(new WorkflowEvent("error", msg, currentState, null));
                    }
                    break;
                }

                workflowLogger.fine(result.getResult());
                iteration++;

                if ("end".equals(interpreter.getCurrentState())) {
                    effectiveEmitter.accept(new WorkflowEvent("completed", "Workflow completed", "end", null));
                    break;
                }
            }

            if (iteration >= maxIterations) {
                effectiveEmitter.accept(new WorkflowEvent("warning", "Max iterations reached (" + maxIterations + ")", null, null));
            }
            if (stopRequested) {
                effectiveEmitter.accept(new WorkflowEvent("stopped", "Workflow stopped by user", null, null));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Workflow execution failed", e);
            effectiveEmitter.accept(new WorkflowEvent("error", "Execution failed: " + e.getMessage(), null, null));
        } finally {
            workflowLogger.removeHandler(sseLogHandler);
            pojoActorLogger.removeHandler(sseLogHandler);
            turingWorkflowLogger.removeHandler(sseLogHandler);
            System.setOut(origOut);
            System.setErr(origErr);
            setOutputListeners(null);
            workflowLogger.setLevel(prevWorkflowLevel);
            pojoActorLogger.setLevel(prevPojoActorLevel);
            turingWorkflowLogger.setLevel(prevTuringWorkflowLevel);
            running.set(false);
        }
    }

    private static void forwardOrSubworkflowEvent(String line, Consumer<WorkflowEvent> emitter) {
        if (line.startsWith("[SUBWORKFLOW_START:") && line.endsWith("]")) {
            String inner = line.substring(19, line.length() - 1);
            int lastColon = inner.lastIndexOf(':');
            if (lastColon > 0) {
                String name = inner.substring(0, lastColon);
                int depth = Integer.parseInt(inner.substring(lastColon + 1));
                emitter.accept(new WorkflowEvent("subworkflow-start", null, null, null, null,
                        Map.of("name", name, "depth", depth)));
                return;
            }
        }
        if (line.startsWith("[SUBWORKFLOW_END:") && line.endsWith("]")) {
            String inner = line.substring(17, line.length() - 1);
            int lastColon = inner.lastIndexOf(':');
            if (lastColon > 0) {
                String name = inner.substring(0, lastColon);
                int depth = Integer.parseInt(inner.substring(lastColon + 1));
                emitter.accept(new WorkflowEvent("subworkflow-end", null, null, null, null,
                        Map.of("name", name, "depth", depth)));
                return;
            }
        }
        emitter.accept(new WorkflowEvent("output", line, null, null));
    }

    private void setOutputListeners(Consumer<String> listener) {
        for (IIActorRef<?> actor : system.getTopLevelActors()) {
            if (actor instanceof ShellActor shell) {
                shell.setOutputListener(listener);
            } else if (actor instanceof LogActor log) {
                log.setOutputListener(listener);
            } else if (actor instanceof MilestoneActor ms) {
                ms.setOutputListener(listener);
            }
            // For dynamically loaded actors, try to call setOutputListener via reflection
            trySetOutputListenerReflective(actor, listener);
        }
    }

    private void trySetOutputListenerReflective(IIActorRef<?> actor, Consumer<String> listener) {
        try {
            var method = actor.getClass().getMethod("setOutputListener", Consumer.class);
            // Skip built-in actors already handled above
            if (actor instanceof ShellActor || actor instanceof LogActor) return;
            method.invoke(actor, listener);
        } catch (NoSuchMethodException e) {
            // Actor doesn't support output listener — that's fine
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to set output listener on " + actor.getName(), e);
        }
    }

    /**
     * Appends arguments to YAML using block scalar (|) for multiline values
     * and double-quoted strings for single-line values.
     */
    private static final ObjectMapper JSON_VALIDATOR = new ObjectMapper();

    private static boolean isJsonStructure(String s) {
        if ((s.startsWith("[") && s.endsWith("]")) || (s.startsWith("{") && s.endsWith("}"))) {
            try {
                JSON_VALIDATOR.readTree(s);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static void appendYamlArguments(StringBuilder sb, String args, String indent) {
        if (isJsonStructure(args)) {
            sb.append(indent).append("arguments: ").append(args).append("\n");
        } else if (args.contains("\n")) {
            // Multiline: use YAML block scalar to preserve newlines
            sb.append(indent).append("arguments: |\n");
            for (String line : args.split("\n", -1)) {
                if (line.isEmpty()) {
                    sb.append("\n");
                } else {
                    sb.append(indent).append("  ").append(line).append("\n");
                }
            }
        } else {
            sb.append(indent).append("arguments: \"").append(escapeYamlString(args)).append("\"\n");
        }
    }

    private static String yamlEscape(String s) {
        if (s == null) return "\"\"";
        if (s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'")
                || s.startsWith(" ") || s.endsWith(" ")) {
            return "\"" + escapeYamlString(s) + "\"";
        }
        return s;
    }

    private static String escapeYamlString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Parses POJO-actor YAML back into structured steps with description/label/note.
     */
    @SuppressWarnings("unchecked")
    public static ParsedWorkflow fromYaml(String yaml) {
        Yaml snakeYaml = new Yaml();
        Map<String, Object> doc = snakeYaml.load(yaml);

        String name = doc.containsKey("name") ? String.valueOf(doc.get("name")) : "workflow";
        String description = doc.containsKey("description") ? String.valueOf(doc.get("description")) : null;
        List<StepDto> stepDtos = new ArrayList<>();

        List<Map<String, Object>> steps = (List<Map<String, Object>>) doc.get("steps");
        if (steps == null) return new ParsedWorkflow(name, description, stepDtos);

        for (Map<String, Object> step : steps) {
            String from;
            String to;
            if (step.containsKey("states")) {
                List<String> states = ((List<?>) step.get("states")).stream()
                        .map(String::valueOf).toList();
                from = states.size() > 0 ? states.get(0) : "";
                to = states.size() > 1 ? states.get(1) : "";
            } else {
                from = String.valueOf(step.getOrDefault("from", ""));
                to = String.valueOf(step.getOrDefault("to", ""));
            }
            String label = step.containsKey("label") ? String.valueOf(step.get("label")) : null;
            String note = step.containsKey("note") ? String.valueOf(step.get("note")) : null;

            List<ActionDto> actionDtos = new ArrayList<>();
            List<Map<String, Object>> actions = (List<Map<String, Object>>) step.get("actions");
            if (actions != null) {
                for (Map<String, Object> action : actions) {
                    String actor = String.valueOf(action.getOrDefault("actor", ""));
                    String method = String.valueOf(action.getOrDefault("method", ""));
                    String args = null;
                    if (action.containsKey("arguments")) {
                        Object rawArgs = action.get("arguments");
                        if (rawArgs instanceof List) {
                            args = new org.json.JSONArray((List<?>) rawArgs).toString();
                        } else if (rawArgs instanceof java.util.Map) {
                            args = new org.json.JSONObject((java.util.Map<?, ?>) rawArgs).toString();
                        } else {
                            args = String.valueOf(rawArgs);
                        }
                    }
                    actionDtos.add(new ActionDto(actor, method, args));
                }
            }

            Long stepDelay = step.containsKey("delay") ? ((Number) step.get("delay")).longValue() : null;
            Boolean stepBreakpoint = step.containsKey("breakpoint") ? (Boolean) step.get("breakpoint") : null;
            stepDtos.add(new StepDto(from, to, label, note, stepDelay, stepBreakpoint, actionDtos));
        }

        // Parse params: section
        Map<String, ParamMeta> params = new LinkedHashMap<>();
        Object rawParams = doc.get("params");
        if (rawParams instanceof Map<?, ?> paramsMap) {
            for (var entry : paramsMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String desc = "";
                String def = "";
                if (entry.getValue() instanceof Map<?, ?> meta) {
                    Object d = meta.get("description");
                    Object v = meta.get("default");
                    if (d != null) desc = String.valueOf(d);
                    if (v != null) def = String.valueOf(v);
                }
                params.put(key, new ParamMeta(desc, def));
            }
        }

        return new ParsedWorkflow(name, description, stepDtos, params);
    }

    /**
     * Converts structured steps to YAML including description/label/note.
     */
    public static String toYamlStructured(String name, String description, List<StepDto> steps) {
        return toYamlStructured(name, description, steps, null);
    }

    public static String toYamlStructured(String name, String description, List<StepDto> steps,
                                          Map<String, ParamMeta> params) {
        var sb = new StringBuilder();
        sb.append("name: ").append(yamlEscape(name)).append("\n");
        if (description != null && !description.isEmpty()) {
            sb.append("description: ").append(yamlEscape(description)).append("\n");
        }
        if (params != null && !params.isEmpty()) {
            sb.append("params:\n");
            for (var entry : params.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(":\n");
                ParamMeta m = entry.getValue();
                if (m.description() != null && !m.description().isEmpty()) {
                    sb.append("    description: ").append(yamlEscape(m.description())).append("\n");
                }
                if (m.defaultValue() != null && !m.defaultValue().isEmpty()) {
                    sb.append("    default: ").append(yamlEscape(m.defaultValue())).append("\n");
                }
            }
        }
        sb.append("steps:\n");

        for (var step : steps) {
            sb.append("- states: [\"").append(escapeYamlString(step.from()))
              .append("\", \"").append(escapeYamlString(step.to())).append("\"]\n");
            if (step.label() != null && !step.label().isEmpty()) {
                sb.append("  label: ").append(yamlEscape(step.label())).append("\n");
            }
            if (step.note() != null && !step.note().isEmpty()) {
                sb.append("  note: ").append(yamlEscape(step.note())).append("\n");
            }
            if (step.delay() != null && step.delay() > 0) {
                sb.append("  delay: ").append(step.delay()).append("\n");
            }
            if (step.breakpoint() != null && step.breakpoint()) {
                sb.append("  breakpoint: true\n");
            }
            sb.append("  actions:\n");
            for (var action : step.actions()) {
                sb.append("    - actor: ").append(yamlEscape(action.actor())).append("\n");
                sb.append("      method: ").append(yamlEscape(action.method())).append("\n");
                if (action.arguments() != null && !action.arguments().isEmpty()) {
                    appendYamlArguments(sb, action.arguments(), "      ");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Expands ${ENV_VAR} placeholders in yaml using process environment variables.
     * Called automatically at the start of runYaml() so workflow files can reference
     * runtime context (e.g. ${MCP_GATEWAY_URL}) without hardcoding values.
     */
    static String expandEnvVars(String yaml) {
        for (var entry : System.getenv().entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (yaml.contains(placeholder)) {
                yaml = yaml.replace(placeholder, entry.getValue());
            }
        }
        return yaml;
    }

    /**
     * Substitutes ${key} placeholders in yaml with values from parameters map.
     *
     * <p>Three YAML string contexts are handled:</p>
     * <ul>
     *   <li>Double-quoted: {@code "${key}"} — escapes \, ", and control chars as YAML escape sequences</li>
     *   <li>Single-quoted: {@code '${key}'} — escapes ' as '' (YAML single-quoted rule)</li>
     *   <li>Embedded in double-quoted: {@code "prefix ${key} suffix"} — same escaping as double-quoted</li>
     * </ul>
     * SnakeYAML automatically reverses the escape sequences when parsing the resulting YAML.
     */
    public static String applyParameters(String yaml, Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) return yaml;
        for (var entry : parameters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            String placeholder = "${" + key + "}";

            // Replace placeholder inside double-quoted YAML strings (embedded or standalone)
            // e.g. "${key}" or "prefix ${key} suffix"
            String dqEscaped = yamlDoubleQuoteEscape(value);
            yaml = replaceInsideDoubleQuotedYaml(yaml, placeholder, dqEscaped);

            // Replace standalone single-quoted YAML string: '${key}'
            String sqPlaceholder = "'" + placeholder + "'";
            if (yaml.contains(sqPlaceholder)) {
                yaml = yaml.replace(sqPlaceholder, "'" + value.replace("'", "''") + "'");
            }

            // Replace remaining occurrences (e.g. embedded in single-quoted strings like '...${key}...')
            // Single-quoted YAML: escape ' as '' only
            if (yaml.contains(placeholder)) {
                yaml = yaml.replace(placeholder, value.replace("'", "''"));
            }
        }
        return yaml;
    }

    /** Escapes a value for embedding in a YAML double-quoted string. */
    private static String yamlDoubleQuoteEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Replaces all occurrences of {@code placeholder} that appear inside YAML double-quoted strings
     * (i.e., between non-escaped {@code "} pairs) with {@code replacement}.
     */
    private static String replaceInsideDoubleQuotedYaml(String yaml, String placeholder, String replacement) {
        if (!yaml.contains(placeholder)) return yaml;
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < yaml.length()) {
            if (yaml.charAt(i) == '"') {
                // Find the closing unescaped "
                int start = i;
                int j = i + 1;
                while (j < yaml.length()) {
                    if (yaml.charAt(j) == '\\') { j += 2; continue; }
                    if (yaml.charAt(j) == '"') break;
                    j++;
                }
                // yaml[start..j] is a double-quoted string (including the quotes)
                String inner = yaml.substring(start + 1, j);
                if (inner.contains(placeholder)) {
                    result.append('"').append(inner.replace(placeholder, replacement)).append('"');
                } else {
                    result.append(yaml, start, j + 1);
                }
                i = j + 1;
            } else {
                result.append(yaml.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    @RegisterForReflection
    public record ParsedWorkflow(String name, String description, List<StepDto> steps,
                                 Map<String, ParamMeta> params) {
        public ParsedWorkflow(String name, String description, List<StepDto> steps) {
            this(name, description, steps, Map.of());
        }
    }

    public record ParamMeta(String description, String defaultValue) {}
    @RegisterForReflection
    public record StepDto(String from, String to, String label, String note, Long delay, Boolean breakpoint, List<ActionDto> actions) {}
    @RegisterForReflection
    public record ActionDto(String actor, String method, String arguments) {}
}
