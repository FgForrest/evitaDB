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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.ASSOCIATED_DATA_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.createExistingAssociatedDataSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAssociatedDataSchemaDescriptionMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class ModifyAssociatedDataSchemaDescriptionMutationTest {

	@Test
	void shouldOverrideDescriptionOfPreviousMutationIfNamesMatch() {
		ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
			ASSOCIATED_DATA_NAME, "newDescription"
		);
		ModifyAssociatedDataSchemaDescriptionMutation existingMutation = new ModifyAssociatedDataSchemaDescriptionMutation(ASSOCIATED_DATA_NAME, "oldDescription");
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)).thenReturn(of(createExistingAssociatedDataSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAssociatedDataSchemaDescriptionMutation.class, result.current()[0]);
		assertEquals("newDescription", ((ModifyAssociatedDataSchemaDescriptionMutation) result.current()[0]).getDescription());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
			ASSOCIATED_DATA_NAME, "newDescription"
		);
		ModifyAssociatedDataSchemaDescriptionMutation existingMutation = new ModifyAssociatedDataSchemaDescriptionMutation("differentName", "oldDescription");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateAssociatedDataSchema() {
		ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
			ASSOCIATED_DATA_NAME, "newDescription"
		);
		final AssociatedDataSchemaContract mutatedSchema = mutation.mutate(createExistingAssociatedDataSchema());
		assertNotNull(mutatedSchema);
		assertEquals("newDescription", mutatedSchema.getDescription());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
			ASSOCIATED_DATA_NAME, "newDescription"
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)).thenReturn(of(createExistingAssociatedDataSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final AssociatedDataSchemaContract newAssociatedDataSchema = newEntitySchema.getAssociatedData(ASSOCIATED_DATA_NAME).orElseThrow();
		assertEquals("newDescription", newAssociatedDataSchema.getDescription());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAssociatedData() {
		ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
			ASSOCIATED_DATA_NAME, "newDescription"
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
