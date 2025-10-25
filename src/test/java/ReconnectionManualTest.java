import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.inbound.ReconnectionConfig;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual test for reconnection functionality.
 *
 * This test will:
 * 1. Connect to FreeSWITCH
 * 2. Subscribe to events
 * 3. Display received events with timestamps
 * 4. Automatically reconnect if connection is lost
 *
 * To test reconnection:
 * 1. Run this test
 * 2. Wait until you see HEARTBEAT events being received
 * 3. Restart FreeSWITCH: sudo systemctl restart freeswitch (or equivalent)
 * 4. Observe the reconnection process in the console
 *
 * Expected behavior:
 * - Initial connection succeeds
 * - HEARTBEAT events arrive every ~20 seconds
 * - After FreeSWITCH restart, connection loss is detected within ~60 seconds
 * - Automatic reconnection begins with exponential backoff
 * - Connection is restored and events resume
 */
public class ReconnectionManualTest {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final AtomicInteger heartbeatCount = new AtomicInteger(0);
    private static final AtomicInteger totalEventCount = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("=== FreeSWITCH ESL Client Reconnection Test ===");
        System.out.println("Time: " + LocalDateTime.now().format(TIME_FORMAT));
        System.out.println();

        // Create client with default reconnection config
        Client client = new Client();

        // Optional: Use faster detection for testing (30 seconds instead of 60)
        ReconnectionConfig config = ReconnectionConfig.fastDetection();
        client.setReconnectionConfig(config);

        System.out.println("Reconnection Configuration:");
        System.out.println("  - Heartbeat timeout: " + config.getHeartbeatTimeoutMs() + "ms");
        System.out.println("  - Health check interval: " + config.getHealthCheckIntervalMs() + "ms");
        System.out.println("  - Initial reconnect delay: " + config.getInitialReconnectDelayMs() + "ms");
        System.out.println("  - Max reconnect delay: " + config.getMaxReconnectDelayMs() + "ms");
        System.out.println();

        // Add event listeners
        client.addEventListener((ctx, event) -> {
            int count = totalEventCount.incrementAndGet();
            String eventName = event.getEventName();
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);

            if ("HEARTBEAT".equals(eventName)) {
                int hbCount = heartbeatCount.incrementAndGet();
                System.out.printf("[%s] ❤️  HEARTBEAT #%d (total events: %d)%n",
                    timestamp, hbCount, count);
            } else {
                System.out.printf("[%s] 📨 Event #%d: %s%n", timestamp, count, eventName);
            }
        });

        // Connect to FreeSWITCH
        System.out.println("Connecting to FreeSWITCH at 127.0.0.1:8021...");
        try {
            client.connect(new InetSocketAddress("127.0.0.1", 8021), "ClueCon", 10);
            System.out.println("✅ Connected and authenticated!");
            System.out.println();
        } catch (Exception e) {
            System.err.println("❌ Connection failed: " + e.getMessage());
            return;
        }

        // Subscribe to events
        System.out.println("Subscribing to events...");
        client.setEventSubscriptions(EventFormat.PLAIN, "HEARTBEAT CHANNEL_CREATE CHANNEL_HANGUP");
        System.out.println("✅ Subscribed to: HEARTBEAT, CHANNEL_CREATE, CHANNEL_HANGUP");
        System.out.println();

        System.out.println("=== Monitoring Events ===");
        System.out.println("Waiting for events... (FreeSWITCH sends HEARTBEAT every ~20 seconds)");
        System.out.println();
        System.out.println("💡 To test reconnection:");
        System.out.println("   1. Wait until you see several HEARTBEAT events");
        System.out.println("   2. Restart FreeSWITCH: sudo systemctl restart freeswitch");
        System.out.println("   3. Observe automatic reconnection in the logs");
        System.out.println();
        System.out.println("Press Ctrl+C to stop...");
        System.out.println("---");
        System.out.println();

        // Monitor reconnection status
        Thread statusMonitor = Thread.startVirtualThread(() -> {
            while (true) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds

                    if (client.isReconnecting()) {
                        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
                        System.out.printf("[%s] 🔄 Reconnection in progress...%n", timestamp);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        // Keep the program running
        Thread.currentThread().join();
    }
}
