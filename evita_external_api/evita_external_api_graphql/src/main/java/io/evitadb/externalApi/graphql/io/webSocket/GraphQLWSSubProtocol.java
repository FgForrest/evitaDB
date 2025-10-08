/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.maybeTruncate;
import static java.util.Collections.emptyList;

/**
 * Handles the graphql-ws sub protocol within a web socket.
 * Handles potentially multiple subscriptions through the same web socket.
 *
 * Note: Inspired by Armeria's GraphQL WebSocket implementation
 */
@Slf4j
class GraphQLWSSubProtocol {
    // todo lho where to get it
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HashMap<String, ExecutionResultSubscriber> graphqlSubscriptions = new HashMap<>();

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private boolean connectionInitiated;

    private final ServiceRequestContext ctx;
    private final AtomicReference<GraphQL> graphQL;
    private final Map<String, Object> upgradeCtx;
    private Map<String, Object> connectionCtx = Map.of();

    public GraphQLWSSubProtocol(
        @Nonnull ServiceRequestContext ctx,
        @Nonnull AtomicReference<GraphQL> graphQL
    ) {
        this.ctx = ctx;
        this.graphQL = graphQL;
        this.upgradeCtx = Map.of("com.linecorp.armeria.graphql.context.key", ctx);
    }

    /**
     * Called when a binary frame is received. Binary frames are not supported by the graphql-ws protocol.
     */
    public void handleBinary(WebSocketWriter out) {
        out.close(WebSocketCloseStatus.INVALID_MESSAGE_TYPE, "Binary frames are not supported");
    }

    /**
     * Receives an event and returns a response if one should be sent.
     */
    public void handleText(String event, WebSocketWriter out) {
        if (!out.isOpen()) {
            return;
        }

        try {
            final Map<String, Object> eventMap = parseJsonString(event, JSON_MAP);
            final String type = toStringFromJson(eventMap.get("type"));
            if (type == null) {
                throw new GraphqlWebSocketCloseException(4400, "type is required");
            }
            final String id;

            switch (type) {
                case "connection_init":
                    if (this.connectionInitiated) {
                        // Already initiated, that's an error
                        throw new GraphqlWebSocketCloseException(4429, "Already initiated");
                    }
                    final Object rawPayload = eventMap.get("payload");
                    if (rawPayload != null) {
	                    this.connectionCtx = toMapFromJson(rawPayload);
                    }
	                this.connectionInitiated = true;
                    writeConnectionAck(out);
                    break;
                case "ping":
                    writePong(out);
                    break;
                case "pong":
                    break;
                case "subscribe":
                    ensureInitiated();
                    id = toStringFromJson(eventMap.get("id"));
                    if (id == null) {
                        throw new GraphqlWebSocketCloseException(4400, "id is required");
                    }
                    final Map<String, Object> payload = toMapFromJson(eventMap.get("payload"));
                    try {
                        if (this.graphqlSubscriptions.containsKey(id)) {
                            // Subscription already exists
                            throw new GraphqlWebSocketCloseException(4409, "Already subscribed");
                        }
                        // todo lho
                        final ExecutedEvent executedEvent = new ExecutedEvent(GraphQLInstanceType.DATA);
                        final String operationName = toStringFromJson(payload.get("operationName"));
                        final String query = toStringFromJson(payload.get("query"));
                        final Map<String, Object> variables = toMapFromJson(payload.get("variables"));
                        final Map<String, Object> extensions = toMapFromJson(payload.get("extensions"));
                        executedEvent.finishInputDeserialization();

                        final ExecutionInput executionInput =
                                ExecutionInput.newExecutionInput()
                                              .graphQLContext(this.connectionCtx)
                                              .graphQLContext(this.upgradeCtx)
                                              .graphQLContext(Map.of(
                                                  GraphQLContextKey.METRIC_EXECUTED_EVENT, executedEvent
                                              ))
                                              .query(query)
                                              .variables(variables)
                                              .operationName(operationName)
                                              .extensions(extensions)
                                              .build();

                        final CompletableFuture<ExecutionResult> future =
	                        this.graphQL.get().executeAsync(executionInput);

                        future.handleAsync((executionResult, throwable) -> {
                            handleExecutionResult(out, id, executionResult, throwable);
                            executedEvent.finishResultSerialization(); // todo lho
                            return null;
                        }, this.ctx.eventLoop());
                    } catch (GraphqlWebSocketCloseException e) {
                        log.debug("Error handling subscription", e);
                        // Also cancel subscription if present before closing websocket
                        final ExecutionResultSubscriber s = this.graphqlSubscriptions.remove(id);
                        if (s != null) {
                            s.setCompleted();
                        }
                        out.close(e.getWebSocketCloseStatus());
                    } catch (Exception e) {
                        log.debug("Error handling subscription", e);
                        // Unknown but possibly recoverable error
                        writeError(out, id, e);
                        return;
                    }
                    break;
                case "complete":
                    ensureInitiated();
                    // Read id and remove that subscription
                    id = toStringFromJson(eventMap.get("id"));
                    if (id == null) {
                        throw new GraphqlWebSocketCloseException(4400, "id is required");
                    }
                    final ExecutionResultSubscriber s = this.graphqlSubscriptions.remove(id);
                    if (s != null) {
                        s.setCompleted();
                    }
                    return;
                default:
                    final String reasonPhrase = maybeTruncate("Unknown event type: " + type);
                    assert reasonPhrase != null;
                    throw new GraphqlWebSocketCloseException(4400, reasonPhrase);
            }
        } catch (GraphqlWebSocketCloseException e) {
            log.debug("Error while handling event", e);
            out.close(e.getWebSocketCloseStatus());
        } catch (Exception e) {
            log.debug("Error while handling event", e);
            out.close(e);
        }
    }

    private void handleExecutionResult(WebSocketWriter out, String id,
                                       @Nullable ExecutionResult executionResult, @Nullable Throwable t) {
        if (t != null) {
            log.debug("Error handling subscription", t);
            writeError(out, id, t);
            return;
        }

        if (executionResult == null) {
            log.debug("ExecutionResult was null but no error was thrown");
            writeError(out, id, new IllegalArgumentException("ExecutionResult was null"));
            return;
        }

        if (!executionResult.getErrors().isEmpty()) {
            try {
                writeError(out, id, executionResult.getErrors());
            } catch (JsonProcessingException e) {
                log.warn("Error serializing error event", e);
                out.close(e);
            }
            return;
        }

        if (!(executionResult.getData() instanceof Publisher)) {
            writeError(out, id, new Exception("Result of operation was not a subscription"));
            return;
        }

        final Publisher<ExecutionResult> publisher = executionResult.getData();
        final StreamMessage<ExecutionResult> streamMessage = StreamMessage.of(publisher);

        final ExecutionResultSubscriber executionResultSubscriber =
                new ExecutionResultSubscriber(id, new GraphQLSubProtocol() {
                    boolean completed;

                    @Override
                    public void sendResult(String operationId, ExecutionResult executionResult)
                            throws JsonProcessingException {
                        writeNext(out, operationId, executionResult);
                    }

                    @Override
                    public void sendGraphqlErrors(List<GraphQLError> errors) throws JsonProcessingException {
                        writeError(out, id, errors);
                    }

                    @Override
                    public void completeWithError(Throwable cause) {
                        if (this.completed) {
                            return;
                        }
	                    this.completed = true;
                        writeError(out, id, cause);
	                    GraphQLWSSubProtocol.this.graphqlSubscriptions.remove(id);
                    }

                    @Override
                    public void complete() {
                        if (this.completed) {
                            return;
                        }
	                    this.completed = true;
                        writeComplete(out, id);
	                    GraphQLWSSubProtocol.this.graphqlSubscriptions.remove(id);
                    }
                });

	    this.graphqlSubscriptions.put(id, executionResultSubscriber);
        streamMessage.subscribe(executionResultSubscriber, this.ctx.eventLoop());
    }

    void cancel() {
        for (ExecutionResultSubscriber subscriber : this.graphqlSubscriptions.values()) {
            subscriber.setCompleted();
        }
	    this.graphqlSubscriptions.clear();
    }

    private void ensureInitiated() throws Exception {
        if (!this.connectionInitiated) {
            // ConnectionAck not sent yet. Must be closed with 4401 Unauthorized.
            throw new GraphqlWebSocketCloseException(4401, "Unauthorized");
        }
    }

    private static String serializeToJson(Object object) throws JsonProcessingException {
        return mapper.writer().writeValueAsString(object);
    }

    @Nullable
    private static String toStringFromJson(@Nullable Object value) throws GraphqlWebSocketCloseException {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return (String) value;
        } else {
            throw new GraphqlWebSocketCloseException(4400, "Expected string value");
        }
    }

    /**
     * This only works reliably if maybeMap is from Json, as maps(objects) in Json
     * can only have string keys.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMapFromJson(@Nullable Object maybeMap)
            throws GraphqlWebSocketCloseException {
        if (maybeMap == null) {
            return Map.of();
        }

        if (maybeMap instanceof final Map<?, ?> map) {
	        if (map.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap((Map<String, Object>) maybeMap);
        } else {
            throw new GraphqlWebSocketCloseException(4400, "Expected map value");
        }
    }

    private static <T> T parseJsonString(String content, TypeReference<T> typeReference)
            throws GraphqlWebSocketCloseException {
        try {
            return mapper.readValue(content, typeReference);
        } catch (JsonProcessingException e) {
            throw new GraphqlWebSocketCloseException(4400, "Invalid JSON");
        }
    }

    private static void writePong(WebSocketWriter out) {
        out.tryWrite("{\"type\":\"pong\"}");
    }

    private static void writeConnectionAck(WebSocketWriter out) {
        out.tryWrite("{\"type\":\"connection_ack\"}");
    }

    private static void writeNext(WebSocketWriter out, String operationId, ExecutionResult executionResult)
            throws JsonProcessingException {
        final Map<String, Object> response = Map.of(
                "id", operationId,
                "type", "next",
                "payload", executionResult.toSpecification());
        final String event = serializeToJson(response);
        log.trace("NEXT: {}", event);
        out.tryWrite(event);
    }

    private static void writeError(WebSocketWriter out, String operationId, List<GraphQLError> errors)
            throws JsonProcessingException {
        final List<Map<String, Object>> errorSpecifications =
                errors.stream().map(GraphQLError::toSpecification).collect(Collectors.toList());
        final Map<String, Object> errorResponse = Map.of(
                "type", "error",
                "id", operationId,
                "payload", errorSpecifications);
        final String event = serializeToJson(errorResponse);
        log.trace("ERROR: {}", event);
        out.tryWrite(event);
    }

    private static void writeError(WebSocketWriter out, String operationId, Throwable t) {
        final Map<String, Object> errorResponse = Map.of(
                "type", "error",
                "id", operationId,
                "payload", List.of(
                        new GraphQLError() {
                            @Nullable
                            @Override
                            public String getMessage() {
                                return t.getMessage();
                            }

                            @Override
                            public List<SourceLocation> getLocations() {
                                return emptyList();
                            }

                            @Override
                            public ErrorClassification getErrorType() {
                                return ErrorType.DataFetchingException;
                            }
                        }.toSpecification()
                ));
        try {
            final String event = serializeToJson(errorResponse);
            log.trace("ERROR: {}", event);
            out.tryWrite(event);
        } catch (JsonProcessingException e) {
            log.warn("Error serializing error event", e);
            out.close(e);
        }
    }

    private static void writeComplete(WebSocketWriter out, String operationId) {
        try {
            final String json = serializeToJson(Map.of("type", "complete", "id", operationId));
            out.tryWrite(json);
        } catch (JsonProcessingException e) {
            log.warn("Unexpected exception while serializing complete event. operationId: {}",
                        operationId, e);
            out.close(e);
        }
    }

    private static final class GraphqlWebSocketCloseException extends Exception {
        private static final long serialVersionUID = 1196626539261081709L;

        private final WebSocketCloseStatus webSocketCloseStatus;

        GraphqlWebSocketCloseException(int code, String reason) {
	        this.webSocketCloseStatus = WebSocketCloseStatus.ofPrivateUse(code, reason);
        }

        WebSocketCloseStatus getWebSocketCloseStatus() {
            return this.webSocketCloseStatus;
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
