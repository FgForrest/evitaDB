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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	/**
	 * The decimal must carry the shortest text of the `Float`, not its binary expansion, and must keep an explicit
	 * zero as a value.
	 */
	@ParameterizedTest
	@CsvSource({"0.1, 0.1", "0.0, 0.0", "2.5, 2.5", "1.0E-4, 0.00010"})
	@DisplayName("renders a finite weight as its shortest decimal")
	void shouldRenderShortestDecimal(float weight, @Nonnull String expected) {
		final DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
			.source(new SetAttributeSchemaSearchableMutation("text", null, null, null, weight))
			.build();

		assertEquals(new BigDecimal(expected), SchemaMutationSearchWeightDataFetcher.getInstance().get(environment));
	}

	/** A mutation that leaves the weight unset must read as null, which the client understands as "unchanged". */
	@Test
	@DisplayName("returns null for a mutation that sets no weight")
	void shouldReturnNullWhenMutationSetsNoWeight() {
		final DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
			.source(new SetAttributeSchemaSearchableMutation("text", null, null, null, null))
			.build();

		assertNull(SchemaMutationSearchWeightDataFetcher.getInstance().get(environment));
	}

	/** A source that carries no ranking weight is a wiring mistake and must not be answered silently. */
	@Test
	@DisplayName("refuses a source without a ranking weight")
	void shouldRefuseUnsupportedSource() {
		final DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
			.source("not a schema")
			.build();

		assertThrows(
			GraphQLQueryResolvingInternalError.class,
			() -> SchemaMutationSearchWeightDataFetcher.getInstance().get(environment)
		);
	}

}
