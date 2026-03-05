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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.evitadb.utils.NamingConvention;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReflectedReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SetReferenceSchemaIndexedMutation} verifying indexed flag mutations,
 * combination with same-type mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("SetReferenceSchemaIndexedMutation")
class SetReferenceSchemaIndexedMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should merge indexed scopes when names match")
		void shouldOverrideIndexedFlagOfPreviousMutationIfNamesMatch() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.NONE
						),
						new ScopedReferenceIndexType(
							Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
						)
					}
				);
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.FOR_FILTERING
						)
					}
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation
				);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(SetReferenceSchemaIndexedMutation.class, result.current()[0]);
			assertArrayEquals(
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.NONE),
					new ScopedReferenceIndexType(
						Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
					)
				},
				((SetReferenceSchemaIndexedMutation) result.current()[0]).getIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should not throw NPE when this.indexedInScopes is null but existing is non-null")
		void shouldNotThrowNpeWhenThisIndexedInScopesIsNullButExistingIsNonNull() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME, (ScopedReferenceIndexType[]) null
				);
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.FOR_FILTERING
						)
					}
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(SetReferenceSchemaIndexedMutation.class, result.current()[0]);
			// null (inherited) takes precedence as the latest mutation
			assertNull(
				((SetReferenceSchemaIndexedMutation) result.current()[0]).getIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation("differentName", true);

			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				)
			);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);
			final LocalEntitySchemaMutation unrelatedMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

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
		@DisplayName("should set indexed scopes on reference")
		void shouldMutateReferenceSchema() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					createExistingReferenceSchema(false)
				);

			assertNotNull(mutatedSchema);
			assertArrayEquals(
				Scope.DEFAULT_SCOPES,
				mutatedSchema.getIndexedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should return same schema when indexed scopes are unchanged")
		void shouldReturnSameSchemaWhenIndexedScopesUnchanged() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema(true);
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					existingSchema.getReferenceIndexTypeInScopes()
						.entrySet()
						.stream()
						.map(
							e -> new ScopedReferenceIndexType(e.getKey(), e.getValue())
						)
						.toArray(ScopedReferenceIndexType[]::new)
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), existingSchema);

			assertSame(existingSchema, mutatedSchema);
		}

		@Test
		@DisplayName("should handle reflected reference schema")
		void shouldHandleReflectedReferenceSchema() {
			final ReflectedReferenceSchema reflectedSchema =
				createExistingReflectedReferenceSchema();
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
		}

		@Test
		@DisplayName("should throw when schema has filterable attribute in non-indexed scope")
		void shouldThrowWhenSchemaHasFilterableAttributeInNonIndexedScope() {
			// Create a reference schema that is NOT indexed but has a filterable
			// attribute (intentionally invalid state created via _internalBuild)
			final ReferenceSchemaContract referenceWithFilterable =
				ReferenceSchema._internalBuild(
					REFERENCE_NAME,
					NamingConvention.generate(REFERENCE_NAME),
					"description", "deprecationNotice",
					Cardinality.ZERO_OR_MORE,
					"category",
					NamingConvention.generate("category"),
					false,
					null,
					Collections.emptyMap(),
					false,
					Collections.emptyMap(),
					Collections.emptyMap(),
					Collections.emptySet(),
					Collections.emptyMap(),
					Map.of(
						"filterableAttr",
						AttributeSchema._internalBuild(
							"filterableAttr",
							null, null,
							new ScopedAttributeUniquenessType[]{
								new ScopedAttributeUniquenessType(
									Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE
								)
							},
							new Scope[]{Scope.LIVE},
							Scope.NO_SCOPE,
							false, false, false,
							Integer.class, null, 0
						)
					),
					Collections.emptyMap()
				);
			// Mutate with different indexed scopes to trigger verification
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
						)
					}
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getName()).thenReturn("product");

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(
					entitySchema, referenceWithFilterable, ConsistencyChecks.APPLY
				)
			);
			assertTrue(
				exception.getMessage().contains("non-indexed"),
				"Exception message should contain 'non-indexed'"
			);
			assertTrue(
				exception.getMessage().contains("filterableAttr"),
				"Exception message should contain the attribute name"
			);
		}

		@Test
		@DisplayName("should throw when mutation changes indexed scope to NONE with filterable attribute")
		void shouldThrowWhenMutationChangesIndexedScopeToNoneWithFilterableAttribute() {
			// Create a reference that IS indexed in LIVE with a filterable attribute
			// (valid initial state — indexed + filterable is allowed)
			final ReferenceSchemaContract indexedReferenceWithFilterable =
				ReferenceSchema._internalBuild(
					REFERENCE_NAME,
					NamingConvention.generate(REFERENCE_NAME),
					"description", null,
					Cardinality.ZERO_OR_MORE,
					"category",
					NamingConvention.generate("category"),
					false,
					null,
					Collections.emptyMap(),
					false,
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
					ReferenceSchema.defaultIndexedComponents(
						Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					),
					Collections.emptySet(),
					Collections.emptyMap(),
					Map.of(
						"filterableAttr",
						AttributeSchema._internalBuild(
							"filterableAttr",
							null, null,
							new ScopedAttributeUniquenessType[]{
								new ScopedAttributeUniquenessType(
									Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE
								)
							},
							new Scope[]{Scope.LIVE},
							Scope.NO_SCOPE,
							false, false, false,
							Integer.class, null, 0
						)
					),
					Collections.emptyMap()
				);
			// Mutation changes LIVE from FOR_FILTERING to NONE — should reject because
			// the NEW state has NONE for LIVE but a filterable attribute exists there
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.NONE
						)
					}
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getName()).thenReturn("product");

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(
					entitySchema, indexedReferenceWithFilterable, ConsistencyChecks.APPLY
				)
			);
			assertTrue(
				exception.getMessage().contains("non-indexed"),
				"Exception message should contain 'non-indexed', got: " + exception.getMessage()
			);
			assertTrue(
				exception.getMessage().contains("filterableAttr"),
				"Exception message should contain 'filterableAttr', got: " + exception.getMessage()
			);
		}

		@Test
		@DisplayName("should not throw when consistency checks are skipped")
		void shouldNotThrowWhenConsistencyChecksSkipped() {
			// Same invalid-state setup: non-indexed with filterable attribute
			final ReferenceSchemaContract referenceWithFilterable =
				ReferenceSchema._internalBuild(
					REFERENCE_NAME,
					NamingConvention.generate(REFERENCE_NAME),
					"description", "deprecationNotice",
					Cardinality.ZERO_OR_MORE,
					"category",
					NamingConvention.generate("category"),
					false,
					null,
					Collections.emptyMap(),
					false,
					Collections.emptyMap(),
					Collections.emptyMap(),
					Collections.emptySet(),
					Collections.emptyMap(),
					Map.of(
						"filterableAttr",
						AttributeSchema._internalBuild(
							"filterableAttr",
							null, null,
							new ScopedAttributeUniquenessType[]{
								new ScopedAttributeUniquenessType(
									Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE
								)
							},
							new Scope[]{Scope.LIVE},
							Scope.NO_SCOPE,
							false, false, false,
							Integer.class, null, 0
						)
					),
					Collections.emptyMap()
				);
			// Same mutation but with SKIP — should NOT throw
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
						)
					}
				);

			final ReferenceSchemaContract result = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				referenceWithFilterable,
				ConsistencyChecks.SKIP
			);

			assertNotNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update indexed in entity schema")
		void shouldMutateEntitySchema() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema(false)));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			assertArrayEquals(
				Scope.DEFAULT_SCOPES,
				newReferenceSchema.getIndexedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class)
				)
			);
		}
	}

	@Nested
	@DisplayName("Indexed components")
	class IndexedComponents {

		@Test
		@DisplayName("should mutate reference schema with indexed components")
		void shouldMutateWithIndexedComponents() {
			final ScopedReferenceIndexedComponents[] components = new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.DEFAULT_SCOPE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				)
			};
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
					},
					components
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			final Set<ReferenceIndexedComponents> result = mutatedSchema.getIndexedComponents(Scope.DEFAULT_SCOPE);
			assertEquals(2, result.size());
			assertTrue(result.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
			assertTrue(result.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
		}

		@Test
		@DisplayName("should default to REFERENCED_ENTITY when no components specified in constructor")
		void shouldDefaultToReferencedEntityWhenNoComponents() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			assertNull(mutation.getIndexedComponentsInScopes());

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					createExistingReferenceSchema(false)
				);

			assertNotNull(mutatedSchema);
			// should default to REFERENCED_ENTITY
			final Set<ReferenceIndexedComponents> result = mutatedSchema.getIndexedComponents(Scope.DEFAULT_SCOPE);
			assertFalse(result.isEmpty());
			assertTrue(result.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
		}

		@Test
		@DisplayName("should handle reflected reference schema with indexed components")
		void shouldHandleReflectedReferenceSchemaWithComponents() {
			final ReflectedReferenceSchema reflectedSchema = createExistingReflectedReferenceSchema();
			final ScopedReferenceIndexedComponents[] components = new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.DEFAULT_SCOPE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY}
				)
			};
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
					},
					components
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
			final ReflectedReferenceSchemaContract reflected =
				(ReflectedReferenceSchemaContract) mutatedSchema;
			assertFalse(reflected.isIndexedComponentsInherited());
		}

		@Test
		@DisplayName("should merge components across different scopes when combining")
		void shouldMergeComponentsAcrossScopesWhenCombining() {
			// Mutation1: LIVE scope with ENTITY and GROUP components
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
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
					}
				);
			// Mutation2: ARCHIVED scope with ENTITY component only
			final SetReferenceSchemaIndexedMutation newMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
					},
					new ScopedReferenceIndexedComponents[]{
						new ScopedReferenceIndexedComponents(
							Scope.ARCHIVED,
							new ReferenceIndexedComponents[]{
								ReferenceIndexedComponents.REFERENCED_ENTITY
							}
						)
					}
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				newMutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNotNull(result);
			assertNotNull(result.current());
			final SetReferenceSchemaIndexedMutation combined =
				(SetReferenceSchemaIndexedMutation) result.current()[0];

			// both scopes' components must be present after combining
			assertNotNull(combined.getIndexedComponentsInScopes());
			assertEquals(2, combined.getIndexedComponentsInScopes().length);

			// verify LIVE components preserved from existing mutation
			final ScopedReferenceIndexedComponents[] components =
				combined.getIndexedComponentsInScopes();
			boolean foundLive = false;
			boolean foundArchived = false;
			for (final ScopedReferenceIndexedComponents component : components) {
				if (component.scope() == Scope.LIVE) {
					foundLive = true;
					assertArrayEquals(
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						},
						component.indexedComponents()
					);
				} else if (component.scope() == Scope.ARCHIVED) {
					foundArchived = true;
					assertArrayEquals(
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY
						},
						component.indexedComponents()
					);
				}
			}
			assertTrue(foundLive, "LIVE scope components should be present");
			assertTrue(foundArchived, "ARCHIVED scope components should be present");
		}

		@Test
		@DisplayName("should override components in same scope when combining")
		void shouldOverrideComponentsInSameScopeWhenCombining() {
			// Mutation1: LIVE with ENTITY only
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					new ScopedReferenceIndexedComponents[]{
						new ScopedReferenceIndexedComponents(
							Scope.LIVE,
							new ReferenceIndexedComponents[]{
								ReferenceIndexedComponents.REFERENCED_ENTITY
							}
						)
					}
				);
			// Mutation2: LIVE with ENTITY and GROUP (overrides)
			final SetReferenceSchemaIndexedMutation newMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
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
					}
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				newMutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNotNull(result);
			assertNotNull(result.current());
			final SetReferenceSchemaIndexedMutation combined =
				(SetReferenceSchemaIndexedMutation) result.current()[0];

			// LIVE scope should have both components from the newer mutation
			assertNotNull(combined.getIndexedComponentsInScopes());
			assertEquals(1, combined.getIndexedComponentsInScopes().length);
			assertEquals(Scope.LIVE, combined.getIndexedComponentsInScopes()[0].scope());
			assertArrayEquals(
				new ReferenceIndexedComponents[]{
					ReferenceIndexedComponents.REFERENCED_ENTITY,
					ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
				},
				combined.getIndexedComponentsInScopes()[0].indexedComponents()
			);
		}

		@Test
		@DisplayName("should strip indexed components for scopes set to NONE during combine")
		void shouldStripComponentsForNoneScopesDuringCombine() {
			// Mutation1: LIVE with FOR_FILTERING and explicit components
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
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
					}
				);
			// Mutation2: sets LIVE to NONE with empty components array
			final SetReferenceSchemaIndexedMutation newMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.NONE)
					},
					ScopedReferenceIndexedComponents.EMPTY
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				newMutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNotNull(result);
			assertNotNull(result.current());
			final SetReferenceSchemaIndexedMutation combined =
				(SetReferenceSchemaIndexedMutation) result.current()[0];

			// LIVE components should be stripped because LIVE is now NONE
			final ScopedReferenceIndexedComponents[] components =
				combined.getIndexedComponentsInScopes();
			assertNotNull(components);
			assertEquals(0, components.length);
			// index types should reflect the merged LIVE=NONE
			final ScopedReferenceIndexType[] indexTypes = combined.getIndexedInScopes();
			assertNotNull(indexTypes);
			assertEquals(1, indexTypes.length);
			assertEquals(Scope.LIVE, indexTypes[0].scope());
			assertEquals(ReferenceIndexType.NONE, indexTypes[0].indexType());
		}

		@Test
		@DisplayName("should strip components for NONE scopes during mutate on non-reflected reference")
		void shouldStripComponentsForNoneScopesDuringMutateNonReflected() {
			// Build a non-faceted but indexed reference (faceted would conflict with NONE)
			final ReferenceSchemaContract nonFacetedIndexed = ReferenceSchema._internalBuild(
				REFERENCE_NAME,
				NamingConvention.generate(REFERENCE_NAME),
				"description", null,
				Cardinality.ZERO_OR_MORE,
				"category",
				NamingConvention.generate("category"),
				false,
				null,
				Collections.emptyMap(),
				false,
				Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				ReferenceSchema.defaultIndexedComponents(
					Map.of(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				),
				Collections.emptySet(),
				Collections.emptyMap(),
				Collections.emptyMap(), Collections.emptyMap()
			);
			// mutation sets LIVE to NONE but carries explicit LIVE components
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.NONE)
					},
					new ScopedReferenceIndexedComponents[]{
						new ScopedReferenceIndexedComponents(
							Scope.LIVE,
							new ReferenceIndexedComponents[]{
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							}
						)
					}
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					nonFacetedIndexed
				);

			assertNotNull(mutatedSchema);
			// LIVE is NONE, so components should be stripped (empty set)
			assertTrue(
				mutatedSchema.getIndexedComponents(Scope.LIVE).isEmpty(),
				"LIVE scope components should be stripped when index type is NONE"
			);
		}

		@Test
		@DisplayName("should strip components for NONE scopes during mutate on reflected reference")
		void shouldStripComponentsForNoneScopesDuringMutateReflected() {
			// Build a non-faceted reflected reference (faceted LIVE would conflict with NONE)
			final ReflectedReferenceSchema reflectedSchema =
				ReflectedReferenceSchema._internalBuild(
					REFERENCE_NAME,
					"reflectedDescription", null,
					"category", "originalRef",
					Cardinality.ZERO_OR_MORE,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.FOR_FILTERING
						)
					},
					null,
					Scope.NO_SCOPE,
					Collections.emptyMap(), Collections.emptyMap(),
					ReflectedReferenceSchemaContract.AttributeInheritanceBehavior
						.INHERIT_ONLY_SPECIFIED,
					null
				);
			// mutation sets LIVE to NONE — components should be stripped even though
			// the mutation's components are the same as the schema's defaults
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.NONE)
					}
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
			// LIVE is NONE, so components should be empty for LIVE scope
			assertTrue(
				mutatedSchema.getIndexedComponents(Scope.LIVE).isEmpty(),
				"LIVE scope components should be stripped when index type is NONE"
			);
		}

		@Test
		@DisplayName("should throw when _internalBuild has components for NONE-indexed scope")
		void shouldThrowWhenInternalBuildHasComponentsForNoneScope() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> ReferenceSchema._internalBuild(
					REFERENCE_NAME,
					NamingConvention.generate(REFERENCE_NAME),
					"description", "deprecationNotice",
					"category",
					NamingConvention.generate("category"),
					false,
					Cardinality.ZERO_OR_MORE,
					null,
					Collections.emptyMap(),
					false,
					// LIVE is NONE
					ScopedReferenceIndexType.EMPTY,
					// but explicit components declared for LIVE
					new ScopedReferenceIndexedComponents[]{
						new ScopedReferenceIndexedComponents(
							Scope.LIVE,
							new ReferenceIndexedComponents[]{
								ReferenceIndexedComponents.REFERENCED_ENTITY
							}
						)
					},
					Scope.NO_SCOPE,
					null,
					Collections.emptyMap(), Collections.emptyMap()
				)
			);
		}

		@Test
		@DisplayName("should succeed when _internalBuild components match indexed scopes")
		void shouldSucceedWhenInternalBuildComponentsMatchIndexedScopes() {
			final ReferenceSchemaContract schema = ReferenceSchema._internalBuild(
				REFERENCE_NAME,
				NamingConvention.generate(REFERENCE_NAME),
				"description", "deprecationNotice",
				"category",
				NamingConvention.generate("category"),
				false,
				Cardinality.ZERO_OR_MORE,
				null,
				Collections.emptyMap(),
				false,
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
				Scope.NO_SCOPE,
				null,
				Collections.emptyMap(), Collections.emptyMap()
			);

			assertNotNull(schema);
			final Set<ReferenceIndexedComponents> liveComponents =
				schema.getIndexedComponents(Scope.LIVE);
			assertEquals(2, liveComponents.size());
			assertTrue(liveComponents.contains(ReferenceIndexedComponents.REFERENCED_ENTITY));
			assertTrue(liveComponents.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
		}

		@Test
		@DisplayName("should switch to inherited components when new mutation has null components")
		void shouldSwitchToInheritedComponentsWhenNewMutationHasNull() {
			// Mutation1: has explicit components for LIVE scope
			final ScopedReferenceIndexedComponents[] existingComponents =
				new ScopedReferenceIndexedComponents[]{
					new ScopedReferenceIndexedComponents(
						Scope.LIVE,
						new ReferenceIndexedComponents[]{
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						}
					)
				};
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					existingComponents
				);
			// Mutation2: has null components (inherited) — should override explicit with inherited
			final SetReferenceSchemaIndexedMutation newMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				newMutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNotNull(result);
			assertNotNull(result.current());
			final SetReferenceSchemaIndexedMutation combined =
				(SetReferenceSchemaIndexedMutation) result.current()[0];

			// null components in the newer mutation means "switch to inherited" — overrides explicit
			assertNull(combined.getIndexedComponentsInScopes());
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);
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
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			final String result = mutation.toString();

			assertTrue(result.contains("Set entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("indexed"));
		}

		@Test
		@DisplayName("should return correct getIndexed for Boolean constructor variants")
		void shouldReturnCorrectGetIndexedForBooleanConstructors() {
			final SetReferenceSchemaIndexedMutation trueMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);
			final SetReferenceSchemaIndexedMutation falseMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, false);
			final SetReferenceSchemaIndexedMutation nullMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, (Boolean) null);

			assertEquals(true, trueMutation.getIndexed());
			assertEquals(false, falseMutation.getIndexed());
			assertNull(nullMutation.getIndexed());
		}
	}
}
