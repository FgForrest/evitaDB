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

package io.evitadb.externalApi.rest.api.resolver.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class ObjectJsonSerializerTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final ObjectJsonSerializer tested = new ObjectJsonSerializer(this.mapper);

	@Test
	void shouldSerializeString() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject("Text"));
		assertEquals("\"Text\"", serialized);
	}

	@Test
	void shouldSerializeBoolean() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(Boolean.FALSE));
		assertEquals("false", serialized);
	}

	@Test
	void shouldSerializeByte() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(Integer.valueOf(8).byteValue()));
		assertEquals("8", serialized);
	}

	@Test
	void shouldSerializeBigDecimal() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(new BigDecimal("154.2640")));
		assertEquals("\"154.2640\"", serialized);
	}

	@Test
	void shouldSerializeLong() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(4587665216654L));
		assertEquals("\"4587665216654\"", serialized);
	}

	@Test
	void shouldSerializeZonedDateTime() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(OffsetDateTime.of(2022, 10, 21, 10, 9, 1, 0, ZoneOffset.ofHours(2))));
		assertEquals("\"2022-10-21T10:09:01+02:00\"", serialized);
	}

	@Test
	void shouldSerializeLocalDateTime() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(LocalDateTime.of(2022, 10, 21, 10, 9, 1)));
		assertEquals("\"2022-10-21T10:09:01\"", serialized);
	}

	@Test
	void shouldSerializeLocalDate() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(LocalDate.of(2022, 10, 21)));
		assertEquals("\"2022-10-21\"", serialized);
	}

	@Test
	void shouldSerializeLocalTime() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(LocalTime.of(10,11, 21)));
		assertEquals("\"10:11:21\"", serialized);
	}

	@Test
	void shouldSerializeLocale() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(new Locale("cs", "CZ")));
		assertEquals("\"cs-CZ\"", serialized);
	}

	@Test
	void shouldSerializeCurrency() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(Currency.getInstance("CZK")));
		assertEquals("\"CZK\"", serialized);
	}

	@Test
	void shouldSerializeListOfIntegers() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(Arrays.asList(5, 2, 6)));
		assertEquals("[5,2,6]", serialized);
	}

	@Test
	void shouldSerializeRangeOfIntegersBetween() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(IntegerNumberRange.between(50, 100)));
		assertEquals("[50,100]", serialized);
	}

	@Test
	void shouldSerializeRangeOfBigDecimalsFrom() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(BigDecimalNumberRange.from(new BigDecimal("50.23"), 2)));
		assertEquals("[\"50.23\",null]", serialized);
	}

	@Test
	void shouldSerializeRangeOfShortsTo() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(ShortNumberRange.to((short) 22)));
		assertEquals("[null,22]", serialized);
	}

	@Test
	void shouldSerializeComplexDataObject() throws Exception {
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(new ComplexDataObject(new DataItemMap(Map.of("value", new DataItemValue("test"))))));
		assertEquals("{\"value\":\"test\"}", serialized);
	}

	@Test
	void shouldSerializePrice() throws Exception {
		final Price price = new Price(new PriceKey(1, "my", Currency.getInstance("CZK")), 2, new BigDecimal("10.5"),
			new BigDecimal("5.4"), new BigDecimal("15.9"), DateTimeRange.until(OffsetDateTime.of(2022, 10, 24, 14, 2, 10,5, ZoneOffset.ofHours(2))), true);
		final String serialized = this.mapper.writeValueAsString(this.tested.serializeObject(price));
		assertEquals("{\"priceId\":1,\"priceList\":\"my\",\"currency\":\"CZK\",\"innerRecordId\":2,\"indexed\":true,\"priceWithoutTax\":\"10.5\",\"priceWithTax\":\"15.9\",\"taxRate\":\"5.4\",\"validity\":[null,\"2022-10-24T14:02:10+02:00\"]}", serialized);
	}

}
