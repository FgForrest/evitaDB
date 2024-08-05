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

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.ASSOCIATED_DATA_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.createExistingAssociatedDataSchema;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAssociatedDataSchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class RemoveAssociatedDataSchemaMutationTest {

	@Test
	void shouldRemovePreviousCreateMutationWithSameName() {
		RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(ASSOCIATED_DATA_NAME);
		CreateAssociatedDataSchemaMutation previousMutation = new CreateAssociatedDataSchemaMutation(
			ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
		);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
		);
		assertNotNull(result);
		assertTrue(result.discarded());
		assertNull(result.origin());
		assertNotNull(result.current());
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAssociateData() {
		RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(ASSOCIATED_DATA_NAME);
		CreateAssociatedDataSchemaMutation previousMutation = new CreateAssociatedDataSchemaMutation(
			"differentName", "differentDescription", "deprecationNotice", String.class, true, true
		);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation));
	}

	@Test
	void shouldRemoveAssociatedData() {
		RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(ASSOCIATED_DATA_NAME);
		final AssociatedDataSchemaContract associatedSchema = mutation.mutate(createExistingAssociatedDataSchema());
		assertNull(associatedSchema);
	}

	@Test
	void shouldRemoveAssociatedDataInEntity() {
		RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(ASSOCIATED_DATA_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
			.thenReturn(of(createExistingAssociatedDataSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		assertFalse(newEntitySchema.getAssociatedData(ASSOCIATED_DATA_NAME).isPresent());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAssociatedData() {
		RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(ASSOCIATED_DATA_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
			.thenReturn(empty());
		final EntitySchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertEquals(1, mutatedSchema.version());
	}

}
