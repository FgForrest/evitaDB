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

/** Verifies the typed GraphQL rendering of mutation ranking weights without creating a server. */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@DisplayName("GraphQL mutation search weight output")
class SchemaMutationSearchWeightDataFetcherTest {

	/**
	 * A schema mutation rejects invalid decimal weights before GraphQL can expose them.
	 * @param weight decimal ranking contribution supplied by this case
	 */
	@ParameterizedTest
	@ValueSource(strings = {"-1", "1E+39", "1E-100", "1E-101", "1E+101"})
	@DisplayName("refuses invalid weights at schema mutation construction")
	void shouldRefuseInvalidWeight(@Nonnull BigDecimal weight) {
		assertThrows(
			io.evitadb.api.exception.InvalidSchemaMutationException.class,
			() -> new SetAttributeSchemaSearchableMutation("text", null, null, null, weight)
		);
	}

	/**
	 * Canonical decimals retain all significant digits and explicit zero in GraphQL output.
	 * @param weight decimal ranking contribution supplied by this case
	 */
	@ParameterizedTest
	@ValueSource(strings = {"0", "0.0000001", "12345678.5", "0.123456789012345678901234567891"})
	@DisplayName("renders a schema weight without losing precision")
	void shouldRenderDecimal(@Nonnull BigDecimal weight) {
		final DataFetchingEnvironment environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
			.source(new SetAttributeSchemaSearchableMutation("text", null, null, null, weight))
			.build();

		assertEquals(weight, SchemaMutationSearchWeightDataFetcher.getInstance().get(environment));
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
