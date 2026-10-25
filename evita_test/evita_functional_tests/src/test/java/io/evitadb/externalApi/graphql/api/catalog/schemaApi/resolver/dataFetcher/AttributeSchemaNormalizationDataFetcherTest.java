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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the public rendering of declared chains without creating a server or consulting indexes. */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(SCHEMA)
@DisplayName("GraphQL attribute normalization output")
class AttributeSchemaNormalizationDataFetcherTest {

	/** Supplies empty and ordered chains, including step parameters. */
	static Stream<List<AnalysisStep>> chains() {
		return Stream.of(List.of(), List.of(lowercase()), List.of(removeCharacters("- "), trim(), asciiFolding()));
	}

	@ParameterizedTest
	@MethodSource("chains")
	@DisplayName("renders declared names, order and parameter objects")
	void shouldRenderDeclaredChain(List<AnalysisStep> chain) {
		final AttributeSchemaContract schema = Mockito.mock(AttributeSchemaContract.class);
		Mockito.when(schema.getNormalization()).thenReturn(chain);
		final DataFetchingEnvironment environment = Mockito.mock(DataFetchingEnvironment.class);
		Mockito.doReturn(schema).when(environment).getSource();
		final List<Map<String, Object>> expected = chain.stream()
			.<Map<String, Object>>map(step -> Map.of("step", step.name(), "parameters", step.parameters()))
			.toList();
		assertEquals(expected, AttributeSchemaNormalizationDataFetcher.getInstance().get(environment));
		Mockito.verify(schema).getNormalization();
		Mockito.verifyNoMoreInteractions(schema);
	}
	/** Supplies free-form parameter objects with values that require evitaDB's custom scalar encodings. */
	static Stream<Arguments> parameterObjects() {
		return Stream.of(
			Arguments.of(Map.of(), Map.of()),
			Arguments.of(Map.of("characters", "- "), Map.of("characters", "- ")),
			Arguments.of(Map.of("longs", new long[]{1L, 2L}, "date", LocalDate.of(2026, 10, 25)),
				Map.of("longs", List.of("1", "2"), "date", "2026-10-25")),
			Arguments.of(Map.of("decimal", new BigDecimal("1.25")), Map.of("decimal", "1.25"))
		);
	}

	@ParameterizedTest
	@MethodSource("parameterObjects")
	@DisplayName("serializes parameter objects through ANY using custom scalar encodings")
	void shouldSerializeParameters(Map<String, Object> parameters, Map<String, Object> expected) {
		assertEquals(expected, GraphQLScalars.ANY.getCoercing().serialize(parameters));
	}

}
