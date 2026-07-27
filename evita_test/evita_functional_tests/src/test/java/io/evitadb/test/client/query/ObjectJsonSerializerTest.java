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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-type contracts of {@link ObjectJsonSerializer} — the exact Jackson node kind
 * emitted for every supported scalar, temporal, ranged, enum, predecessor and price value, the
 * array flattening of collections and Java arrays, and the failure mode for unsupported types.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(SERIALIZATION)
@DisplayName("ObjectJsonSerializer value serialization")
class ObjectJsonSerializerTest {

	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");

	private final ObjectJsonSerializer serializer = new ObjectJsonSerializer();

	@Nested
	@DisplayName("Scalar values")
	class Scalars {

		@Test
		@DisplayName("Should serialize a null value into a JSON null node")
		void shouldSerializeNullAsNullNode() {
			final JsonNode result = serializer.serializeObject(null);

			assertTrue(result.isNull());
		}

		@Test
		@DisplayName("Should serialize a String into a textual node")
		void shouldSerializeStringAsTextNode() {
			final JsonNode result = serializer.serializeObject("hello");

			assertTrue(result.isTextual());
			assertEquals("hello", result.asText());
		}

		@Test
		@DisplayName("Should serialize a Character into a textual node")
		void shouldSerializeCharacterAsTextNode() {
			final JsonNode result = serializer.serializeObject('A');

			assertTrue(result.isTextual());
			assertEquals("A", result.asText());
		}

		@Test
		@DisplayName("Should serialize an Integer into a numeric node")
		void shouldSerializeIntegerAsNumberNode() {
			final JsonNode result = serializer.serializeObject(42);

			assertTrue(result.isInt());
			assertEquals(42, result.intValue());
		}

		@Test
		@DisplayName("Should serialize a Short into a numeric node")
		void shouldSerializeShortAsNumberNode() {
			final JsonNode result = serializer.serializeObject((short) 9);

			assertTrue(result.isNumber());
			assertEquals((short) 9, result.shortValue());
		}

		@Test
		@DisplayName("Should serialize a Byte into a numeric node")
		void shouldSerializeByteAsNumberNode() {
			final JsonNode result = serializer.serializeObject((byte) 3);

			assertTrue(result.isNumber());
			assertEquals(3, result.intValue());
		}

		@Test
		@DisplayName("Should serialize a Long into a textual node holding its decimal string")
		void shouldSerializeLongAsTextNode() {
			final JsonNode result = serializer.serializeObject(123456789012L);

			assertTrue(result.isTextual());
			assertFalse(result.isNumber());
			assertEquals("123456789012", result.asText());
		}

		@Test
		@DisplayName("Should serialize a Boolean into a boolean node")
		void shouldSerializeBooleanAsBooleanNode() {
			final JsonNode result = serializer.serializeObject(Boolean.TRUE);

			assertTrue(result.isBoolean());
			assertTrue(result.booleanValue());
		}

		@Test
		@DisplayName("Should serialize a BigDecimal into formatted text")
		void shouldSerializeBigDecimalAsFormattedText() {
			final BigDecimal value = new BigDecimal("1.50");

			final JsonNode result = serializer.serializeObject(value);

			assertTrue(result.isTextual());
			assertEquals(EvitaDataTypes.formatValue(value), result.asText());
		}
	}

	@Nested
	@DisplayName("Typed and temporal values")
	class TypedValues {

		@Test
		@DisplayName("Should serialize a Locale into its language tag")
		void shouldSerializeLocaleAsLanguageTag() {
			final JsonNode result = serializer.serializeObject(Locale.forLanguageTag("cs-CZ"));

			assertTrue(result.isTextual());
			assertEquals("cs-CZ", result.asText());
		}

		@Test
		@DisplayName("Should serialize a Currency into its currency code")
		void shouldSerializeCurrencyAsCode() {
			final JsonNode result = serializer.serializeObject(CURRENCY_CZK);

			assertTrue(result.isTextual());
			assertEquals("CZK", result.asText());
		}

		@Test
		@DisplayName("Should serialize an OffsetDateTime in ISO offset date-time format")
		void shouldSerializeOffsetDateTimeAsIso() {
			final OffsetDateTime value = OffsetDateTime.of(2023, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2));

			final JsonNode result = serializer.serializeObject(value);

			assertTrue(result.isTextual());
			assertEquals(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value), result.asText());
		}

		@Test
		@DisplayName("Should serialize a LocalDateTime in ISO local date-time format")
		void shouldSerializeLocalDateTimeAsIso() {
			final LocalDateTime value = LocalDateTime.of(2023, 1, 2, 3, 4, 5);

			final JsonNode result = serializer.serializeObject(value);

			assertTrue(result.isTextual());
			assertEquals(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value), result.asText());
		}

		@Test
		@DisplayName("Should serialize a LocalDate in ISO local date format")
		void shouldSerializeLocalDateAsIso() {
			final LocalDate value = LocalDate.of(2023, 1, 2);

			final JsonNode result = serializer.serializeObject(value);

			assertTrue(result.isTextual());
			assertEquals(DateTimeFormatter.ISO_LOCAL_DATE.format(value), result.asText());
		}

		@Test
		@DisplayName("Should serialize a LocalTime in ISO local time format")
		void shouldSerializeLocalTimeAsIso() {
			final LocalTime value = LocalTime.of(3, 4, 5);

			final JsonNode result = serializer.serializeObject(value);

			assertTrue(result.isTextual());
			assertEquals(DateTimeFormatter.ISO_LOCAL_TIME.format(value), result.asText());
		}

		@Test
		@DisplayName("Should serialize an Enum into its constant name")
		void shouldSerializeEnumAsName() {
			final JsonNode result = serializer.serializeObject(OrderDirection.ASC);

			assertTrue(result.isTextual());
			assertEquals("ASC", result.asText());
		}

		@Test
		@DisplayName("Should serialize a Predecessor into its predecessor primary key")
		void shouldSerializePredecessorAsPk() {
			final JsonNode result = serializer.serializeObject(new Predecessor(42));

			assertTrue(result.isInt());
			assertEquals(42, result.intValue());
		}
	}

	@Nested
	@DisplayName("Ranges")
	class Ranges {

		@Test
		@DisplayName("Should serialize a closed range into a two-element array of both bounds")
		void shouldSerializeClosedRangeAsTwoElementArray() {
			final JsonNode result = serializer.serializeObject(IntegerNumberRange.between(1, 10));

			assertTrue(result.isArray());
			assertEquals(2, result.size());
			assertEquals(1, result.get(0).intValue());
			assertEquals(10, result.get(1).intValue());
		}

		@Test
		@DisplayName("Should serialize an open range bound into a JSON null node")
		void shouldSerializeOpenRangeBoundAsNullNode() {
			final JsonNode result = serializer.serializeObject(IntegerNumberRange.from(5));

			assertTrue(result.isArray());
			assertEquals(2, result.size());
			assertEquals(5, result.get(0).intValue());
			assertTrue(result.get(1).isNull());
		}
	}

	@Nested
	@DisplayName("Prices")
	class Prices {

		@Test
		@DisplayName("Should serialize a price into an object keyed by the price descriptor names")
		void shouldSerializePriceKeyedByDescriptor() {
			final Price price = new Price(
				1, "basic", CURRENCY_CZK, 5,
				new BigDecimal("100.00"), new BigDecimal("21"), new BigDecimal("121.00"),
				null, true
			);

			final JsonNode result = serializer.serializeObject(price);

			assertTrue(result.isObject());
			assertEquals(1, result.get(PriceDescriptor.PRICE_ID.name()).intValue());
			assertEquals("basic", result.get(PriceDescriptor.PRICE_LIST.name()).asText());
			assertEquals("CZK", result.get(PriceDescriptor.CURRENCY.name()).asText());
			assertEquals(5, result.get(PriceDescriptor.INNER_RECORD_ID.name()).intValue());
			assertTrue(result.get(PriceDescriptor.INDEXED.name()).booleanValue());
			assertEquals(
				EvitaDataTypes.formatValue(new BigDecimal("100.00")),
				result.get(PriceDescriptor.PRICE_WITHOUT_TAX.name()).asText()
			);
			assertEquals(
				EvitaDataTypes.formatValue(new BigDecimal("121.00")),
				result.get(PriceDescriptor.PRICE_WITH_TAX.name()).asText()
			);
			assertEquals(
				EvitaDataTypes.formatValue(new BigDecimal("21")),
				result.get(PriceDescriptor.TAX_RATE.name()).asText()
			);
		}

		@Test
		@DisplayName("Should emit JSON null nodes for a null inner record id and validity")
		void shouldEmitNullNodesForNullInnerRecordIdAndValidity() {
			final Price price = new Price(
				1, "basic", CURRENCY_CZK, null,
				new BigDecimal("100.00"), new BigDecimal("21"), new BigDecimal("121.00"),
				null, true
			);

			final JsonNode result = serializer.serializeObject(price);

			assertTrue(result.get(PriceDescriptor.INNER_RECORD_ID.name()).isNull());
			assertTrue(result.get(PriceDescriptor.VALIDITY.name()).isNull());
		}
	}

	@Nested
	@DisplayName("Collections and arrays")
	class CollectionsAndArrays {

		@Test
		@DisplayName("Should serialize a collection into an array preserving element order")
		void shouldSerializeCollectionAsArray() {
			final JsonNode result = serializer.serializeObject(List.of("a", "b", "c"));

			assertTrue(result.isArray());
			assertEquals(3, result.size());
			assertEquals("a", result.get(0).asText());
			assertEquals("b", result.get(1).asText());
			assertEquals("c", result.get(2).asText());
		}

		@Test
		@DisplayName("Should serialize an Object array dispatching each element by its runtime type")
		void shouldSerializeObjectArrayAsArray() {
			final JsonNode result = serializer.serializeObject(new Object[] { 1, "x", Boolean.TRUE });

			assertTrue(result.isArray());
			assertEquals(3, result.size());
			assertEquals(1, result.get(0).intValue());
			assertEquals("x", result.get(1).asText());
			assertTrue(result.get(2).booleanValue());
		}

		@Test
		@DisplayName("Should serialize a primitive int array into a numeric array via reflection")
		void shouldSerializePrimitiveIntArrayAsArray() {
			final JsonNode result = serializer.serializeObject(new int[] { 1, 2, 3 });

			assertTrue(result.isArray());
			assertEquals(3, result.size());
			assertEquals(1, result.get(0).intValue());
			assertEquals(2, result.get(1).intValue());
			assertEquals(3, result.get(2).intValue());
		}
	}

	@Nested
	@DisplayName("Unsupported types")
	class UnsupportedTypes {

		@Test
		@DisplayName("Should throw a generic internal error naming the unsupported class")
		void shouldThrowWhenTypeUnsupported() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> serializer.serializeObject(new Object())
			);

			assertTrue(ex.getMessage().contains(Object.class.getName()));
		}
	}
}
