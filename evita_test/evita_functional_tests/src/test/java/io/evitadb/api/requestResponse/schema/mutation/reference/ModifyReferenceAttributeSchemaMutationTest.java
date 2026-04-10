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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_ATTRIBUTE_PRIORITY;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ModifyReferenceAttributeSchemaMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyReferenceAttributeSchemaMutation")
class ModifyReferenceAttributeSchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous attribute mutation when reference and attribute names match")
		void shouldOverrideAttributeOfPreviousMutationIfNamesMatch() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);
			final ModifyReferenceAttributeSchemaMutation existingMutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
					)
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
			assertEquals(1, result.current().length);
			assertInstanceOf(
				ModifyReferenceAttributeSchemaMutation.class, result.current()[0]
			);
			final ReferenceAttributeSchemaMutation attributeSchemaMutation =
				((ModifyReferenceAttributeSchemaMutation) result.current()[0])
					.getAttributeSchemaMutation();
			assertInstanceOf(SetAttributeSchemaUniqueMutation.class, attributeSchemaMutation);
			assertEquals(
				AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
				((SetAttributeSchemaUniqueMutation) attributeSchemaMutation).getUnique()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);
			final ModifyReferenceAttributeSchemaMutation existingMutation =
				new ModifyReferenceAttributeSchemaMutation(
					"differentName",
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
					)
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
		@DisplayName("should return null when reference names match but attribute names differ")
		void shouldReturnNullWhenAttributeNamesDiffer() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);
			final ModifyReferenceAttributeSchemaMutation existingMutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						"differentAttribute",
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
					)
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
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
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
		@DisplayName("should set attribute uniqueness on reference schema")
		void shouldMutateReferenceSchema() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			final AttributeSchemaContract attributeSchema =
				mutatedSchema.getAttribute(REFERENCE_ATTRIBUTE_PRIORITY).orElseThrow();
			assertEquals(
				AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
				attributeSchema.getUniquenessType()
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update attribute uniqueness in entity schema")
		void shouldMutateEntitySchema() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			final AttributeSchemaContract attributeSchema =
				newReferenceSchema.getAttribute(REFERENCE_ATTRIBUTE_PRIORITY).orElseThrow();
			assertEquals(
				AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
				attributeSchema.getUniquenessType()
			);
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
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
		@DisplayName("should return composed container name with reference and attribute name")
		void shouldReturnComposedContainerName() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);

			assertEquals(
				REFERENCE_NAME + "." + REFERENCE_ATTRIBUTE_PRIORITY,
				mutation.containerName()
			);
		}

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
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
			final ModifyReferenceAttributeSchemaMutation mutation =
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_NAME,
					new SetAttributeSchemaUniqueMutation(
						REFERENCE_ATTRIBUTE_PRIORITY,
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
					)
				);

			final String result = mutation.toString();

			assertTrue(result.contains("Modify entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
		}
	}
}
