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
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedBucketedPartiallyDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedHistogramIndexDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexedComponentsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.CreateReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CreateReferenceSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateReferenceSchemaMutationConverterTest {

	private CreateReferenceSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateReferenceSchemaMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE,
			TestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateReferenceSchemaMutation expectedMutation = createReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			true,
			"tagGroup",
			true,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE}
		);

		final CreateReferenceSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReferenceSchemaMutationDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE)
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE.name(), "tagGroup")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), true)
				.e(
					CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(),
								ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.e(CreateReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateReferenceSchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReferenceSchemaMutationDescriptor.CARDINALITY.name(), "ZERO_OR_MORE")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), "true")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE.name(), "tagGroup")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), "true")
				.e(
					CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(),
								ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.e(CreateReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateReferenceSchemaMutation expectedMutation = createReferenceSchemaMutation(
			"tags",
			null,
			null,
			null,
			"tag",
			true,
			null,
			false,
			null,
			null,
			null
		);

		final CreateReferenceSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
				.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
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
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final CreateReferenceSchemaMutation inputMutation = createReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			true,
			"tagGroup",
			true,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
			},
			null,
			new Scope[]{Scope.LIVE}
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(),
						CreateReferenceSchemaMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.e(CreateReferenceSchemaMutationDescriptor.CARDINALITY.name(),
						Cardinality.ZERO_OR_MORE.name())
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE.name(), "tagGroup")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), true)
					.e(
						CreateReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
								.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(),
									ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING.name())
						)
					)
					.e(
						CreateReferenceSchemaMutationDescriptor.INDEXED_COMPONENTS_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
								.e(
									ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS.name(),
									array().i(ReferenceIndexedComponents.REFERENCED_ENTITY.name())
								)
						)
					)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateReferenceSchemaMutationDescriptor.FACETED_PARTIALLY_IN_SCOPES.name(),
						list())
					.e(CreateReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name(),
						list())
					.e(CreateReferenceSchemaMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name(),
						list())
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
		final CreateReferenceSchemaMutation inputMutation = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			true,
			"tagGroup",
			true,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			new ScopedFacetedPartially[]{
				new ScopedFacetedPartially(Scope.LIVE, expression)
			},
			null,
			null
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation).containsKey(
			CreateReferenceSchemaMutationDescriptor.FACETED_PARTIALLY_IN_SCOPES.name()
		);
	}

	/**
	 * Verifies that input parsing without FACETED_PARTIALLY_IN_SCOPES
	 * produces a mutation with empty facetedPartially (null defaults to empty).
	 */
	@Test
	void shouldResolveInputWithoutFacetedPartially() {
		final CreateReferenceSchemaMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(),
						true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(),
						false)
					.e(CreateReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(),
						list().i(Scope.LIVE))
					.build()
			);

		assertEquals(0, convertedMutation.getFacetedPartiallyInScopes().length);
	}

	/**
	 * Verifies that input with BUCKETED_IN_SCOPES and BUCKETED_PARTIALLY_IN_SCOPES
	 * is correctly resolved to a mutation with populated bucketed fields.
	 */
	@Test
	void shouldResolveInputWithBucketedFields() {
		final CreateReferenceSchemaMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(),
						true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(),
						false)
					.e(CreateReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name(), list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedHistogramIndexDefinitionDescriptor.NAME_OF_THE_INDEX.name(),
								"priceHistogram")
							.e(ScopedHistogramIndexDefinitionDescriptor.VALUE_EXPRESSION.name(),
								"$price * $quantity")
					))
					.e(CreateReferenceSchemaMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name(),
						list().i(
							map()
								.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
								.e(ScopedBucketedPartiallyDescriptor.EXPRESSION.name(),
									"$active == 1")
						))
					.build()
			);

		assertEquals(1, convertedMutation.getBucketedInScopes().length);
		assertEquals(Scope.LIVE, convertedMutation.getBucketedInScopes()[0].scope());
		assertEquals("priceHistogram",
			convertedMutation.getBucketedInScopes()[0].nameOfTheIndex());
		assertNotNull(convertedMutation.getBucketedInScopes()[0].valueExpression());

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
		final CreateReferenceSchemaMutation inputMutation = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			true,
			"tagGroup",
			true,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", valueExpression)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, bucketedPartiallyExpr)
			}
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation =
			(Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation).containsKey(
			CreateReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name()
		);
		assertThat(serializedMutation).containsKey(
			CreateReferenceSchemaMutationDescriptor.BUCKETED_PARTIALLY_IN_SCOPES.name()
		);

		//noinspection unchecked
		final List<Map<String, Object>> bucketedList =
			(List<Map<String, Object>>) serializedMutation.get(
				CreateReferenceSchemaMutationDescriptor.BUCKETED_IN_SCOPES.name()
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
	 * produces a mutation with empty bucketed arrays (null defaults to empty).
	 */
	@Test
	void shouldResolveInputWithoutBucketedFields() {
		final CreateReferenceSchemaMutation convertedMutation =
			this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(),
						true)
					.e(CreateReferenceSchemaMutationDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(),
						false)
					.build()
			);

		assertEquals(0, convertedMutation.getBucketedInScopes().length);
		assertEquals(0, convertedMutation.getBucketedPartiallyInScopes().length);
	}

	/**
	 * Test-only helper producing a {@link CreateReferenceSchemaMutation} with empty
	 * `facetedPartially`, `bucketed` and `bucketedPartially` arrays. The production
	 * code intentionally no longer exposes an 11-arg constructor so that callers
	 * rebuilding mutations from an existing schema cannot silently drop the per-scope
	 * expression fields; tests that don't exercise those fields use this helper to
	 * preserve readability.
	 */
	@Nonnull
	private static CreateReferenceSchemaMutation createReferenceSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		boolean referencedEntityTypeManaged,
		@Nullable String referencedGroupType,
		boolean referencedGroupTypeManaged,
		@Nullable ScopedReferenceIndexType[] indexedInScopes,
		@Nullable ScopedReferenceIndexedComponents[] indexedComponentsInScopes,
		@Nullable Scope[] facetedInScopes
	) {
		return new CreateReferenceSchemaMutation(
			name, description, deprecationNotice, cardinality,
			referencedEntityType, referencedEntityTypeManaged,
			referencedGroupType, referencedGroupTypeManaged,
			indexedInScopes, indexedComponentsInScopes, facetedInScopes,
			null, null, null
		);
	}
}
