/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_ATTRIBUTE_COMPOUND;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_ATTRIBUTE_PRIORITY;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ModifyReferenceSortableAttributeCompoundSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyReferenceSortableAttributeCompoundSchemaMutationTest {

	@Test
	void shouldOverrideAttributeCompoundOfPreviousMutationIfNamesMatch() {
		ModifyReferenceSortableAttributeCompoundSchemaMutation mutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			REFERENCE_NAME,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "newDescription"
			)
		);
		ModifyReferenceSortableAttributeCompoundSchemaMutation existingMutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			REFERENCE_NAME,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "differentDescription"
			)
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(of(createExistingReferenceSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyReferenceSortableAttributeCompoundSchemaMutation.class, result.current()[0]);
		final ReferenceSortableAttributeCompoundSchemaMutation attributeCompoundSchemaMutation = ((ModifyReferenceSortableAttributeCompoundSchemaMutation) result.current()[0]).getSortableAttributeCompoundSchemaMutation();
		assertInstanceOf(ModifySortableAttributeCompoundSchemaDescriptionMutation.class, attributeCompoundSchemaMutation);
		assertEquals("newDescription", ((ModifySortableAttributeCompoundSchemaDescriptionMutation) attributeCompoundSchemaMutation).getDescription());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifyReferenceSortableAttributeCompoundSchemaMutation mutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			REFERENCE_NAME,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "newDescription"
			)
		);
		ModifyReferenceSortableAttributeCompoundSchemaMutation existingMutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			"differentName",
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "differentDescription"
			)
		);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateReferenceSchema() {
		ModifyReferenceSortableAttributeCompoundSchemaMutation mutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			REFERENCE_NAME,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "newDescription"
			)
		);
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema());
		assertNotNull(mutatedSchema);
		final SortableAttributeCompoundSchemaContract attributeCompoundSchema = mutatedSchema.getSortableAttributeCompound(REFERENCE_ATTRIBUTE_COMPOUND).orElseThrow();
		assertEquals("newDescription", attributeCompoundSchema.getDescription());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyReferenceSortableAttributeCompoundSchemaMutation mutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			REFERENCE_NAME,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "newDescription"
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
		final SortableAttributeCompoundSchemaContract attributeCompoundSchema = newReferenceSchema.getSortableAttributeCompound(REFERENCE_ATTRIBUTE_COMPOUND).orElseThrow();
		assertEquals("newDescription", attributeCompoundSchema.getDescription());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
		ModifyReferenceSortableAttributeCompoundSchemaMutation mutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			REFERENCE_NAME,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(
				REFERENCE_ATTRIBUTE_PRIORITY, "newDescription"
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
