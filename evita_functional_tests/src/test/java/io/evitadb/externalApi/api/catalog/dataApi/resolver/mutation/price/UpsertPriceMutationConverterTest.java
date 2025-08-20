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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.PriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link UpsertPriceMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class UpsertPriceMutationConverterTest {

	private UpsertPriceMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter =  new UpsertPriceMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final UpsertPriceMutation expectedMutation = new UpsertPriceMutation(
			1,
			"basic",
			Currency.getInstance("CZK"),
			1,
			BigDecimal.valueOf(10),
			BigDecimal.valueOf(10),
			BigDecimal.valueOf(11),
			DateTimeRange.between(
				LocalDateTime.of(2022, 10, 4, 15, 0),
				LocalDateTime.of(2022, 10, 8, 15, 0),
				ZoneOffset.UTC
			),
			true
		);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
				.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
				.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
				.e(UpsertPriceMutationDescriptor.INNER_RECORD_ID.name(), 1)
				.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
				.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
				.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
				.e(UpsertPriceMutationDescriptor.VALIDITY.name(), List.of("2022-10-04T15:00:00Z", "2022-10-08T15:00:00Z"))
				.e(UpsertPriceMutationDescriptor.INDEXED.name(), true)
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = this.converter.convertFromInput(
			map()
				.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
				.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
				.e(PriceMutationDescriptor.CURRENCY.name(), "CZK")
				.e(UpsertPriceMutationDescriptor.INNER_RECORD_ID.name(), 1)
				.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), "10")
				.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), "10")
				.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), "11")
				.e(UpsertPriceMutationDescriptor.VALIDITY.name(), List.of("2022-10-04T15:00:00Z", "2022-10-08T15:00:00Z"))
				.e(UpsertPriceMutationDescriptor.INDEXED.name(), true)
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final UpsertPriceMutation expectedMutation = new UpsertPriceMutation(
			1,
			"basic",
			Currency.getInstance("CZK"),
			null,
			BigDecimal.valueOf(10),
			BigDecimal.valueOf(10),
			BigDecimal.valueOf(11),
			null,
			false
		);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
				.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
				.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
				.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
				.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
				.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
				.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
				.build()
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceMutationDescriptor.CURRENCY.name(), Currency.getInstance("CZK"))
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), BigDecimal.valueOf(10))
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), BigDecimal.valueOf(11))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), false)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final UpsertPriceMutation inputMutation = new UpsertPriceMutation(
			1,
			"basic",
			Currency.getInstance("CZK"),
			1,
			BigDecimal.valueOf(10),
			BigDecimal.valueOf(10),
			BigDecimal.valueOf(11),
			DateTimeRange.between(
				LocalDateTime.of(2022, 10, 4, 15, 0),
				LocalDateTime.of(2022, 10, 8, 15, 0),
				ZoneOffset.UTC
			),
			true
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(PriceMutationDescriptor.PRICE_ID.name(), 1)
					.e(PriceMutationDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceMutationDescriptor.CURRENCY.name(), "CZK")
					.e(UpsertPriceMutationDescriptor.INNER_RECORD_ID.name(), 1)
					.e(UpsertPriceMutationDescriptor.PRICE_WITHOUT_TAX.name(), "10")
					.e(UpsertPriceMutationDescriptor.TAX_RATE.name(), "10")
					.e(UpsertPriceMutationDescriptor.PRICE_WITH_TAX.name(), "11")
					.e(UpsertPriceMutationDescriptor.VALIDITY.name(), array()
						.i("2022-10-04T15:00:00Z").i("2022-10-08T15:00:00Z"))
					.e(UpsertPriceMutationDescriptor.INDEXED.name(), true)
					.build()
			);
	}
}
