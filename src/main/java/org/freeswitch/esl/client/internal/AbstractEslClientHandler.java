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
package org.freeswitch.esl.client.internal;

import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.event.EslEventHeaderNames;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;
import org.freeswitch.esl.client.transport.message.EslHeaders.Value;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.socket.SocketWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * Base handler that implements the logic of an ESL connection that is common to both inbound and
 * outbound clients. This handler expects to receive decoded {@link EslMessage} or {@link EslEvent}
 * objects. The key responsibilities for this class are:
 * <ul><li>
 * To synthesize a synchronous command/response API using CompletableFuture. This class provides
 * for an asynchronous mechanism for responses to commands issued to the server. A key assumption
 * here is that the FreeSWITCH server will process synchronous requests in the order they are received.
 * </li><li>
 * All message processing is done in virtual threads for maximum scalability.
 * </li></ul>
 */
public abstract class AbstractEslClientHandler {

	public static final String MESSAGE_TERMINATOR = "\n\n";
	public static final String LINE_TERMINATOR = "\n";

	protected final Logger log = LoggerFactory.getLogger(this.getClass());
	// used to preserve association between adding future to queue and sending message on socket
	private final ReentrantLock syncLock = new ReentrantLock();
	private final ConcurrentLinkedQueue<CompletableFuture<EslMessage>> apiCalls =
			new ConcurrentLinkedQueue<>();

	private final ConcurrentHashMap<String, CompletableFuture<EslEvent>> backgroundJobs =
			new ConcurrentHashMap<>();
	private final ExecutorService backgroundJobExecutor = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * Handle exceptions during message processing
	 */
	public void handleException(Throwable e) {
		log.error("Exception in ESL handler", e);

		for (final CompletableFuture<EslMessage> apiCall : apiCalls) {
			apiCall.completeExceptionally(e);
		}

		for (final CompletableFuture<EslEvent> backgroundJob : backgroundJobs.values()) {
			backgroundJob.completeExceptionally(e);
		}
	}

	/**
	 * Process an incoming ESL message
	 */
	public void processMessage(SocketWrapper socket, EslMessage message) {
		final String contentType = message.getContentType();
		if (contentType.equals(Value.TEXT_EVENT_PLAIN) ||
				contentType.equals(Value.TEXT_EVENT_XML)) {
			//  transform into an event
			final EslEvent eslEvent = new EslEvent(message);
			if (eslEvent.getEventName().equals("BACKGROUND_JOB")) {
				final String backgroundUuid = eslEvent.getEventHeaders().get(EslEventHeaderNames.JOB_UUID);
				final CompletableFuture<EslEvent> future = backgroundJobs.remove(backgroundUuid);
				if (null != future) {
					future.complete(eslEvent);
				}
			} else {
				handleEslEvent(socket, eslEvent);
			}
		} else {
			handleEslMessage(socket, message);
		}
	}

	protected void handleEslMessage(SocketWrapper socket, EslMessage message) {
		log.info("Received message: [{}]", message);
		final String contentType = message.getContentType();

		switch (contentType) {
			case Value.API_RESPONSE:
				log.debug("Api response received [{}]", message);
				CompletableFuture<EslMessage> apiCall = apiCalls.poll();
				if (apiCall != null) {
					apiCall.complete(message);
				}
				break;

			case Value.COMMAND_REPLY:
				log.debug("Command reply received [{}]", message);
				CompletableFuture<EslMessage> cmdCall = apiCalls.poll();
				if (cmdCall != null) {
					cmdCall.complete(message);
				}
				break;

			case Value.AUTH_REQUEST:
				log.debug("Auth request received [{}]", message);
				handleAuthRequest(socket);
				break;

			case Value.TEXT_DISCONNECT_NOTICE:
				log.debug("Disconnect notice received [{}]", message);
				handleDisconnectionNotice();
				break;

			default:
				log.warn("Unexpected message content type [{}]", contentType);
				break;
		}
	}

	/**
	 * Synthesize a synchronous command/response by creating a callback object which is placed in
	 * queue and waits for another thread to process an incoming {@link EslMessage} and
	 * attach it to the callback.
	 *
	 * @param socket  the socket wrapper
	 * @param command single string to send
	 * @return the {@link EslMessage} attached to this command's callback
	 */
	public CompletableFuture<EslMessage> sendApiSingleLineCommand(SocketWrapper socket, final String command) {
		final CompletableFuture<EslMessage> future = new CompletableFuture<>();
		try {
			syncLock.lock();
			apiCalls.add(future);
			socket.writeAndFlush(command + MESSAGE_TERMINATOR);
		} catch (IOException e) {
			apiCalls.poll();
			future.completeExceptionally(e);
		} finally {
			syncLock.unlock();
		}

		return future;
	}

	/**
	 * Sends a FreeSWITCH API command to the server and returns a future for the response.
	 * <p/>
	 * The outcome of the command from the server is returned in an {@link EslMessage} object.
	 *
	 * @param socket  the socket wrapper
	 * @param command API command to send
	 * @param arg     command arguments
	 * @return an {@link EslMessage} containing command results
	 */
	public CompletableFuture<EslMessage> sendSyncApiCommand(SocketWrapper socket, String command, String arg) {

		checkArgument(!isNullOrEmpty(command), "command may not be null or empty");
		checkArgument(!isNullOrEmpty(arg), "arg may not be null or empty");

		return sendApiSingleLineCommand(socket, "api " + command + ' ' + arg);
	}

	/**
	 * Synthesize a synchronous command/response by creating a callback object which is placed in
	 * queue and waits for another thread to process an incoming {@link EslMessage} and
	 * attach it to the callback.
	 *
	 * @param socket the socket wrapper
	 * @return the {@link EslMessage} attached to this command's callback
	 */
	public CompletableFuture<EslMessage> sendApiMultiLineCommand(SocketWrapper socket, final List<String> commandLines) {
		//  Build command with double line terminator at the end
		final StringBuilder sb = new StringBuilder();
		for (final String line : commandLines) {
			sb.append(line);
			sb.append(LINE_TERMINATOR);
		}
		sb.append(LINE_TERMINATOR);

		final CompletableFuture<EslMessage> future = new CompletableFuture<>();
		try {
			syncLock.lock();
			apiCalls.add(future);
			socket.writeAndFlush(sb.toString());
		} catch (IOException e) {
			apiCalls.poll();
			future.completeExceptionally(e);
		} finally {
			syncLock.unlock();
		}

		return future;
	}

	/**
	 * Returns the Job UUID of that the response event will have.
	 *
	 * @param socket  the socket wrapper
	 * @param command the command to send
	 * @return Job-UUID as a string
	 */
	public CompletableFuture<EslEvent> sendBackgroundApiCommand(SocketWrapper socket, final String command) {

		return sendApiSingleLineCommand(socket, command)
				.thenComposeAsync(result -> {
					if (result.hasHeader(Name.JOB_UUID)) {
						final String jobId = result.getHeaderValue(Name.JOB_UUID);
						final CompletableFuture<EslEvent> resultFuture = new CompletableFuture<>();
						backgroundJobs.put(jobId, resultFuture);
						return resultFuture;
					} else {
						final CompletableFuture<EslEvent> resultFuture = new CompletableFuture<>();
						resultFuture.completeExceptionally(new IllegalStateException("Missing Job-UUID header in bgapi response"));
						return resultFuture;
					}
				}, backgroundJobExecutor);
	}

	protected abstract void handleEslEvent(SocketWrapper socket, EslEvent event);

	protected abstract void handleAuthRequest(SocketWrapper socket);

	protected abstract void handleDisconnectionNotice();

	/**
	 * Wait for CHANNEL_EXECUTE_COMPLETE event with the given Application-UUID.
	 * Default implementation throws UnsupportedOperationException.
	 * Subclasses (like OutboundClientHandler) can override to provide actual implementation.
	 *
	 * @param appUuid the Application-UUID (event-uuid) to wait for
	 * @return a CompletableFuture that completes with the CHANNEL_EXECUTE_COMPLETE event
	 */
	public CompletableFuture<EslEvent> waitForExecuteComplete(String appUuid) {
		throw new UnsupportedOperationException("waitForExecuteComplete is only supported in outbound mode");
	}

}
