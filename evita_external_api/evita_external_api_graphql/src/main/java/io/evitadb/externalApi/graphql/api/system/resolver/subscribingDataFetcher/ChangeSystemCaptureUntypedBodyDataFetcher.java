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

package io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureBody;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.system.resolver.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.graphql.api.catalog.resolver.mutation.GraphQLMutationResolvingExceptionFactory;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Returns converted {@link ChangeSystemCapture#body()} to correct GraphQL representation for untyped subscriptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ChangeSystemCaptureUntypedBodyDataFetcher implements DataFetcher<Object> {

	private static ChangeSystemCaptureUntypedBodyDataFetcher INSTANCE = null;

	@Nonnull
	public static ChangeSystemCaptureUntypedBodyDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ChangeSystemCaptureUntypedBodyDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	private final DelegatingEngineMutationConverter bodyConverter = new DelegatingEngineMutationConverter(
		PassThroughMutationObjectMapper.INSTANCE,
		GraphQLMutationResolvingExceptionFactory.INSTANCE
	);

	@Override
	@Nullable
	public Object get(DataFetchingEnvironment environment) throws Exception {
		final ChangeSystemCapture capture = Objects.requireNonNull(environment.getSource());
		final SystemCaptureBody body = capture.body();
		Assert.isPremiseValid(
			body != null,
			() -> new GraphQLQueryResolvingInternalError("ChangeSystemCapture body is null even though it was requested.")
		);
		if (body instanceof EngineMutation<?> engineMutation) {
			return this.bodyConverter.convertToOutput(engineMutation);
		}
		if (body instanceof HostSystemEvent hostEvent) {
			// host events are plain records — the untyped projection returns the record itself
			// and the GraphQL framework serializes it via the registered object types
			return hostEvent;
		}
		throw new GenericEvitaInternalError(
			"Unsupported `ChangeSystemCapture#body` type: " + body.getClass().getName()
		);
	}
}
