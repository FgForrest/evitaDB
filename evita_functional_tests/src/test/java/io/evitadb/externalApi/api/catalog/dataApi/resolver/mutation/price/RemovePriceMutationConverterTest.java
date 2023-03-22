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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.RemovePriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RemovePriceMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class RemovePriceMutationConverterTest {

	private RemovePriceMutationConverter converter;

	@BeforeEach
	void init() {
		converter =  new RemovePriceMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final RemovePriceMutation expectedMutation = new RemovePriceMutation(1, "basic", Currency.getInstance("CZK"));

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(RemovePriceMutationDescriptor.PRICE_ID.name(), 1)
				.e(RemovePriceMutationDescriptor.PRICE_LIST.name(), "basic")
				.e(RemovePriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = converter.convert(
			map()
				.e(RemovePriceMutationDescriptor.PRICE_ID.name(), "1")
				.e(RemovePriceMutationDescriptor.PRICE_LIST.name(), "basic")
				.e(RemovePriceMutationDescriptor.CURRENCY.name(), "CZK")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(RemovePriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(RemovePriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(RemovePriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(RemovePriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(RemovePriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(RemovePriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}