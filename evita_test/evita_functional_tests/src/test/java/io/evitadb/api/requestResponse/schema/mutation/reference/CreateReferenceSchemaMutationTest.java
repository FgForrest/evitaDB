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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.NamingConvention;
import io.evitadb.exception.InvalidClassifierFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CreateReferenceSchemaMutation} verifying creation of reference schemas,
 * combination with removal mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CreateReferenceSchemaMutation")
class CreateReferenceSchemaMutationTest {

	static final String REFERENCE_NAME = "categories";
	static final String REFERENCE_TYPE = "category";
	static final String GROUP_TYPE = "group";
	static final String REFERENCE_ATTRIBUTE_PRIORITY = "priority";
	static final String REFERENCE_ATTRIBUTE_QUANTITY = "quantity";
	static final String REFERENCE_ATTRIBUTE_COMPOUND = "priority";

	/**
	 * Creates a reference schema fixture with standard properties for testing.
	 *
	 * @return a reference schema with indexed=true and faceted=true
	 */
	@Nonnull
	static ReferenceSchemaContract createExistingReferenceSchema() {
		return createExistingReferenceSchema(true);
	}

	/**
	 * Creates a reference schema fixture with configurable indexing for testing.
	 *
	 * @param indexed whether the reference should be indexed
	 * @return a reference schema with the specified indexing configuration
	 */
	@Nonnull
	static ReferenceSchemaContract createExistingReferenceSchema(boolean indexed) {
		final Map<Scope, ReferenceIndexType> indexedScopesMap = indexed ?
				Map.of(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) :
				Collections.emptyMap();
			return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			NamingConvention.generate(REFERENCE_NAME),
			"oldDescription",
			"oldDeprecationNotice",
			Cardinality.ZERO_OR_MORE,
			REFERENCE_TYPE,
			NamingConvention.generate(REFERENCE_TYPE),
			false,
			GROUP_TYPE,
			NamingConvention.generate(GROUP_TYPE),
			false,
			indexedScopesMap,
			ReferenceSchema.defaultIndexedComponents(indexedScopesMap),
			indexed ? EnumSet.of(Scope.LIVE) : EnumSet.noneOf(Scope.class),
			Collections.emptyMap(),
			Map.of(
				REFERENCE_ATTRIBUTE_PRIORITY,
				AttributeSchema._internalBuild(
					REFERENCE_ATTRIBUTE_PRIORITY,
					"oldDescription",
					"oldDeprecationNotice",
					new ScopedAttributeUniquenessType[]{
						new ScopedAttributeUniquenessType(
							Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE
						)
					},
					Scope.NO_SCOPE,
					Scope.NO_SCOPE,
					false,
					false,
					false,
					Integer.class,
					null,
					2
				),
				REFERENCE_ATTRIBUTE_QUANTITY,
				AttributeSchema._internalBuild(
					REFERENCE_ATTRIBUTE_QUANTITY,
					"oldDescription",
					"oldDeprecationNotice",
					new ScopedAttributeUniquenessType[]{
						new ScopedAttributeUniquenessType(
							Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE
						)
					},
					Scope.NO_SCOPE,
					Scope.NO_SCOPE,
					false,
					false,
					false,
					Integer.class,
					null,
					2
				)
			),
			Map.of(
				REFERENCE_ATTRIBUTE_COMPOUND,
				SortableAttributeCompoundSchema._internalBuild(
					REFERENCE_ATTRIBUTE_COMPOUND,
					"oldDescription",
					"oldDeprecationNotice",
					new Scope[]{Scope.LIVE},
					List.of(
						new AttributeElement(
							REFERENCE_ATTRIBUTE_PRIORITY,
							OrderDirection.DESC,
							OrderBehaviour.NULLS_FIRST
						),
						new AttributeElement(
							REFERENCE_ATTRIBUTE_QUANTITY,
							OrderDirection.ASC,
							OrderBehaviour.NULLS_LAST
						)
					)
				)
			)
		);
	}

	/**
	 * Creates a reflected reference schema fixture for testing mutations that handle
	 * reflected references differently.
	 *
	 * @return a reflected reference schema with standard test properties
	 */
	@Nonnull
	static ReflectedReferenceSchema createExistingReflectedReferenceSchema() {
		// Uses INHERIT_ONLY_SPECIFIED with empty filter so that getAttributes()
		// can be called without needing the reflected reference to be available.
		return ReflectedReferenceSchema._internalBuild(
			REFERENCE_NAME,
			"reflectedDescription",
			"reflectedDeprecationNotice",
			REFERENCE_TYPE,
			"originalRef",
			Cardinality.ZERO_OR_MORE,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null
		);
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should decompose into individual mutations when remove+create with different settings")
		void shouldBeReplacedWithIndividualMutationsWhenReferenceWasRemovedAndCreatedWithDifferentSettings() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"description", "deprecationNotice",
				Cardinality.EXACTLY_ONE, "brand", false,
				null, false,
				false, false
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			assertNotNull(result);
			assertFalse(result.discarded());
			assertEquals(11, result.current().length);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceSchemaDescriptionMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceSchemaDeprecationNoticeMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceSchemaCardinalityMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceSchemaRelatedEntityMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceSchemaRelatedEntityGroupMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaIndexedMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaFacetedMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceAttributeSchemaMutation.class::isInstance)
			);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(
						ModifyReferenceSortableAttributeCompoundSchemaMutation.class::isInstance
					)
			);
		}

		@Test
		@DisplayName("should emit single SetReferenceSchemaFacetedMutation carrying both scopes and expressions")
		void shouldEmitSingleFacetedMutationWithBothScopesAndExpressions() {
			// Create has faceted={LIVE} + facetedPartially={LIVE: expr}
			// Existing has faceted={} (not faceted) + facetedPartially={}
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"oldDescription", "oldDeprecationNotice",
				Cardinality.ZERO_OR_MORE, REFERENCE_TYPE, false,
				GROUP_TYPE, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				}
			);
			// existing schema is NOT faceted — facetedInScopes={}
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema(false)));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			assertNotNull(result);
			// Count SetReferenceSchemaFacetedMutation instances — must be exactly 1
			final SetReferenceSchemaFacetedMutation[] facetedMutations = Arrays.stream(result.current())
				.filter(SetReferenceSchemaFacetedMutation.class::isInstance)
				.map(SetReferenceSchemaFacetedMutation.class::cast)
				.toArray(SetReferenceSchemaFacetedMutation[]::new);
			assertEquals(
				1, facetedMutations.length,
				"Expected exactly one SetReferenceSchemaFacetedMutation but found " +
					facetedMutations.length + " — two separate mutations would cause " +
					"Set+Set combining to lose facetedInScopes"
			);
			// The single mutation must carry both facetedInScopes AND facetedPartially
			final SetReferenceSchemaFacetedMutation facetedMutation = facetedMutations[0];
			assertNotNull(
				facetedMutation.getFacetedInScopes(),
				"facetedInScopes must not be null — null means 'don't change'"
			);
			assertArrayEquals(new Scope[]{Scope.LIVE}, facetedMutation.getFacetedInScopes());
			assertNotNull(
				facetedMutation.getFacetedPartiallyInScopes(),
				"facetedPartiallyInScopes must not be null"
			);
			assertEquals(1, facetedMutation.getFacetedPartiallyInScopes().length);
			assertEquals(Scope.LIVE, facetedMutation.getFacetedPartiallyInScopes()[0].scope());
		}

		@Test
		@DisplayName("should not combine when removal targets different reference")
		void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentReference() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"oldDescription",
				"oldDeprecationNotice",
				Cardinality.ZERO_OR_MORE,
				REFERENCE_TYPE,
				false,
				GROUP_TYPE,
				false,
				true,
				true
			);
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation("differentName");

			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					removeMutation
				)
			);
		}

		@Test
		@DisplayName("should return null when existing is a reflected reference")
		void shouldReturnNullWhenExistingIsReflectedReference() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"description", "deprecationNotice",
				Cardinality.EXACTLY_ONE, REFERENCE_TYPE, false,
				null, false,
				true, false
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReflectedReferenceSchema()));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			assertNull(result);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"desc", null,
				Cardinality.ZERO_OR_MORE, REFERENCE_TYPE, false,
				null, false,
				true, false
			);
			final LocalEntitySchemaMutation unrelatedMutation =
				new ModifyReferenceSchemaDescriptionMutation(REFERENCE_NAME, "notice");

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					unrelatedMutation
				);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("should create new reference schema")
		void shouldCreateReference() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"description",
				"deprecationNotice",
				Cardinality.ZERO_OR_MORE,
				REFERENCE_TYPE,
				false,
				GROUP_TYPE,
				false,
				true,
				true
			);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			assertEquals(REFERENCE_NAME, referenceSchema.getName());
			assertEquals("description", referenceSchema.getDescription());
			assertEquals("deprecationNotice", referenceSchema.getDeprecationNotice());
			assertEquals(Cardinality.ZERO_OR_MORE, referenceSchema.getCardinality());
			assertEquals(REFERENCE_TYPE, referenceSchema.getReferencedEntityType());
			assertEquals(GROUP_TYPE, referenceSchema.getReferencedGroupType());
			assertFalse(referenceSchema.isReferencedEntityTypeManaged());
			assertFalse(referenceSchema.isReferencedGroupTypeManaged());
			assertTrue(referenceSchema.isIndexed());
			assertTrue(referenceSchema.isFaceted());
		}

		@Test
		@DisplayName("should create reference with explicit indexed components")
		void shouldCreateReferenceWithExplicitIndexedComponents() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"description", "deprecationNotice",
				Cardinality.ZERO_OR_MORE,
				REFERENCE_TYPE, false,
				GROUP_TYPE, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				},
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				},
				new Scope[]{Scope.LIVE}
			);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			final Set<ReferenceIndexedComponents> components =
				referenceSchema.getIndexedComponents(Scope.LIVE);
			assertEquals(2, components.size());
			assertTrue(components.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
			assertTrue(components.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));

			final Map<Scope, Set<ReferenceIndexedComponents>> allComponents =
				referenceSchema.getIndexedComponentsInScopes();
			assertEquals(1, allComponents.size());
			assertNotNull(allComponents.get(Scope.LIVE));
		}

		/**
		 * Verifies that the 12-arg constructor with facetedPartially produces
		 * a reference schema where the expression is retrievable.
		 */
		@Test
		@DisplayName("should create reference with facetedPartially expression")
		void shouldCreateReferenceWithFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"description",
				"deprecationNotice",
				Cardinality.ZERO_OR_MORE,
				REFERENCE_TYPE,
				false,
				GROUP_TYPE,
				false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(
						Scope.LIVE, ReferenceIndexType.FOR_FILTERING
					)
				},
				null,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{
					new ScopedFacetedPartially(Scope.LIVE, expression)
				}
			);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			assertTrue(referenceSchema.isFacetedInScope(Scope.LIVE));
			final Expression actual =
				referenceSchema.getFacetedPartiallyInScope(Scope.LIVE);
			assertNotNull(actual);
			assertEquals(
				expression.toExpressionString(),
				actual.toExpressionString()
			);
		}

		@Test
		@DisplayName("should reject invalid classifier name")
		void shouldThrowExceptionWhenInvalidNameIsProvided() {
			assertThrows(
				InvalidClassifierFormatException.class,
				() -> new CreateReferenceSchemaMutation(
					"primaryKey", "description", "deprecationNotice",
					Cardinality.ZERO_OR_ONE, REFERENCE_TYPE, false,
					null, false,
					false, false
				)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should add reference to entity schema")
		void shouldCreateReferenceInEntity() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"description",
				"deprecationNotice",
				Cardinality.ZERO_OR_MORE,
				REFERENCE_TYPE,
				false,
				GROUP_TYPE,
				false,
				true,
				true
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema =
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);

			assertNotNull(newEntitySchema);
			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract referenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			assertNotNull(referenceSchema);
			assertEquals(REFERENCE_NAME, referenceSchema.getName());
			assertEquals("description", referenceSchema.getDescription());
			assertEquals("deprecationNotice", referenceSchema.getDeprecationNotice());
			assertEquals(Cardinality.ZERO_OR_MORE, referenceSchema.getCardinality());
			assertEquals(REFERENCE_TYPE, referenceSchema.getReferencedEntityType());
			assertEquals(GROUP_TYPE, referenceSchema.getReferencedGroupType());
			assertFalse(referenceSchema.isReferencedEntityTypeManaged());
			assertFalse(referenceSchema.isReferencedGroupTypeManaged());
			assertTrue(referenceSchema.isIndexed());
			assertTrue(referenceSchema.isFaceted());
		}

		@Test
		@DisplayName("should throw when reference already exists with different settings")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingReference() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"differentDescription",
				"differentDeprecationNotice",
				Cardinality.ZERO_OR_MORE,
				REFERENCE_TYPE,
				false,
				GROUP_TYPE,
				false,
				true,
				true
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					final EntitySchemaContract entitySchema =
						Mockito.mock(EntitySchemaContract.class);
					Mockito.when(entitySchema.getReference(REFERENCE_NAME))
						.thenReturn(of(createExistingReferenceSchema()));
					mutation.mutate(
						Mockito.mock(CatalogSchemaContract.class), entitySchema
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"desc", null,
				Cardinality.ZERO_OR_MORE, REFERENCE_TYPE, false,
				null, false,
				true, false
			);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"desc", null,
				Cardinality.ZERO_OR_MORE, REFERENCE_TYPE, false,
				null, false,
				true, false
			);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"testEntity", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);

			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final CreateReferenceSchemaMutation mutation = new CreateReferenceSchemaMutation(
				REFERENCE_NAME,
				"test description", null,
				Cardinality.ZERO_OR_MORE, REFERENCE_TYPE, false,
				GROUP_TYPE, false,
				true, true
			);

			final String result = mutation.toString();

			assertTrue(result.contains("Create entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains(REFERENCE_TYPE));
		}
	}
}
