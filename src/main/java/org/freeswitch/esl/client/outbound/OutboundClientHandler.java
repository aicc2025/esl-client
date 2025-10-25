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
package org.freeswitch.esl.client.outbound;

import org.freeswitch.esl.client.internal.AbstractEslClientHandler;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.socket.SocketWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;

/**
 * Specialized {@link AbstractEslClientHandler} that implements the base connection logic for an
 * 'Outbound' FreeSWITCH Event Socket connection.  The responsibilities for this class are:
 * <ul><li>
 * To send a 'connect' command when the FreeSWITCH server first establishes a new connection with
 * the socket client in Outbound mode.  This will result in an incoming {@link EslMessage} that is
 * transformed into an {@link EslEvent} that sub classes can handle.
 * </ul>
 * All message processing is done in virtual threads for maximum scalability.
 */
class OutboundClientHandler extends AbstractEslClientHandler {

	private final IClientHandler clientHandler;
	// Single-threaded virtual thread executor for this connection to ensure event ordering
	private final ExecutorService eventExecutor;
	// Map to track pending execute completions: key is Application-UUID, value is CompletableFuture
	private final Map<String, CompletableFuture<EslEvent>> pendingExecutions = new ConcurrentHashMap<>();

	public OutboundClientHandler(IClientHandler clientHandler, ExecutorService callbackExecutor) {
		this.clientHandler = clientHandler;
		// Each outbound connection gets its own single-threaded executor to preserve event order
		this.eventExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
	}

	/**
	 * Called when a new connection is established.
	 * Sends 'connect' command to FreeSWITCH server.
	 * The onConnect callback is executed in a virtual thread to allow blocking calls.
	 */
	public void onConnectionEstablished(final SocketWrapper socket) {
		// Have received a connection from FreeSWITCH server, send connect response
		log.debug("Received new connection from server, sending connect message");

		sendApiSingleLineCommand(socket, "connect")
				.thenAccept(response -> {
					// Execute onConnect in a virtual thread to allow blocking calls
					// This enables user code to use Execute API (answer(), playAndGetDigits(), etc.) directly
					Thread.startVirtualThread(() -> {
						try {
							clientHandler.onConnect(
									new Context(socket, OutboundClientHandler.this),
									new EslEvent(response, true));
						} catch (Exception e) {
							log.error("Error in onConnect callback", e);
							try {
								socket.close();
							} catch (Exception ignored) {
							}
						}
					});
				})
				.exceptionally(throwable -> {
					try {
						socket.close();
					} catch (Exception ignored) {
					}
					handleDisconnectionNotice();
					return null;
				});
	}

	@Override
	protected void handleEslEvent(final SocketWrapper socket, final EslEvent event) {
		// Use single-threaded executor to ensure events are processed in order
		eventExecutor.execute(() -> {
			// First, check if anyone is waiting for this event
			if ("CHANNEL_EXECUTE_COMPLETE".equals(event.getEventName())) {
				String appUuid = event.getEventHeaders().get("Application-UUID");
				if (appUuid != null) {
					CompletableFuture<EslEvent> future = pendingExecutions.remove(appUuid);
					if (future != null) {
						future.complete(event);
					}
				}
			}

			// Then deliver to user handler
			clientHandler.onEslEvent(
					new Context(socket, OutboundClientHandler.this), event);
		});
	}

	@Override
	protected void handleAuthRequest(SocketWrapper socket) {
		// This should not happen in outbound mode
		log.warn("Auth request received in outbound mode, ignoring");
	}

	@Override
	protected void handleDisconnectionNotice() {
		log.debug("Received disconnection notice");
	}

	/**
	 * Register a listener for CHANNEL_EXECUTE_COMPLETE event with the given Application-UUID.
	 * This allows blocking-style Execute API calls to wait for command completion.
	 *
	 * @param appUuid the Application-UUID to wait for
	 * @return a CompletableFuture that will be completed when the CHANNEL_EXECUTE_COMPLETE event arrives
	 */
	@Override
	public CompletableFuture<EslEvent> waitForExecuteComplete(String appUuid) {
		CompletableFuture<EslEvent> future = new CompletableFuture<>();
		pendingExecutions.put(appUuid, future);
		return future;
	}

	/**
	 * Shutdown the event executor to release resources.
	 * Should be called when the connection is closed.
	 */
	public void shutdown() {
		log.debug("Shutting down event executor");
		// Complete all pending futures with exception
		pendingExecutions.values().forEach(future ->
				future.completeExceptionally(new RuntimeException("Connection closed")));
		pendingExecutions.clear();
		eventExecutor.shutdown();
	}
}
