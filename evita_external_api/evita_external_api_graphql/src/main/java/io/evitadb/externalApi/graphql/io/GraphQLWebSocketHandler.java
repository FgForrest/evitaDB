/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.exception.EvitaGraphQLError;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.core.protocol.Handshake;
import io.undertow.websockets.core.protocol.version07.Hybi07Handshake;
import io.undertow.websockets.core.protocol.version08.Hybi08Handshake;
import io.undertow.websockets.core.protocol.version13.Hybi13Handshake;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@Slf4j
public class GraphQLWebSocketHandler extends WebSocketProtocolHandshakeHandler {

	private static final String GRAPHQL_WS_SUB_PROTOCOL = "graphql-protocol-ws";
	private static final Set<String> SUPPORTED_SUB_PROTOCOLS = Set.of(GRAPHQL_WS_SUB_PROTOCOL);
	private static final Set<Handshake> SUPPORTED_HANDSHAKES = Set.of(
		new Hybi13Handshake(SUPPORTED_SUB_PROTOCOLS, false),
		new Hybi08Handshake(SUPPORTED_SUB_PROTOCOLS, false),
		new Hybi07Handshake(SUPPORTED_SUB_PROTOCOLS, false)
	);

	public GraphQLWebSocketHandler(@Nonnull ObjectMapper objectMapper,
								   @Nonnull Evita evita,
	                               @Nonnull AtomicReference<GraphQL> graphQL) {
		super(SUPPORTED_HANDSHAKES, new GraphQLWebSocketCallback(objectMapper, evita, graphQL));
	}

	@RequiredArgsConstructor
	private static class GraphQLWebSocketCallback implements WebSocketConnectionCallback {

		private static final String SESSION_ATTRIBUTE_KEY = "session";

		@Nonnull
		private final ObjectMapper objectMapper;
		@Nonnull
		private final Evita evita;
		@Nonnull
		private final AtomicReference<GraphQL> graphQL;

		@Override
		public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
			initSession(channel);

			evita.getExecutor().schedule(
				() -> {
					final WebSocketSession session = getSession(channel);
					if (session.getState() == WebSocketSessionState.NEW) {
						// we didn't receive init request in time, closing channel
						WebSockets.sendClose(GraphQLCloseMessage.INIT_TIMEOUT, channel, null);
					}
				},
				10,
				TimeUnit.SECONDS
			);

			channel.getReceiveSetter().set(new AbstractReceiveListener() {
				@SneakyThrows
				@Override
				protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
					final String messageText = message.getData();
					final GraphQLWebSocketMessage graphQLMessage = objectMapper.readValue(messageText, GraphQLWebSocketMessage.class);

					final String id = graphQLMessage.id();
					final GraphQLWebSocketMessageType type = graphQLMessage.type();

					final WebSocketSession session = getSession(channel);

					switch (type) {
						case CONNECTION_INIT -> {
							if (session.getState() != WebSocketSessionState.NEW) {
								WebSockets.sendClose(GraphQLCloseMessage.TOO_MANY_INIT_REQUESTS, channel, null);
							} else {
								session.setState(WebSocketSessionState.ACTIVE);
								final String ack = objectMapper.writeValueAsString(GraphQLWebSocketMessage.connectionAck());
								WebSockets.sendText(ack, channel, null);
							}
						}
						case PING -> {
							final String pong = objectMapper.writeValueAsString(GraphQLWebSocketMessage.pong());
							WebSockets.sendText(pong, channel, null);
						}
						case SUBSCRIBE -> {
							if (session.getState() != WebSocketSessionState.ACTIVE) {
								WebSockets.sendClose(GraphQLCloseMessage.UNAUTHORIZED, channel, null);
								return;
							}

							if (session.getSubscriptions().containsKey(id)) {
								WebSockets.sendClose(GraphQLCloseMessage.SUBSCRIBER_ALREADY_EXISTS, channel, null);
								return;
							}
							final WebSocketSubscription subscription = new WebSocketSubscription(id);
							session.getSubscriptions().put(id, subscription);

							final GraphQLRequest graphQLRequest = new GraphQLRequest(
								(String) ((Map<String, Object>) graphQLMessage.payload()).get("query"),
								(String) ((Map<String, Object>) graphQLMessage.payload()).get("operationName"),
								null
							);
							try {
								final ExecutionResult result = graphQL.get()
									.executeAsync(graphQLRequest.toExecutionInput())
									.orTimeout(evita.getConfiguration().server().shortRunningThreadsTimeoutInSeconds(), TimeUnit.SECONDS)
									.join();

								final Publisher<ExecutionResult> publisher = result.getData();
								publisher.subscribe(new Subscriber<>() {

									private Subscription subscription;

									@Override
									public void onSubscribe(Subscription subscription) {
										this.subscription = subscription;
										this.subscription.request(1);
									}

									@SneakyThrows
									@Override
									public void onNext(ExecutionResult item) {
										final GraphQLResponse<?> graphQLResponse = GraphQLResponse.fromExecutionResult(item);

										final String next = objectMapper.writeValueAsString(GraphQLWebSocketMessage.next(id, graphQLResponse));
										WebSockets.sendText(next, channel, null);

										this.subscription.request(1);
									}

									@SneakyThrows
									@Override
									public void onError(Throwable e) {
										subscription.cancel();
										session.getSubscriptions().remove(id);
										final String error = objectMapper.writeValueAsString(GraphQLWebSocketMessage.error(id, List.of(new EvitaGraphQLError(e.getMessage(), null, null, null))));
										WebSockets.sendText(error, channel, null);
									}

									@SneakyThrows
									@Override
									public void onComplete() {
										// todo lho this is not implement by server?
										session.getSubscriptions().remove(id);
										final String completion = objectMapper.writeValueAsString(GraphQLWebSocketMessage.complete(id));
										WebSockets.sendText(completion, channel, null);
									}
								});


								// todo lho client may request to complete it before its executed, we cannot sent him result
								// todo lho but what if client creates new subscriotion with same id before it is completed?

//								final String next = objectMapper.writeValueAsString(GraphQLWebSocketMessage.next(id, graphQLResponse));
//								WebSockets.sendText(next, channel, null);
//
//								final String completion = objectMapper.writeValueAsString(GraphQLWebSocketMessage.complete(id));
//								WebSockets.sendText(completion, channel, null);
							} catch (Exception e) {
								// todo lho should there be call to cancel subscription from publisher?
								session.getSubscriptions().remove(id);
								final String error = objectMapper.writeValueAsString(GraphQLWebSocketMessage.error(id, List.of(new EvitaGraphQLError(e.getMessage(), null, null, null))));
								WebSockets.sendText(error, channel, null);
							}
						}
						case COMPLETE -> {
							session.getSubscriptions().remove(id);
						}
						default -> {
							WebSockets.sendClose(GraphQLCloseMessage.INVALID_MESSAGE, channel, null);
						}
					}
				}
			});
			channel.resumeReceives();
		}

		private void initSession(@Nonnull WebSocketChannel channel) {
			channel.setAttribute(SESSION_ATTRIBUTE_KEY, new WebSocketSession());
		}

		private WebSocketSession getSession(@Nonnull WebSocketChannel channel) {
			return (WebSocketSession) channel.getAttribute(SESSION_ATTRIBUTE_KEY);
		}
	}


	@RequiredArgsConstructor
	private static class WebSocketSession {

		@Getter
		@Setter
		private WebSocketSessionState state = WebSocketSessionState.NEW;

		@Getter
		private final Map<String, WebSocketSubscription> subscriptions = createConcurrentHashMap(5);
	}

	private enum WebSocketSessionState {
		NEW, ACTIVE
	}

	private record WebSocketSubscription(@Nonnull String id) {
	}

	private static class GraphQLCloseMessage extends CloseMessage {

		public static final GraphQLCloseMessage INVALID_MESSAGE = new GraphQLCloseMessage(4400, "Invalid message");
		public static final GraphQLCloseMessage UNAUTHORIZED = new GraphQLCloseMessage(4401, "Unauthorized");
		public static final GraphQLCloseMessage FORBIDDEN = new GraphQLCloseMessage(4403, "Forbidden");
		public static final GraphQLCloseMessage INIT_TIMEOUT = new GraphQLCloseMessage(4408, "Connection initialisation timeout");
		public static final GraphQLCloseMessage SUBSCRIBER_ALREADY_EXISTS = new GraphQLCloseMessage(4409, "Subscriber already exists");
		public static final GraphQLCloseMessage TOO_MANY_INIT_REQUESTS = new GraphQLCloseMessage(4429, "Too many initialisation requests");

		private GraphQLCloseMessage(int code, String reason) {
			super(code, reason);
		}
	}
}
