package com.samples.a2a;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.A2A;
import io.a2a.client.*;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.transport.grpc.GrpcTransport;
import io.a2a.client.transport.grpc.GrpcTransportConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.*;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;


import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;


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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static String messageText = "pong";
    /**
     * Agent executor implementation for content writer.
     */
    private static class PingableAgentExecutor implements AgentExecutor {

        static final String myUrl = "http://127.0.0.1:9998";

        static TextPart buildBDITextPart(String illoc, String codec, String content){
            Map<String, Object> md = new Hashtable<>();
            md.put("illocution", illoc);
            md.put("codec", codec);
            return new TextPart(content, md);
        }

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

                spawn_send_pong(context.getConfiguration().pushNotificationConfig().url());
            }
            else {
                eventQueue.enqueueEvent(A2A.toAgentMessage("KO : Unknown request."));
                System.out.println("Unknown request (only receive ping requests)." );
                System.exit(-1);
            }



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


        String extractIllocutionFromMessage(final Message m){
            List<Part<?>> l = m.getParts() ;
            if (l != null && l.size() != 0) {
                Part<?> p = l.get(0) ;
                Map<String,Object> md = p.getMetadata();
                if (md != null)
                    return md.get("illocution").toString();
            }
            return null ;
        }

        String extractCodecFromMessage(final Message m){
            List<Part<?>> l = m.getParts() ;
            if (l != null && l.size() != 0) {
                Part<?> p = l.get(0) ;
                Map<String,Object> md = p.getMetadata();
                if (md != null) {
                    Object codec = md.get("codec");
                    if (codec == null){
                        System.out.println("No codec found in " + md.toString());
                        return null ;
                    }
                    else return codec.toString();
                }
                else {
                    System.out.println("No metadata found");
                    return null ;
                }
            }
            else {
                System.out.println("No part found.");
                return null ;
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

        void spawn_send_pong(String serverUrl) {
            class MyRunnable implements Runnable {

                @Override
                public void run() {
                    try {
                        System.out.println("Connecting to agent at: " + serverUrl);
                        AgentCard publicAgentCard =
                                new A2ACardResolver(serverUrl).getAgentCard();
                        System.out.println("Successfully fetched public agent card:");
                        System.out.println(OBJECT_MAPPER.writeValueAsString(publicAgentCard));
                        System.out.println("Using public agent card for client initialization.");

                        // Create a CompletableFuture to handle async response
                        final CompletableFuture<String> messageResponse
                                = new CompletableFuture<>();

                        // Create consumers for handling client events
                        List<BiConsumer<ClientEvent, AgentCard>> consumers
                                = getConsumers(messageResponse);

                        // Create error handler for streaming errors
                        Consumer<Throwable> streamingErrorHandler = (error) -> {
                            System.out.println("***!!!***!!! Streaming error occurred: " + error.getMessage());
                            //error.printStackTrace();
                            messageResponse.completeExceptionally(error);
                        };

                        // Create channel factory for gRPC transport
                        Function<String, Channel> channelFactory = agentUrl -> {
                            return ManagedChannelBuilder.forTarget(agentUrl).usePlaintext().build();
                        };

                        ClientConfig clientConfig = new ClientConfig.Builder()
                                .setAcceptedOutputModes(List.of("Text"))
                                .setPushNotificationConfig(new PushNotificationConfig(myUrl, null, null, null))
                                .build();
                        // Create a custom HTTP client
                        //A2AHttpClient customHttpClient = ...
                        //HttpClient c1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL).build();
                        //A2AHttpClient c2 ;
                        //JdkA2AHttpClient myHttpClient;

                        // Create the client with both JSON-RPC and gRPC transport support.
                        // The A2A server agent's preferred transport is gRPC, since the client
                        // also supports gRPC, this is the transport that will get used
                        Client client = Client.builder(publicAgentCard)
                                .addConsumers(consumers)
                                .streamingErrorHandler(streamingErrorHandler)
                                .withTransport(GrpcTransport.class,
                                        new GrpcTransportConfig(channelFactory))
                                .withTransport(JSONRPCTransport.class,
                                        new JSONRPCTransportConfig())
                                //.withTransport(RestTransport.class, new RestTransportConfig())
                                .clientConfig(clientConfig)
                                .build();

                        // Create and send the message
                        TextPart p = buildBDITextPart("tell", "atom", messageText);
                        Message.Builder messageBuilder = (new Message.Builder()).role(Message.Role.AGENT).parts(Collections.singletonList(p));
                        Message message = messageBuilder.build();
                        //Message message = A2A.toUserMessage(messageText);
                        //Message.Builder b = new Message.Builder();
                        //Map<String, Object> m = new HashMap<>();
                        //m.put("illocution", "tell");
                        //m.put("codec", "tmp_codec");
                        //b.metadata(m) ;
                        //b.parts(Collections.singletonList(new TextPart("pong")));
                        //b.role(Message.Role.AGENT);
                        //Message message = b.build();

                        System.out.println("Sending message: " + messageText);
                        client.sendMessage(message);
                        System.out.println("Message sent successfully. Waiting for response...");

                        try {
                            // Wait for response with timeout
                            String responseText = messageResponse.get();
                            System.out.println("Final response: " + responseText);
                        } catch (Exception e) {
                            System.err.println("Failed to get response: " + e.getMessage());
                            e.printStackTrace();
                        }

                    } catch (Exception e) {
                        System.err.println("An error occurred: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
            Thread t = new Thread(new MyRunnable());
            t.start();
            System.out.println("Sending thread started.");
        }

        private static List<BiConsumer<ClientEvent, AgentCard>> getConsumers(
                final CompletableFuture<String> messageResponse) {
            List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
            consumers.add(
                    (event, agentCard) -> {
                        if (event instanceof MessageEvent messageEvent) {
                            Message responseMessage = messageEvent.getMessage();
                            String text = extractTextFromParts(responseMessage.getParts());
                            System.out.println("Received message: " + text);
                            messageResponse.complete(text);
                        } else if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                            UpdateEvent updateEvent = taskUpdateEvent.getUpdateEvent();
                            if (updateEvent
                                    instanceof TaskStatusUpdateEvent taskStatusUpdateEvent) {
                                System.out.println(
                                        "Received status-update: "
                                                + taskStatusUpdateEvent.getStatus().state().asString());
                                if (taskStatusUpdateEvent.isFinal()) {
                                    StringBuilder textBuilder = new StringBuilder();
                                    List<Artifact> artifacts
                                            = taskUpdateEvent.getTask().getArtifacts();
                                    for (Artifact artifact : artifacts) {
                                        textBuilder.append(extractTextFromParts(artifact.parts()));
                                    }
                                    String text = textBuilder.toString();
                                    messageResponse.complete(text);
                                }
                            } else if (updateEvent instanceof TaskArtifactUpdateEvent
                                    taskArtifactUpdateEvent) {
                                List<Part<?>> parts = taskArtifactUpdateEvent
                                        .getArtifact()
                                        .parts();
                                String text = extractTextFromParts(parts);
                                System.out.println("Received artifact-update: " + text);
                            }
                        } else if (event instanceof TaskEvent taskEvent) {
                            System.out.println("Received task event: "
                                    + taskEvent.getTask().getId());
                        }
                    });
            return consumers;
        }
        private static String extractTextFromParts(final List<Part<?>> parts) {
            final StringBuilder textBuilder = new StringBuilder();
            if (parts != null) {
                for (final Part<?> part : parts) {
                    if (part instanceof TextPart textPart) {
                        textBuilder.append(textPart.getText());
                    }
                }
            }
            return textBuilder.toString();
        }
    }
}
