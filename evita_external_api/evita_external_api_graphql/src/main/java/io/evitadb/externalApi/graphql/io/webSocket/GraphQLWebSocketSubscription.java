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

import graphql.ExecutionResult;
import io.evitadb.externalApi.graphql.exception.EvitaGraphQLError;
import io.evitadb.externalApi.graphql.io.GraphQLResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.annotation.Nonnull;

/**
 * Represents an active GraphQL subscription within a WebSocket connection. This class
 * manages the lifecycle of a single subscription, including subscription execution,
 * result streaming, error handling, and cleanup operations. It maintains the context
 * and state necessary for delivering subscription results to the client over the WebSocket.
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class GraphQLWebSocketSubscription implements Subscriber<ExecutionResult> {

	@Nonnull @Getter private final String id;
	@Nonnull private final GraphQLWebSocketSession session;
	@Nonnull private final GraphQLWebSocketExceptionHandler exceptionHandler;

	private Subscription cdcSubscription;

	@Override
	public void onSubscribe(Subscription cdcSubscription) {
		this.cdcSubscription = cdcSubscription;
		this.cdcSubscription.request(1);
	}

	@SneakyThrows
	@Override
	public void onNext(ExecutionResult item) {
		final GraphQLResponse<?> graphQLResponse = GraphQLResponse.fromExecutionResult(item);
		this.session.sendMessage(GraphQLWebSocketMessage.next(this.id, graphQLResponse));
		this.cdcSubscription.request(1);
	}

	@SneakyThrows
	@Override
	public void onError(Throwable e) {
		this.session.removeSubscription(this.id);
		final EvitaGraphQLError graphQLError = this.exceptionHandler.toGraphQLError(e);
		this.session.sendMessage(GraphQLWebSocketMessage.error(this.id, graphQLError));
	}

	@SneakyThrows
	@Override
	public void onComplete() {
		this.session.removeSubscription(this.id);
		this.session.sendMessage(GraphQLWebSocketMessage.complete(this.id));
	}

	/**
	 * Manually complete the subscription by client.
	 */
	public void complete() {
		this.cdcSubscription.cancel();
		this.session.removeSubscription(this.id);
	}
}
