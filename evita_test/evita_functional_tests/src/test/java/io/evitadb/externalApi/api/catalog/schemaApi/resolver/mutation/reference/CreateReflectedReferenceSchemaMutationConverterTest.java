/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedBucketedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedHistogramIndexDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.CreateReflectedReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CreateReflectedReferenceSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateReflectedReferenceSchemaMutationConverterTest {

	private CreateReflectedReferenceSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateReflectedReferenceSchemaMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE,
			TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateReflectedReferenceSchemaMutation expectedMutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				"depr",
				Cardinality.ZERO_OR_MORE,
				"tag",
				"tags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		final CreateReflectedReferenceSchemaMutation convertedMutation1 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(),
						"depr")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(),
						Cardinality.ZERO_OR_MORE)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(
						CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
								.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(),
									ReferenceIndexType.FOR_FILTERING.name())
						)
					)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(),
						list().i(Scope.LIVE))
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT)
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTE_INHERITANCE_FILTER.name(), list().i("order"))
					.build()
			);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateReflectedReferenceSchemaMutation convertedMutation2 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(),
						"depr")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(),
						"ZERO_OR_MORE")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(
						CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
								.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(),
									ReferenceIndexType.FOR_FILTERING.name())
						)
					)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(),
						list().i(Scope.LIVE.name()))
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTE_INHERITANCE_FILTER.name(), list().i("order"))
					.build()
			);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateReflectedReferenceSchemaMutation expectedMutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				null,
				null,
				null,
				"tag",
				"tags",
				null,
				null,
				null,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				null
			);

		final CreateReflectedReferenceSchemaMutation convertedMutation1 =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final CreateReflectedReferenceSchemaMutation inputMutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				"depr",
				Cardinality.ZERO_OR_MORE,
				"tag",
				"tags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(),
						CreateReflectedReferenceSchemaMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(),
						"depr")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(),
						Cardinality.ZERO_OR_MORE.name())
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(
						CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
								.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(),
									ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING.name())
						)
					)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(),
						array().i(Scope.LIVE.name()))
					.e(CreateReflectedReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name(),
						list())
					.e(CreateReflectedReferenceSchemaMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name(),
						list())
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTE_INHERITANCE_FILTER.name(), array().i("order"))
					.build()
			);
	}

	/**
	 * Verifies that output serialization includes FACETED_PARTIALLY_IN_SCOPES
	 * when the mutation contains facetedPartially expressions.
	 */
	@Test
	void shouldSerializeOutputWithFacetedPartially() {
		final Expression expression = ExpressionFactory.parse("1 > 0");
		final CreateReflectedReferenceSchemaMutation inputMutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				"depr",
				Cardinality.ZERO_OR_MORE,
				"tag",
				"tags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				},
				null,
				null,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation).containsKey(
			CreateReflectedReferenceSchemaMutationDescriptor.FACETED_PARTIALLY_IN_SCOPES.name()
		);
	}

	/**
	 * Verifies that input parsing without FACETED_PARTIALLY_IN_SCOPES
	 * produces a mutation with null facetedPartially (meaning inherited).
	 */
	@Test
	void shouldResolveInputWithoutFacetedPartially() {
		final CreateReflectedReferenceSchemaMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(),
						list().i(Scope.LIVE))
					.build()
			);

		// facetedPartially is null when not provided (inherited)
		assertNull(convertedMutation.getFacetedPartiallyInScopes());
	}

	/**
	 * Verifies that input with BUCKETED_IN_SCOPES and BUCKETED_PARTIALLY_IN_SCOPES
	 * is correctly resolved to a mutation with populated bucketed fields.
	 */
	@Test
	void shouldResolveInputWithBucketedFields() {
		final CreateReflectedReferenceSchemaMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
								.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(),
									"priceHistogram")
								.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(),
									"$price * $quantity")
						))
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.BUCKETED_PARTIALLY_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedBucketedPartiallyDescriptor.EXPRESSION.name(),
								"$active == 1")
					))
					.build()
			);

		assertNotNull(convertedMutation.getBucketedInScopes());
		assertEquals(1, convertedMutation.getBucketedInScopes().length);
		assertEquals(Scope.LIVE, convertedMutation.getBucketedInScopes()[0].scope());
		assertEquals("priceHistogram",
			convertedMutation.getBucketedInScopes()[0].nameOfTheIndex());
		assertNotNull(convertedMutation.getBucketedInScopes()[0].valueExpression());

		assertNotNull(convertedMutation.getBucketedPartiallyInScopes());
		assertEquals(1, convertedMutation.getBucketedPartiallyInScopes().length);
		assertEquals(Scope.LIVE, convertedMutation.getBucketedPartiallyInScopes()[0].scope());
		assertNotNull(convertedMutation.getBucketedPartiallyInScopes()[0].expression());
	}

	/**
	 * Verifies that output serialization includes BUCKETED_IN_SCOPES and
	 * BUCKETED_PARTIALLY_IN_SCOPES when the mutation contains bucketed data.
	 */
	@Test
	void shouldSerializeOutputWithBucketedFields() {
		final Expression valueExpression = ExpressionFactory.parse("$price * $quantity");
		final Expression bucketedPartiallyExpr = ExpressionFactory.parse("$active == 1");
		final CreateReflectedReferenceSchemaMutation inputMutation =
			new CreateReflectedReferenceSchemaMutation(
				"tags",
				"desc",
				"depr",
				Cardinality.ZERO_OR_MORE,
				"tag",
				"tags",
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				null,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, "priceHistogram", valueExpression
					)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, bucketedPartiallyExpr)
				},
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				new String[]{"order"}
			);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation).containsKey(
			CreateReflectedReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name()
		);
		assertThat(serializedMutation).containsKey(
			CreateReflectedReferenceSchemaMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name()
		);

		//noinspection unchecked
		final List<Map<String, Object>> bucketedList =
			(List<Map<String, Object>>) serializedMutation.get(
				CreateReflectedReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name()
			);
		assertEquals(1, bucketedList.size());
		assertEquals("priceHistogram", bucketedList.get(0).get(
			ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name()
		));
		assertEquals("$price * $quantity", bucketedList.get(0).get(
			ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name()
		));
	}

	/**
	 * Verifies that input parsing without BUCKETED_IN_SCOPES and BUCKETED_PARTIALLY_IN_SCOPES
	 * produces a mutation with null bucketed fields (inherited from reflected reference).
	 */
	@Test
	void shouldResolveInputWithoutBucketedFields() {
		final CreateReflectedReferenceSchemaMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(),
						"tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME
						.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor
						.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(),
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT)
					.build()
			);

		// bucketed is not inheritable — absent input coalesces to EMPTY
		assertNotNull(convertedMutation.getBucketedInScopes());
		assertEquals(0, convertedMutation.getBucketedInScopes().length);
		assertNotNull(convertedMutation.getBucketedPartiallyInScopes());
		assertEquals(0, convertedMutation.getBucketedPartiallyInScopes().length);
	}
}
