import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.SendMsg;
import org.freeswitch.esl.client.transport.event.EslEvent;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual test for Outbound ESL socket mode.
 *
 * This test will:
 * 1. Start a socket server listening on port 8084
 * 2. Accept connections from FreeSWITCH when calls are made
 * 3. Display call information and events
 * 4. Automatically answer and handle the call
 *
 * To test:
 * 1. Run this test: gradle runOutboundTest
 * 2. Configure FreeSWITCH dialplan (see README or dialplan XML)
 * 3. Make a call to the configured extension (e.g., dial 9999)
 * 4. Observe the call handling in the console
 *
 * Expected behavior:
 * - Server starts and listens on port 8084
 * - FreeSWITCH connects when a call is made to the test extension
 * - Call details are displayed (caller, destination, UUID, etc.)
 * - Call is automatically answered
 * - Events are logged (ANSWER, HANGUP, etc.)
 */
public class OutboundManualTest {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final AtomicInteger callCounter = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("=== FreeSWITCH ESL Outbound Test ===");
        System.out.println("Time: " + LocalDateTime.now().format(TIME_FORMAT));
        System.out.println();

        // Create outbound server on port 8084
        SocketClient server = new SocketClient(
            new InetSocketAddress("127.0.0.1", 8084),
            () -> new CallHandler()
        );

        // Start the server
        System.out.println("Starting outbound server on port 8084...");
        server.start();
        System.out.println("✅ Outbound server listening on 127.0.0.1:8084");
        System.out.println();

        System.out.println("=== Waiting for Calls ===" );
        System.out.println("To test:");
        System.out.println("  1. Configure FreeSWITCH dialplan:");
        System.out.println("     <extension name=\"outbound_test\">");
        System.out.println("       <condition field=\"destination_number\" expression=\"^9999$\">");
        System.out.println("         <action application=\"socket\" data=\"127.0.0.1:8084 async full\"/>");
        System.out.println("       </condition>");
        System.out.println("     </extension>");
        System.out.println();
        System.out.println("  2. Reload dialplan: fs_cli -x \"reloadxml\"");
        System.out.println("  3. Make a call to extension 9999");
        System.out.println();
        System.out.println("Press Ctrl+C to stop...");
        System.out.println("---");
        System.out.println();

        // Keep the program running
        Thread.currentThread().join();
    }

    /**
     * Handler for each incoming call connection.
     * Each call gets its own instance of this handler.
     */
    static class CallHandler implements IClientHandler {
        private final int callNumber = callCounter.incrementAndGet();
        private String callUuid;
        private String caller;
        private String destination;
        private LocalDateTime callStartTime;
        private boolean answered = false;

        @Override
        public void onConnect(Context ctx, EslEvent event) {
            callStartTime = LocalDateTime.now();
            String timestamp = callStartTime.format(TIME_FORMAT);

            // Extract call information from the connect event
            Map<String, String> headers = event.getEventHeaders();
            callUuid = headers.get("Unique-ID");
            caller = headers.get("Caller-Caller-ID-Number");
            destination = headers.get("Caller-Destination-Number");

            // Display call information
            System.out.println("[" + timestamp + "] 📞 New call #" + callNumber + " connected!");
            System.out.println("  Caller: " + caller);
            System.out.println("  Destination: " + destination);
            System.out.println("  UUID: " + callUuid);
            System.out.println("  Channel: " + headers.get("Channel-Name"));
            System.out.println();

            // Run commands in a separate virtual thread to avoid blocking the IO thread
            Thread.startVirtualThread(() -> {
                try {
                    String ts = LocalDateTime.now().format(TIME_FORMAT);

                    // Subscribe to events for this channel
                    System.out.println("[" + ts + "] 🔔 Subscribing to channel events...");
                    ctx.sendCommand("myevents");

                    // Answer the call using SendMsg (correct way for outbound mode)
                    System.out.println("[" + ts + "] 📞 Answering call...");
                    SendMsg answerMsg = new SendMsg()
                            .addCallCommand("execute")
                            .addExecuteAppName("answer");
                    ctx.sendMessage(answerMsg);

                    // Note: We'll play the tone when we receive CHANNEL_ANSWER event
                    // This demonstrates event-driven, non-blocking flow

                } catch (Exception e) {
                    String ts = LocalDateTime.now().format(TIME_FORMAT);
                    System.err.println("[" + ts + "] ❌ Error handling call: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }

        @Override
        public void onEslEvent(Context ctx, EslEvent event) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            String eventName = event.getEventName();

            // Log important events and drive call flow based on events
            switch (eventName) {
                case "CHANNEL_ANSWER":
                    System.out.println("[" + timestamp + "] ✅ Call #" + callNumber + " answered");
                    answered = true;
                    // Now play the tone (event-driven, run in virtual thread to avoid blocking event loop)
                    Thread.startVirtualThread(() -> {
                        try {
                            String ts = LocalDateTime.now().format(TIME_FORMAT);
                            System.out.println("[" + ts + "] 🔊 Playing tone...");
                            SendMsg playMsg = new SendMsg()
                                    .addCallCommand("execute")
                                    .addExecuteAppName("playback")
                                    .addExecuteAppArg("tone_stream://%(1000,4000,440)");
                            ctx.sendMessage(playMsg);
                        } catch (Exception e) {
                            String ts = LocalDateTime.now().format(TIME_FORMAT);
                            System.err.println("[" + ts + "] ❌ Error playing tone: " + e.getMessage());
                        }
                    });
                    break;

                case "CHANNEL_HANGUP":
                    String cause = event.getEventHeaders().get("Hangup-Cause");
                    long duration = System.currentTimeMillis() - callStartTime.atZone(
                        java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    System.out.println("[" + timestamp + "] 📴 Call #" + callNumber + " hung up");
                    System.out.println("  Cause: " + cause);
                    System.out.println("  Duration: " + (duration / 1000.0) + " seconds");
                    System.out.println("  Answered: " + answered);
                    System.out.println();
                    break;

                case "CHANNEL_EXECUTE":
                    String app = event.getEventHeaders().get("Application");
                    System.out.println("[" + timestamp + "] 🔧 Executing: " + app);
                    break;

                case "CHANNEL_EXECUTE_COMPLETE":
                    String completedApp = event.getEventHeaders().get("Application");
                    String result = event.getEventHeaders().get("Application-Response");
                    System.out.println("[" + timestamp + "] ✅ Completed: " + completedApp +
                        (result != null ? " (result: " + result + ")" : ""));
                    break;

                default:
                    // Log other events at debug level
                    System.out.println("[" + timestamp + "] 📨 Event: " + eventName);
                    break;
            }
        }
    }
}
