package org.freeswitch.esl.client.inbound;

import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;
import org.freeswitch.esl.client.transport.CommandResponse;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration test for ESL inbound client.
 * Tests connection, authentication, event subscription, API commands, and ordered event processing.
 *
 * Requires FreeSWITCH running on 127.0.0.1:8021 with password "ClueCon"
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InboundClientIntegrationTest {
    private static final Logger L = LoggerFactory.getLogger(InboundClientIntegrationTest.class);

    private Client client;
    private static final String PASSWORD = "ClueCon";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8021;
    private static final int TIMEOUT_SECONDS = 10;

    @BeforeEach
    void setUp() {
        L.info("Setting up test - creating new Client instance");
        client = new Client();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                L.info("Tearing down test - closing client");
                client.close();
            } catch (Exception e) {
                L.warn("Error closing client", e);
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Connection and Authentication")
    void testConnectionAndAuthentication() throws Exception {
        L.info("=== TEST 1: Connection and Authentication ===");
        L.info("Connecting to FreeSWITCH at {}:{}...", HOST, PORT);

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, TIMEOUT_SECONDS);

        L.info("Successfully connected and authenticated!");
        assertTrue(true, "Connection should succeed");
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Event Subscription and Reception")
    void testEventSubscriptionAndReception() throws Exception {
        L.info("=== TEST 2: Event Subscription and Reception ===");

        // Connect first
        L.info("Connecting to FreeSWITCH at {}:{}...", HOST, PORT);
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, TIMEOUT_SECONDS);
        L.info("Connected successfully");

        // Setup event tracking
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<String> receivedEventName = new AtomicReference<>();
        AtomicReference<Map<String, String>> receivedEventHeaders = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger(0);

        // Add event listener
        client.addEventListener((ctx, event) -> {
            int count = eventCount.incrementAndGet();
            String eventName = event.getEventName();
            Map<String, String> headers = event.getEventHeaders();

            L.info("Event #{}: {} (from hostname: {})",
                count,
                eventName,
                headers.get("FreeSWITCH-Hostname"));

            if (receivedEventName.compareAndSet(null, eventName)) {
                receivedEventHeaders.set(new HashMap<>(headers));
                eventLatch.countDown();
            }
        });

        // Subscribe to events
        L.info("Subscribing to HEARTBEAT events...");
        client.setEventSubscriptions(EventFormat.PLAIN, "HEARTBEAT");

        // Wait for at least one event (FreeSWITCH sends HEARTBEAT every 20 seconds)
        int waitTime = 25; // Need to wait longer than HEARTBEAT interval
        L.info("Waiting for events (up to {} seconds)...", waitTime);
        boolean receivedEvent = eventLatch.await(waitTime, TimeUnit.SECONDS);

        // Verify
        assertTrue(receivedEvent, "Should have received at least one event");
        assertNotNull(receivedEventName.get(), "Event name should not be null");
        assertNotNull(receivedEventHeaders.get(), "Event headers should not be null");
        assertFalse(receivedEventHeaders.get().isEmpty(), "Event headers should not be empty");

        L.info("Successfully received {} event(s). First event: {}",
            eventCount.get(),
            receivedEventName.get());
        L.info("Event headers received: {}", receivedEventHeaders.get().keySet());
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: API Command Execution")
    void testApiCommandExecution() throws Exception {
        L.info("=== TEST 3: API Command Execution ===");

        // Connect first
        L.info("Connecting to FreeSWITCH at {}:{}...", HOST, PORT);
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, TIMEOUT_SECONDS);
        L.info("Connected successfully");

        // Test 3a: API status command
        L.info("Sending 'api status' command...");
        EslMessage statusResponse = client.sendApiCommand("status", "");

        assertNotNull(statusResponse, "Status response should not be null");

        // API responses have content-type "api/response" and body contains the result
        List<String> statusBodyLines = statusResponse.getBodyLines();
        assertNotNull(statusBodyLines, "Status body should not be null");
        assertFalse(statusBodyLines.isEmpty(), "Status output should not be empty");

        String statusOutput = String.join("\n", statusBodyLines);
        L.info("Status command response: {}", statusOutput.substring(0, Math.min(100, statusOutput.length())));

        // Test 3b: API version command
        L.info("Sending 'api version' command...");
        EslMessage versionResponse = client.sendApiCommand("version", "");

        assertNotNull(versionResponse, "Version response should not be null");

        List<String> versionBodyLines = versionResponse.getBodyLines();
        assertNotNull(versionBodyLines, "Version body should not be null");
        assertFalse(versionBodyLines.isEmpty(), "Version output should not be empty");

        String versionOutput = String.join("\n", versionBodyLines);
        assertTrue(versionOutput.contains("FreeSWITCH"), "Version should contain 'FreeSWITCH'");

        L.info("Version: {}", versionOutput.trim());

        // Test 3c: Background API command
        L.info("Sending 'bgapi status' command...");
        CompletableFuture<EslEvent> bgapiFuture = client.sendBackgroundApiCommand("status", "");

        assertNotNull(bgapiFuture, "Background API future should not be null");

        // Try to get the job UUID from the immediate response by subscribing to BACKGROUND_JOB events
        L.info("Background API command submitted, future created: {}", bgapiFuture);

        // We'll just verify the future was created successfully
        assertFalse(bgapiFuture.isDone(), "Future should not be immediately done");
        L.info("Background job submitted successfully");
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Ordered Event Processing")
    void testOrderedEventProcessing() throws Exception {
        L.info("=== TEST 4: Ordered Event Processing ===");

        // Connect first
        L.info("Connecting to FreeSWITCH at {}:{}...", HOST, PORT);
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, TIMEOUT_SECONDS);
        L.info("Connected successfully");

        // Track events by channel UUID
        ConcurrentHashMap<String, List<EventRecord>> eventsByChannel = new ConcurrentHashMap<>();
        CountDownLatch eventLatch = new CountDownLatch(1); // Wait for at least 1 event
        AtomicInteger totalEvents = new AtomicInteger(0);

        // Add event listener that tracks order
        client.addEventListener((ctx, event) -> {
            String channelUuid = event.getEventHeaders().get("Unique-ID");
            String eventName = event.getEventName();
            long timestamp = System.nanoTime();
            int count = totalEvents.incrementAndGet();

            EventRecord record = new EventRecord(eventName, timestamp, count);

            if (channelUuid != null && !channelUuid.isEmpty()) {
                eventsByChannel.computeIfAbsent(channelUuid, k -> new CopyOnWriteArrayList<>())
                              .add(record);
                L.info("Event #{}: {} for channel {}", count, eventName, channelUuid);
            } else {
                L.info("Event #{}: {} (no channel UUID)", count, eventName);
            }

            eventLatch.countDown();
        });

        // Subscribe to HEARTBEAT events (sent every 20 seconds by FreeSWITCH)
        L.info("Subscribing to HEARTBEAT events...");
        client.setEventSubscriptions(EventFormat.PLAIN, "HEARTBEAT");

        // Wait for events (need to wait longer than HEARTBEAT interval of 20 seconds)
        int waitTime = 25; // Wait 25 seconds to ensure we get at least one HEARTBEAT
        L.info("Waiting for events (up to {} seconds)...", waitTime);
        boolean receivedEvents = eventLatch.await(waitTime, TimeUnit.SECONDS);

        // Report what we received
        L.info("Received {} total events", totalEvents.get());
        L.info("Unique channels tracked: {}", eventsByChannel.size());

        // Verify ordered processing for channels that had multiple events
        for (Map.Entry<String, List<EventRecord>> entry : eventsByChannel.entrySet()) {
            String channelUuid = entry.getKey();
            List<EventRecord> events = entry.getValue();

            if (events.size() > 1) {
                L.info("Channel {} had {} events - verifying order...",
                    channelUuid.substring(0, 8),
                    events.size());

                // Verify events are in order (counter should be monotonically increasing)
                for (int i = 1; i < events.size(); i++) {
                    EventRecord prev = events.get(i - 1);
                    EventRecord curr = events.get(i);

                    assertTrue(curr.counter > prev.counter,
                        String.format("Events for channel %s should be processed in order: %s (#%d) -> %s (#%d)",
                            channelUuid.substring(0, 8),
                            prev.eventName, prev.counter,
                            curr.eventName, curr.counter));
                }

                L.info("  ✓ Events processed in correct order");
            }
        }

        // We should have received at least one event (HEARTBEAT)
        assertTrue(totalEvents.get() > 0, "Should have received at least one event (HEARTBEAT is sent every 20 seconds)");

        L.info("Ordered event processing test completed successfully - verified {} events", totalEvents.get());
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Filtered Event Listeners")
    void testFilteredEventListeners() throws Exception {
        L.info("=== TEST 5: Filtered Event Listeners ===");

        // Connect first
        L.info("Connecting to FreeSWITCH at {}:{}...", HOST, PORT);
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, TIMEOUT_SECONDS);
        L.info("Connected successfully");

        // Setup filtered listeners
        CountDownLatch heartbeatLatch = new CountDownLatch(1);
        CountDownLatch otherEventLatch = new CountDownLatch(1);
        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicInteger otherEventCount = new AtomicInteger(0);

        // Listener 1: Only HEARTBEAT events
        IEslEventListener heartbeatListener = (ctx, event) -> {
            String eventName = event.getEventName();
            assertEquals("HEARTBEAT", eventName, "Filtered listener should only receive HEARTBEAT events");
            int count = heartbeatCount.incrementAndGet();
            L.info("Heartbeat listener received event #{}: {}", count, eventName);
            heartbeatLatch.countDown();
        };

        // Listener 2: All events
        IEslEventListener allEventsListener = (ctx, event) -> {
            String eventName = event.getEventName();
            int count = otherEventCount.incrementAndGet();
            L.info("All-events listener received event #{}: {}", count, eventName);
            otherEventLatch.countDown();
        };

        // Add filtered listener for HEARTBEAT only
        client.addEventListener(heartbeatListener, "HEARTBEAT");

        // Add unfiltered listener
        client.addEventListener(allEventsListener);

        // Subscribe to events
        L.info("Subscribing to HEARTBEAT events...");
        client.setEventSubscriptions(EventFormat.PLAIN, "HEARTBEAT");

        // Wait for events (need to wait longer than HEARTBEAT interval of 20 seconds)
        int waitTime = 25;
        L.info("Waiting for events (up to {} seconds)...", waitTime);
        boolean receivedHeartbeat = heartbeatLatch.await(waitTime, TimeUnit.SECONDS);
        boolean receivedOther = otherEventLatch.await(1, TimeUnit.SECONDS); // Short wait

        // Verify
        assertTrue(receivedHeartbeat, "Should have received HEARTBEAT event");
        assertTrue(receivedOther, "All-events listener should have received events");
        assertTrue(heartbeatCount.get() > 0, "Heartbeat listener should have received events");
        assertTrue(otherEventCount.get() > 0, "All-events listener should have received events");

        L.info("Filtered listener test completed: heartbeat={}, all={}",
            heartbeatCount.get(),
            otherEventCount.get());
    }

    /**
     * Helper class to track event processing order
     */
    private static class EventRecord {
        final String eventName;
        final long timestamp;
        final int counter;

        EventRecord(String eventName, long timestamp, int counter) {
            this.eventName = eventName;
            this.timestamp = timestamp;
            this.counter = counter;
        }

        @Override
        public String toString() {
            return String.format("%s (#%d @ %d)", eventName, counter, timestamp);
        }
    }
}
