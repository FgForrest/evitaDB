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

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AllowEvolutionModeInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AllowEvolutionModeInEntitySchemaMutationTest {

	@Test
	void shouldOverrideCurrenciesOfPreviousMutationIfNamesMatch() {
		AllowEvolutionModeInEntitySchemaMutation mutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		AllowEvolutionModeInEntitySchemaMutation existingMutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_REFERENCES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(AllowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final EvolutionMode[] modes = ((AllowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(3, modes.length);
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_REFERENCES, EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, Arrays.stream(modes).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedMutationIfNamesMatch() {
		AllowEvolutionModeInEntitySchemaMutation mutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		DisallowEvolutionModeInEntitySchemaMutation existingMutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(AllowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final EvolutionMode[] modes = ((AllowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(2, modes.length);
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, Arrays.stream(modes).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedWiderMutationIfNamesMatch() {
		AllowEvolutionModeInEntitySchemaMutation mutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		DisallowEvolutionModeInEntitySchemaMutation existingMutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_REFERENCES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(DisallowEvolutionModeInEntitySchemaMutation.class, result.origin());
		final Set<EvolutionMode> disallowedCurrencies = ((DisallowEvolutionModeInEntitySchemaMutation) result.origin()).getEvolutionModes();
		assertEquals(1, disallowedCurrencies.size());
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_REFERENCES }, disallowedCurrencies.stream().sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(AllowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final EvolutionMode[] allowedCurrencies = ((AllowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(2, allowedCurrencies.length);
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, Arrays.stream(allowedCurrencies).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedNonOverlappingMutationIfNamesMatch() {
		AllowEvolutionModeInEntitySchemaMutation mutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		DisallowEvolutionModeInEntitySchemaMutation existingMutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_REFERENCES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(DisallowEvolutionModeInEntitySchemaMutation.class, result.origin());
		final Set<EvolutionMode> disallowedCurrencies = ((DisallowEvolutionModeInEntitySchemaMutation) result.origin()).getEvolutionModes();
		assertEquals(1, disallowedCurrencies.size());
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_REFERENCES }, disallowedCurrencies.stream().sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(AllowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final EvolutionMode[] allowedCurrencies = ((AllowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(2, allowedCurrencies.length);
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, Arrays.stream(allowedCurrencies).sorted().toArray());
	}

	@Test
	void shouldMutateEntitySchema() {
		AllowEvolutionModeInEntitySchemaMutation mutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final Set<EvolutionMode> modes = newEntitySchema.getEvolutionMode();
		assertEquals(2, modes.size());
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, modes.stream().sorted().toArray());
	}

}
