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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link SetEntitySchemaWithGeneratedPrimaryKeyMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SetEntitySchemaWithGeneratedPrimaryKeyMutationTest {

	@Test
	void shouldOverridePrimaryKeySettingsOfPreviousMutationIfNamesMatch() {
		SetEntitySchemaWithGeneratedPrimaryKeyMutation mutation = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(true);
		SetEntitySchemaWithGeneratedPrimaryKeyMutation existingMutation = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(false);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetEntitySchemaWithGeneratedPrimaryKeyMutation.class, result.current()[0]);
		assertTrue(((SetEntitySchemaWithGeneratedPrimaryKeyMutation) result.current()[0]).isWithGeneratedPrimaryKey());
	}

	@Test
	void shouldMutateEntitySchema() {
		SetEntitySchemaWithGeneratedPrimaryKeyMutation mutation = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(true);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		assertTrue(newEntitySchema.isWithGeneratedPrimaryKey());
	}

}
