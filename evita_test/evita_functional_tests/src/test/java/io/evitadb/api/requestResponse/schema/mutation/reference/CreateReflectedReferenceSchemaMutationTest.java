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
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.InvalidClassifierFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_TYPE;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReflectedReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CreateReflectedReferenceSchemaMutation} verifying creation of reflected reference schemas,
 * combination with removal mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CreateReflectedReferenceSchemaMutation")
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
