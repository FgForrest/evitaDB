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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.InvalidClassifierFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_TYPE;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReflectedReferenceSchema;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CreateReflectedReferenceSchemaMutation} verifying creation of reflected reference schemas,
 * combination with removal mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CreateReflectedReferenceSchemaMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(REFERENCE)
class CreateReflectedReferenceSchemaMutationTest {

	private static final String REFLECTED_REFERENCE_NAME = "originalRef";

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should decompose into individual mutations when remove+create reflected reference")
		void shouldDecomposeIntoIndividualMutationsWhenRemoveAndCreate() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"newDescription", "newDeprecationNotice",
					Cardinality.EXACTLY_ONE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					false,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
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

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertTrue(result.current().length > 0);
		}

		@Test
		@DisplayName("should not generate indexed/faceted mutations when inheritance status matches")
		void shouldNotGenerateIndexedOrFacetedMutationsWhenInheritanceMatches() {
			// Create a fully inherited existing reflected reference (all null indexed/faceted/components)
			final ReflectedReferenceSchema existingReflected = ReflectedReferenceSchema._internalBuild(
				REFERENCE_NAME,
				"reflectedDescription",
				"reflectedDeprecationNotice",
				REFERENCE_TYPE,
				REFLECTED_REFERENCE_NAME,
				Cardinality.ZERO_OR_MORE,
				// inherited indexed scopes
				null,
				null,
				// inherited faceted scopes
				null,
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);
			// Create a mutation with DIFFERENT description but same inherited
			// indexed/faceted/components
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"newDescription", "reflectedDeprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					// inherited (null) — matching existing
					null,
					null,
					null,
					null, null, null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(existingReflected));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			// Result must not be null - the combineWith should produce mutations for changed properties
			assertNotNull(
				result,
				"combineWith should return non-null result for remove+create with different properties"
			);
			assertNotNull(result.current());
			// Since description is different, there should be a description mutation
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(ModifyReferenceSchemaDescriptionMutation.class::isInstance),
				"Should generate description mutation since description differs"
			);
			// Since indexed scopes, components, and faceted are all inherited in both
			// versions, there should be NO SetReferenceSchemaIndexedMutation or
			// SetReferenceSchemaFacetedMutation
			assertFalse(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaIndexedMutation.class::isInstance),
				"Should not generate SetReferenceSchemaIndexedMutation when inheritance matches"
			);
			assertFalse(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaFacetedMutation.class::isInstance),
				"Should not generate SetReferenceSchemaFacetedMutation when inheritance matches"
			);
		}

		@Test
		@DisplayName("should generate indexed components mutation when components change")
		void shouldGenerateIndexedComponentsMutationWhenComponentsChange() {
			// Create a mutation with explicit indexed components
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"reflectedDescription", "reflectedDeprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					// same indexed scopes as the existing reflected reference
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING
						)
					},
					// explicit indexed components — the existing reflected reference has inherited (null)
					new ScopedReferenceIndexedComponents[]{
						new ScopedReferenceIndexedComponents(
							Scope.DEFAULT_SCOPE,
							new ReferenceIndexedComponents[]{
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							}
						)
					},
					new Scope[]{Scope.LIVE},
					null, null, null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			// The existing reflected reference has inherited (null) indexed components
			final ReflectedReferenceSchema existingReflected = createExistingReflectedReferenceSchema();
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(existingReflected));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			assertNotNull(result);
			assertNotNull(result.current());
			// Since indexed components differ (explicit vs inherited), a SetReferenceSchemaIndexedMutation
			// should be generated
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaIndexedMutation.class::isInstance),
				"Should generate SetReferenceSchemaIndexedMutation when indexed components change"
			);
		}

		@Test
		@DisplayName("should not combine when removal targets different reference")
		void shouldNotCombineWhenRemovalTargetsDifferentReference() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
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
		@DisplayName("should return null when existing is a regular reference (not reflected)")
		void shouldReturnNullWhenExistingIsRegularReference() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
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

			assertNull(result);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
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

		/**
		 * Verifies that when a reflected reference with bucketed config is
		 * combined with a remove mutation against an existing reflected reference
		 * without bucketing, the result contains a SetReferenceSchemaBucketedMutation.
		 */
		@Test
		@DisplayName("should emit bucketed mutation when remove+create reflected with different bucketed")
		void shouldEmitBucketedMutationWhenRemoveAndCreateReflectedWithDifferentBucketed() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"reflectedDescription", "reflectedDeprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null, null)
					},
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			// existing reflected reference has inherited bucketing (no explicit bucketed)
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReflectedReferenceSchema()));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			assertNotNull(result);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaBucketedMutation.class::isInstance),
				"Should emit SetReferenceSchemaBucketedMutation when bucketed config differs"
			);
		}

		/**
		 * Verifies that when both created and existing reflected references have
		 * inherited bucketed (null), no SetReferenceSchemaBucketedMutation is emitted.
		 */
		@Test
		@DisplayName("should not emit bucketed mutation when bucketed inheritance matches")
		void shouldNotEmitBucketedMutationWhenBucketedInheritanceMatches() {
			final ReflectedReferenceSchema existingReflected = ReflectedReferenceSchema._internalBuild(
				REFERENCE_NAME,
				"reflectedDescription",
				"reflectedDeprecationNotice",
				REFERENCE_TYPE,
				REFLECTED_REFERENCE_NAME,
				Cardinality.ZERO_OR_MORE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);
			// Create mutation with inherited bucketed (both null)
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"newDescription", "reflectedDeprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					null,
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(existingReflected));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
				);

			assertNotNull(result);
			assertFalse(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaBucketedMutation.class::isInstance),
				"Should not emit SetReferenceSchemaBucketedMutation when both versions have inherited bucketing"
			);
		}

		/**
		 * Verifies that when the only difference between the created and existing reflected
		 * reference versions is the per-histogram `assignedWhen` selector, the diff path inside
		 * `createCombinedBucketedMutation(...)` still emits a
		 * {@link SetReferenceSchemaBucketedMutation}. Pins the array-equality check against a
		 * regression that ignores the `assignedWhen` slot on the reflected path.
		 */
		@Test
		@DisplayName("should emit bucketed diff when only assignedWhen differs on reflected")
		void shouldEmitBucketedDiffWhenOnlyAssignedWhenDiffers() {
			final Expression existingAssignedWhen = ExpressionFactory.parse("$active == 1");
			final Expression createdAssignedWhen = ExpressionFactory.parse("$active == 2");
			final ReflectedReferenceSchema inheritedBase = ReflectedReferenceSchema._internalBuild(
				REFERENCE_NAME,
				"reflectedDescription",
				"reflectedDeprecationNotice",
				REFERENCE_TYPE,
				REFLECTED_REFERENCE_NAME,
				Cardinality.ZERO_OR_MORE,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
				},
				null,
				new Scope[]{Scope.LIVE},
				null, null, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				null
			);
			final ReferenceSchemaContract existingReflected = inheritedBase.withBucketed(
				Map.of(
					Scope.LIVE,
					Map.of(
						"priceHistogram",
						HistogramIndexDefinition.of("priceHistogram", null, existingAssignedWhen)
					)
				)
			);
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"reflectedDescription", "reflectedDeprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(
							Scope.LIVE, "priceHistogram", null, createdAssignedWhen
						)
					},
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(existingReflected));
			final RemoveReferenceSchemaMutation removeMutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), entitySchema, removeMutation
			);

			assertNotNull(result);
			assertTrue(
				Arrays.stream(result.current())
					.anyMatch(SetReferenceSchemaBucketedMutation.class::isInstance),
				"assignedWhen-only diff on reflected reference must still emit a "
					+ "SetReferenceSchemaBucketedMutation"
			);
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("should create new reflected reference schema")
		void shouldCreateReflectedReference() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"description",
					"deprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					true,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					new String[]{"excludedAttr"}
				);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, referenceSchema);
			final ReflectedReferenceSchemaContract reflected =
				(ReflectedReferenceSchemaContract) referenceSchema;
			assertEquals(REFERENCE_NAME, reflected.getName());
			assertEquals("description", reflected.getDescription());
			assertEquals("deprecationNotice", reflected.getDeprecationNotice());
			assertEquals(Cardinality.ZERO_OR_MORE, reflected.getCardinality());
			assertEquals(REFERENCE_TYPE, reflected.getReferencedEntityType());
			assertEquals(REFLECTED_REFERENCE_NAME, reflected.getReflectedReferenceName());
			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				reflected.getAttributesInheritanceBehavior()
			);
		}

		@Test
		@DisplayName("should create reflected reference with inherited properties when nulls are passed")
		void shouldCreateReflectedReferenceWithInheritedProperties() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					null,
					null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, referenceSchema);
			final ReflectedReferenceSchemaContract reflected =
				(ReflectedReferenceSchemaContract) referenceSchema;
			assertTrue(reflected.isDescriptionInherited());
			assertTrue(reflected.isDeprecatedInherited());
			assertTrue(reflected.isCardinalityInherited());
		}

		@Test
		@DisplayName("should reject invalid classifier name")
		void shouldThrowExceptionWhenInvalidNameIsProvided() {
			assertThrows(
				InvalidClassifierFormatException.class,
				() -> new CreateReflectedReferenceSchemaMutation(
					"primaryKey",
					"desc", null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				)
			);
		}

		/**
		 * Verifies that the 14-arg constructor with explicit bucketed histogram
		 * data produces a reflected reference schema where bucketed is not
		 * inherited and the histogram definition is retrievable.
		 */
		@Test
		@DisplayName("should create reflected reference with bucketed histogram")
		void shouldCreateReflectedReferenceWithBucketedHistogram() {
			final Expression expression =
				ExpressionFactory.parse("1 > 0");
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", expression, null)
					},
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, expression)
					},
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, referenceSchema);
			assertTrue(referenceSchema.isBucketedInScope(Scope.LIVE));
			assertEquals(
				"priceHistogram",
				referenceSchema.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram").nameOfTheIndex()
			);
			assertNotNull(referenceSchema.getBucketedPartiallyInScope(Scope.LIVE));
		}

		/**
		 * Verifies that a non-null `assignedWhen` selector supplied on
		 * {@link ScopedHistogramIndexDefinition} for a reflected reference survives
		 * `mutate(...)` and lands unchanged on the resulting reflected schema's
		 * {@link HistogramIndexDefinition}.
		 */
		@Test
		@DisplayName("should preserve non-null assignedWhen through reflected mutate round-trip")
		void shouldPreserveNonNullAssignedWhenInMutateRoundTrip() {
			final Expression valueExpr = ExpressionFactory.parse("$reference.attributes['quantity']");
			final Expression assignedWhenExpr = ExpressionFactory.parse("$active == 1");
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(
							Scope.LIVE, "priceHistogram", valueExpr, assignedWhenExpr
						)
					},
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);

			final ReferenceSchemaContract referenceSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), null);

			assertNotNull(referenceSchema);
			final Expression preserved = referenceSchema
				.getHistogramIndexDefinition(Scope.LIVE, "priceHistogram")
				.assignedWhen();
			assertNotNull(preserved, "assignedWhen must survive reflected mutate(...)");
			assertEquals(
				assignedWhenExpr.toExpressionString(),
				preserved.toExpressionString(),
				"assignedWhen expression must round-trip unchanged on reflected reference"
			);
		}

	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should add reflected reference to entity schema")
		void shouldCreateReflectedReferenceInEntity() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"description",
					"deprecationNotice",
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					false,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final CatalogSchemaContract catalogSchema =
				Mockito.mock(CatalogSchemaContract.class);

			final EntitySchemaContract newEntitySchema =
				mutation.mutate(catalogSchema, entitySchema);

			assertNotNull(newEntitySchema);
			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract referenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			assertInstanceOf(ReflectedReferenceSchemaContract.class, referenceSchema);
		}

		@Test
		@DisplayName("should throw when reflected reference already exists with different settings")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingReference() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"differentDescription",
					null,
					Cardinality.EXACTLY_ONE,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					true,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					final EntitySchemaContract entitySchema =
						Mockito.mock(EntitySchemaContract.class);
					Mockito.when(entitySchema.getReference(REFERENCE_NAME))
						.thenReturn(of(createExistingReflectedReferenceSchema()));
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
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);
			final List<ConflictKey> keys = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.NONE)).withEntityType(
				"testEntity", null,
				ctx -> mutation.collectConflictKeys(ctx).toList()
			);

			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final CreateReflectedReferenceSchemaMutation mutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"test description", null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);

			final String result = mutation.toString();

			assertTrue(result.contains("reflected reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains(REFERENCE_TYPE));
			assertTrue(result.contains(REFLECTED_REFERENCE_NAME));
		}

		/**
		 * Verifies that toString output includes bucketed state information
		 * for all three states: inherited, not bucketed, and bucketed in scopes.
		 */
		@Test
		@DisplayName("should include bucketed in toString")
		void shouldIncludeBucketedInToString() {
			// cleared bucketed (null)
			final CreateReflectedReferenceSchemaMutation clearedBucketed =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null, null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null, null, null, null,
					null, null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			assertTrue(clearedBucketed.toString().contains("bucketed=(not bucketed)"));

			// not bucketed (empty array)
			final CreateReflectedReferenceSchemaMutation notBucketed =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null, null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null, null, null, null,
					ScopedHistogramIndexDefinition.EMPTY,
					ScopedBucketedPartially.EMPTY,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			assertTrue(notBucketed.toString().contains("bucketed=(not bucketed)"));

			// bucketed in scopes
			final CreateReflectedReferenceSchemaMutation bucketed =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null, null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					null, null, null, null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, "priceHistogram", null, null)
					},
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			assertTrue(bucketed.toString().contains("bucketed=(bucketed in scopes"));
		}

		@Test
		@DisplayName("should report isFaceted based on facetedInScopes")
		void shouldReportIsFacetedCorrectly() {
			final CreateReflectedReferenceSchemaMutation facetedMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					null, null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					true,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);
			final CreateReflectedReferenceSchemaMutation notFacetedMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					null, null,
					null,
					REFERENCE_TYPE,
					REFLECTED_REFERENCE_NAME,
					false,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					null
				);

			assertTrue(facetedMutation.isFaceted());
			assertFalse(notFacetedMutation.isFaceted());
		}
	}
}
