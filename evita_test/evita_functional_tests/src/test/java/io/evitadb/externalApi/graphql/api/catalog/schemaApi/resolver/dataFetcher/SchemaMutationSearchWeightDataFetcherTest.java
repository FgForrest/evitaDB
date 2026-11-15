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


package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the typed GraphQL rendering of mutation ranking weights without creating a server. */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@DisplayName("GraphQL mutation search weight output")
class SchemaMutationSearchWeightDataFetcherTest {

	/**
	 * A mutation may carry a non-finite weight until schema validation refuses it, and a decimal cannot hold one;
	 * null is not an answer either, because a null weight reads as "keep the current weight".
	 */
	@ParameterizedTest
	@ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
	@DisplayName("refuses a non-finite weight with a descriptive error")
	void shouldRefuseNonFiniteWeight(float weight) {
		final DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
			.source(new SetAttributeSchemaSearchableMutation("text", null, null, null, weight))
			.build();

		final GraphQLQueryResolvingInternalError error = assertThrows(
			GraphQLQueryResolvingInternalError.class,
			() -> SchemaMutationSearchWeightDataFetcher.getInstance().get(environment)
		);

		assertTrue(error.getPublicMessage().contains(Float.toString(weight)), error.getPublicMessage());
	}

}
