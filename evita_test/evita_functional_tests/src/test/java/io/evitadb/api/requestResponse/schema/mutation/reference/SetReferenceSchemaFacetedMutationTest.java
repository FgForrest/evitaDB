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
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_TYPE;
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
 * Tests for {@link SetReferenceSchemaFacetedMutation} verifying faceted flag mutations,
 * combination with same-type mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("SetReferenceSchemaFacetedMutation")
class SetReferenceSchemaFacetedMutationTest {

	/**
	 * Creates a reflected reference schema with inherited faceting (facetedInherited=true)
	 * for testing inheritance-related mutation paths.
	 */
	private static ReflectedReferenceSchema createInheritedReflectedReferenceSchema() {
		return ReflectedReferenceSchema._internalBuild(
			REFERENCE_NAME,
			"reflectedDescription",
			"reflectedDeprecationNotice",
			REFERENCE_TYPE,
			"originalRef",
			io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(
					Scope.DEFAULT_SCOPE,
					io.evitadb.api.requestResponse.schema.ReferenceIndexType
						.FOR_FILTERING
				)
			},
			null,
			null, // null facetedInScopes -> facetedInherited = true
			Map.of(),
			Map.of(),
			io.evitadb.api.requestResponse.schema
				.ReflectedReferenceSchemaContract
				.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null
		);
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous faceted mutation when names match")
		void shouldOverrideFacetedFlagOfPreviousMutationIfNamesMatch() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final SetReferenceSchemaFacetedMutation existingMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
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
			assertInstanceOf(SetReferenceSchemaFacetedMutation.class, result.current()[0]);
			assertArrayEquals(
				Scope.NO_SCOPE,
				((SetReferenceSchemaFacetedMutation) result.current()[0]).getFacetedInScopes()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final SetReferenceSchemaFacetedMutation existingMutation =
				new SetReferenceSchemaFacetedMutation("differentName", Scope.DEFAULT_SCOPES);

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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final LocalEntitySchemaMutation unrelatedMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					unrelatedMutation
				);

			assertNull(result);
		}

		/**
		 * Verifies that combining with CreateReferenceSchemaMutation when only
		 * facetedPartially is set preserves the Create mutation's facetedInScopes.
		 */
		@Test
		@DisplayName("should absorb only facetedPartially into Create mutation preserving facetedInScopes")
		void shouldCombineWithCreateMutationOnlyFacetedPartiallySet() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// mutation only sets facetedPartially, facetedInScopes is null
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					null,
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category", false,
					null, false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					null,
					new Scope[]{Scope.LIVE},
					null // create mutation has no facetedPartially initially
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					createMutation
				);

			assertNotNull(result);
			final CreateReferenceSchemaMutation absorbed =
				assertInstanceOf(CreateReferenceSchemaMutation.class, result.origin());
			// facetedInScopes should be preserved from the Create mutation
			assertArrayEquals(
				new Scope[]{Scope.LIVE},
				absorbed.getFacetedInScopes()
			);
			// facetedPartially should be absorbed from the Set mutation
			final ScopedFacetedPartially[] partiallyInScopes =
				absorbed.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertEquals(1, partiallyInScopes.length);
			assertEquals(Scope.LIVE, partiallyInScopes[0].scope());
		}

		/**
		 * Verifies that combining with CreateReferenceSchemaMutation when only
		 * facetedInScopes is set preserves the Create mutation's facetedPartially.
		 */
		@Test
		@DisplayName("should absorb only facetedInScopes into Create mutation preserving facetedPartially")
		void shouldCombineWithCreateMutationOnlyFacetedInScopesSet() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// mutation only sets facetedInScopes, facetedPartially is null
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE}
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category", false,
					null, false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					null,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					createMutation
				);

			assertNotNull(result);
			final CreateReferenceSchemaMutation absorbed =
				assertInstanceOf(CreateReferenceSchemaMutation.class, result.origin());
			// facetedInScopes should be taken from the Set mutation
			assertArrayEquals(
				new Scope[]{Scope.LIVE},
				absorbed.getFacetedInScopes()
			);
			// facetedPartially should be preserved from the Create mutation
			final ScopedFacetedPartially[] partiallyInScopes =
				absorbed.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertEquals(1, partiallyInScopes.length);
			assertNotNull(partiallyInScopes[0].expression());
		}

		/**
		 * Verifies that combining with CreateReflectedReferenceSchemaMutation when only
		 * facetedInScopes is set takes scopes from the Set mutation and preserves
		 * the Create mutation's facetedPartially.
		 */
		@Test
		@DisplayName("should absorb only facetedInScopes into CreateReflected mutation")
		void shouldCombineWithCreateReflectedMutationOnlyFacetedInScopesSet() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// mutation only sets facetedInScopes, facetedPartially is null
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE}
				);
			final CreateReflectedReferenceSchemaMutation createMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category",
					"originalRef",
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					null,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					},
					io.evitadb.api.requestResponse.schema
						.ReflectedReferenceSchemaContract
						.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					createMutation
				);

			assertNotNull(result);
			final CreateReflectedReferenceSchemaMutation absorbed =
				assertInstanceOf(
					CreateReflectedReferenceSchemaMutation.class, result.origin()
				);
			// facetedInScopes should be taken from the Set mutation
			assertArrayEquals(
				new Scope[]{Scope.LIVE},
				absorbed.getFacetedInScopes()
			);
			// facetedPartially should be preserved from the Create mutation
			final ScopedFacetedPartially[] partiallyInScopes =
				absorbed.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertEquals(1, partiallyInScopes.length);
			assertNotNull(partiallyInScopes[0].expression());
		}

		/**
		 * Verifies that combining with CreateReflectedReferenceSchemaMutation when both
		 * facetedInScopes and facetedPartially are set takes both from the Set mutation.
		 */
		@Test
		@DisplayName("should absorb both fields into CreateReflected mutation")
		void shouldCombineWithCreateReflectedMutationBothFieldsSet() {
			final Expression expression = ExpressionFactory.parse("5 > 4");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final CreateReflectedReferenceSchemaMutation createMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category",
					"originalRef",
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					null,
					Scope.NO_SCOPE, // originally not faceted
					io.evitadb.api.requestResponse.schema
						.ReflectedReferenceSchemaContract
						.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					createMutation
				);

			assertNotNull(result);
			final CreateReflectedReferenceSchemaMutation absorbed =
				assertInstanceOf(
					CreateReflectedReferenceSchemaMutation.class, result.origin()
				);
			// both fields should come from the Set mutation
			assertArrayEquals(
				new Scope[]{Scope.LIVE},
				absorbed.getFacetedInScopes()
			);
			final ScopedFacetedPartially[] partiallyInScopes =
				absorbed.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertEquals(1, partiallyInScopes.length);
			assertEquals(Scope.LIVE, partiallyInScopes[0].scope());
			assertNotNull(partiallyInScopes[0].expression());
		}

		/**
		 * Verifies that combining with CreateReferenceSchemaMutation returns null
		 * when both facetedInScopes and facetedPartiallyInScopes are null (no-op).
		 */
		@Test
		@DisplayName("should not combine with Create mutation when both fields are null")
		void shouldNotCombineWithCreateMutationWhenBothFieldsAreNull() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, null, null
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category", false,
					null, false,
					true, true
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					createMutation
				);

			assertNull(
				result,
				"combineWith should return null when both fields are null (no-op)"
			);
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("should set faceted scopes on reference")
		void shouldMutateReferenceSchema() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			assertArrayEquals(
				Scope.NO_SCOPE,
				mutatedSchema.getFacetedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should return same schema when faceted scopes are unchanged")
		void shouldReturnSameSchemaWhenFacetedScopesUnchanged() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();
			// existing schema is faceted in Scope.LIVE
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, new Scope[]{Scope.LIVE}
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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
		}

		/**
		 * Verifies that when both facetedInScopes and facetedPartially are null
		 * for a non-reflected reference, the schema is returned unchanged.
		 */
		@Test
		@DisplayName("should return same schema when both fields are null on regular reference")
		void shouldReturnSameSchemaWhenBothFieldsNullOnRegularReference() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, null, null
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), existingSchema
				);

			assertSame(existingSchema, result);
		}

		/**
		 * Verifies that when only facetedPartially is set (facetedInScopes null)
		 * on a non-reflected reference, existing faceted scopes are preserved
		 * while the partially expression is applied.
		 */
		@Test
		@DisplayName("should preserve faceted scopes and apply partially when only facetedPartially set")
		void shouldPreserveScopesAndApplyPartiallyOnRegularReference() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();
			// existingSchema is faceted in Scope.LIVE
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					null, // preserve existing scopes
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), existingSchema
				);

			// faceted scopes should be preserved from existing schema
			assertEquals(
				existingSchema.getFacetedInScopes(),
				result.getFacetedInScopes()
			);
			// facetedPartially should be applied
			assertNotNull(result.getFacetedPartiallyInScope(Scope.LIVE));
			assertEquals(
				expression.toExpressionString(),
				result.getFacetedPartiallyInScope(Scope.LIVE).toExpressionString()
			);
		}
	}

	@Nested
	@DisplayName("Reflected reference inheritance")
	class MutateReflectedReferenceInheritance {

		/**
		 * Verifies that when both fields are null on an already-inherited reflected
		 * reference, the mutation returns the same instance (early return).
		 */
		@Test
		@DisplayName("should return same instance when both null on inherited reflected ref")
		void shouldReturnSameInstanceWhenBothNullOnInheritedReflectedRef() {
			final ReflectedReferenceSchema inheritedRef =
				createInheritedReflectedReferenceSchema();
			assertTrue(inheritedRef.isFacetedInherited());

			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, null, null
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), inheritedRef
				);

			assertSame(inheritedRef, result);
		}

		/**
		 * Verifies that when both fields are null on a non-inherited reflected
		 * reference, the mutation transitions it to inherited faceting.
		 */
		@Test
		@DisplayName("should transition to inherited when both null on non-inherited reflected ref")
		void shouldTransitionToInheritedWhenBothNullOnNonInheritedReflectedRef() {
			final ReflectedReferenceSchema nonInheritedRef =
				createExistingReflectedReferenceSchema();
			assertFalse(nonInheritedRef.isFacetedInherited());

			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, null, null
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), nonInheritedRef
				);

			assertInstanceOf(ReflectedReferenceSchemaContract.class, result);
			assertTrue(
				((ReflectedReferenceSchemaContract) result).isFacetedInherited()
			);
		}

		/**
		 * Verifies that when facetedInScopes is null but facetedPartially is set
		 * on a non-inherited reflected reference, it transitions to inherited
		 * faceting AND applies the partially expression.
		 */
		@Test
		@DisplayName("should inherit faceting and apply partially on non-inherited reflected ref")
		void shouldInheritAndApplyPartiallyOnNonInheritedReflectedRef() {
			final ReflectedReferenceSchema nonInheritedRef =
				createExistingReflectedReferenceSchema();
			assertFalse(nonInheritedRef.isFacetedInherited());

			final Expression expression = ExpressionFactory.parse("1 > 0");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					null, // inherit faceting
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), nonInheritedRef
				);

			final ReflectedReferenceSchemaContract reflected =
				assertInstanceOf(
					ReflectedReferenceSchemaContract.class, result
				);
			assertTrue(reflected.isFacetedInherited());
			assertNotNull(result.getFacetedPartiallyInScope(Scope.LIVE));
			assertEquals(
				expression.toExpressionString(),
				result.getFacetedPartiallyInScope(Scope.LIVE).toExpressionString()
			);
		}

		/**
		 * Verifies that when facetedInScopes is null but facetedPartially is set
		 * on an already-inherited reflected reference, it stays inherited and
		 * applies the partially expression.
		 */
		@Test
		@DisplayName("should stay inherited and apply partially on inherited reflected ref")
		void shouldStayInheritedAndApplyPartiallyOnInheritedReflectedRef() {
			final ReflectedReferenceSchema inheritedRef =
				createInheritedReflectedReferenceSchema();
			assertTrue(inheritedRef.isFacetedInherited());

			final Expression expression = ExpressionFactory.parse("2 > 1");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					null, // stay inherited
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), inheritedRef
				);

			final ReflectedReferenceSchemaContract reflected =
				assertInstanceOf(
					ReflectedReferenceSchemaContract.class, result
				);
			assertTrue(reflected.isFacetedInherited());
			assertNotNull(result.getFacetedPartiallyInScope(Scope.LIVE));
			assertEquals(
				expression.toExpressionString(),
				result.getFacetedPartiallyInScope(Scope.LIVE).toExpressionString()
			);
		}

		/**
		 * Verifies that when the reflected reference already has matching
		 * non-inherited faceted scopes, the faceted change is a no-op but
		 * a different facetedPartially expression is still applied.
		 */
		@Test
		@DisplayName("should skip faceted change and still apply partially when scopes match")
		void shouldSkipFacetedChangeAndApplyPartiallyWhenScopesMatch() {
			final ReflectedReferenceSchema reflectedSchema =
				createExistingReflectedReferenceSchema();
			// existing schema is faceted in Scope.LIVE (non-inherited)
			assertFalse(reflectedSchema.isFacetedInherited());

			final Expression expression = ExpressionFactory.parse("3 > 2");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE}, // same as existing
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			// partially should be applied even though scopes didn't change
			assertNotNull(result.getFacetedPartiallyInScope(Scope.LIVE));
			assertEquals(
				expression.toExpressionString(),
				result.getFacetedPartiallyInScope(Scope.LIVE).toExpressionString()
			);
		}

		/**
		 * Verifies that when both faceted scopes and partially expression
		 * already match on a reflected reference, the same instance is returned.
		 */
		@Test
		@DisplayName("should return same instance when both faceted and partially match on reflected ref")
		void shouldReturnSameInstanceWhenBothMatchOnReflectedRef() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// set up a reflected ref with explicit scopes and partially
			final ReflectedReferenceSchema baseRef =
				createExistingReflectedReferenceSchema();
			final SetReferenceSchemaFacetedMutation setupMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final ReferenceSchemaContract schemaWithPartially =
				setupMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), baseRef
				);

			// now apply the same mutation again
			final SetReferenceSchemaFacetedMutation sameMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final ReferenceSchemaContract result =
				sameMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), schemaWithPartially
				);

			assertSame(schemaWithPartially, result);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update faceted in entity schema")
		void shouldMutateEntitySchema() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			// assert on FACETED scopes, not indexed scopes (BUG FIX from original test)
			assertArrayEquals(
				Scope.NO_SCOPE,
				newReferenceSchema.getFacetedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

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
	@DisplayName("FacetedPartially expression handling")
	class FacetedPartially {

		/**
		 * Verifies that a facetedPartially expression is correctly applied to a non-reflected
		 * reference schema and can be retrieved via the scope accessor.
		 */
		@Test
		@DisplayName("should set facetedPartially expression on reference")
		void shouldMutateReferenceSchemaWithFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			final Expression actualExpression =
				mutatedSchema.getFacetedPartiallyInScope(Scope.LIVE);
			assertNotNull(actualExpression);
			assertEquals(
				expression.toExpressionString(),
				actualExpression.toExpressionString()
			);
		}

		/**
		 * Verifies that mutating with an unchanged facetedPartially expression
		 * returns the same schema instance (identity check).
		 */
		@Test
		@DisplayName("should return same schema when facetedPartially is unchanged")
		void shouldReturnSameSchemaWhenFacetedPartiallyUnchanged() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// build a reference schema that already has facetedPartially set
			final ReferenceSchemaContract baseSchema = createExistingReferenceSchema();
			// first apply the expression
			final SetReferenceSchemaFacetedMutation setupMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final ReferenceSchemaContract schemaWithPartially =
				setupMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), baseSchema
				);

			// now apply the same expression again
			final SetReferenceSchemaFacetedMutation sameMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final ReferenceSchemaContract result =
				sameMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), schemaWithPartially
				);

			assertSame(schemaWithPartially, result);
		}

		/**
		 * Verifies that a facetedPartially expression is correctly applied
		 * to a reflected reference schema.
		 */
		@Test
		@DisplayName("should set facetedPartially expression on reflected reference")
		void shouldMutateReflectedReferenceSchemaWithFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("3 > 2");
			final ReflectedReferenceSchema reflectedSchema =
				createExistingReflectedReferenceSchema();
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
			final Expression actualExpression =
				mutatedSchema.getFacetedPartiallyInScope(Scope.LIVE);
			assertNotNull(actualExpression);
			assertEquals(
				expression.toExpressionString(),
				actualExpression.toExpressionString()
			);
		}

		/**
		 * Verifies that when facetedInScopes is narrowed (e.g. only LIVE), expressions
		 * for removed scopes (e.g. ARCHIVED) are stripped from the facetedPartially map.
		 */
		@Test
		@DisplayName("should strip facetedPartially for scopes no longer faceted")
		void shouldFilterFacetedPartiallyWhenFacetedScopesChange() {
			final Expression liveExpr = ExpressionFactory.parse("1 > 0");
			final Expression archivedExpr = ExpressionFactory.parse("2 > 1");
			// first set up a schema with expressions in both scopes
			final SetReferenceSchemaFacetedMutation setupMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE, Scope.ARCHIVED},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, liveExpr),
						new ScopedFacetedPartially(Scope.ARCHIVED, archivedExpr)
					}
				);
			// need a schema indexed in both scopes for faceting in both
			final ReferenceSchemaContract indexedBothScopes =
				ReferenceSchema._internalBuild(
					REFERENCE_NAME,
					"category",
					false,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					null,
					false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						),
						new ScopedReferenceIndexType(
							Scope.ARCHIVED,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					new Scope[]{Scope.LIVE, Scope.ARCHIVED}
				);
			final ReferenceSchemaContract schemaWithBoth =
				setupMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), indexedBothScopes
				);

			// now narrow faceted to LIVE only (without providing facetedPartially)
			final SetReferenceSchemaFacetedMutation narrowMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE}
				);

			final ReferenceSchemaContract narrowed =
				narrowMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), schemaWithBoth
				);

			// LIVE expression should remain, ARCHIVED should be stripped
			assertNotNull(narrowed.getFacetedPartiallyInScope(Scope.LIVE));
			assertNull(narrowed.getFacetedPartiallyInScope(Scope.ARCHIVED));
		}

		/**
		 * Verifies that passing a null expression via ScopedFacetedPartially
		 * clears the partial faceting for that scope.
		 */
		@Test
		@DisplayName("should clear facetedPartially when expression is null")
		void shouldClearFacetedPartiallyWhenExpressionIsNull() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// set up schema with an expression
			final SetReferenceSchemaFacetedMutation setupMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final ReferenceSchemaContract baseSchema = createExistingReferenceSchema();
			final ReferenceSchemaContract schemaWithPartially =
				setupMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), baseSchema
				);

			// now clear the expression
			final SetReferenceSchemaFacetedMutation clearMutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, null)
					}
				);
			final ReferenceSchemaContract cleared =
				clearMutation.mutate(
					Mockito.mock(EntitySchemaContract.class), schemaWithPartially
				);

			assertNull(cleared.getFacetedPartiallyInScope(Scope.LIVE));
			assertTrue(cleared.getFacetedPartiallyInScopes().isEmpty());
		}

		/**
		 * Verifies that combining a SetReferenceSchemaFacetedMutation (with facetedPartially)
		 * into a CreateReferenceSchemaMutation absorbs the expression.
		 */
		@Test
		@DisplayName("should absorb facetedPartially into Create mutation")
		void shouldCombineWithCreateReferenceMutationIncludingFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category", false,
					null, false,
					true, false
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					createMutation
				);

			assertNotNull(result);
			// combineWith absorbs into the origin (the existing Create mutation)
			assertNotNull(result.origin());
			assertInstanceOf(
				CreateReferenceSchemaMutation.class, result.origin()
			);
			final CreateReferenceSchemaMutation absorbed =
				(CreateReferenceSchemaMutation) result.origin();
			final ScopedFacetedPartially[] partiallyInScopes =
				absorbed.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertTrue(partiallyInScopes.length > 0);
			assertEquals(Scope.LIVE, partiallyInScopes[0].scope());
			assertNotNull(partiallyInScopes[0].expression());
		}

		/**
		 * Verifies that combining a SetReferenceSchemaFacetedMutation (with only
		 * facetedPartially, null facetedInScopes) into a CreateReflectedReferenceSchemaMutation
		 * absorbs the expression while preserving the Create mutation's facetedInScopes.
		 */
		@Test
		@DisplayName("should absorb facetedPartially into CreateReflected mutation preserving facetedInScopes")
		void shouldCombineWithCreateReflectedMutationIncludingFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// mutation only sets facetedPartially, facetedInScopes is null ("don't change")
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					null, // null facetedInScopes -- "don't change" semantic
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);
			final CreateReflectedReferenceSchemaMutation createMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category",
					"originalRef",
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					null,
					new Scope[]{Scope.LIVE},
					io.evitadb.api.requestResponse.schema
						.ReflectedReferenceSchemaContract
						.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					createMutation
				);

			assertNotNull(result);
			assertNotNull(result.origin());
			assertInstanceOf(
				CreateReflectedReferenceSchemaMutation.class, result.origin()
			);
			final CreateReflectedReferenceSchemaMutation absorbed =
				(CreateReflectedReferenceSchemaMutation) result.origin();

			// facetedPartially should be absorbed from the Set mutation
			final ScopedFacetedPartially[] partiallyInScopes =
				absorbed.getFacetedPartiallyInScopes();
			assertNotNull(partiallyInScopes);
			assertTrue(partiallyInScopes.length > 0);
			assertEquals(Scope.LIVE, partiallyInScopes[0].scope());
			assertNotNull(partiallyInScopes[0].expression());

			// facetedInScopes must be preserved from the Create mutation (not reset to null)
			assertNotNull(
				absorbed.getFacetedInScopes(),
				"facetedInScopes should be preserved from CreateReflected mutation, not reset to null"
			);
			assertArrayEquals(
				new Scope[]{Scope.LIVE},
				absorbed.getFacetedInScopes()
			);
		}

		/**
		 * Verifies that when both facetedInScopes and facetedPartiallyInScopes are null
		 * (no-op mutation), combineWith returns null for the reflected path, meaning
		 * the mutations are not combined (same guard behavior as the non-reflected path).
		 */
		@Test
		@DisplayName("should not combine with CreateReflected when both fields are null")
		void shouldNotCombineWithCreateReflectedMutationWhenBothFieldsAreNull() {
			// Both facetedInScopes and facetedPartiallyInScopes are null
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					null, // null facetedInScopes
					null  // null facetedPartiallyInScopes
				);
			final CreateReflectedReferenceSchemaMutation createMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					io.evitadb.api.requestResponse.schema.Cardinality.ZERO_OR_MORE,
					"category",
					"originalRef",
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE,
							io.evitadb.api.requestResponse.schema.ReferenceIndexType
								.FOR_FILTERING
						)
					},
					null,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(
							Scope.LIVE, ExpressionFactory.parse("1 > 0")
						)
					},
					io.evitadb.api.requestResponse.schema
						.ReflectedReferenceSchemaContract
						.AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					null
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					createMutation
				);

			// when both fields are null, there is nothing to combine -- should return null
			assertNull(
				result,
				"combineWith should return null when both facetedInScopes and " +
					"facetedPartiallyInScopes are null (no-op)"
			);
		}

		/**
		 * Verifies that toString includes facetedPartially information when set.
		 */
		@Test
		@DisplayName("should include facetedPartially in toString")
		void shouldProduceReadableToStringWithFacetedPartially() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					new ScopedFacetedPartially[]{
						new ScopedFacetedPartially(Scope.LIVE, expression)
					}
				);

			final String result = mutation.toString();

			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("facetedPartially"));
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			final String result = mutation.toString();

			assertTrue(result.contains("Set entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("faceted"));
		}

		@Test
		@DisplayName("should return correct getFaceted for Boolean constructor variants")
		void shouldReturnCorrectGetFacetedForBooleanConstructors() {
			final SetReferenceSchemaFacetedMutation trueMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, true);
			final SetReferenceSchemaFacetedMutation falseMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, false);
			final SetReferenceSchemaFacetedMutation nullMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, (Boolean) null);

			assertEquals(true, trueMutation.getFaceted());
			assertEquals(false, falseMutation.getFaceted());
			assertNull(nullMutation.getFaceted());
		}

		/**
		 * Verifies that toString outputs "(inherited)" when faceted is null.
		 */
		@Test
		@DisplayName("should produce toString with inherited when faceted is null")
		void shouldProduceToStringWithInherited() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, (Boolean) null
				);

			final String result = mutation.toString();

			assertTrue(result.contains("(inherited)"));
		}

		/**
		 * Verifies that toString outputs "(not faceted)" when faceted scopes are empty.
		 */
		@Test
		@DisplayName("should produce toString with not faceted when scopes are empty")
		void shouldProduceToStringWithNotFaceted() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, false
				);

			final String result = mutation.toString();

			assertTrue(result.contains("(not faceted)"));
		}

		/**
		 * Verifies that toString outputs "facetedPartially=(none)" when the array is empty.
		 */
		@Test
		@DisplayName("should produce toString with none when facetedPartially is empty array")
		void shouldProduceToStringWithEmptyFacetedPartially() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME,
					new Scope[]{Scope.LIVE},
					ScopedFacetedPartially.EMPTY
				);

			final String result = mutation.toString();

			assertTrue(result.contains("facetedPartially=(none)"));
		}
	}
}
