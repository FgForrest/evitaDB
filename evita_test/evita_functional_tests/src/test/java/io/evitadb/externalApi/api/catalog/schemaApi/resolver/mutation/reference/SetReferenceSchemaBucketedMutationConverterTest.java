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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedHistogramIndexDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedBucketedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaBucketedMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link SetReferenceSchemaBucketedMutationConverter} verifying input
 * resolution, output serialization, and bucketed histogram/partially expression handling.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SetReferenceSchemaBucketedMutationConverter (REST/GraphQL)")
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(REFERENCE)
class SetReferenceSchemaBucketedMutationConverterTest {

	private SetReferenceSchemaBucketedMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetReferenceSchemaBucketedMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE,
			TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	/**
	 * Verifies that a basic input map with BUCKETED_IN_SCOPES containing a histogram entry
	 * is correctly resolved to a SetReferenceSchemaBucketedMutation. Tests both enum-typed
	 * and string-typed scope inputs.
	 */
	@Test
	@DisplayName("should resolve basic input to mutation")
	void shouldResolveInputToLocalMutation() {
		final SetReferenceSchemaBucketedMutation expectedMutation =
			new SetReferenceSchemaBucketedMutation(
				"tags",
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null, null)
				}
			);

		final SetReferenceSchemaBucketedMutation convertedMutation1 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
					))
					.build()
			);
		assertEquals(expectedMutation, convertedMutation1);

		final SetReferenceSchemaBucketedMutation convertedMutation2 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
					))
					.build()
			);
		assertEquals(expectedMutation, convertedMutation2);
	}

	/**
	 * Verifies that missing required data throws an exception.
	 */
	@Test
	@DisplayName("should throw when required data is missing")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), true)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(Map.of())
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput((Object) null)
		);
	}

	/**
	 * Verifies that output serialization produces the expected structure including
	 * mutationType, name, and bucketedInScopes list of maps.
	 */
	@Test
	@DisplayName("should serialize basic mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		final SetReferenceSchemaBucketedMutation inputMutation =
			new SetReferenceSchemaBucketedMutation(
				"tags",
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null, null)
				}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(),
						SetReferenceSchemaBucketedMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
							.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(), null)
							.e(ScopedHistogramIndexDefinitionDescriptor.ASSIGNED_WHEN.name(), null)
					))
					.build()
			);
	}

	/**
	 * Verifies that an input map with BUCKETED_PARTIALLY_IN_SCOPES containing
	 * scope+expression objects is correctly parsed into the mutation.
	 */
	@Test
	@DisplayName("should resolve input with bucketedPartially expressions")
	void shouldResolveInputWithBucketedPartially() {
		final SetReferenceSchemaBucketedMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
					))
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedBucketedPartiallyDescriptor.EXPRESSION.name(), "1 > 0")
					))
					.build()
			);

		assertNotNull(convertedMutation.getBucketedPartiallyInScopes());
		assertEquals(1, convertedMutation.getBucketedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, convertedMutation.getBucketedPartiallyInScopes()[0].scope());
		assertNotNull(convertedMutation.getBucketedPartiallyInScopes()[0].expression());
	}

	/**
	 * Verifies that a mutation with bucketedPartially expression serializes
	 * to output containing the BUCKETED_PARTIALLY_IN_SCOPES key.
	 */
	@Test
	@DisplayName("should serialize mutation with bucketedPartially to output")
	void shouldSerializeOutputWithBucketedPartially() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final SetReferenceSchemaBucketedMutation inputMutation =
			new SetReferenceSchemaBucketedMutation(
				"tags",
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null, null)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, expression)
				}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);

		assertNotNull(serializedMutation);
		assertThat(serializedMutation).containsKey(
			SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name()
		);
	}

	/**
	 * Verifies that an input with a valueExpression in the histogram entry
	 * is correctly parsed into the mutation's histogram entry.
	 */
	@Test
	@DisplayName("should resolve input with bucketed histogram and valueExpression")
	void shouldResolveInputWithBucketedHistogramAndValueExpression() {
		final SetReferenceSchemaBucketedMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
							.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(), "$price * $quantity")
					))
					.build()
			);

		assertNotNull(convertedMutation.getBucketedInScopes());
		assertEquals(1, convertedMutation.getBucketedInScopes().length);
		assertNotNull(convertedMutation.getBucketedInScopes()[0].valueExpression());
	}

	/**
	 * Verifies that a mutation with a non-null valueExpression serializes
	 * to output containing the correct expression string.
	 */
	@Test
	@DisplayName("should serialize output with valueExpression")
	void shouldSerializeOutputWithValueExpression() {
		final Expression valueExpression = ExpressionFactory.parse("$price * $quantity");
		final SetReferenceSchemaBucketedMutation inputMutation =
			new SetReferenceSchemaBucketedMutation(
				"tags",
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", valueExpression, null)
				}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);

		assertNotNull(serializedMutation);
		assertThat(serializedMutation).containsKey(
			SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name()
		);
		//noinspection unchecked
		final java.util.List<Map<String, Object>> bucketedList =
			(java.util.List<Map<String, Object>>) serializedMutation.get(
				SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name()
			);
		assertEquals(1, bucketedList.size());
		assertEquals("$price * $quantity", bucketedList.get(0).get(
			ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name()
		));
	}

	/**
	 * Pins the input-parse path for the per-histogram `assignedWhen` partition selector
	 * — a non-null `assignedWhen` field in the input map must end up on the parsed
	 * `ScopedHistogramIndexDefinition`. Mirrors the gRPC encoder-bug coverage so the
	 * REST/GraphQL surface cannot silently drop the field.
	 */
	@Test
	@DisplayName("should resolve input with bucketed histogram and assignedWhen partition selector")
	void shouldResolveInputWithBucketedHistogramAndAssignedWhen() {
		final SetReferenceSchemaBucketedMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(), "priceHistogram")
							.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(), "$price * $quantity")
							.e(ScopedHistogramIndexDefinitionDescriptor.ASSIGNED_WHEN.name(), "$active == 1")
					))
					.build()
			);

		assertNotNull(convertedMutation.getBucketedInScopes());
		assertEquals(1, convertedMutation.getBucketedInScopes().length);
		assertNotNull(
			convertedMutation.getBucketedInScopes()[0].assignedWhen(),
			"assignedWhen must be parsed from input"
		);
		assertEquals(
			"$active == 1",
			convertedMutation.getBucketedInScopes()[0].assignedWhen().toExpressionString()
		);
	}

	/**
	 * Pins the output-serialize path for `assignedWhen` — a mutation carrying a non-null
	 * `assignedWhen` on a histogram entry must emit the corresponding expression string
	 * under the `ASSIGNED_WHEN` property in the serialized map.
	 */
	@Test
	@DisplayName("should serialize output with assignedWhen partition selector")
	void shouldSerializeOutputWithAssignedWhen() {
		final Expression valueExpression = ExpressionFactory.parse("$price * $quantity");
		final Expression assignedWhen = ExpressionFactory.parse("$active == 1");
		final SetReferenceSchemaBucketedMutation inputMutation =
			new SetReferenceSchemaBucketedMutation(
				"tags",
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "priceHistogram", valueExpression, assignedWhen
					)
				}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);

		assertNotNull(serializedMutation);
		//noinspection unchecked
		final java.util.List<Map<String, Object>> bucketedList =
			(java.util.List<Map<String, Object>>) serializedMutation.get(
				SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name()
			);
		assertEquals(1, bucketedList.size());
		assertEquals(
			"$active == 1",
			bucketedList.get(0).get(ScopedHistogramIndexDefinitionDescriptor.ASSIGNED_WHEN.name())
		);
	}

	/**
	 * Verifies that an input map with nameOfTheIndex omitted throws an appropriate exception
	 * because the ScopedHistogramIndexDefinition compact constructor validates nameOfTheIndex is not null.
	 */
	@Test
	@DisplayName("should throw when nameOfTheIndex is missing from histogram entry")
	void shouldThrowWhenNameOfTheIndexIsMissing() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaBucketedMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
					))
					.build()
			)
		);
	}
}
