import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.inbound.ReconnectionConfig;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual test to verify channel executor cleanup during reconnection.
 *
 * This test validates the fix for the resource leak where channel executors
 * were not being cleaned up when connections were closed during reconnection.
 *
 * Test procedure:
 * 1. Run this test with FreeSWITCH running
 * 2. Wait for some channel events to create executors
 * 3. Restart FreeSWITCH to trigger reconnection
 * 4. Observe that channel executors are cleaned up (count goes to 0)
 * 5. After reconnection, new executors will be created as needed
 *
 * Expected behavior:
 * - Channel executors are created as channels are seen
 * - When connection is lost, all executors are shut down
 * - Executor count drops to 0 during disconnection
 * - New executors are created after reconnection
 */
public class ExecutorCleanupManualTest {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final AtomicInteger eventCount = new AtomicInteger(0);
    private static Field channelExecutorsField;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Channel Executor Cleanup Test ===");
        System.out.println("Time: " + LocalDateTime.now().format(TIME_FORMAT));
        System.out.println();

        // Create client with fast detection for quicker testing
        Client client = new Client();
        ReconnectionConfig config = ReconnectionConfig.fastDetection();
        client.setReconnectionConfig(config);

        // Use reflection to access channelExecutors map
        channelExecutorsField = Client.class.getDeclaredField("channelExecutors");
        channelExecutorsField.setAccessible(true);

        System.out.println("Configuration:");
        System.out.println("  - Heartbeat timeout: " + config.getHeartbeatTimeoutMs() + "ms");
        System.out.println("  - Health check interval: " + config.getHealthCheckIntervalMs() + "ms");
        System.out.println();

        // Add event listeners
        client.addEventListener((ctx, event) -> {
            int count = eventCount.incrementAndGet();
            String eventName = event.getEventName();
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            String channelUuid = event.getEventHeaders().get("Unique-ID");

            if (channelUuid != null && !channelUuid.isEmpty()) {
                System.out.printf("[%s] Event #%d: %s (Channel: %s)%n",
                    timestamp, count, eventName, channelUuid.substring(0, 8));
            } else {
                System.out.printf("[%s] Event #%d: %s%n", timestamp, count, eventName);
            }

            // Show executor count after each event
            try {
                int executorCount = getExecutorCount(client);
                System.out.printf("           Channel executors: %d%n", executorCount);
            } catch (Exception e) {
                System.err.println("Error getting executor count: " + e.getMessage());
            }
        });

        // Connect to FreeSWITCH
        System.out.println("Connecting to FreeSWITCH at 127.0.0.1:8021...");
        try {
            client.connect(new InetSocketAddress("127.0.0.1", 8021), "ClueCon", 10);
            System.out.println("Connected and authenticated!");
            System.out.println();
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            return;
        }

        // Subscribe to all events to maximize chances of seeing channel events
        System.out.println("Subscribing to events...");
        client.setEventSubscriptions(EventFormat.PLAIN, "ALL");
        System.out.println("Subscribed to ALL events");
        System.out.println();

        System.out.println("=== Monitoring Channel Executors ===");
        System.out.println();
        System.out.println("Test procedure:");
        System.out.println("  1. Make some calls to create channel events");
        System.out.println("  2. Note the channel executor count");
        System.out.println("  3. Restart FreeSWITCH: sudo systemctl restart freeswitch");
        System.out.println("  4. Verify executor count drops to 0 during disconnection");
        System.out.println("  5. After reconnection, executors are created again as needed");
        System.out.println();
        System.out.println("Press Ctrl+C to stop...");
        System.out.println("---");
        System.out.println();

        // Monitor executor count periodically
        Thread executorMonitor = Thread.startVirtualThread(() -> {
            int lastCount = -1;
            while (true) {
                try {
                    Thread.sleep(5000); // Check every 5 seconds

                    int currentCount = getExecutorCount(client);
                    boolean isReconnecting = client.isReconnecting();
                    String timestamp = LocalDateTime.now().format(TIME_FORMAT);

                    // Only print if count changed or reconnecting
                    if (currentCount != lastCount || isReconnecting) {
                        String status = isReconnecting ? " (RECONNECTING)" : "";
                        System.out.printf("[%s] Channel executors: %d%s%n",
                            timestamp, currentCount, status);
                        lastCount = currentCount;
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("Error monitoring executors: " + e.getMessage());
                }
            }
        });

        // Keep the program running
        Thread.currentThread().join();
    }

    @SuppressWarnings("unchecked")
    private static int getExecutorCount(Client client) throws Exception {
        Map<String, ExecutorService> executors =
            (Map<String, ExecutorService>) channelExecutorsField.get(client);
        return executors.size();
    }
}
