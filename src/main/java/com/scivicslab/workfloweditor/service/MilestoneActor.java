package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.json.JSONArray;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Built-in actor for workflow milestone reporting.
 * Workflows call milestone.report("message") to push status
 * messages that appear in the Actor Tree panel.
 */
public class MilestoneActor extends IIActorRef<MilestoneActor> {

    private volatile Consumer<String> outputListener;
    private final CopyOnWriteArrayList<MilestoneEntry> history = new CopyOnWriteArrayList<>();
    private volatile String latestMessage = null;

    public MilestoneActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    public void setOutputListener(Consumer<String> listener) {
        this.outputListener = listener;
    }

    /**
     * Returns the most recent milestone message, or null.
     */
    public String getLatestMessage() {
        return latestMessage;
    }

    /**
     * Returns the full history of milestone messages.
     */
    public List<Map<String, String>> getHistory() {
        List<Map<String, String>> result = new ArrayList<>();
        for (MilestoneEntry entry : history) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("message", entry.message);
            map.put("timestamp", entry.timestamp.toString());
            result.add(map);
        }
        return result;
    }

    /**
     * Clears milestone history. Called on workflow reset.
     */
    public void reset() {
        history.clear();
        latestMessage = null;
    }

    @Action("report")
    public ActionResult report(String args) {
        // args comes as JSON array string e.g. '["message"]' from interpreter
        String message = args;
        try {
            JSONArray arr = new JSONArray(args);
            if (arr.length() > 0) {
                message = arr.getString(0);
            }
        } catch (Exception e) {
            // Not JSON array - use as-is
        }
        latestMessage = message;
        history.add(new MilestoneEntry(message, Instant.now()));
        var listener = this.outputListener;
        if (listener != null) {
            listener.accept("[milestone] " + message);
        }
        return new ActionResult(true, message);
    }

    private record MilestoneEntry(String message, Instant timestamp) {}
}
