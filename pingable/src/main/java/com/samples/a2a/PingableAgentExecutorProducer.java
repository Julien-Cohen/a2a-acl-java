package com.samples.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;


import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/**
 * Producer for Content Writer Agent Executor.
 */
@ApplicationScoped
public final class PingableAgentExecutorProducer {

    /**
     * Creates the agent executor for the content writer agent.
     *
     * @return the agent executor
     */
    @Produces
    public AgentExecutor agentExecutor() {
        return new PingableAgentExecutor();
    }

    /**
     * Agent executor implementation for content writer.
     */
    private static class PingableAgentExecutor implements AgentExecutor {

        static Map<String, Object> tellMetadata ;
        {
            tellMetadata = new Hashtable<>();
            tellMetadata.put("illocution", "tell");
            tellMetadata.put("codec", "tmp_codec");
        }


        @Override
        public void execute(final RequestContext context,
                            final EventQueue eventQueue) throws JSONRPCError {
            final TaskUpdater updater = new TaskUpdater(context, eventQueue);

            // mark the task as submitted and start working on it
            if (context.getTask() == null) {
                updater.submit();
            }
            updater.startWork();

            // extract the text from the message
            final String assignment = extractTextFromMessage(context.getMessage());

            if (assignment.equals("ping")) {
                // create the response part
                final TextPart responsePart = new TextPart("pong", tellMetadata);
                final List<Part<?>> parts = List.of(responsePart);

                // add the response as an artifact
                updater.addArtifact(parts, null, null, null);
            }
            else {
                // create the response part
                final TextPart responsePart = new TextPart("Unknown request (only receive ping requests)." );
                final List<Part<?>> parts = List.of(responsePart);

                // add the response as an artifact
                updater.addArtifact(parts, null, null, null);
            }
            // complete the task
            updater.complete();
        }

        private String extractTextFromMessage(final Message message) {
            final StringBuilder textBuilder = new StringBuilder();
            if (message.getParts() != null) {
                for (final Part part : message.getParts()) {
                    if (part instanceof TextPart textPart) {
                        textBuilder.append(textPart.getText());
                    }
                }
            }
            return textBuilder.toString();
        }

        @Override
        public void cancel(final RequestContext context,
                           final EventQueue eventQueue) throws JSONRPCError {
            final Task task = context.getTask();

            if (task.getStatus().state() == TaskState.CANCELED) {
                // task already cancelled
                throw new TaskNotCancelableError();
            }

            if (task.getStatus().state() == TaskState.COMPLETED) {
                // task already completed
                throw new TaskNotCancelableError();
            }

            // cancel the task
            final TaskUpdater updater = new TaskUpdater(context, eventQueue);
            updater.cancel();
        }
    }
}
