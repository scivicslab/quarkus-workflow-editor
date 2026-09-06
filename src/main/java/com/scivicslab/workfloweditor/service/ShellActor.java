package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in actor that executes shell commands.
 */
public class ShellActor extends IIActorRef<ShellActor> {

    private static final Logger logger = Logger.getLogger(ShellActor.class.getName());
    private static final long TIMEOUT_SECONDS = 300;

    private volatile Consumer<String> outputListener;

    public ShellActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    public void setOutputListener(Consumer<String> listener) {
        this.outputListener = listener;
    }

    private void emit(String message) {
        var listener = this.outputListener;
        if (listener != null) {
            listener.accept(message);
        }
    }

    @Action("exec")
    public ActionResult exec(String command) {
        if (command == null || command.isBlank()) {
            return new ActionResult(false, "No command specified");
        }

        // Strip JSON array wrapping if present: ["cmd"] -> cmd
        // Use JSONArray parser to correctly unescape \n, \", etc.
        String cmd = command.trim();
        if (cmd.startsWith("[")) {
            try {
                var jsonArray = new org.json.JSONArray(cmd);
                if (jsonArray.length() > 0) {
                    cmd = jsonArray.getString(0);
                }
            } catch (Exception e) {
                // Not valid JSON array, use as-is
            }
        } else if (cmd.startsWith("\"") && cmd.endsWith("\"")) {
            try {
                // Parse as JSON string to unescape
                cmd = new org.json.JSONTokener(cmd).nextValue().toString();
            } catch (Exception e) {
                cmd = cmd.substring(1, cmd.length() - 1);
            }
        }

        try {
            emit("$ " + cmd);
            logger.info("Executing: " + cmd);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    emit(line);
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ActionResult(false, "Command timed out after " + TIMEOUT_SECONDS + "s: " + cmd);
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (exitCode == 0) {
                return new ActionResult(true, result.isEmpty() ? "OK (exit 0)" : result);
            } else {
                return new ActionResult(false, "Exit " + exitCode + ": " + result);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Command failed: " + cmd, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}
