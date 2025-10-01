/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.AllowCurrencyInEntitySchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.AllowCurrencyInEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link AllowCurrencyInEntitySchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class AllowCurrencyInEntitySchemaMutationConverterTest {

	private AllowCurrencyInEntitySchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new AllowCurrencyInEntitySchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final AllowCurrencyInEntitySchemaMutation expectedMutation = new AllowCurrencyInEntitySchemaMutation(
			Currency.getInstance("USD"),
			Currency.getInstance("CZK")
		);

		final AllowCurrencyInEntitySchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AllowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of(
					Currency.getInstance("USD"),
					Currency.getInstance("CZK")
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final AllowCurrencyInEntitySchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(AllowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of("USD", "CZK"))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final AllowCurrencyInEntitySchemaMutation expectedMutation = new AllowCurrencyInEntitySchemaMutation();

		final AllowCurrencyInEntitySchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AllowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), List.of())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	/**
	 * Tests that the converter properly serializes local mutation object back to output map.
	 * This test verifies the reverse conversion from mutation object to API output format,
	 * ensuring that the serialized output contains the correct field names and currency values.
	 */
	@Test
	void shouldSerializeLocalMutationToOutput() {
		final AllowCurrencyInEntitySchemaMutation inputMutation = new AllowCurrencyInEntitySchemaMutation(
			Currency.getInstance("USD"),
			Currency.getInstance("CZK")
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), AllowCurrencyInEntitySchemaMutation.class.getSimpleName())
					.e(AllowCurrencyInEntitySchemaMutationDescriptor.CURRENCIES.name(), new String[]{
						"USD",
						"CZK"
					})
					.build()
			);
	}
}
