/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_ATTRIBUTE_PRIORITY;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ModifyReferenceAttributeSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyReferenceAttributeSchemaMutationTest {

	@Test
	void shouldOverrideAttributeOfPreviousMutationIfNamesMatch() {
		ModifyReferenceAttributeSchemaMutation mutation = new ModifyReferenceAttributeSchemaMutation(
			REFERENCE_NAME,
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
			)
		);
		ModifyReferenceAttributeSchemaMutation existingMutation = new ModifyReferenceAttributeSchemaMutation(
			REFERENCE_NAME,
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
			)
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(of(createExistingReferenceSchema()));
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyReferenceAttributeSchemaMutation.class, result.current()[0]);
		final ReferenceSchemaMutation attributeSchemaMutation = ((ModifyReferenceAttributeSchemaMutation) result.current()[0]).getAttributeSchemaMutation();
		assertInstanceOf(SetAttributeSchemaUniqueMutation.class, attributeSchemaMutation);
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, ((SetAttributeSchemaUniqueMutation) attributeSchemaMutation).getUnique());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifyReferenceAttributeSchemaMutation mutation = new ModifyReferenceAttributeSchemaMutation(
			REFERENCE_NAME,
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
			)
		);
		ModifyReferenceAttributeSchemaMutation existingMutation = new ModifyReferenceAttributeSchemaMutation(
			"differentName",
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
			)
		);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateReferenceSchema() {
		ModifyReferenceAttributeSchemaMutation mutation = new ModifyReferenceAttributeSchemaMutation(
			REFERENCE_NAME,
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
			)
		);
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema());
		assertNotNull(mutatedSchema);
		final AttributeSchemaContract attributeSchema = mutatedSchema.getAttribute(REFERENCE_ATTRIBUTE_PRIORITY).orElseThrow();
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, attributeSchema.getUniquenessType());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyReferenceAttributeSchemaMutation mutation = new ModifyReferenceAttributeSchemaMutation(
			REFERENCE_NAME,
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
			)
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(of(createExistingReferenceSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final ReferenceSchemaContract newReferenceSchema = newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
		final AttributeSchemaContract attributeSchema = newReferenceSchema.getAttribute(REFERENCE_ATTRIBUTE_PRIORITY).orElseThrow();
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, attributeSchema.getUniquenessType());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
		ModifyReferenceAttributeSchemaMutation mutation = new ModifyReferenceAttributeSchemaMutation(
			REFERENCE_NAME,
			new SetAttributeSchemaUniqueMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
			)
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
