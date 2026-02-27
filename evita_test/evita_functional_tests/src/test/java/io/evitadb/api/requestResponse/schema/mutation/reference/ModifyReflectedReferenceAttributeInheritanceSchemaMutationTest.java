/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModifyReflectedReferenceAttributeInheritanceSchemaMutation} verifying
 * attribute inheritance behavior and filter mutations on reflected reference schemas.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("ModifyReflectedReferenceAttributeInheritanceSchemaMutation")
class ModifyReflectedReferenceAttributeInheritanceSchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous inheritance mutation when names match")
		void shouldReplacePreviousMutationWhenNamesMatch() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					"attrA", "attrB"
				);
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation existingMutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					"attrX"
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
			assertInstanceOf(
				ModifyReflectedReferenceAttributeInheritanceSchemaMutation.class,
				result.current()[0]
			);
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation combined =
				(ModifyReflectedReferenceAttributeInheritanceSchemaMutation) result.current()[0];
			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
				combined.getAttributeInheritanceBehavior()
			);
			assertArrayEquals(
				new String[]{"attrA", "attrB"},
				combined.getAttributeInheritanceFilter()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldNotCombineWhenNamesDiffer() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
				);
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation existingMutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					"differentName",
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED
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
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
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
		@DisplayName("should set attribute inheritance on reflected reference")
		void shouldSetAttributeInheritanceOnReflectedReference() {
			final ReflectedReferenceSchema reflectedSchema =
				createExistingReflectedReferenceSchema();
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					"attrA"
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
			final ReflectedReferenceSchemaContract reflected =
				(ReflectedReferenceSchemaContract) mutatedSchema;
			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				reflected.getAttributesInheritanceBehavior()
			);
			assertArrayEquals(
				new String[]{"attrA"},
				reflected.getAttributeInheritanceFilter()
			);
		}

		@Test
		@DisplayName("should throw when applied to regular (non-reflected) reference schema")
		void shouldThrowWhenAppliedToRegularReferenceSchema() {
			final ReferenceSchemaContract regularSchema = createExistingReferenceSchema();
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
				);

			assertThrows(
				EvitaInternalError.class,
				() -> mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), regularSchema
				)
			);
		}

		@Test
		@DisplayName("should throw when reference schema is null")
		void shouldThrowWhenReferenceSchemaIsNull() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
				);

			assertThrows(
				EvitaInternalError.class,
				() -> mutation.mutate(Mockito.mock(EntitySchemaContract.class), null)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update attribute inheritance in entity schema")
		void shouldMutateEntitySchema() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					"attrA"
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReflectedReferenceSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			assertInstanceOf(ReflectedReferenceSchemaContract.class, newReferenceSchema);
			final ReflectedReferenceSchemaContract reflected =
				(ReflectedReferenceSchemaContract) newReferenceSchema;
			assertEquals(
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
				reflected.getAttributesInheritanceBehavior()
			);
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
				);

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
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
				);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
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
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					"attrA", "attrB"
				);

			final String result = mutation.toString();

			assertTrue(result.contains("reflected reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("INHERIT_ALL_EXCEPT"));
		}

		@Test
		@DisplayName("should use 'only' label when behavior is INHERIT_ONLY_SPECIFIED")
		void shouldUseOnlyLabelWhenBehaviorIsInheritOnlySpecified() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					"attr1"
				);

			final String result = mutation.toString();

			// When behavior is INHERIT_ONLY_SPECIFIED, the filter is an inclusion list,
			// so the label should NOT be "excluding"
			assertFalse(
				result.contains("excluding"),
				"toString should not use 'excluding' label for INHERIT_ONLY_SPECIFIED behavior, got: " + result
			);
			assertTrue(
				result.contains("only"),
				"toString should use 'only' label for INHERIT_ONLY_SPECIFIED behavior, got: " + result
			);
		}

		@Test
		@DisplayName("should use 'excluding' label when behavior is INHERIT_ALL_EXCEPT")
		void shouldUseExcludingLabelWhenBehaviorIsInheritAllExcept() {
			final ModifyReflectedReferenceAttributeInheritanceSchemaMutation mutation =
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					REFERENCE_NAME,
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
					"attr1"
				);

			final String result = mutation.toString();

			assertTrue(
				result.contains("excluding"),
				"toString should use 'excluding' label for INHERIT_ALL_EXCEPT behavior, got: " + result
			);
		}
	}
}
