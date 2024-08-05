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
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link DisallowEvolutionModeInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class DisallowEvolutionModeInEntitySchemaMutationTest {

	@Test
	void shouldOverrideCurrenciesOfPreviousMutationIfNamesMatch() {
		DisallowEvolutionModeInEntitySchemaMutation mutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		DisallowEvolutionModeInEntitySchemaMutation existingMutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_REFERENCES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(DisallowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final Set<EvolutionMode> currencies = ((DisallowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(3, currencies.size());
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_REFERENCES, EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, currencies.stream().sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousAllowedMutationIfNamesMatch() {
		DisallowEvolutionModeInEntitySchemaMutation mutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES, EvolutionMode.ADDING_REFERENCES);
		AllowEvolutionModeInEntitySchemaMutation existingMutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_REFERENCES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getEvolutionMode()).thenReturn(Stream.of(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES).collect(Collectors.toSet()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(DisallowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final Set<EvolutionMode> currencies = ((DisallowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(2, currencies.size());
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, currencies.stream().sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousAllowedWiderMutationIfNamesMatch() {
		DisallowEvolutionModeInEntitySchemaMutation mutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		AllowEvolutionModeInEntitySchemaMutation existingMutation = new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_REFERENCES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getEvolutionMode()).thenReturn(Stream.of(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES).collect(Collectors.toSet()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(AllowEvolutionModeInEntitySchemaMutation.class, result.origin());
		final EvolutionMode[] allowedCurrencies = ((AllowEvolutionModeInEntitySchemaMutation) result.origin()).getEvolutionModes();
		assertEquals(1, allowedCurrencies.length);
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_REFERENCES }, Arrays.stream(allowedCurrencies).sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(DisallowEvolutionModeInEntitySchemaMutation.class, result.current()[0]);
		final Set<EvolutionMode> disallowedCurrencies = ((DisallowEvolutionModeInEntitySchemaMutation) result.current()[0]).getEvolutionModes();
		assertEquals(2, disallowedCurrencies.size());
		assertArrayEquals(new EvolutionMode[] { EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES }, disallowedCurrencies.stream().sorted().toArray());
	}

	@Test
	void shouldMutateEntitySchema() {
		DisallowEvolutionModeInEntitySchemaMutation mutation = new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getEvolutionMode()).thenReturn(Stream.of(EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES).collect(Collectors.toSet()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final Set<EvolutionMode> currencies = newEntitySchema.getEvolutionMode();
		assertTrue(currencies.isEmpty());
	}

}
