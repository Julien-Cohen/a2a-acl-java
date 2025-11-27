package com.samples.a2a.pingable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.client.*;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.JdkA2AHttp11Client;
import io.a2a.client.transport.grpc.GrpcTransport;
import io.a2a.client.transport.grpc.GrpcTransportConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.client.transport.rest.RestTransport ;
import io.a2a.client.transport.rest.RestTransportConfig;
import io.a2a.spec.*;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class BDIAgentExecutor {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static String extractTextFromMessage(final Message message) {
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

    static String extractIllocutionFromMessage(final Message m){
        List<Part<?>> l = m.getParts() ;
        if (l != null && !l.isEmpty()) {
            Part<?> p = l.get(0) ;
            Map<String,Object> md = p.getMetadata();
            if (md != null)
                return md.get("illocution").toString();
        }
        return null ;
    }

    static String extractCodecFromMessage(final Message m){
        List<Part<?>> l = m.getParts() ;
        if (l != null && !l.isEmpty()) {
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

    static TextPart buildBDITextPart(String illoc, String codec, String content){
        Map<String, Object> md = new Hashtable<>();
        md.put("illocution", illoc);
        md.put("codec", codec);
        return new TextPart(content, md);
    }

    static void spawn_send_pong(String toUrl, final String replyToUrl, final String illocution, final String codec, final String content) {
        class MyRunnable implements Runnable {

            @Override
            public void run() {
                try {
                    System.out.println("Connecting to agent at: " + toUrl);
                    AgentCard publicAgentCard =
                            new A2ACardResolver(toUrl).getAgentCard();
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
                            .setPushNotificationConfig(new PushNotificationConfig(replyToUrl, null, null, null))
                            .build();
                    // Create a custom HTTP client for HTTP 1.1 instead of HTTP 2 (for python uvicorn A2A servers)
                    A2AHttpClient customHttpClient = new JdkA2AHttp11Client();

                    // Create the client with several transport support.

                    Client client = Client.builder(publicAgentCard)
                            .addConsumers(consumers)
                            .streamingErrorHandler(streamingErrorHandler)
                            .withTransport(GrpcTransport.class,
                                    new GrpcTransportConfig(channelFactory))
                            .withTransport(JSONRPCTransport.class,
                                    new JSONRPCTransportConfig(customHttpClient))
                            .withTransport(RestTransport.class, new RestTransportConfig())
                            .clientConfig(clientConfig)
                            .build();

                    // Create and send the message
                    TextPart p = buildBDITextPart(illocution, codec, content);
                    Message.Builder messageBuilder = (new Message.Builder()).role(Message.Role.AGENT).parts(Collections.singletonList(p));
                    Message message = messageBuilder.build();

                    System.out.println("Sending message: " + content);
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

    static List<BiConsumer<ClientEvent, AgentCard>> getConsumers(
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

    static String extractTextFromParts(final List<Part<?>> parts) {
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
