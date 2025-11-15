/*
 * Copyright 2010 david varnes.
 *
 * Licensed under the Apache License, version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.freeswitch.esl.client.inbound;

import com.google.common.base.Throwables;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.internal.IModEslApi;
import org.freeswitch.esl.client.transport.CommandResponse;
import org.freeswitch.esl.client.transport.SendMsg;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.socket.SocketFrameDecoder;
import org.freeswitch.esl.client.transport.socket.SocketWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point to connect to a running FreeSWITCH Event Socket Library module, as a client.
 * <p/>
 * This class provides what the FreeSWITCH documentation refers to as an 'Inbound' connection
 * to the Event Socket module. That is, with reference to the socket listening on the FreeSWITCH
 * server, this client occurs as an inbound connection to the server.
 * <p/>
 * See <a href="http://wiki.freeswitch.org/wiki/Mod_event_socket">http://wiki.freeswitch.org/wiki/Mod_event_socket</a>
 */
public class Client implements IModEslApi {

	/**
	 * Internal wrapper for event listeners with optional Event-Name filtering.
	 */
	private static class FilteredListener {
		final IEslEventListener listener;
		final Set<String> eventNames; // null = all events

		FilteredListener(IEslEventListener listener, Set<String> eventNames) {
			this.listener = listener;
			this.eventNames = eventNames;
		}

		boolean matches(String eventName) {
			return eventNames == null || eventNames.contains(eventName);
		}
	}

	private final Logger log = LoggerFactory.getLogger(this.getClass());
	private final List<FilteredListener> eventListeners = new CopyOnWriteArrayList<>();
	private final AtomicBoolean authenticatorResponded = new AtomicBoolean(false);
	private final ConcurrentHashMap<String, CompletableFuture<EslEvent>> backgroundJobs =
			new ConcurrentHashMap<>();

	// Channel-partitioned executors: each channel UUID gets its own single-threaded virtual thread executor
	// This ensures events for the same channel are processed in order, while different channels can process concurrently
	private final ConcurrentHashMap<String, ExecutorService> channelExecutors = new ConcurrentHashMap<>();

	private boolean authenticated;
	private CommandResponse authenticationResponse;
	private Optional<Context> clientContext = Optional.empty();
	private Optional<SocketWrapper> socket = Optional.empty();
	private Optional<Thread> messageReaderThread = Optional.empty();
	// Executor for events without channel UUID (e.g., HEARTBEAT, system events)
	private ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();

	// Auto-update server subscription based on listener filters
	private boolean autoUpdateServerSubscription = false;
	private String lastServerSubscription = null;

	// Reconnection support (enabled by default)
	private boolean reconnectionEnabled = true;
	private ReconnectionConfig reconnectionConfig = new ReconnectionConfig();
	private HeartbeatMonitor heartbeatMonitor;
	private ExponentialBackoffStrategy reconnectStrategy;
	private ScheduledExecutorService healthCheckExecutor;
	private ScheduledFuture<?> healthCheckTask;
	private final AtomicBoolean reconnecting = new AtomicBoolean(false);
	private final AtomicBoolean connected = new AtomicBoolean(false);

	// Connection parameters (saved for reconnection)
	private SocketAddress savedAddress;
	private String savedPassword;
	private int savedTimeoutSeconds;
	private volatile String savedEventSubscription;
	private volatile EventFormat savedEventFormat;

	/**
	 * Add a global event listener that receives all events.
	 *
	 * @param listener the event listener to add
	 */
	public void addEventListener(IEslEventListener listener) {
		if (listener != null) {
			eventListeners.add(new FilteredListener(listener, null));
			updateServerSubscriptions();
		}
	}

	/**
	 * Add an event listener that only receives specific event types (filtered by Event-Name).
	 *
	 * @param listener the event listener to add
	 * @param eventNames the event names to filter (e.g., "CHANNEL_CREATE", "CHANNEL_HANGUP")
	 */
	public void addEventListener(IEslEventListener listener, String... eventNames) {
		if (listener != null && eventNames != null && eventNames.length > 0) {
			Set<String> eventNameSet = new HashSet<>(Arrays.asList(eventNames));
			eventListeners.add(new FilteredListener(listener, eventNameSet));
			updateServerSubscriptions();
		}
	}

	/**
	 * Remove an event listener.
	 *
	 * @param listener the event listener to remove
	 * @return true if the listener was found and removed
	 */
	public boolean removeEventListener(IEslEventListener listener) {
		if (listener == null) {
			return false;
		}
		boolean removed = eventListeners.removeIf(fl -> fl.listener == listener);
		if (removed) {
			updateServerSubscriptions();
		}
		return removed;
	}

	@Override
	public boolean canSend() {
		return clientContext.isPresent()
			&& clientContext.get().canSend()
			&& authenticated;
	}

	private void checkConnected() {
		if (!canSend()) {
			throw new IllegalStateException("Not connected to FreeSWITCH Event Socket");
		}
	}

	public void setCallbackExecutor(ExecutorService callbackExecutor) {
		this.callbackExecutor = callbackExecutor;
	}

	/**
	 * Enable or disable automatic server-side event subscription optimization.
	 * When enabled, the client will automatically calculate the union of all listener event filters
	 * and update the server subscription accordingly to minimize network traffic.
	 *
	 * @param enable true to enable auto-update, false to disable (default: false)
	 */
	public void setAutoUpdateServerSubscription(boolean enable) {
		this.autoUpdateServerSubscription = enable;
		if (enable && canSend()) {
			// Immediately update if already connected
			updateServerSubscriptions();
		}
	}

	/**
	 * Enable or disable automatic reconnection on connection failure.
	 * When enabled, the client will monitor HEARTBEAT events and automatically reconnect
	 * if the connection is lost, using exponential backoff strategy.
	 *
	 * @param enable true to enable reconnection (default: true), false to disable
	 */
	public void setReconnectable(boolean enable) {
		this.reconnectionEnabled = enable;

		if (enable && connected.get()) {
			// If enabling and already connected, start monitoring
			if (heartbeatMonitor == null) {
				heartbeatMonitor = new HeartbeatMonitor(reconnectionConfig.getHeartbeatTimeoutMs());
			}
			if (reconnectStrategy == null) {
				reconnectStrategy = ExponentialBackoffStrategy.fromConfig(reconnectionConfig);
			}
			startHealthCheck();
		} else if (!enable) {
			// If disabling, stop monitoring
			stopHealthCheck();
		}

		log.info("Reconnection {}", enable ? "enabled" : "disabled");
	}

	/**
	 * Set the reconnection configuration.
	 * This allows customization of heartbeat timeout, health check interval, and reconnection strategy.
	 *
	 * @param config the reconnection configuration
	 */
	public void setReconnectionConfig(ReconnectionConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("ReconnectionConfig cannot be null");
		}
		this.reconnectionConfig = config;

		// Update monitors if already initialized
		if (heartbeatMonitor != null) {
			heartbeatMonitor = new HeartbeatMonitor(config.getHeartbeatTimeoutMs());
		}
		if (reconnectStrategy != null) {
			reconnectStrategy = ExponentialBackoffStrategy.fromConfig(config);
		}

		// Restart health check if running
		if (healthCheckTask != null && !healthCheckTask.isCancelled()) {
			stopHealthCheck();
			startHealthCheck();
		}

		log.info("Reconnection config updated: {}", config);
	}

	/**
	 * Check if the client is currently in reconnection state.
	 *
	 * @return true if reconnection is in progress, false otherwise
	 */
	public boolean isReconnecting() {
		return reconnecting.get();
	}

	/**
	 * Attempt to establish an authenticated connection to the nominated FreeSWITCH ESL server socket.
	 * This call will block, waiting for an authentication handshake to occur, or timeout after the
	 * supplied number of seconds.
	 *
	 * @param clientAddress  a SocketAddress representing the endpoint to connect to
	 * @param password       server event socket is expecting (set in event_socket_conf.xml)
	 * @param timeoutSeconds number of seconds to wait for the server socket before aborting
	 */
	public void connect(SocketAddress clientAddress, String password, int timeoutSeconds) throws InboundConnectionFailure {
		// If already connected, disconnect first
		if (canSend()) {
			close();
		}

		// Save connection parameters for reconnection
		this.savedAddress = clientAddress;
		this.savedPassword = password;
		this.savedTimeoutSeconds = timeoutSeconds;

		// Perform initial connection
		try {
			doConnect();
		} catch (Exception e) {
			if (e instanceof InboundConnectionFailure) {
				throw (InboundConnectionFailure) e;
			}
			throw new InboundConnectionFailure("Failed to connect to " + clientAddress, e);
		}
	}

	/**
	 * Message reader loop - runs in a virtual thread.
	 * Reads and processes ESL messages from the socket.
	 */
	private void messageReaderLoop(SocketWrapper socket, InboundClientHandler handler) {
		SocketFrameDecoder decoder = new SocketFrameDecoder(8192);

		try {
			while (socket.isConnected() && !Thread.currentThread().isInterrupted()) {
				try {
					EslMessage message = decoder.decode(socket.getInputStream());
					log.debug("Received message: {}", message.getContentType());

					// Process message through handler
					handler.processMessage(socket, message);

				} catch (Exception e) {
					log.error("Error reading/processing message", e);
					handler.handleException(e);
					break;
				}
			}
		} finally {
			log.info("Message reader thread exiting");
		}
	}

	/**
	 * Sends a FreeSWITCH API command to the server and blocks, waiting for an immediate response from the
	 * server.
	 * <p/>
	 * The outcome of the command from the server is retured in an {@link EslMessage} object.
	 *
	 * @param command API command to send
	 * @param arg     command arguments
	 * @return an {@link EslMessage} containing command results
	 */
	@Override
	public EslMessage sendApiCommand(String command, String arg) {
		checkConnected();
		return clientContext.get().sendApiCommand(command, arg);
	}

	/**
	 * Submit a FreeSWITCH API command to the server to be executed in background mode. A synchronous
	 * response from the server provides a UUID to identify the job execution results. When the server
	 * has completed the job execution it fires a BACKGROUND_JOB Event with the execution results.<p/>
	 * Note that this Client must be subscribed in the normal way to BACKGOUND_JOB Events, in order to
	 * receive this event.
	 *
	 * @param command API command to send
	 * @param arg     command arguments
	 * @return String Job-UUID that the server will tag result event with.
	 */
	@Override
	public CompletableFuture<EslEvent> sendBackgroundApiCommand(String command, String arg) {
		checkConnected();
		return clientContext.get().sendBackgroundApiCommand(command, arg);
	}

	/**
	 * Set the current event subscription for this connection to the server.  Examples of the events
	 * argument are:
	 * <pre>
	 *   ALL
	 *   CHANNEL_CREATE CHANNEL_DESTROY HEARTBEAT
	 *   CUSTOM conference::maintenance
	 *   CHANNEL_CREATE CHANNEL_DESTROY CUSTOM conference::maintenance sofia::register sofia::expire
	 * </pre>
	 * Subsequent calls to this method replaces any previous subscriptions that were set.
	 * </p>
	 * Note: current implementation can only process 'plain' events.
	 *
	 * @param format can be { plain | xml }
	 * @param events { all | space separated list of events }
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse setEventSubscriptions(EventFormat format, String events) {
		checkConnected();

		// Save subscription for restoration after reconnection
		this.savedEventFormat = format;
		this.savedEventSubscription = events;

		return clientContext.get().setEventSubscriptions(format, events);
	}

	/**
	 * Cancel any existing event subscription.
	 *
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse cancelEventSubscriptions() {
		checkConnected();
		return clientContext.get().cancelEventSubscriptions();
	}

	/**
	 * Add an event filter to the current set of event filters on this connection. Any of the event headers
	 * can be used as a filter.
	 * </p>
	 * Note that event filters follow 'filter-in' semantics. That is, when a filter is applied
	 * only the filtered values will be received. Multiple filters can be added to the current
	 * connection.
	 * </p>
	 * Example filters:
	 * <pre>
	 *    eventHeader        valueToFilter
	 *    ----------------------------------
	 *    Event-Name         CHANNEL_EXECUTE
	 *    Channel-State      CS_NEW
	 * </pre>
	 *
	 * @param eventHeader   to filter on
	 * @param valueToFilter the value to match
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse addEventFilter(String eventHeader, String valueToFilter) {
		checkConnected();
		return clientContext.get().addEventFilter(eventHeader, valueToFilter);
	}

	/**
	 * Delete an event filter from the current set of event filters on this connection.  See
	 *
	 * @param eventHeader   to remove
	 * @param valueToFilter to remove
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse deleteEventFilter(String eventHeader, String valueToFilter) {
		checkConnected();
		return clientContext.get().deleteEventFilter(eventHeader, valueToFilter);
	}

	/**
	 * Send a {@link SendMsg} command to FreeSWITCH.  This client requires that the {@link SendMsg}
	 * has a call UUID parameter.
	 *
	 * @param sendMsg a {@link SendMsg} with call UUID
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse sendMessage(SendMsg sendMsg) {
		checkConnected();
		return clientContext.get().sendMessage(sendMsg);
	}

	/**
	 * Enable log output.
	 *
	 * @param level using the same values as in console.conf
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse setLoggingLevel(LoggingLevel level) {
		checkConnected();
		return clientContext.get().setLoggingLevel(level);
	}

	/**
	 * Disable any logging previously enabled with setLogLevel().
	 *
	 * @return a {@link CommandResponse} with the server's response.
	 */
	@Override
	public CommandResponse cancelLogging() {
		checkConnected();
		return clientContext.get().cancelLogging();
	}

	/**
	 * Automatically update server-side event subscriptions based on all listener filters.
	 * This method is called automatically when auto-update is enabled and listeners are added/removed.
	 */
	private void updateServerSubscriptions() {
		// Only update if feature is enabled and connected
		if (!autoUpdateServerSubscription || !canSend()) {
			return;
		}

		// Calculate required subscription
		String newSubscription;
		boolean hasGlobalListener = false;
		Set<String> allEventTypes = new HashSet<>();

		for (FilteredListener fl : eventListeners) {
			if (fl.eventNames == null) {
				// Global listener found - need to subscribe to all events
				hasGlobalListener = true;
				break;
			}
			allEventTypes.addAll(fl.eventNames);
		}

		if (hasGlobalListener || allEventTypes.isEmpty()) {
			// Subscribe to all events
			newSubscription = "all";
		} else {
			// Subscribe to specific event types (sorted for consistent comparison)
			newSubscription = String.join(" ", allEventTypes.stream().sorted().toList());
		}

		// Only update if subscription changed
		if (newSubscription.equals(lastServerSubscription)) {
			log.debug("Server subscription unchanged: {}", newSubscription);
			return;
		}

		// Update server subscription
		try {
			log.info("Auto-updating server subscription to: {}", newSubscription);
			setEventSubscriptions(EventFormat.PLAIN, newSubscription);
			lastServerSubscription = newSubscription;
		} catch (Exception e) {
			log.error("Failed to auto-update server subscription", e);
		}
	}

	/**
	 * Close the socket connection
	 *
	 * @return a {@link CommandResponse} with the server's response.
	 */
	public CommandResponse close() {
		checkConnected();

		try {
			if (clientContext.isPresent()) {
				CommandResponse response = new CommandResponse("exit", clientContext.get().sendCommand("exit"));

				// Interrupt reader thread
				messageReaderThread.ifPresent(Thread::interrupt);

				// Close socket
				socket.ifPresent(s -> {
					try {
						s.close();
					} catch (Exception e) {
						log.warn("Error closing socket", e);
					}
				});

				// Clear state
				socket = Optional.empty();
				clientContext = Optional.empty();
				messageReaderThread = Optional.empty();
				authenticated = false;
				authenticatorResponded.set(false);

				// Shutdown all channel executors
				channelExecutors.values().forEach(ExecutorService::shutdown);
				channelExecutors.clear();

				return response;
			} else {
				throw new IllegalStateException("not connected/authenticated");
			}
		} catch (Throwable t) {
			throw Throwables.propagate(t);
		}
	}

	/*
		*  Internal observer of the ESL protocol
		*/
	private final IEslProtocolListener protocolListener = new IEslProtocolListener() {

		@Override
		public void authResponseReceived(CommandResponse response) {
			authenticatorResponded.set(true);
			authenticated = response.isOk();
			authenticationResponse = response;
			log.debug("Auth response success={}, message=[{}]", authenticated, response.getReplyText());
		}

		@Override
		public void eventReceived(final Context ctx, final EslEvent event) {
			log.debug("Event received [{}]", event);

			// Get channel UUID and event name from event headers
			String channelUuid = event.getEventHeaders().get("Unique-ID");
			String eventName = event.getEventName();

			// Record heartbeat for reconnection monitoring
			if ("HEARTBEAT".equals(eventName) && heartbeatMonitor != null) {
				heartbeatMonitor.recordHeartbeat();
			}

			for (final FilteredListener fl : eventListeners) {
				// Check if this listener is interested in this event type
				if (!fl.matches(eventName)) {
					continue;
				}

				final IEslEventListener listener = fl.listener;
				if (channelUuid != null && !channelUuid.isEmpty()) {
					// Events with channel UUID: process in order per channel
					ExecutorService channelExecutor = channelExecutors.computeIfAbsent(
							channelUuid,
							uuid -> {
								log.debug("Creating executor for channel {}", uuid);
								return Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
							}
					);

					channelExecutor.execute(() -> {
						try {
							listener.onEslEvent(ctx, event);
						} finally {
							// Cleanup executor when channel is destroyed
							if ("CHANNEL_DESTROY".equals(eventName)) {
								log.debug("Removing executor for channel {}", channelUuid);
								ExecutorService executor = channelExecutors.remove(channelUuid);
								if (executor != null) {
									executor.shutdown();
								}
							}
						}
					});
				} else {
					// Events without channel UUID (HEARTBEAT, etc.): process concurrently
					callbackExecutor.execute(() -> listener.onEslEvent(ctx, event));
				}
			}
		}

		@Override
		public void disconnected() {
			log.info("Disconnected ...");
		}
	};

	/**
	 * Initialize reconnection support after successful connection.
	 * Sets up heartbeat monitoring and health checks.
	 */
	private void initializeReconnectionSupport() {
		if (!reconnectionEnabled) {
			return;
		}

		// Initialize heartbeat monitor
		heartbeatMonitor = new HeartbeatMonitor(reconnectionConfig.getHeartbeatTimeoutMs());

		// Initialize reconnection strategy
		reconnectStrategy = ExponentialBackoffStrategy.fromConfig(reconnectionConfig);

		// Subscribe to HEARTBEAT events for monitoring
		try {
			clientContext.ifPresent(ctx -> ctx.sendCommand("event plain HEARTBEAT"));
			log.debug("Subscribed to HEARTBEAT for reconnection monitoring");
		} catch (Exception e) {
			log.warn("Failed to subscribe to HEARTBEAT events", e);
		}

		// Record initial heartbeat to avoid false timeout
		heartbeatMonitor.reset();

		log.debug("Reconnection support initialized");

		// Start health check
		startHealthCheck();

		// Set connected flag
		connected.set(true);

		log.info("Client connected to {}", savedAddress);
	}

	/**
	 * Start the periodic health check task.
	 */
	private void startHealthCheck() {
		if (healthCheckExecutor == null) {
			healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = Thread.ofVirtual().unstarted(r);
				t.setName("health-check");
				return t;
			});
		}

		healthCheckTask = healthCheckExecutor.scheduleAtFixedRate(
			this::performHealthCheck,
			reconnectionConfig.getHealthCheckIntervalMs(),
			reconnectionConfig.getHealthCheckIntervalMs(),
			TimeUnit.MILLISECONDS
		);

		log.debug("Health check started with interval: {}ms", reconnectionConfig.getHealthCheckIntervalMs());
	}

	/**
	 * Stop the health check task.
	 */
	private void stopHealthCheck() {
		if (healthCheckTask != null) {
			healthCheckTask.cancel(false);
			healthCheckTask = null;
		}

		if (healthCheckExecutor != null) {
			healthCheckExecutor.shutdown();
			healthCheckExecutor = null;
		}
	}

	/**
	 * Perform a health check - called periodically by the health check task.
	 * Checks for heartbeat timeout and triggers reconnection if needed.
	 */
	private void performHealthCheck() {
		if (heartbeatMonitor == null || !connected.get()) {
			return;
		}

		if (heartbeatMonitor.isTimeout()) {
			long elapsed = heartbeatMonitor.getTimeSinceLastHeartbeat();
			log.warn("Heartbeat timeout detected after {}ms, triggering reconnection", elapsed);
			connected.set(false);
			attemptReconnection();
		}
	}

	/**
	 * Attempt to reconnect with exponential backoff.
	 * Runs in a separate virtual thread.
	 */
	private void attemptReconnection() {
		if (!reconnecting.compareAndSet(false, true)) {
			log.debug("Reconnection already in progress, skipping");
			return;
		}

		log.info("Starting reconnection process");

		Thread.startVirtualThread(() -> {
			while (reconnectionEnabled && reconnecting.get()) {
				try {
					// Clean up old connection
					try {
						closeConnection();
					} catch (Exception e) {
						log.debug("Error closing old connection", e);
					}

					// Calculate delay with exponential backoff
					long delay = reconnectStrategy.nextDelay();
					log.info("Waiting {}ms before reconnection attempt {} ...",
							delay, reconnectStrategy.getAttemptCount());
					Thread.sleep(delay);

					// Attempt reconnection
					doConnect();

					// Success
					log.info("Reconnection successful after {} attempts", reconnectStrategy.getAttemptCount());
					reconnectStrategy.reset();
					reconnecting.set(false);
					connected.set(true);

					if (heartbeatMonitor != null) {
						heartbeatMonitor.reset();
					}

					// Exit the reconnection loop
					break;

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.warn("Reconnection interrupted");
					reconnecting.set(false);
					break;
				} catch (Exception e) {
					log.error("Reconnection attempt {} failed: {}",
							reconnectStrategy.getAttemptCount(), e.getMessage());
					// Continue loop to retry
				}
			}
		});
	}

	/**
	 * Close the current connection cleanly.
	 */
	private void closeConnection() {
		// Interrupt reader thread
		messageReaderThread.ifPresent(Thread::interrupt);

		// Close socket
		socket.ifPresent(s -> {
			try {
				s.close();
			} catch (Exception e) {
				log.warn("Error closing socket", e);
			}
		});

		// Shutdown all channel executors
		channelExecutors.values().forEach(ExecutorService::shutdown);
		channelExecutors.clear();

		// Clear state
		socket = Optional.empty();
		clientContext = Optional.empty();
		messageReaderThread = Optional.empty();
	}

	/**
	 * Internal connection method used by connect() and reconnection logic.
	 */
	private void doConnect() throws Exception {
		log.info("Connecting to {} ...", savedAddress);

		// Create socket connection with timeout
		SocketWrapper socketWrapper = SocketWrapper.connect(savedAddress, savedTimeoutSeconds * 1000);
		this.socket = Optional.of(socketWrapper);

		log.info("Connected to {}", savedAddress);

		// Create handler
		InboundClientHandler handler = new InboundClientHandler(savedPassword, protocolListener);

		// Create context
		this.clientContext = Optional.of(new Context(socketWrapper, handler));

		// Reset authentication state
		authenticated = false;
		authenticatorResponded.set(false);

		// Start message reader thread (virtual thread)
		Thread readerThread = Thread.startVirtualThread(() -> messageReaderLoop(socketWrapper, handler));
		this.messageReaderThread = Optional.of(readerThread);

		// Wait for the authentication handshake to complete
		long startTime = System.currentTimeMillis();
		while (!authenticatorResponded.get()) {
			if (System.currentTimeMillis() - startTime > savedTimeoutSeconds * 1000L) {
				throw new InboundConnectionFailure("Timeout waiting for authentication response");
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InboundConnectionFailure("Interrupted while waiting for authentication", e);
			}
		}

		if (!authenticated) {
			throw new InboundConnectionFailure("Authentication failed: " + authenticationResponse.getReplyText());
		}

		log.info("Authenticated");

		// Restore event subscriptions
		if (savedEventSubscription != null && savedEventFormat != null) {
			setEventSubscriptions(savedEventFormat, savedEventSubscription);
			log.debug("Restored event subscriptions: {}", savedEventSubscription);
		}

		// Reinitialize reconnection support
		initializeReconnectionSupport();
	}
}
