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
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link DisallowLocaleInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class DisallowLocaleInEntitySchemaMutationTest {

	@Test
	void shouldOverrideCurrenciesOfPreviousMutationIfNamesMatch() {
		DisallowLocaleInEntitySchemaMutation mutation = new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		DisallowLocaleInEntitySchemaMutation existingMutation = new DisallowLocaleInEntitySchemaMutation(Locale.FRENCH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Set<Locale> currencies = ((DisallowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(3, currencies.size());
		assertArrayEquals(new String[] {"de", "en", "fr" }, currencies.stream().map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousAllowedMutationIfNamesMatch() {
		DisallowLocaleInEntitySchemaMutation mutation = new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH);
		AllowLocaleInEntitySchemaMutation existingMutation = new AllowLocaleInEntitySchemaMutation(Locale.FRENCH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getLocales()).thenReturn(Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet()));
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Set<Locale> currencies = ((DisallowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(2, currencies.size());
		assertArrayEquals(new String[] { "de", "en" }, currencies.stream().map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousAllowedWiderMutationIfNamesMatch() {
		DisallowLocaleInEntitySchemaMutation mutation = new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		AllowLocaleInEntitySchemaMutation existingMutation = new AllowLocaleInEntitySchemaMutation(Locale.FRENCH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getLocales()).thenReturn(Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet()));
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.origin());
		final Locale[] allowedCurrencies = ((AllowLocaleInEntitySchemaMutation) result.origin()).getLocales();
		assertEquals(1, allowedCurrencies.length);
		assertArrayEquals(new String[] {"fr" }, Arrays.stream(allowedCurrencies).map(Locale::toLanguageTag).sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Set<Locale> disallowedCurrencies = ((DisallowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(2, disallowedCurrencies.size());
		assertArrayEquals(new String[] {"de", "en" }, disallowedCurrencies.stream().map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldMutateEntitySchema() {
		DisallowLocaleInEntitySchemaMutation mutation = new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getLocales()).thenReturn(Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final Set<Locale> currencies = newEntitySchema.getLocales();
		assertTrue(currencies.isEmpty());
	}

}
