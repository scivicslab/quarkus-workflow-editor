package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Built-in actor that logs messages. Useful for debugging workflows.
 */
public class LogActor extends IIActorRef<LogActor> {

    private static final Logger logger = Logger.getLogger(LogActor.class.getName());

    private volatile Consumer<String> outputListener;

    public LogActor(String name, IIActorSystem system) {
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

    @Action("info")
    public ActionResult info(String args) {
        logger.info("[workflow] " + args);
        emit(args);
        return new ActionResult(true, "Logged: " + args);
    }

    @Action("warn")
    public ActionResult warn(String args) {
        logger.warning("[workflow] " + args);
        emit(args);
        return new ActionResult(true, "Warned: " + args);
    }
}
