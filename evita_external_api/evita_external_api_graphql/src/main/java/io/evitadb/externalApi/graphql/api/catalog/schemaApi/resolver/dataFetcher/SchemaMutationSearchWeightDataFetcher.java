/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Resolves the decimal ranking weight shared by schema reads and fulltext mutation output.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SchemaMutationSearchWeightDataFetcher implements DataFetcher<BigDecimal> {
	/** Shared fetcher for the ranking weights of schema reads and of fulltext mutation output. */
	@Nonnull
	@Getter private static final SchemaMutationSearchWeightDataFetcher instance =
		new SchemaMutationSearchWeightDataFetcher();

	/**
	 * Reads the canonical decimal weight, preserving zero and an omitted mutation setting.
	 *
	 * @param environment selection carrying a schema or fulltext weight mutation
	 * @return the decimal weight, or `null` when the mutation does not set one
	 */
	@Nullable
	@Override
	public BigDecimal get(@Nonnull DataFetchingEnvironment environment) {
		final Object source = environment.getSource();
		return switch (source) {
			case AttributeSchemaContract schema -> schema.getSearchWeight();
			case AssociatedDataSchemaContract schema -> schema.getSearchWeight();
			case ReferenceSchemaContract schema -> schema.getSearchableThroughWeight();
			case CreateAttributeSchemaMutation mutation -> mutation.getSearchWeight();
			case CreateGlobalAttributeSchemaMutation mutation -> mutation.getSearchWeight();
			case SetAttributeSchemaSearchableMutation mutation -> mutation.getSearchWeight();
			case CreateAssociatedDataSchemaMutation mutation -> mutation.getSearchWeight();
			case SetAssociatedDataSchemaSearchableMutation mutation -> mutation.getSearchWeight();
			case CreateReferenceSchemaMutation mutation -> mutation.getSearchableThroughWeight();
			case CreateReflectedReferenceSchemaMutation mutation -> mutation.getSearchableThroughWeight();
			case SetReferenceSchemaSearchableThroughMutation mutation -> mutation.getWeight();
			default -> throw new GraphQLQueryResolvingInternalError("Unsupported fulltext mutation: " + source);
		};
	}
}
