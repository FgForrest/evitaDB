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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.subscribingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingInfrastructureMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.graphql.api.catalog.resolver.mutation.GraphQLMutationResolvingExceptionFactory;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * Returns converted {@link ChangeSystemCapture#body()} to correct GraphQL representation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ChangeCatalogSchemaCaptureBodyDataFetcher implements DataFetcher<Object> {

	@Nonnull
	private final DelegatingLocalCatalogSchemaMutationConverter localCatalogSchemaMutationConverter;
	@Nonnull
	private final DelegatingEntitySchemaMutationConverter entitySchemaMutationConverter;
	@Nonnull
	private final DelegatingInfrastructureMutationConverter infrastructureMutationConverter;

	public ChangeCatalogSchemaCaptureBodyDataFetcher() {
		final PassThroughMutationObjectMapper mutationObjectMapper = PassThroughMutationObjectMapper.INSTANCE;
		final GraphQLMutationResolvingExceptionFactory exceptionFactory = GraphQLMutationResolvingExceptionFactory.INSTANCE;
		this.localCatalogSchemaMutationConverter = new DelegatingLocalCatalogSchemaMutationConverter(mutationObjectMapper, exceptionFactory);
		this.entitySchemaMutationConverter = new DelegatingEntitySchemaMutationConverter(mutationObjectMapper, exceptionFactory);
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
		final Mutation body = capture.body();
		Assert.isPremiseValid(
			body != null,
			() -> new GraphQLQueryResolvingInternalError("ChangeCatalogCapture body is null even though it was requested.")
		);

		final Object convertedBody;
		if (body instanceof LocalCatalogSchemaMutation localCatalogSchemaMutation) {
			convertedBody = this.localCatalogSchemaMutationConverter.convertToOutput(localCatalogSchemaMutation);
		} else if (body instanceof EntitySchemaMutation entitySchemaMutation) {
			convertedBody = this.entitySchemaMutationConverter.convertToOutput(entitySchemaMutation);
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
