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
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ModifyReferenceSchemaRelatedEntityMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifyReferenceSchemaRelatedEntityMutationTest {

	@Test
	void shouldOverrideRelatedEntityTypeOfPreviousMutationIfNamesMatch() {
		ModifyReferenceSchemaRelatedEntityMutation mutation = new ModifyReferenceSchemaRelatedEntityMutation(
			REFERENCE_NAME, "newRelatedEntityType", false
		);
		ModifyReferenceSchemaRelatedEntityMutation existingMutation = new ModifyReferenceSchemaRelatedEntityMutation(REFERENCE_NAME, "oldRelatedEntityType", false);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(of(createExistingReferenceSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyReferenceSchemaRelatedEntityMutation.class, result.current()[0]);
		assertEquals("newRelatedEntityType", ((ModifyReferenceSchemaRelatedEntityMutation) result.current()[0]).getReferencedEntityType());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifyReferenceSchemaRelatedEntityMutation mutation = new ModifyReferenceSchemaRelatedEntityMutation(
			REFERENCE_NAME, "newRelatedEntityType", false
		);
		ModifyReferenceSchemaRelatedEntityMutation existingMutation = new ModifyReferenceSchemaRelatedEntityMutation("differentName", "oldRelatedEntityType", false);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateReferenceSchema() {
		ModifyReferenceSchemaRelatedEntityMutation mutation = new ModifyReferenceSchemaRelatedEntityMutation(
			REFERENCE_NAME, "newRelatedEntityType", false
		);
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema());
		assertNotNull(mutatedSchema);
		assertEquals("newRelatedEntityType", mutatedSchema.getReferencedEntityType());
		assertFalse(mutatedSchema.isReferencedGroupTypeManaged());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyReferenceSchemaRelatedEntityMutation mutation = new ModifyReferenceSchemaRelatedEntityMutation(
			REFERENCE_NAME, "newRelatedEntityType", false
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
		assertEquals("newRelatedEntityType", newReferenceSchema.getReferencedEntityType());
		assertFalse(newReferenceSchema.isReferencedGroupTypeManaged());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
		ModifyReferenceSchemaRelatedEntityMutation mutation = new ModifyReferenceSchemaRelatedEntityMutation(
			REFERENCE_NAME, "newRelatedEntityType", false
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
