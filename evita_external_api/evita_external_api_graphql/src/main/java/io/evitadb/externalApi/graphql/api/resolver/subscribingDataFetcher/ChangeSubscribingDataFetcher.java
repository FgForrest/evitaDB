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

package io.evitadb.externalApi.graphql.api.resolver.subscribingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.core.Evita;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.FlowAdapters;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Publisher;

/**
 * Subscription data fetcher for listening to changes from evitaDB. Currently, it mainly acts as a bridge between GraphQL's reactive
 * streams and evitaDB's flow API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ChangeSubscribingDataFetcher<C extends ChangeCapture> implements DataFetcher<org.reactivestreams.Publisher<C>> {

	protected final Evita evita;

	@Override
	@Nonnull
	public org.reactivestreams.Publisher<C> get(DataFetchingEnvironment environment) throws Exception {
		return FlowAdapters.toPublisher(createPublisher(environment));
	}

	@Nonnull
	protected abstract Publisher<C> createPublisher(@Nonnull DataFetchingEnvironment environment);
}
