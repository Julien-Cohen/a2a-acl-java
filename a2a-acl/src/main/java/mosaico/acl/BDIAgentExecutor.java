package mosaico.acl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.JdkA2AHttp11Client;
import io.a2a.client.transport.grpc.GrpcTransport;
import io.a2a.client.transport.grpc.GrpcTransportConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.client.transport.rest.RestTransport;
import io.a2a.client.transport.rest.RestTransportConfig;
import io.a2a.client.ClientEvent ;
import io.a2a.client.MessageEvent ;
import io.a2a.client.TaskUpdateEvent ;
import io.a2a.client.TaskEvent ;
import io.a2a.client.Client ;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.*;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;


public abstract class BDIAgentExecutor implements AgentExecutor {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(); // FIXME

    public static final String extension_uri = "https://gitlab.eclipse.org/eclipse-research-labs/mosaico-project/a2a-acl/-/blob/main/a2a_acl_protocol/MOSAICO_A2A_ACL_PROTOCOL";
    public static final String invalid_message = "This message is not compliant with MOSAICO A2A ACL.";

    /** Returns a copy of a given message with an illocution added accordingly to the MOSAICO ACL A2A extension. */
    public static Message addIllocution(Message m, String illoc, String codec){

        // First, get a copy of the meta-data of m, or build a fresh metadata map if null.
        Map<String,Object> md ;
        if (m.getMetadata() == null){
            md = new HashMap<>();
        }
        else { md = new HashMap<>(m.getMetadata()); }

        // Second, create a fresh map for MOSAICO-ACL metadata
        Map<String,Object> md2 = new HashMap<>();
        md2.put("illocution", illoc);
        md2.put("codec", codec);

        // Then put the MOSAICO-ACL map in the metadata map.
        md.put(extension_uri, md2);

        List<String> extensions ;
        if (m.getExtensions() == null)
            extensions = Collections.singletonList(extension_uri);
        else {
            extensions = new ArrayList<>(m.getExtensions());
            extensions.add(extension_uri);
        }
        return new Message(m.getRole(), m.getParts(), m.getMessageId(), m.getContextId(), m.getTaskId(), m.getReferenceTaskIds(), md, extensions, m.getKind());
    }



    public static String extractTextFromMessage(final Message message) {
        final StringBuilder textBuilder = new StringBuilder();
        if (message.getParts() != null) {
            for (final Part<?> part : message.getParts()) {
                if (part instanceof TextPart textPart) {
                    textBuilder.append(textPart.getText());
                }
            }
        }
        return textBuilder.toString();
    }

    public static boolean checkMetadata(final Message m){
        Map<String, Object> md = m.getMetadata() ;
        if(md==null) return false;
        if(!md.containsKey(extension_uri)) return false;
        Map<String,Object> md2 = (Map<String,Object>) md.get(extension_uri) ;
        if (md2==null) return false ;
        if (!md2.containsKey("illocution")) return false ;
        if(md2.get("illocution") == null) return false ;
        return true ;
    }

    public static String extractIllocutionFromMessage(final Message m){
        if (!checkMetadata(m))
            throw new UnsupportedOperationException(invalid_message);
        else {
            Map<String, Object> md = (Map<String, Object>) m.getMetadata().get(extension_uri);
            return md.get("illocution").toString();
        }
    }

    public static String extractCodecFromMessage(final Message m){
        if (!checkMetadata(m)) // FIXME : checked twice
            throw new UnsupportedOperationException(invalid_message);
        else {
            Map<String, Object> md = (Map<String, Object>) m.getMetadata().get(extension_uri);
            return md.get("codec").toString();
        }
    }


    public static void spawn_send_message(String toUrl, final String replyToUrl, final String illocution, final String codec, final String content) {
        class MyRunnable implements Runnable {

            @Override
            public void run() {
                try {
                    System.out.println("(Connecting to agent at: " + toUrl + ")");
                    AgentCard publicAgentCard =
                            new A2ACardResolver(toUrl).getAgentCard();
                    System.out.println("(Successfully fetched public agent card)");

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
                    TextPart p = new TextPart(content);

                    Message.Builder messageBuilder = (new Message.Builder()).role(Message.Role.AGENT).parts(Collections.singletonList(p));
                    Message message0 = messageBuilder.build();
                    Message message = addIllocution(message0, illocution, codec);

                    System.out.println("(Sending message: " + content + ")");
                    client.sendMessage(message);
                    System.out.println("(Message sent successfully. Handling sync response.)");

                    try {
                        // Wait for response with timeout
                        String responseText = messageResponse.get();
                        System.out.println("Synchronous response: " + responseText);
                    } catch (Exception e) {
                        System.err.println("Error while getting synchronous response: " + e.getMessage());
                    }

                } catch (Exception e) {
                    System.err.println("An error occurred: " + e.getMessage());
                }
            }
        }
        Thread t = new Thread(new MyRunnable());
        t.start();
        System.out.println("(Sending thread started.)");
    }

    static List<BiConsumer<ClientEvent, AgentCard>> getConsumers(
            final CompletableFuture<String> messageResponse
        ) {
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
        consumers.add(
                (event, agentCard) -> {
                    if (event instanceof MessageEvent messageEvent) {
                        Message responseMessage = messageEvent.getMessage();
                        String text = extractTextFromParts(responseMessage.getParts());
                        System.out.println("(Consume message: " + text + ")");
                        messageResponse.complete(text);
                    }
                    else if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                        UpdateEvent updateEvent = taskUpdateEvent.getUpdateEvent();
                        if (updateEvent
                                instanceof TaskStatusUpdateEvent taskStatusUpdateEvent) {
                            System.out.println(
                                    "(Consume status-update: "
                                            + taskStatusUpdateEvent.getStatus().state().asString() + ")");
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
                        }
                        else if (updateEvent instanceof TaskArtifactUpdateEvent
                                taskArtifactUpdateEvent) {
                            List<Part<?>> parts = taskArtifactUpdateEvent
                                    .getArtifact()
                                    .parts();
                            String text = extractTextFromParts(parts);
                            System.out.println("(Consume artifact-update: " + text + ")");
                        }
                    }
                    else if (event instanceof TaskEvent taskEvent) {
                        System.out.println("(Consume task event: "
                                + taskEvent.getTask().getId() + ")");
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

    @Override
    public void execute(final RequestContext context,
                        final EventQueue eventQueue) throws JSONRPCError {
        System.out.println("Received a message with metadata: " + context.getMessage().getMetadata());
        Message message = context.getMessage();
        final String content = extractTextFromMessage(message);
        final String illoc = extractIllocutionFromMessage(message);
        final String codec = extractCodecFromMessage(message);
        final String sender = context.getConfiguration().pushNotificationConfig().url() ;
        ACLMessage m = new ACLMessage(illoc, content, sender, codec);
        System.out.println(m.toString());
        if (illoc == null) throw new InvalidParamsError();
        else {
            switch (illoc) {
                case "tell":
                    this.executeTell(m, eventQueue);
                    break;
                case "achieve":
                    this.executeAchieve(m, eventQueue);
                    break;
                case "ask":
                    this.executeAsk(m, eventQueue);
                    break;
                default:
                    this.executeOther(m, eventQueue);
            }
        }
    }

    public abstract void executeTell(final ACLMessage message, final EventQueue eventQueue);
    public abstract void executeAchieve(final ACLMessage message, final EventQueue eventQueue);
    public abstract void executeAsk(final ACLMessage message, final EventQueue eventQueue);
    public abstract void executeOther(final ACLMessage message, final EventQueue eventQueue);



    @Override
    public void cancel(final RequestContext context,
                       final EventQueue eventQueue) throws JSONRPCError {
        System.out.println("!CANCEL!");
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
