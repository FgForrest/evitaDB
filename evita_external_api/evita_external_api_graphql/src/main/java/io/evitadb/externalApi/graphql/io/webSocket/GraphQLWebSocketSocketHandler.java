/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.io.webSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import io.evitadb.core.Evita;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Socket handler implementation for GraphQL WebSocket connections. This class provides
 * the low-level WebSocket handling functionality, managing the actual socket connections,
 * message transmission, and connection lifecycle events. It serves as the bridge between
 * the WebSocket transport layer and the GraphQL protocol implementation.
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
@Slf4j
class GraphQLWebSocketSocketHandler /*implements WebSocketConnectionCallback*/ {

	private static final int HANDSHAKE_INIT_TIMEOUT = 10;
	private static final String SESSION_ATTRIBUTE_KEY = "session";

	@Nonnull private final ObjectMapper objectMapper;
	@Nonnull private final Evita evita;
	@Nonnull private final AtomicReference<GraphQL> graphQL;

	@Nonnull private final GraphQLWebSocketExceptionHandler exceptionHandler = new GraphQLWebSocketExceptionHandler();

	/*@Override
	public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
		initSession(channel);

		// connection must be closed when a client doesn't send init request in time
		evita.getExecutor().schedule(
			() -> {
				final GraphQLWebSocketSession session = getSession(channel);
				if (session.isNew()) {
					// we didn't receive init request in time, closing channel
					WebSockets.sendClose(GraphQLWebSocketCloseMessage.initTimeout(), channel, null);
				}
			},
			HANDSHAKE_INIT_TIMEOUT,
			TimeUnit.SECONDS
		);

		channel.getReceiveSetter().set(new AbstractReceiveListener() {
			@Override
			protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
				try {
					final GraphQLWebSocketSession session = getSession(channel);
					final String messageContent = message.getData();
					final GraphQLWebSocketMessage graphQLMessage;
					try {
						graphQLMessage = objectMapper.readValue(messageContent, GraphQLWebSocketMessage.class);
					} catch (JsonProcessingException e) {
						throw new GraphQLInvalidArgumentException("Invalid message object.", e);
					}

					switch (graphQLMessage.type()) {
						case CONNECTION_INIT -> handleConnectionInit(session);
						case PING -> handlePing(session);
						case SUBSCRIBE -> handleSubscribe(session, graphQLMessage);
						case COMPLETE -> handleComplete(session, graphQLMessage);
						case INVALID_MESSAGE -> handleInvalidMessage(graphQLMessage);
						default -> session.closeChannel(GraphQLWebSocketCloseMessage.invalidMessage("Unknown message type `" + graphQLMessage.type() + "`."));
					}
				} catch (Exception e) {
					final GraphQLWebSocketCloseMessage closeMessage = exceptionHandler.toCloseMessage(e);
					if (channel.isCloseFrameSent()) {
						// should ideally never happen
						log.error(
							"Evita thrown an exception but " + GraphQLProvider.CODE + " WebSocket already closed: status code {}, message:",
							closeMessage.getCode(),
							closeMessage.getReason()
						);
					} else {
						WebSockets.sendClose(closeMessage, channel, null);
					}
				}
			}
		});
		channel.resumeReceives();
	}

	*//**
	 * Creates new session for a single WebSocket channel/client connection.
	 *//*
	private void initSession(@Nonnull WebSocketChannel channel) {
		channel.setAttribute(SESSION_ATTRIBUTE_KEY, new GraphQLWebSocketSession(channel, objectMapper));
	}

	*//**
	 * Retrieves current session for current channel/connection.
	 *//*
	@Nonnull
	private GraphQLWebSocketSession getSession(@Nonnull WebSocketChannel channel) {
		final GraphQLWebSocketSession session = (GraphQLWebSocketSession) channel.getAttribute(SESSION_ATTRIBUTE_KEY);
		Assert.isPremiseValid(
			session != null,
			() -> new GraphQLInternalError("Couldn't not find WebSocket session.")
		);
		return session;
	}

	*//**
	 * Processes {@code "connection_init"} message from a client.
	 *//*
	private void handleConnectionInit(@Nonnull GraphQLWebSocketSession session) {
		if (!session.isNew()) {
			session.closeChannel(GraphQLWebSocketCloseMessage.tooManyInitRequests());
		} else {
			session.activate();
			session.sendMessage(GraphQLWebSocketMessage.connectionAck());
		}
	}

	*//**
	 * Processes {@code "ping"} message from a client.
	 *//*
	private void handlePing(@Nonnull GraphQLWebSocketSession session) {
		session.sendMessage(GraphQLWebSocketMessage.pong());
	}

	*//**
	 * Processes {@code "subscribe"} message from a client.
	 *//*
	private void handleSubscribe(@Nonnull GraphQLWebSocketSession session,
	                             @Nonnull GraphQLWebSocketMessage message) {
		if (!session.isActive()) {
			session.closeChannel(GraphQLWebSocketCloseMessage.unauthorized());
			return;
		}

		if (session.isExistsSubscription(message.id())) {
			session.closeChannel(GraphQLWebSocketCloseMessage.subscriberAlreadyExists());
			return;
		}
		final GraphQLWebSocketSubscription subscription = new GraphQLWebSocketSubscription(message.id(), session, exceptionHandler);
		session.addSubscription(subscription);

		final GraphQLRequest graphQLRequest;
		try {
			graphQLRequest = objectMapper.convertValue(message.payload(), GraphQLRequest.class);
		} catch (IllegalArgumentException e) {
			session.closeChannel(GraphQLWebSocketCloseMessage.invalidMessage("Invalid GraphQL request object."));
			return;
		}
		try {
			final ExecutionResult result = graphQL.get()
				.executeAsync(graphQLRequest.toExecutionInput())
				.orTimeout(evita.getConfiguration().server().shortRunningThreadsTimeoutInSeconds(), TimeUnit.SECONDS)
				.join();

			if (result.getData() instanceof Publisher<?>) {
				final Publisher<ExecutionResult> publisher = result.getData();
				publisher.subscribe(subscription);
			} else {
				final GraphQLResponse<?> graphQLResponse = GraphQLResponse.fromExecutionResult(result);
				session.sendMessage(GraphQLWebSocketMessage.next(subscription.getId(), graphQLResponse));
				session.sendMessage(GraphQLWebSocketMessage.complete(subscription.getId()));
			}
		} catch (Exception e) {
			session.removeSubscription(subscription);
			final EvitaGraphQLError graphQLError = exceptionHandler.toGraphQLError(e);
			session.sendMessage(GraphQLWebSocketMessage.error(subscription.getId(), graphQLError));
		}
	}

	*//**
	 * Processes {@code "complete"} message from a client.
	 *//*
	private void handleComplete(@Nonnull GraphQLWebSocketSession session, @Nonnull GraphQLWebSocketMessage message) {
		session.getSubscription(message.id()).ifPresent(GraphQLWebSocketSubscription::complete);
	}

	*//**
	 * Processes {@code "invalid_message"} message from a client.
	 *//*
	private void handleInvalidMessage(@Nonnull GraphQLWebSocketMessage message) {
		log.error("Invalid GraphQL WebSocket message received from client: {}", message.payload());
	}*/
}
