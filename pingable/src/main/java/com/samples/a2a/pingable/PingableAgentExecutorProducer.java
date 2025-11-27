package com.samples.a2a.pingable;

import io.a2a.A2A;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;


/**
 * Producer for Content Pingable Agent Executor.
 */
@ApplicationScoped
public final class PingableAgentExecutorProducer extends BDIAgentExecutor {

    /**
     * Creates the agent executor for the content writer agent.
     *
     * @return the agent executor
     */
    @Produces
    public AgentExecutor agentExecutor() {
        return new PingableAgentExecutor();
    }

    static String messageText = "pong";
    /**
     * Agent executor implementation for content writer.
     */
    private static class PingableAgentExecutor implements AgentExecutor {

        static final String myUrl = "http://127.0.0.1:9998";

        @Override
        public void execute(final RequestContext context,
                            final EventQueue eventQueue) throws JSONRPCError {

            // extract the text from the message
            Message message = context.getMessage();
            final String assignment = extractTextFromMessage(message);
            final String illoc = extractIllocutionFromMessage(message) ;
            System.out.println("Message illocution: "+ illoc);
            final String codec = extractCodecFromMessage(message);
            System.out.println("Content codec: "+ codec);


            if (assignment.equals("ping") && illoc!= null && illoc.equals("achieve")) {
                System.out.println("Received a achieve/ping request");
                eventQueue.enqueueEvent(A2A.toAgentMessage("OK : achieve/ping received."));

                spawn_send_pong(context.getConfiguration().pushNotificationConfig().url(), myUrl, "tell", "atom_codec", messageText);
            }
            else {
                eventQueue.enqueueEvent(A2A.toAgentMessage("KO : Unknown request."));
                System.out.println("Unknown request (only receive ping requests)." );
                System.exit(-1);
            }

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
