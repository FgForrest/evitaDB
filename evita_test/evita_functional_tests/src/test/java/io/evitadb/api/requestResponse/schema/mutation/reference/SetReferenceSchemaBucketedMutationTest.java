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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_TYPE;
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
 * Tests for {@link SetReferenceSchemaBucketedMutation} verifying bucketed histogram mutations,
 * combination with same-type mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SetReferenceSchemaBucketedMutation")
class SetReferenceSchemaBucketedMutationTest {

	private static final String INDEX_NAME = "priceHistogram";

	/**
	 * Creates a non-reflected reference schema with bucketed histogram configuration for testing.
	 *
	 * @return a reference schema that is indexed, faceted, and bucketed in LIVE scope
	 */
	@Nonnull
	private static ReferenceSchemaContract createBucketedReferenceSchema() {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			NamingConvention.generate(REFERENCE_NAME),
			"description",
			"deprecationNotice",
			REFERENCE_TYPE,
			NamingConvention.generate(REFERENCE_TYPE),
			false,
			Cardinality.ZERO_OR_MORE,
			null,
			Collections.emptyMap(),
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
			},
			null,
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	/**
	 * Creates a non-reflected reference schema without bucketed configuration for testing.
	 *
	 * @return a reference schema that is indexed and faceted, but not bucketed
	 */
	@Nonnull
	private static ReferenceSchemaContract createNonBucketedReferenceSchema() {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			NamingConvention.generate(REFERENCE_NAME),
			"description",
			"deprecationNotice",
			REFERENCE_TYPE,
			NamingConvention.generate(REFERENCE_TYPE),
			false,
			Cardinality.ZERO_OR_MORE,
			null,
			Collections.emptyMap(),
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			null,
			null,
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	/**
	 * Creates a non-reflected reference schema with bucketed and bucketedPartially configuration for testing.
	 *
	 * @return a reference schema that is bucketed in LIVE scope with a bucketedPartially expression
	 */
	@Nonnull
	private static ReferenceSchemaContract createBucketedWithPartiallyReferenceSchema() {
		return ReferenceSchema._internalBuild(
			REFERENCE_NAME,
			NamingConvention.generate(REFERENCE_NAME),
			"description",
			"deprecationNotice",
			REFERENCE_TYPE,
			NamingConvention.generate(REFERENCE_TYPE),
			false,
			Cardinality.ZERO_OR_MORE,
			null,
			Collections.emptyMap(),
			false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
			},
			null,
			new Scope[]{Scope.LIVE},
			null,
			new ScopedHistogramIndexDefinition[]{
				new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
			},
			new ScopedBucketedPartially[]{
				new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse("1 > 0"))
			},
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	/**
	 * Creates a reflected reference schema with inherited bucketing for testing.
	 *
	 * @return a reflected reference schema where bucketing is inherited from the reflected reference
	 */
	@Nonnull
	private static ReflectedReferenceSchema createInheritedReflectedReferenceSchema() {
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
			null, null, null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
			null
		);
	}

	/**
	 * Creates a reflected reference schema with explicit bucketed configuration for testing.
	 *
	 * @return a reflected reference schema with bucketed overriding inheritance
	 */
	@Nonnull
	private static ReflectedReferenceSchema createExplicitBucketedReflectedSchema() {
		final ReflectedReferenceSchema base = createInheritedReflectedReferenceSchema();
		return (ReflectedReferenceSchema) base.withBucketed(
			Map.of(Scope.LIVE, new HistogramIndexDefinition(INDEX_NAME, null))
		);
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous bucketed mutation when names match")
		void shouldOverrideBucketedOfPreviousMutationIfNamesMatch() {
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					ScopedHistogramIndexDefinition.EMPTY
				);
			final SetReferenceSchemaBucketedMutation existingMutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
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
			assertInstanceOf(SetReferenceSchemaBucketedMutation.class, result.current()[0]);
			assertArrayEquals(
				ScopedHistogramIndexDefinition.EMPTY,
				((SetReferenceSchemaBucketedMutation) result.current()[0]).getBucketedInScopes()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					ScopedHistogramIndexDefinition.EMPTY
				);
			final SetReferenceSchemaBucketedMutation existingMutation =
				new SetReferenceSchemaBucketedMutation(
					"differentName",
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);

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
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					ScopedHistogramIndexDefinition.EMPTY
				);
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

		@Test
		@DisplayName("should absorb bucketed into Create mutation preserving other fields")
		void shouldAbsorbBucketedIntoCreateMutationPreservingOtherFields() {
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE, false,
					null, false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE}
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
			// bucketed should come from the Set mutation
			final ScopedHistogramIndexDefinition[] bucketed = absorbed.getBucketedInScopes();
			assertEquals(1, bucketed.length);
			assertEquals(Scope.LIVE, bucketed[0].scope());
			assertEquals(INDEX_NAME, bucketed[0].nameOfTheIndex());
			// faceted should be preserved from the Create mutation
			assertArrayEquals(new Scope[]{Scope.LIVE}, absorbed.getFacetedInScopes());
		}

		@Test
		@DisplayName("should absorb only bucketedPartially into Create mutation preserving bucketedInScopes")
		void shouldAbsorbOnlyBucketedPartiallyIntoCreateMutationPreservingBucketedInScopes() {
			final Expression expression = ExpressionFactory.parse("1 > 0");
			// mutation only sets bucketedPartially, bucketedInScopes is null
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					null,
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, expression)
					}
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE, false,
					null, false,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					},
					null
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
			// bucketed should be preserved from the Create mutation
			assertEquals(1, absorbed.getBucketedInScopes().length);
			assertEquals(INDEX_NAME, absorbed.getBucketedInScopes()[0].nameOfTheIndex());
			// bucketedPartially should come from the Set mutation
			assertEquals(1, absorbed.getBucketedPartiallyInScopes().length);
			assertEquals(Scope.LIVE, absorbed.getBucketedPartiallyInScopes()[0].scope());
		}

		@Test
		@DisplayName("should absorb bucketed into CreateReflected mutation preserving other fields")
		void shouldAbsorbBucketedIntoCreateReflectedMutationPreservingOtherFields() {
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);
			final CreateReflectedReferenceSchemaMutation createMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					"originalRef",
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					null,
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
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
				assertInstanceOf(CreateReflectedReferenceSchemaMutation.class, result.origin());
			// bucketed should come from the Set mutation
			final ScopedHistogramIndexDefinition[] bucketed = absorbed.getBucketedInScopes();
			assertNotNull(bucketed);
			assertEquals(1, bucketed.length);
			assertEquals(INDEX_NAME, bucketed[0].nameOfTheIndex());
			// attribute inheritance should be preserved
			assertEquals(AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED, absorbed.getAttributeInheritanceBehavior());
		}

		@Test
		@DisplayName("should absorb only bucketedPartially into CreateReflected mutation preserving bucketed")
		void shouldAbsorbOnlyBucketedPartiallyIntoCreateReflectedMutation() {
			final Expression expression = ExpressionFactory.parse("2 > 1");
			// mutation only sets bucketedPartially, bucketedInScopes is null
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					null,
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, expression)
					}
				);
			final CreateReflectedReferenceSchemaMutation createMutation =
				new CreateReflectedReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE,
					"originalRef",
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
					},
					null,
					new Scope[]{Scope.LIVE},
					null,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					},
					null,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
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
				assertInstanceOf(CreateReflectedReferenceSchemaMutation.class, result.origin());
			// bucketed should be preserved from the CreateReflected mutation
			final ScopedHistogramIndexDefinition[] bucketed = absorbed.getBucketedInScopes();
			assertNotNull(bucketed);
			assertEquals(1, bucketed.length);
			assertEquals(INDEX_NAME, bucketed[0].nameOfTheIndex());
			// bucketedPartially should come from the Set mutation
			final ScopedBucketedPartially[] partially = absorbed.getBucketedPartiallyInScopes();
			assertNotNull(partially);
			assertEquals(1, partially.length);
			assertEquals(Scope.LIVE, partially[0].scope());
		}

		@Test
		@DisplayName("should not combine with Create mutation when both fields are null")
		void shouldNotCombineWithCreateMutationWhenBothFieldsAreNull() {
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME, null, null
				);
			final CreateReferenceSchemaMutation createMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"desc", null,
					Cardinality.ZERO_OR_MORE,
					REFERENCE_TYPE, false,
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
	@DisplayName("Mutate non-reflected reference schema")
	class MutateNonReflectedReference {

		@Test
		@DisplayName("should mutate non-reflected reference with bucketed scopes")
		void shouldMutateNonReflectedReferenceWithBucketedScopes() {
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					createNonBucketedReferenceSchema()
				);

			assertNotNull(result);
			assertTrue(result.isBucketedInScope(Scope.LIVE));
			final HistogramIndexDefinition definition =
				result.getHistogramIndexDefinition(Scope.LIVE);
			assertNotNull(definition);
			assertEquals(INDEX_NAME, definition.nameOfTheIndex());
		}

		@Test
		@DisplayName("should filter orphaned bucketedPartially when bucketed scopes removed")
		void shouldMutateNonReflectedReferenceAndFilterOrphanedBucketedPartially() {
			final ReferenceSchemaContract schemaWithPartially =
				createBucketedWithPartiallyReferenceSchema();
			// verify precondition
			assertNotNull(schemaWithPartially.getBucketedPartiallyInScope(Scope.LIVE));

			// apply mutation that removes LIVE from bucketed scopes
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					ScopedHistogramIndexDefinition.EMPTY // empty = not bucketed
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					schemaWithPartially
				);

			assertNotNull(result);
			assertFalse(result.isBucketedInScope(Scope.LIVE));
			assertTrue(result.getBucketedPartiallyInScopes().isEmpty());
		}

		@Test
		@DisplayName("should return same schema when nothing changed")
		void shouldReturnSameSchemaWhenNothingChanged() {
			final ReferenceSchemaContract existingSchema = createBucketedReferenceSchema();

			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					existingSchema
				);

			assertSame(existingSchema, result);
		}

		@Test
		@DisplayName("should update only bucketedPartially while preserving existing bucketed definitions")
		void shouldMutateNonReflectedReferenceUpdatingOnlyBucketedPartially() {
			final ReferenceSchemaContract existingSchema = createBucketedReferenceSchema();
			// verify precondition: bucketed in LIVE, no partially
			assertTrue(existingSchema.isBucketedInScope(Scope.LIVE));
			assertTrue(existingSchema.getBucketedPartiallyInScopes().isEmpty());

			final Expression expression = ExpressionFactory.parse("2 > 1");
			// bucketedInScopes=null (don't change), bucketedPartiallyInScopes=non-null
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					null,
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					existingSchema
				);

			assertNotNull(result);
			// bucketed definitions should be preserved
			assertTrue(result.isBucketedInScope(Scope.LIVE));
			final HistogramIndexDefinition definition = result.getHistogramIndexDefinition(Scope.LIVE);
			assertNotNull(definition);
			assertEquals(INDEX_NAME, definition.nameOfTheIndex());
			// bucketedPartially should be updated
			final Expression actual = result.getBucketedPartiallyInScope(Scope.LIVE);
			assertNotNull(actual);
			assertEquals(expression.toExpressionString(), actual.toExpressionString());
		}
	}

	@Nested
	@DisplayName("Mutate reflected reference schema")
	class MutateReflectedReference {

		@Test
		@DisplayName("should mutate reflected reference with explicit bucketed")
		void shouldMutateReflectedReferenceWithExplicitBucketed() {
			final ReflectedReferenceSchema inheritedRef = createInheritedReflectedReferenceSchema();
			assertTrue(inheritedRef.isBucketedInherited());

			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					inheritedRef
				);

			assertNotNull(result);
			final ReflectedReferenceSchemaContract reflected =
				assertInstanceOf(ReflectedReferenceSchemaContract.class, result);
			assertFalse(reflected.isBucketedInherited());
			assertTrue(result.isBucketedInScope(Scope.LIVE));
			assertEquals(INDEX_NAME, result.getHistogramIndexDefinition(Scope.LIVE).nameOfTheIndex());
		}

		@Test
		@DisplayName("should mutate reflected reference to inherited when both null")
		void shouldMutateReflectedReferenceToInheritedWhenBothNull() {
			final ReflectedReferenceSchema explicitRef = createExplicitBucketedReflectedSchema();
			assertFalse(explicitRef.isBucketedInherited());

			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME, null, null
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					explicitRef
				);

			assertNotNull(result);
			final ReflectedReferenceSchemaContract reflected =
				assertInstanceOf(ReflectedReferenceSchemaContract.class, result);
			assertTrue(reflected.isBucketedInherited());
		}

		@Test
		@DisplayName("should mutate reflected reference applying bucketedPartially")
		void shouldMutateReflectedReferenceApplyingBucketedPartially() {
			final ReflectedReferenceSchema explicitRef = createExplicitBucketedReflectedSchema();
			final Expression expression = ExpressionFactory.parse("1 > 0");

			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					},
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					explicitRef
				);

			assertNotNull(result);
			final Expression actual = result.getBucketedPartiallyInScope(Scope.LIVE);
			assertNotNull(actual);
			assertEquals(expression.toExpressionString(), actual.toExpressionString());
		}

		@Test
		@DisplayName("should return same instance when explicit bucketed already matches")
		void shouldReturnSameReflectedSchemaWhenBucketedAlreadyMatches() {
			final ReflectedReferenceSchema explicitRef = createExplicitBucketedReflectedSchema();
			assertFalse(explicitRef.isBucketedInherited());

			// mutation carries the same bucketed definition as the existing schema
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					explicitRef
				);

			assertSame(explicitRef, result);
		}

		@Test
		@DisplayName("should return same instance when already inherited and mutation inherits")
		void shouldReturnSameReflectedSchemaWhenAlreadyInherited() {
			final ReflectedReferenceSchema inheritedRef = createInheritedReflectedReferenceSchema();
			assertTrue(inheritedRef.isBucketedInherited());

			// both fields null means "inherit" — schema is already inherited
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME, null, null
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					inheritedRef
				);

			assertSame(inheritedRef, result);
		}

		@Test
		@DisplayName("should transition to inherited bucketing and apply partially expression")
		void shouldTransitionReflectedReferenceToInheritedBucketingAndApplyPartially() {
			final ReflectedReferenceSchema explicitRef = createExplicitBucketedReflectedSchema();
			assertFalse(explicitRef.isBucketedInherited());
			final Expression expression = ExpressionFactory.parse("3 > 2");

			// bucketedInScopes=null (→inherit), bucketedPartiallyInScopes=non-null
			final SetReferenceSchemaBucketedMutation mutation =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					null,
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, expression)
					}
				);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					explicitRef
				);

			assertNotNull(result);
			final ReflectedReferenceSchemaContract reflected =
				assertInstanceOf(ReflectedReferenceSchemaContract.class, result);
			// bucketed should now be inherited
			assertTrue(reflected.isBucketedInherited());
			// partially expression should be applied
			final Expression actual = result.getBucketedPartiallyInScope(Scope.LIVE);
			assertNotNull(actual);
			assertEquals(expression.toExpressionString(), actual.toExpressionString());
		}
	}

	@Nested
	@DisplayName("toString output")
	class ToStringOutput {

		@Test
		@DisplayName("should produce readable toString for all states")
		void shouldProduceReadableToString() {
			// inherited (null)
			final SetReferenceSchemaBucketedMutation inherited =
				new SetReferenceSchemaBucketedMutation(REFERENCE_NAME, null, null);
			assertTrue(inherited.toString().contains("(inherited)"));

			// not bucketed (empty)
			final SetReferenceSchemaBucketedMutation notBucketed =
				new SetReferenceSchemaBucketedMutation(REFERENCE_NAME, ScopedHistogramIndexDefinition.EMPTY);
			assertTrue(notBucketed.toString().contains("(not bucketed)"));

			// bucketed in scopes
			final SetReferenceSchemaBucketedMutation bucketed =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					}
				);
			assertTrue(bucketed.toString().contains("bucketed in scopes"));

			// bucketedPartially present
			final SetReferenceSchemaBucketedMutation withPartially =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					new ScopedHistogramIndexDefinition[]{
						new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null)
					},
					new ScopedBucketedPartially[]{
						new ScopedBucketedPartially(Scope.LIVE, null)
					}
				);
			assertTrue(withPartially.toString().contains("bucketedPartially"));

			// bucketedPartially none
			final SetReferenceSchemaBucketedMutation partiallyNone =
				new SetReferenceSchemaBucketedMutation(
					REFERENCE_NAME,
					ScopedHistogramIndexDefinition.EMPTY,
					ScopedBucketedPartially.EMPTY
				);
			assertTrue(partiallyNone.toString().contains("bucketedPartially=(none)"));
		}
	}

	@Nested
	@DisplayName("Scoped records validation")
	class ScopedRecords {

		@Test
		@DisplayName("should reject null scope in ScopedHistogramIndexDefinition")
		void shouldRejectNullScopeInScopedHistogramIndexDefinition() {
			assertThrows(
				Exception.class,
				() -> new ScopedHistogramIndexDefinition(null, INDEX_NAME, null)
			);
		}

		@Test
		@DisplayName("should reject null scope in ScopedBucketedPartially")
		void shouldRejectNullScopeInScopedBucketedPartially() {
			assertThrows(
				Exception.class,
				() -> new ScopedBucketedPartially(null, null)
			);
		}

		@Test
		@DisplayName("should reject null nameOfTheIndex in ScopedHistogramIndexDefinition")
		void shouldRejectNullNameOfTheIndex() {
			assertThrows(
				Exception.class,
				() -> new ScopedHistogramIndexDefinition(Scope.LIVE, null, null)
			);
		}

		@Test
		@DisplayName("should allow null expressions in scoped records")
		void shouldAllowNullExpressions() {
			final ScopedHistogramIndexDefinition histogram =
				new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, null);
			assertNotNull(histogram);
			assertNull(histogram.valueExpression());

			final ScopedBucketedPartially partially =
				new ScopedBucketedPartially(Scope.LIVE, null);
			assertNotNull(partially);
			assertNull(partially.expression());
		}

		@Test
		@DisplayName("should support equality and hash code for scoped records")
		void shouldSupportEqualityAndHashCode() {
			final Expression expression = ExpressionFactory.parse("1 > 0");

			final ScopedHistogramIndexDefinition h1 =
				new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, expression);
			final ScopedHistogramIndexDefinition h2 =
				new ScopedHistogramIndexDefinition(Scope.LIVE, INDEX_NAME, expression);
			assertEquals(h1, h2);
			assertEquals(h1.hashCode(), h2.hashCode());

			final ScopedBucketedPartially p1 =
				new ScopedBucketedPartially(Scope.LIVE, expression);
			final ScopedBucketedPartially p2 =
				new ScopedBucketedPartially(Scope.LIVE, expression);
			assertEquals(p1, p2);
			assertEquals(p1.hashCode(), p2.hashCode());
		}
	}
}
