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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.subscribingDataFetcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingInfrastructureMutationConverter;
import io.evitadb.externalApi.graphql.api.catalog.resolver.mutation.GraphQLMutationResolvingExceptionFactory;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Returns converted {@link ChangeSystemCapture#body()} to correct GraphQL representation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ChangeCatalogDataCaptureBodyDataFetcher implements DataFetcher<Object> {

	@Nonnull
	private final DelegatingLocalMutationConverter localMutationConverter;
	@Nonnull
	private final DelegatingEntityMutationConverter entityMutationConverter;
	@Nonnull
	private final DelegatingInfrastructureMutationConverter infrastructureMutationConverter;

	public ChangeCatalogDataCaptureBodyDataFetcher(@Nonnull ObjectMapper objectMapper) {
		final PassThroughMutationObjectMapper mutationObjectMapper = PassThroughMutationObjectMapper.INSTANCE;
		final GraphQLMutationResolvingExceptionFactory exceptionFactory = GraphQLMutationResolvingExceptionFactory.INSTANCE;
		this.localMutationConverter = new DelegatingLocalMutationConverter(objectMapper, mutationObjectMapper, exceptionFactory);
		this.entityMutationConverter = new DelegatingEntityMutationConverter(objectMapper, mutationObjectMapper, exceptionFactory);
		this.infrastructureMutationConverter = new DelegatingInfrastructureMutationConverter(mutationObjectMapper, exceptionFactory);
	}

	@Override
	@Nonnull
	public Object get(DataFetchingEnvironment environment) throws Exception {
		final ChangeCatalogCapture capture = environment.getSource();
		Assert.isPremiseValid(
			capture != null,
			() -> new GraphQLQueryResolvingInternalError("Missing mandatory capture object.")
		);
		final Mutation body =  capture.body();
		Assert.isPremiseValid(
			body != null,
			() -> new GraphQLQueryResolvingInternalError("ChangeCatalogCapture body is null even though it was requested.")
		);

		final Object convertedBody;
		if (body instanceof EntityMutation entityMutation) {
			convertedBody = this.entityMutationConverter.convertToOutput(entityMutation);
		} else if (body instanceof LocalMutation<?, ?> localMutation) {
			convertedBody = this.localMutationConverter.convertToOutput(localMutation);
		} else if (body instanceof TransactionMutation transactionMutation) {
			convertedBody = this.infrastructureMutationConverter.convertToOutput(transactionMutation);
		} else {
			throw new  GraphQLQueryResolvingInternalError("Unsupported entity mutation: " + capture.body());
		}
		Assert.isPremiseValid(
			convertedBody != null,
			() -> new GraphQLQueryResolvingInternalError("Converter body is null even though source body is present.")
		);

		return convertedBody;
	}
}
