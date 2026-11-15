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
 * Converts the `Float` ranking weights of the fulltext schema mutations to the decimal the mutation output descriptors
 * declare, because the evitaDB data types the API exposes have no `Float`. The decimal is built from the shortest
 * `Float` text, so a weight such as `0.1` is not rendered as its binary expansion.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SchemaMutationSearchWeightDataFetcher implements DataFetcher<BigDecimal> {
	/** Shared fetcher for fulltext mutation weights in catalog and system schemas. */
	@Getter private static final SchemaMutationSearchWeightDataFetcher instance =
		new SchemaMutationSearchWeightDataFetcher();

	/**
	 * Converts the weight of the source mutation to a decimal carrying the `Float` decimal precision, zero included.
	 *
	 * @param environment the environment whose source is one of the fulltext schema mutations carrying a weight
	 * @return the weight as a decimal, or `null` when the mutation does not set a weight
	 * @throws GraphQLQueryResolvingInternalError when the weight is NaN or infinite, which a mutation may carry until
	 *                                            schema validation refuses it: a decimal cannot hold such a value,
	 *                                            and `null` would misreport it as a weight the mutation does not set
	 */
	@Nullable
	@Override
	public BigDecimal get(@Nonnull DataFetchingEnvironment environment) {
		final Object source = environment.getSource();
		final Float weight = switch (source) {
			case CreateAttributeSchemaMutation mutation -> mutation.getSearchWeight();
			case CreateGlobalAttributeSchemaMutation mutation -> mutation.getSearchWeight();
			case SetAttributeSchemaSearchableMutation mutation -> mutation.getSearchWeight();
			case CreateAssociatedDataSchemaMutation mutation -> mutation.getSearchWeight();
			case SetAssociatedDataSchemaSearchableMutation mutation -> mutation.getSearchWeight();
			case CreateReferenceSchemaMutation mutation -> mutation.getSearchableThroughWeight();
			case CreateReflectedReferenceSchemaMutation mutation -> mutation.getSearchableThroughWeight();
			case SetReferenceSchemaSearchableThroughMutation mutation -> mutation.getWeight();
			default -> throw new IllegalArgumentException("Unsupported fulltext mutation: " + source);
		};
		if (weight == null) {
			return null;
		}
		if (!Float.isFinite(weight)) {
			throw new GraphQLQueryResolvingInternalError(
				"Mutation `" + source.getClass().getSimpleName() + "` carries the non-finite ranking weight `" +
					weight + "`, which the decimal weight field cannot represent."
			);
		}
		return new BigDecimal(Float.toString(weight));
	}
}
