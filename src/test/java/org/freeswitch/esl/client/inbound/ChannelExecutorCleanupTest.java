package org.freeswitch.esl.client.inbound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test to verify channel executor cleanup during connection closure.
 *
 * This test verifies the fix for the resource leak where channel executors
 * were not being cleaned up when connections were closed during reconnection.
 */
class ChannelExecutorCleanupTest {

    private Client client;
    private Field channelExecutorsField;
    private Method closeConnectionMethod;

    @BeforeEach
    void setUp() throws Exception {
        client = new Client();

        // Access private channelExecutors field
        channelExecutorsField = Client.class.getDeclaredField("channelExecutors");
        channelExecutorsField.setAccessible(true);

        // Access private closeConnection method
        closeConnectionMethod = Client.class.getDeclaredMethod("closeConnection");
        closeConnectionMethod.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Test
    @DisplayName("Channel executors should be cleaned up when closeConnection is called")
    void testChannelExecutorsCleanupOnCloseConnection() throws Exception {
        // Get the channelExecutors map
        @SuppressWarnings("unchecked")
        Map<String, ExecutorService> channelExecutors =
            (Map<String, ExecutorService>) channelExecutorsField.get(client);

        // Initially should be empty
        assertEquals(0, channelExecutors.size(), "Initially no executors should exist");

        // Simulate creation of channel executors (as would happen during event processing)
        ExecutorService executor1 = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        ExecutorService executor2 = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        ExecutorService executor3 = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

        channelExecutors.put("channel-uuid-1", executor1);
        channelExecutors.put("channel-uuid-2", executor2);
        channelExecutors.put("channel-uuid-3", executor3);

        // Verify executors were added
        assertEquals(3, channelExecutors.size(), "Should have 3 executors");

        // Call closeConnection - this should clean up all executors
        closeConnectionMethod.invoke(client);

        // Verify all executors were shut down and removed
        assertEquals(0, channelExecutors.size(),
            "All channel executors should be removed after closeConnection");

        // Verify executors were actually shut down
        assertTrue(executor1.isShutdown(), "Executor 1 should be shut down");
        assertTrue(executor2.isShutdown(), "Executor 2 should be shut down");
        assertTrue(executor3.isShutdown(), "Executor 3 should be shut down");
    }

    @Test
    @DisplayName("Executors cleanup is consistent between close and closeConnection")
    void testExecutorCleanupConsistency() throws Exception {
        // This test verifies that closeConnection now has the same cleanup logic as close
        // Both should clean up channel executors

        // Get the channelExecutors map
        @SuppressWarnings("unchecked")
        Map<String, ExecutorService> channelExecutors =
            (Map<String, ExecutorService>) channelExecutorsField.get(client);

        // Test closeConnection cleanup
        ExecutorService executor1 = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        channelExecutors.put("test-uuid-1", executor1);
        assertEquals(1, channelExecutors.size());

        closeConnectionMethod.invoke(client);

        assertEquals(0, channelExecutors.size(),
            "closeConnection should clean up executors");
        assertTrue(executor1.isShutdown(),
            "closeConnection should shutdown executors");

        // Note: We cannot easily test close() without a real connection,
        // but the close() method at line 531-532 uses the same cleanup logic
    }

    @Test
    @DisplayName("closeConnection should handle empty executor map gracefully")
    void testCloseConnectionWithNoExecutors() throws Exception {
        // Get the channelExecutors map
        @SuppressWarnings("unchecked")
        Map<String, ExecutorService> channelExecutors =
            (Map<String, ExecutorService>) channelExecutorsField.get(client);

        // Verify map is empty
        assertEquals(0, channelExecutors.size(), "Should have no executors");

        // Call closeConnection with no executors - should not throw
        assertDoesNotThrow(() -> closeConnectionMethod.invoke(client),
            "closeConnection should handle empty executor map gracefully");

        // Verify map is still empty
        assertEquals(0, channelExecutors.size(), "Should still have no executors");
    }

    @Test
    @DisplayName("Multiple closeConnection calls should be safe")
    void testMultipleCloseConnectionCalls() throws Exception {
        // Get the channelExecutors map
        @SuppressWarnings("unchecked")
        Map<String, ExecutorService> channelExecutors =
            (Map<String, ExecutorService>) channelExecutorsField.get(client);

        // Add some executors
        ExecutorService executor1 = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        channelExecutors.put("channel-uuid-1", executor1);

        assertEquals(1, channelExecutors.size(), "Should have 1 executor");

        // First close
        closeConnectionMethod.invoke(client);
        assertEquals(0, channelExecutors.size(), "Should have no executors after first close");
        assertTrue(executor1.isShutdown(), "Executor should be shut down");

        // Second close - should not throw
        assertDoesNotThrow(() -> closeConnectionMethod.invoke(client),
            "Multiple closeConnection calls should be safe");

        assertEquals(0, channelExecutors.size(), "Should still have no executors");
    }
}
