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
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.TopLevelCatalogSchemaMutationAggregateConverter;
import io.evitadb.externalApi.graphql.api.catalog.resolver.mutation.GraphQLMutationResolvingExceptionFactory;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Returns converted {@link ChangeSystemCapture#body()} to correct GraphQL representation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ChangeSystemCaptureBodyDataFetcher implements DataFetcher<Object> {

	@Nonnull
	private final TopLevelCatalogSchemaMutationAggregateConverter bodyConverter = new TopLevelCatalogSchemaMutationAggregateConverter(
		new PassThroughMutationObjectParser(),
		new GraphQLMutationResolvingExceptionFactory()
	);

	@Override
	@Nullable
	public Object get(DataFetchingEnvironment environment) throws Exception {
		final ChangeSystemCapture capture = Objects.requireNonNull(environment.getSource());
		final TopLevelCatalogSchemaMutation body = (TopLevelCatalogSchemaMutation) capture.body();
		Assert.isPremiseValid(
			body != null,
			() -> new GraphQLQueryResolvingInternalError("ChangeSystemCapture body is null even though it was requested.")
		);
		return this.bodyConverter.convertToOutput(body);
	}
}
