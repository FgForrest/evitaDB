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

import java.util.Arrays;
import java.util.Currency;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AllowCurrencyInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AllowCurrencyInEntitySchemaMutationTest {

	@Test
	void shouldOverrideCurrenciesOfPreviousMutationIfNamesMatch() {
		AllowCurrencyInEntitySchemaMutation mutation = new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"), Currency.getInstance("EUR"));
		AllowCurrencyInEntitySchemaMutation existingMutation = new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("USD"));
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(AllowCurrencyInEntitySchemaMutation.class, result.current()[0]);
		final Currency[] currencies = ((AllowCurrencyInEntitySchemaMutation) result.current()[0]).getCurrencies();
		assertEquals(3, currencies.length);
		assertArrayEquals(new String[] {"CZK", "EUR", "USD" }, Arrays.stream(currencies).map(Currency::getCurrencyCode).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedMutationIfNamesMatch() {
		AllowCurrencyInEntitySchemaMutation mutation = new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"), Currency.getInstance("EUR"));
		DisallowCurrencyInEntitySchemaMutation existingMutation = new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"));
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(AllowCurrencyInEntitySchemaMutation.class, result.current()[0]);
		final Currency[] currencies = ((AllowCurrencyInEntitySchemaMutation) result.current()[0]).getCurrencies();
		assertEquals(2, currencies.length);
		assertArrayEquals(new String[] {"CZK", "EUR" }, Arrays.stream(currencies).map(Currency::getCurrencyCode).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedWiderMutationIfNamesMatch() {
		AllowCurrencyInEntitySchemaMutation mutation = new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"), Currency.getInstance("EUR"));
		DisallowCurrencyInEntitySchemaMutation existingMutation = new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"), Currency.getInstance("USD"));
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(DisallowCurrencyInEntitySchemaMutation.class, result.origin());
		final Set<Currency> disallowedCurrencies = ((DisallowCurrencyInEntitySchemaMutation) result.origin()).getCurrencies();
		assertEquals(1, disallowedCurrencies.size());
		assertArrayEquals(new String[] {"USD" }, disallowedCurrencies.stream().map(Currency::getCurrencyCode).sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(AllowCurrencyInEntitySchemaMutation.class, result.current()[0]);
		final Currency[] allowedCurrencies = ((AllowCurrencyInEntitySchemaMutation) result.current()[0]).getCurrencies();
		assertEquals(2, allowedCurrencies.length);
		assertArrayEquals(new String[] {"CZK", "EUR" }, Arrays.stream(allowedCurrencies).map(Currency::getCurrencyCode).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedNonOverlappingMutationIfNamesMatch() {
		AllowCurrencyInEntitySchemaMutation mutation = new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"), Currency.getInstance("EUR"));
		DisallowCurrencyInEntitySchemaMutation existingMutation = new DisallowCurrencyInEntitySchemaMutation(Currency.getInstance("USD"));
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(DisallowCurrencyInEntitySchemaMutation.class, result.origin());
		final Set<Currency> disallowedCurrencies = ((DisallowCurrencyInEntitySchemaMutation) result.origin()).getCurrencies();
		assertEquals(1, disallowedCurrencies.size());
		assertArrayEquals(new String[] {"USD" }, disallowedCurrencies.stream().map(Currency::getCurrencyCode).sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(AllowCurrencyInEntitySchemaMutation.class, result.current()[0]);
		final Currency[] allowedCurrencies = ((AllowCurrencyInEntitySchemaMutation) result.current()[0]).getCurrencies();
		assertEquals(2, allowedCurrencies.length);
		assertArrayEquals(new String[] {"CZK", "EUR" }, Arrays.stream(allowedCurrencies).map(Currency::getCurrencyCode).sorted().toArray());
	}

	@Test
	void shouldMutateEntitySchema() {
		AllowCurrencyInEntitySchemaMutation mutation = new AllowCurrencyInEntitySchemaMutation(Currency.getInstance("CZK"), Currency.getInstance("EUR"));
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final Set<Currency> currencies = newEntitySchema.getCurrencies();
		assertEquals(2, currencies.size());
		assertArrayEquals(new String[] {"CZK", "EUR" }, currencies.stream().map(Currency::getCurrencyCode).sorted().toArray());
	}

}
