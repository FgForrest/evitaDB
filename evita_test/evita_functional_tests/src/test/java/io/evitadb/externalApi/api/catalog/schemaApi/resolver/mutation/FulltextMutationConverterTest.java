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


package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import java.math.BigDecimal;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.FulltextMutationTestSupport;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Round-trips Java mutations through the shared GraphQL and REST dispatchers and input aggregates. */
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@DisplayName("Fulltext mutation core round trips")
class FulltextMutationConverterTest {
	/** Omitting the additive input member keeps old GraphQL and REST analyzer declarations valid. */
	@DisplayName("defaults omitted analyzer samples to an empty list")
	@Test
	void shouldReadOmittedSamples() {
		for (final LocalCatalogSchemaMutation mutation : List.of(
			new CreateFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of()),
			new ModifyFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of()))) {
			final Map<String, Object> declaration = CollectionUtils.createLinkedHashMap(5);
			declaration.put("name", "custom");
			declaration.put("basedOn", "text");
			final String type = mutation.getClass().getSimpleName();
			assertEquals(List.of(mutation), new LocalCatalogSchemaMutationInputAggregateConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertFromInput(Map.of(Character.toLowerCase(type.charAt(0)) + type.substring(1), declaration)));
		}
	}

	/** Checks every create and set family with absent, zero and positive weights. */
	@DisplayName("Preserves every declared fulltext setting through conversion")
	@ParameterizedTest
	@MethodSource("io.evitadb.externalApi.FulltextMutationTestSupport#roundTripMutations")
	void shouldRoundTrip(@Nonnull SchemaMutation mutation) {
		final Object output = output(mutation);
		final String type = mutation.getClass().getSimpleName();
		final Map<String, Object> input = Map.of(Character.toLowerCase(type.charAt(0)) + type.substring(1),
			FulltextMutationTestSupport.typedInput(output));
		if (mutation instanceof LocalEntitySchemaMutation) {
			assertEquals(List.of(mutation), new LocalEntitySchemaMutationInputAggregateConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertFromInput(input));
		}
		if (mutation instanceof LocalCatalogSchemaMutation) {
			assertEquals(List.of(mutation), new LocalCatalogSchemaMutationInputAggregateConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertFromInput(input));
		}
	}

	/** Exercises the reference attribute wrapper's dedicated input aggregate and output dispatcher. */
	@DisplayName("Preserves the reference attribute mutation wrapper")
	@Test
	void shouldRoundTripReferenceAttribute() {
		final SetAttributeSchemaSearchableMutation attribute = new SetAttributeSchemaSearchableMutation(
			"text", Scope.DEFAULT_SCOPES, "text", 73, BigDecimal.ZERO);
		final ModifyReferenceAttributeSchemaMutation mutation =
			new ModifyReferenceAttributeSchemaMutation("related", attribute);
		final Map<String, Object> input = Map.of("modifyReferenceAttributeSchemaMutation", Map.of(
			"name", "related",
			"attributeSchemaMutation", Map.of("setAttributeSchemaSearchableMutation", output(attribute))
		));
		assertEquals(List.of(mutation), new LocalEntitySchemaMutationInputAggregateConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
		).convertFromInput(input));
		final Map<?, ?> serialized = (Map<?, ?>) output(mutation);
		assertThat(serialized.get("attributeSchemaMutation")).usingRecursiveComparison().isEqualTo(output(attribute));
	}

	/** Converts through every applicable output dispatcher, including the global attribute route. */
	@Nonnull
	private static Object output(@Nonnull SchemaMutation mutation) {
		final Object output;
		if (mutation instanceof EntitySchemaMutation entity) {
			output = new DelegatingEntitySchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput(entity);
		} else {
			output = new DelegatingLocalCatalogSchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput((LocalCatalogSchemaMutation) mutation);
		}
		if (mutation instanceof LocalCatalogSchemaMutation catalog) {
			assertThat(output).usingRecursiveComparison().isEqualTo(new DelegatingLocalCatalogSchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput(catalog));
		}
		return output;
	}
}
