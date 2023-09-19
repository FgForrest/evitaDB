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
 * TODO lho docs
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
		session.sendMessage(GraphQLWebSocketMessage.next(id, graphQLResponse));
		this.cdcSubscription.request(1);
	}

	@SneakyThrows
	@Override
	public void onError(Throwable e) {
		session.removeSubscription(id);
		final EvitaGraphQLError graphQLError = exceptionHandler.toGraphQLError(e);
		session.sendMessage(GraphQLWebSocketMessage.error(id, graphQLError));
	}

	@SneakyThrows
	@Override
	public void onComplete() {
		session.removeSubscription(id);
		session.sendMessage(GraphQLWebSocketMessage.complete(id));
	}

	/**
	 * Manually complete the subscription by client.
	 */
	public void complete() {
		cdcSubscription.cancel();
		session.removeSubscription(id);
	}
}
