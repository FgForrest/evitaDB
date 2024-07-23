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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.price;

import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpsertPriceMutationConverterTest {

	private static UpsertPriceMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = new UpsertPriceMutationConverter();
	}

	@Test
	void shouldConvertMutation() {
		final UpsertPriceMutation mutation1 = new UpsertPriceMutation(
			1,
			"basic",
			Currency.getInstance("EUR"),
			1,
			BigDecimal.TEN,
			BigDecimal.ZERO,
			BigDecimal.TEN,
			DateTimeRange.until(OffsetDateTime.of(2022, 12, 24, 10, 10, 0, 0, ZoneOffset.UTC)),
			true
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final UpsertPriceMutation mutation2 = new UpsertPriceMutation(
			1,
			"basic",
			Currency.getInstance("EUR"),
			null,
			BigDecimal.TEN,
			BigDecimal.ZERO,
			BigDecimal.TEN,
			null,
			false
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}
}
