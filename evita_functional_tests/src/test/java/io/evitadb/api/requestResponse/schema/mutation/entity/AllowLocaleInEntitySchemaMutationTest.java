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
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AllowLocaleInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AllowLocaleInEntitySchemaMutationTest {

	@Test
	void shouldOverrideCurrenciesOfPreviousMutationIfNamesMatch() {
		AllowLocaleInEntitySchemaMutation mutation = new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		AllowLocaleInEntitySchemaMutation existingMutation = new AllowLocaleInEntitySchemaMutation(Locale.FRENCH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Locale[] locales = ((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(3, locales.length);
		assertArrayEquals(new String[] {"de", "en", "fr" }, Arrays.stream(locales).map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedMutationIfNamesMatch() {
		AllowLocaleInEntitySchemaMutation mutation = new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		DisallowLocaleInEntitySchemaMutation existingMutation = new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Locale[] locales = ((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(2, locales.length);
		assertArrayEquals(new String[] {"de", "en" }, Arrays.stream(locales).map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedWiderMutationIfNamesMatch() {
		AllowLocaleInEntitySchemaMutation mutation = new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		DisallowLocaleInEntitySchemaMutation existingMutation = new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.FRENCH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.origin());
		final Set<Locale> disallowedCurrencies = ((DisallowLocaleInEntitySchemaMutation) result.origin()).getLocales();
		assertEquals(1, disallowedCurrencies.size());
		assertArrayEquals(new String[] {"fr" }, disallowedCurrencies.stream().map(Locale::toLanguageTag).sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Locale[] allowedCurrencies = ((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(2, allowedCurrencies.length);
		assertArrayEquals(new String[] {"de", "en" }, Arrays.stream(allowedCurrencies).map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldOverrideCurrenciesOfPreviousDisallowedNonOverlappingMutationIfNamesMatch() {
		AllowLocaleInEntitySchemaMutation mutation = new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		DisallowLocaleInEntitySchemaMutation existingMutation = new DisallowLocaleInEntitySchemaMutation(Locale.FRENCH);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNotNull(result.origin());
		assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.origin());
		final Set<Locale> disallowedCurrencies = ((DisallowLocaleInEntitySchemaMutation) result.origin()).getLocales();
		assertEquals(1, disallowedCurrencies.size());
		assertArrayEquals(new String[] {"fr"}, disallowedCurrencies.stream().map(Locale::toLanguageTag).sorted().toArray());
		assertNotNull(result.current());
		assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.current()[0]);
		final Locale[] allowedCurrencies = ((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
		assertEquals(2, allowedCurrencies.length);
		assertArrayEquals(new String[] {"de", "en" }, Arrays.stream(allowedCurrencies).map(Locale::toLanguageTag).sorted().toArray());
	}

	@Test
	void shouldMutateEntitySchema() {
		AllowLocaleInEntitySchemaMutation mutation = new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final Set<Locale> locales = newEntitySchema.getLocales();
		assertEquals(2, locales.size());
		assertArrayEquals(new String[] {"de", "en" }, locales.stream().map(Locale::toLanguageTag).sorted().toArray());
	}
	
}
