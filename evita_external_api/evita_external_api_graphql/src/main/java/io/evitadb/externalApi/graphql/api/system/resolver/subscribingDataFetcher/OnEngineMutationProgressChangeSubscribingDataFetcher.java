/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.engine.EngineMutationProgressStatus;
import io.evitadb.externalApi.graphql.api.system.model.OnEngineMutationProgressChangeSubscriptionHeaderDescriptor;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.UUID;

/**
 * Subscription data fetcher for the progress of an engine operation started by a `...WithProgress` mutation. It
 * bridges {@link Evita#subscribeToEngineMutationProgress(UUID)} to GraphQL's reactive streams.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class OnEngineMutationProgressChangeSubscribingDataFetcher
	implements DataFetcher<Publisher<EngineMutationProgressStatus>> {

	private final Evita evita;

	@Nonnull
	@Override
	public Publisher<EngineMutationProgressStatus> get(DataFetchingEnvironment environment) throws Exception {
		final UUID progressId = Objects.requireNonNull(
			environment.getArgument(OnEngineMutationProgressChangeSubscriptionHeaderDescriptor.PROGRESS_ID.name())
		);
		return FlowAdapters.toPublisher(this.evita.subscribeToEngineMutationProgress(progressId));
	}
}
