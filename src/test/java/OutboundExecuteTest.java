import org.freeswitch.esl.client.dptools.Execute;
import org.freeswitch.esl.client.dptools.ExecuteException;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test Outbound ESL using the Execute API.
 *
 * This demonstrates the high-level blocking API style where:
 * - onConnect runs in a virtual thread (automatic)
 * - Execute methods (answer, playAndGetDigits, hangup) can be called directly
 * - Blocking calls are safe because virtual threads are cheap
 *
 * To test:
 * 1. Run this test: gradle runOutboundExecuteTest
 * 2. Make a call to extension 9999
 * 3. Observe the call handling with Execute API
 */
public class OutboundExecuteTest {

    private static final Logger log = LoggerFactory.getLogger(OutboundExecuteTest.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final AtomicInteger callCounter = new AtomicInteger(0);

    // Sound files for playAndGetDigits
    private static final String SOUND_BASE = "tone_stream://%(1000,4000,440)";
    private static final String PROMPT = SOUND_BASE;
    private static final String INVALID = SOUND_BASE;

    public static void main(String[] args) throws Exception {
        System.out.println("=== FreeSWITCH ESL Outbound Execute API Test ===");
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

        System.out.println("=== Waiting for Calls ===");
        System.out.println("To test: Make a call to extension 9999");
        System.out.println("Press Ctrl+C to stop...");
        System.out.println();

        // Keep the program running
        Thread.currentThread().join();
    }

    /**
     * Handler for each incoming call using Execute API.
     * Note: onConnect runs in a virtual thread automatically,
     * so blocking calls are safe!
     */
    static class CallHandler implements IClientHandler {
        private final int callNumber = callCounter.incrementAndGet();

        @Override
        public void onConnect(Context context, EslEvent eslEvent) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);

            // Extract call information
            String uuid = eslEvent.getEventHeaders().get("Unique-ID");
            String caller = eslEvent.getEventHeaders().get("Caller-Caller-ID-Number");
            String destination = eslEvent.getEventHeaders().get("Caller-Destination-Number");

            // Display call information
            System.out.println("[" + timestamp + "] 📞 New call #" + callNumber + " connected!");
            System.out.println("  Caller: " + caller);
            System.out.println("  Destination: " + destination);
            System.out.println("  UUID: " + uuid);
            System.out.println();

            // Create Execute API instance
            Execute exe = new Execute(context, uuid);

            try {
                // Answer the call (blocking - but safe in virtual thread!)
                System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] 📞 Answering call...");
                exe.answer();
                System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] ✅ Call answered");

                // Subscribe to events (optional - for monitoring)
                context.sendCommand("myevents");

                // Collect digits (blocking call - waits for user input!)
                // Note: playAndGetDigits will play the prompt automatically
                System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] 🔢 Collecting digits...");
                String digits = exe.playAndGetDigits(
                    3,              // min digits
                    5,              // max digits
                    3,              // tries
                    10000,          // timeout (10s)
                    "#",            // terminator
                    PROMPT,         // prompt file
                    INVALID,        // invalid file
                    "^\\d+",        // regex
                    10000           // digit timeout
                );

                if (digits != null && !digits.isEmpty()) {
                    System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] ✅ Collected digits: " + digits);
                } else {
                    System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] ⚠️  No digits collected");
                }

                // Hangup
                System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] 📴 Hanging up...");
                exe.hangup(null);
                System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] ✅ Call completed");
                System.out.println();

            } catch (ExecuteException e) {
                System.err.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] ❌ Execute error: " + e.getMessage());
                e.printStackTrace();

                // Try to hangup on error
                try {
                    exe.hangup(null);
                } catch (ExecuteException hangupError) {
                    System.err.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] ❌ Failed to hangup: " + hangupError.getMessage());
                }
            }
        }

        @Override
        public void onEslEvent(Context ctx, EslEvent event) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            String eventName = event.getEventName();

            // Log important events
            switch (eventName) {
                case "CHANNEL_ANSWER":
                    log.info("[{}] Event: CHANNEL_ANSWER", timestamp);
                    break;

                case "CHANNEL_HANGUP":
                    String cause = event.getEventHeaders().get("Hangup-Cause");
                    log.info("[{}] Event: CHANNEL_HANGUP ({})", timestamp, cause);
                    break;

                case "CHANNEL_EXECUTE":
                    String app = event.getEventHeaders().get("Application");
                    log.debug("[{}] Event: CHANNEL_EXECUTE ({})", timestamp, app);
                    break;

                case "CHANNEL_EXECUTE_COMPLETE":
                    String completedApp = event.getEventHeaders().get("Application");
                    log.debug("[{}] Event: CHANNEL_EXECUTE_COMPLETE ({})", timestamp, completedApp);
                    break;

                default:
                    log.trace("[{}] Event: {}", timestamp, eventName);
                    break;
            }
        }
    }
}
