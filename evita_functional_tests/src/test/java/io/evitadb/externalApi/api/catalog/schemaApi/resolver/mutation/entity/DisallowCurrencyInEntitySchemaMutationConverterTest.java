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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.DisallowCurrencyInEntitySchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DisallowCurrencyInEntitySchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class DisallowCurrencyInEntitySchemaMutationConverterTest {

	private DisallowCurrencyInEntitySchemaMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new DisallowCurrencyInEntitySchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final DisallowCurrencyInEntitySchemaMutation expectedMutation = new DisallowCurrencyInEntitySchemaMutation(
			Currency.getInstance("CZK"),
			Currency.getInstance("USD")
		);

		final DisallowCurrencyInEntitySchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of(
					Currency.getInstance("CZK"),
					Currency.getInstance("USD")
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final DisallowCurrencyInEntitySchemaMutation convertedMutation2 = converter.convert(
			map()
				.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of("CZK", "USD"))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final DisallowCurrencyInEntitySchemaMutation expectedMutation = new DisallowCurrencyInEntitySchemaMutation();

		final DisallowCurrencyInEntitySchemaMutation convertedMutation1 = converter.convert(
			map()
				.e(DisallowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}