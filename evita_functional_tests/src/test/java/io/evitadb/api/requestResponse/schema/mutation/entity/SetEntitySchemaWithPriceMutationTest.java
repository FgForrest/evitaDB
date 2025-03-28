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

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link SetEntitySchemaWithPriceMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SetEntitySchemaWithPriceMutationTest {

	@Test
	void shouldOverridePriceSettingsOfPreviousMutationIfNamesMatch() {
		SetEntitySchemaWithPriceMutation mutation = new SetEntitySchemaWithPriceMutation(true, Scope.values(),2);
		SetEntitySchemaWithPriceMutation existingMutation = new SetEntitySchemaWithPriceMutation(false, Scope.NO_SCOPE,0);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetEntitySchemaWithPriceMutation.class, result.current()[0]);
		assertTrue(((SetEntitySchemaWithPriceMutation) result.current()[0]).isWithPrice());
		assertEquals(2, ((SetEntitySchemaWithPriceMutation) result.current()[0]).getIndexedPricePlaces());
		assertArrayEquals(Scope.values(), ((SetEntitySchemaWithPriceMutation) result.current()[0]).getIndexedInScopes());
	}

	@Test
	void shouldMutateEntitySchema() {
		SetEntitySchemaWithPriceMutation mutation = new SetEntitySchemaWithPriceMutation(true, Scope.values(),2);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		assertTrue(newEntitySchema.isWithPrice());
		assertEquals(2, newEntitySchema.getIndexedPricePlaces());
		assertArrayEquals(Scope.values(), newEntitySchema.getPriceIndexedInScopes().toArray(Scope[]::new));
	}

}
