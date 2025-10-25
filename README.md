# esl-client

**Java 21 Virtual Threads** Event Socket Library for [FreeSWITCH](https://freeswitch.org/)

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Gradle](https://img.shields.io/badge/Gradle-9.1-blue.svg)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

## Overview

**esl-client** is a modern Java-based Event Socket Library for FreeSWITCH, completely rewritten to leverage **Java 21 Virtual Threads (Project Loom)** for maximum performance and scalability.

This version removes all Netty dependencies and uses native Java Sockets with Virtual Threads, providing:

- ✅ **Zero external networking dependencies** - Pure Java implementation
- ✅ **Massive scalability** - Handle millions of concurrent connections with Virtual Threads
- ✅ **Simplified architecture** - Straightforward blocking I/O model that's easy to understand
- ✅ **High performance** - Virtual threads provide excellent throughput with minimal overhead
- ✅ **Modern Java** - Takes full advantage of Java 21 features

This project is a fork of the original unmaintained project at:
<https://github.com/esl-client/esl-client>

## Requirements

- **Java 21 or higher** (for Virtual Threads support)
- **Gradle 9.1+** (for building)
- FreeSWITCH server with Event Socket module enabled

## Quick Start

### Inbound Connection (Client Mode)

Connect to FreeSWITCH and receive events:

```java
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;

import java.net.InetSocketAddress;

public class InboundExample {
    public static void main(String[] args) throws Exception {
        // Create client (uses virtual threads internally)
        Client client = new Client();

        // Reconnection is enabled by default
        // To disable: client.setReconnectable(false);

        // Add event listener
        client.addEventListener((ctx, event) -> {
            System.out.println("Event: " + event.getEventName());
        });

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);

        // Subscribe to events
        client.setEventSubscriptions(EventFormat.PLAIN, "all");

        // Keep running
        Thread.currentThread().join();
    }
}
```

### Outbound Connection (Server Mode)

Accept connections from FreeSWITCH and control calls with the Execute API:

```java
import org.freeswitch.esl.client.dptools.Execute;
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.event.EslEvent;

import java.net.InetSocketAddress;

public class OutboundExample {
    public static void main(String[] args) throws Exception {
        // Create outbound server (uses virtual threads for each connection)
        SocketClient server = new SocketClient(
            new InetSocketAddress("localhost", 8084),
            () -> new IClientHandler() {
                @Override
                public void onConnect(Context context, EslEvent event) {
                    String uuid = event.getEventHeaders().get("Unique-ID");
                    Execute exe = new Execute(context, uuid);

                    try {
                        // Blocking calls are safe in virtual threads!
                        exe.answer();
                        String digits = exe.playAndGetDigits(3, 10, 3, 10000, "#",
                            "prompt.wav", "invalid.wav", "^\\d+", 10000);
                        System.out.println("Collected digits: " + digits);
                        exe.hangup();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onEslEvent(Context ctx, EslEvent event) {
                    // Handle events
                }
            }
        );

        // Start server
        server.start();

        System.out.println("Outbound server listening on port 8084");
        Thread.currentThread().join();
    }
}
```

## Building

### Using Gradle

```bash
# Build the project
gradle clean build

# Skip tests
gradle build -x test

# Build JAR
gradle jar
```

### Using SDKMAN for Gradle

```bash
# Install Gradle 9.1
sdk install gradle 9.1.0

# Build
gradle clean build
```

## Virtual Threads Architecture

This library makes extensive use of Java 21 Virtual Threads with **guaranteed event ordering**:

### Inbound Mode (Client)

**Message Processing**:
- 1 virtual thread per connection for reading ESL messages

**Event Processing with Ordering**:
- Each Channel UUID gets its own single-threaded virtual thread executor
- Events for the same channel are processed **sequentially** (preserves order)
- Different channels process **concurrently** (maximum throughput)
- Events without Channel UUID (HEARTBEAT, etc.) use shared thread pool

```java
// Per-channel executor ensures sequential processing
ExecutorService channelExecutor = channelExecutors.computeIfAbsent(
    channelUuid,
    uuid -> Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
);
```

**Critical**: This ensures channel state transitions maintain correct order:
```
CHANNEL_CREATE → CHANNEL_ANSWER → CHANNEL_EXECUTE → CHANNEL_HANGUP
```

### Outbound Mode (Server)

**Connection Handling**:
- 1 virtual thread for accepting connections
- 1 virtual thread per FreeSWITCH connection for reading messages

**Event Processing with Ordering**:
- Each connection gets its own single-threaded virtual thread executor
- Events are processed **sequentially** to preserve order
- Virtual thread overhead is minimal (~1KB per thread)

```java
// Single-threaded executor per connection ensures event ordering
private final ExecutorService eventExecutor =
    Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
```

## Performance Benefits

**Virtual Threads** provide massive advantages over traditional threading models:

- **Low Memory Overhead**: Each virtual thread uses only ~1KB of memory (vs ~1MB for platform threads)
- **Millions of Threads**: Can handle millions of concurrent connections on commodity hardware
- **Simple Code**: Write blocking I/O code that performs like async code
- **JVM Optimized**: Automatic scheduling and management by the JVM

**Removed Dependencies**:
- ✅ No Netty (removed ~15k lines of dependency code)
- ✅ Smaller JAR size (~52KB)
- ✅ Faster startup time
- ✅ Easier to debug and maintain

## API Changes from Previous Versions

### Breaking Changes

1. **Java Version**: Requires Java 21+ (was Java 8)
2. **SocketClient.start()**: Changed from `startAsync()` to `start()` (removed Guava dependency)

### New Features

**Execute API (Outbound Mode)**
- `Execute` class with blocking-style dialplan applications
- `Execute.playAndGetDigits()` - now properly waits for user input completion (uses `event-uuid` mechanism)
- `Execute.answer()`, `Execute.hangup()`, etc. - all blocking calls safe with Virtual Threads
- Automatic tracking via `CHANNEL_EXECUTE_COMPLETE` events

**Event Filtering & Optimization**
- `Client.addEventListener(listener, eventNames...)` - filtered listeners for specific event types
- `Client.removeEventListener(listener)` - remove specific listeners
- `Client.setAutoUpdateServerSubscription(boolean)` - automatic server-side subscription optimization

**Automatic Reconnection**
- `Client.setReconnectable(boolean)` - enable/disable auto-reconnection (enabled by default)
- `Client.setReconnectionConfig(config)` - customize reconnection behavior
- Heartbeat monitoring and automatic connection recovery
- State preservation across reconnections

### Compatible APIs

All core APIs remain the same:
- `Client.connect()`
- `Client.addEventListener()`
- `Client.setEventSubscriptions()`
- All ESL commands and message handling

## FreeSWITCH Configuration

### Event Socket Configuration

In `conf/autoload_configs/event_socket.conf.xml`:

```xml
<configuration name="event_socket.conf" description="Socket Client">
  <settings>
    <param name="nat-map" value="false"/>
    <param name="listen-ip" value="127.0.0.1"/>
    <param name="listen-port" value="8021"/>
    <param name="password" value="ClueCon"/>
  </settings>
</configuration>
```

## Testing

Run the included test to verify your setup:

```bash
# Compile and run test
gradle compileTestJava
java -cp build/classes/java/main:build/classes/java/test:<dependencies> \
     SimpleTest ClueCon
```

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## License

**esl-client** is licensed under the [Apache License, version 2](LICENSE).

## Additional Resources

- [FreeSWITCH Event Socket Documentation](https://developer.signalwire.com/freeswitch/FreeSWITCH-Explained/Client-and-Developer-Interfaces/Event-Socket-Library/)
- [Java 21 Virtual Threads Guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)

---

**Powered by Java 21 Virtual Threads** 🚀
