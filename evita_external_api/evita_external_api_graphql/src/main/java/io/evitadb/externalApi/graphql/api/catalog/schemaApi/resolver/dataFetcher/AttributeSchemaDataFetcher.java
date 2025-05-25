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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Finds and returns concrete {@link AttributeSchemaContract} from current parent {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AttributeSchemaDataFetcher implements DataFetcher<AttributeSchemaContract> {

	@Nonnull
	private final String name;

	@Nonnull
	@Override
	public AttributeSchemaContract get(DataFetchingEnvironment environment) throws Exception {
		final AttributeSchemaProvider<AttributeSchemaContract> attributeSchemaProvider = Objects.requireNonNull(environment.getSource());
		return attributeSchemaProvider
			.getAttribute(this.name)
			.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find attribute schema for name `" + this.name + "`."));
	}
}
