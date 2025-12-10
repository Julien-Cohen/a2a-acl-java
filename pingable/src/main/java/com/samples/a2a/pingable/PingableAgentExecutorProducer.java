package com.samples.a2a.pingable;

import io.a2a.A2A;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import mosaico.acl.BDIAgentExecutor;


/**
 * Producer for Content Pingable Agent Executor.
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
     * Agent executor implementation for ping service.
     */
    private static class PingableAgentExecutor extends BDIAgentExecutor {

        static final String myUrl = "http://127.0.0.1:9998";

        @Override
        public void execute(final RequestContext context,
                            final EventQueue eventQueue) throws JSONRPCError {

            // extract the text from the message
            Message message = context.getMessage();
            final String content = extractTextFromMessage(message);
            final String illoc = extractIllocutionFromMessage(message) ;
            System.out.println("Message illocution: "+ illoc);
            final String codec = extractCodecFromMessage(message);
            System.out.println("Content codec: "+ codec);


            if (content.equals("ping") && illoc!= null && illoc.equals("achieve")) {
                System.out.println("Received a achieve/ping request");
                eventQueue.enqueueEvent(A2A.toAgentMessage("OK : achieve/ping received."));

                spawn_send_message(context.getConfiguration().pushNotificationConfig().url(), myUrl, "tell", "atom_codec", "pong");
            }
            else {
                eventQueue.enqueueEvent(A2A.toAgentMessage("KO : Unknown request."));
                System.out.println("Unknown request (only receive ping requests)." );
                System.exit(-1);
            }

        }




    }
}
