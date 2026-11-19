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

package io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressHandleDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.WriteDataFetcher;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;

/**
 * Ancestor of the data fetchers of the `...WithProgress` engine mutations of the system API. It starts the operation
 * and returns its handle (`EngineMutationProgressHandle`) as soon as the engine has accepted it, without waiting for
 * the operation to finish. The acceptance itself - lock, applicability checks and, for rename and replace, the drain of
 * open sessions and commits - still runs on the calling thread, so its failures fail the mutation directly.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EngineOperationWithProgressMutatingDataFetcher
	implements DataFetcher<Map<String, Object>>, WriteDataFetcher {

	protected final Evita evita;

	@Nullable
	@Override
	public Map<String, Object> get(DataFetchingEnvironment environment) throws Exception {
		return startOperation(environment)
			.map(progress -> Map.<String, Object>of(
				EngineMutationProgressHandleDescriptor.PROGRESS_ID.name(),
				progress.progressId()
					.orElseThrow(() -> new GenericEvitaInternalError("Engine mutation progress has no id!"))
			))
			.orElse(null);
	}

	/**
	 * Starts the operation with arguments taken from the environment.
	 *
	 * @param environment environment carrying the arguments of the mutation
	 * @return progress of the accepted operation, or empty when there was nothing to do (deleting a missing catalog)
	 */
	@Nonnull
	protected abstract Optional<Progress<?>> startOperation(@Nonnull DataFetchingEnvironment environment);
}
