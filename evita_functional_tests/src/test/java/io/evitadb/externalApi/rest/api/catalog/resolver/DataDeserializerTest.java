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

package io.evitadb.externalApi.rest.api.catalog.resolver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataDeserializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.scalarFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class DataDeserializerTest {

	private DataDeserializer dataDeserializer;

	@BeforeEach
	void setup() {
		this.dataDeserializer = new DataDeserializer(new OpenAPI(), new HashMap<>());
	}

	@Test
	void shouldDeserializeString() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(String.class).toSchema(), new String[]{"abc"});
		if (deserialized instanceof String val) {
			assertEquals("abc", val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeArrayOfStrings() {
		final Object deserialized = this.dataDeserializer.deserializeValue(arrayOf(scalarFrom(String.class)).toSchema(), new String[]{"abc", "def"});
		if (deserialized instanceof String[] val) {
			assertEquals("abc", val[0]);
			assertEquals("def", val[1]);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeEmptyStringArray() {
		final Object deserialized = this.dataDeserializer.deserializeValue(arrayOf(scalarFrom(String.class)).toSchema(), new String[]{});
		if (deserialized instanceof String[] val) {
			assertEquals(0, val.length);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeEmptyArrayNode() {
		final Object deserialized = this.dataDeserializer.deserializeValue(arrayOf(scalarFrom(String.class)).toSchema(), new ArrayNode(JsonNodeFactory.instance));
		if (deserialized instanceof String[] val) {
			assertEquals(0, val.length);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeEnum() {
		final OpenAPI openApi = new OpenAPI();
		final OpenApiEnum priceContentModeEnum = enumFrom(PriceContentMode.class);
		openApi.components(new Components().addSchemas(priceContentModeEnum.getName(), priceContentModeEnum.toSchema()));
		final DataDeserializer dataDeserializer = new DataDeserializer(openApi, Map.of(priceContentModeEnum.getName(), priceContentModeEnum.getEnumTemplate()));

		final Object deserialized = dataDeserializer.deserializeValue(priceContentModeEnum.toSchema(), new String[]{"ALL"});
		assertEquals(PriceContentMode.ALL, deserialized);
	}

	@Test
	void shouldDeserializeChar() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Character.class).toSchema(), new String[]{"D"});
		if (deserialized instanceof Character val) {
			assertEquals(Character.valueOf('D'), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeByteNumber() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Byte.class).toSchema(), new String[]{"6"});
		if (deserialized instanceof Byte val) {
			assertEquals(6, ((Byte) deserialized).intValue());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLocale() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Locale.class).toSchema(), new String[]{"cs-CZ"});
		if (deserialized instanceof Locale val) {
			assertEquals(new Locale("cs", "CZ"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeCurrency() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Currency.class).toSchema(), new String[]{"CZK"});
		if (deserialized instanceof Currency val) {
			assertEquals(Currency.getInstance("CZK"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeUuid() {
		final UUID uuid = UUID.randomUUID();
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(UUID.class).toSchema(), new String[]{uuid.toString()});
		if (deserialized instanceof UUID val) {
			assertEquals(uuid, val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeInteger() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Integer.class).toSchema(), new String[]{"28"});
		if (deserialized instanceof Integer val) {
			assertEquals(Integer.valueOf(28), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeShort() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Short.class).toSchema(), new String[]{"28"});
		if (deserialized instanceof Short val) {
			assertEquals(Short.valueOf((short) 28), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLong() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Long.class).toSchema(), new String[]{"568794"});
		if (deserialized instanceof Long val) {
			assertEquals(Long.valueOf(568794), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfIntegers() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(IntegerNumberRange.class).toSchema(), new String[]{"755", "5648"});
		if (deserialized instanceof IntegerNumberRange val) {
			assertEquals(755, val.getPreciseFrom());
			assertEquals(5648, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfIntegersWithFromOnly() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(IntegerNumberRange.class).toSchema(), new String[]{"755", null});
		if (deserialized instanceof IntegerNumberRange val) {
			assertEquals(755, val.getPreciseFrom());
			assertNull(val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfIntegersWithToOnly() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(IntegerNumberRange.class).toSchema(), new String[]{null, "5648"});
		if (deserialized instanceof IntegerNumberRange val) {
			assertNull(val.getPreciseFrom());
			assertEquals(5648, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldFailToDeserializeRangeOfIntegersWhenArrayIsTooShort() {
		final RestInternalError error = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(scalarFrom(IntegerNumberRange.class).toSchema(), new String[]{"5648"}));
		assertTrue(error.getPublicMessage().startsWith("Array of two values is required"));
	}

	@Test
	void shouldFailToDeserializeRangeOfIntegersWhenArrayContainsNullValues() {
		final RestInternalError error = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(scalarFrom(IntegerNumberRange.class).toSchema(), new String[]{null, null}));
		assertTrue(error.getPublicMessage().startsWith("Both values for range data type are null"));
	}

	@Test
	void shouldDeserializeRangeOfIntegersFromJsonNode() {
		final ArrayNode arrayNode = new ArrayNode(JsonNodeFactory.instance, 2);
		arrayNode.add(new IntNode(755));
		arrayNode.add(new IntNode(5648));
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(IntegerNumberRange.class).toSchema(), arrayNode);
		if (deserialized instanceof IntegerNumberRange val) {
			assertEquals(755, val.getPreciseFrom());
			assertEquals(5648, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfBigDecimals() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(BigDecimalNumberRange.class).toSchema(), new String[]{"755", "5648"});
		if (deserialized instanceof BigDecimalNumberRange val) {
			assertEquals(new BigDecimal("755"), val.getPreciseFrom());
			assertEquals(new BigDecimal("5648"), val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfBigDecimalsWithScale() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(BigDecimalNumberRange.class).toSchema(), new String[]{"755.54", "5648.63"});
		if (deserialized instanceof BigDecimalNumberRange val) {
			assertEquals(new BigDecimal("755.54"), val.getPreciseFrom());
			assertEquals(new BigDecimal("5648.63"), val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfDateTimes() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(DateTimeRange.class).toSchema(), new String[]{"2022-09-27T13:28:27.357442951+02:00", "2022-10-27T13:28:27.357442951+02:00"});
		if (deserialized instanceof DateTimeRange val) {
			assertEquals(OffsetDateTime.parse("2022-09-27T13:28:27.357442951+02:00"), val.getPreciseFrom());
			assertEquals(OffsetDateTime.parse("2022-10-27T13:28:27.357442951+02:00"), val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfLongs() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(LongNumberRange.class).toSchema(), new String[]{"75587", "564865"});
		if (deserialized instanceof LongNumberRange val) {
			assertEquals(75587L, val.getPreciseFrom());
			assertEquals(564865L, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfShorts() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(ShortNumberRange.class).toSchema(), new String[]{"75", "564"});
		if (deserialized instanceof ShortNumberRange val) {
			assertEquals((short) 75, val.getPreciseFrom());
			assertEquals((short) 564, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfBytes() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(ByteNumberRange.class).toSchema(), new String[]{"6", "8"});
		if (deserialized instanceof ByteNumberRange val) {
			assertEquals(6, val.getPreciseFrom().intValue());
			assertEquals(8, val.getPreciseTo().intValue());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeArrayOfIntegers() {
		final Object deserialized = this.dataDeserializer.deserializeValue(arrayOf(scalarFrom(Integer.class)).toSchema(), new String[]{"54", "63"});
		if (deserialized instanceof Integer[] val) {
			assertEquals(Integer.valueOf(54), val[0]);
			assertEquals(Integer.valueOf("63"), val[1]);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeBigDecimal() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(BigDecimal.class).toSchema(), new String[]{"56.23"});
		if (deserialized instanceof BigDecimal val) {
			assertEquals(0, new BigDecimal("56.23").compareTo(val));
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeBoolean() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(Boolean.class).toSchema(), new String[]{"true"});
		if (deserialized instanceof Boolean val) {
			assertEquals(Boolean.TRUE, val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeOffsetDateTime() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(OffsetDateTime.class).toSchema(), new String[]{"2022-09-27T13:28:27.357442951+02:00"});
		if (deserialized instanceof OffsetDateTime val) {
			assertEquals(OffsetDateTime.parse("2022-09-27T13:28:27.357442951+02:00"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeArrayWhenArrayItemIsReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<?> firstSchema = scalarFrom(String.class).toSchema();
		firstSchema.setName("firstSchema");
		components.addSchemas(firstSchema.getName(), firstSchema);

		final ArrayNode jsonNodes = new ArrayNode(JsonNodeFactory.instance, Arrays.asList(new TextNode("ABC"), new TextNode("DEF")));

		final Object[] objects = new DataDeserializer(openAPI, new HashMap<>()).deserializeArray(arrayOf(typeRefTo(firstSchema.getName())).toSchema(), jsonNodes);
		assertEquals("ABC", (String) objects[0]);
		assertEquals("DEF", (String) objects[1]);
	}

	@Test
	void shouldDeserializeLocalDateTime() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(LocalDateTime.class).toSchema(), new String[]{"2022-09-27T13:28:27.357442951"});
		if (deserialized instanceof LocalDateTime val) {
			assertEquals(LocalDateTime.parse("2022-09-27T13:28:27.357442951"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLocalDate() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(LocalDate.class).toSchema(), new String[]{"2022-09-27"});
		if (deserialized instanceof LocalDate val) {
			assertEquals(LocalDate.parse("2022-09-27"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLocalTime() {
		final Object deserialized = this.dataDeserializer.deserializeValue(scalarFrom(LocalTime.class).toSchema(), new String[]{"13:28:27.357442951"});
		if (deserialized instanceof LocalTime val) {
			assertEquals(LocalTime.parse("13:28:27.357442951"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldFailToDeserializeDataWhenUnknownStringFormatUsed() {
		final Schema<?> stringSchema = scalarFrom(String.class).toSchema();
		stringSchema.setFormat("UnknownFormat");
		final RestInternalError exception = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(stringSchema, new TextNode("abc")));
		assertTrue(exception.getPublicMessage().startsWith("Unknown schema format"));
	}

	@Test
	void shouldFailToDeserializeDataWhenUnknownIntegerFormatUsed() {
		final Schema<?> stringSchema = scalarFrom(Integer.class).toSchema();
		stringSchema.setFormat("UnknownFormat");
		final RestInternalError exception = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(stringSchema, new TextNode("abc")));
		assertTrue(exception.getPublicMessage().startsWith("Unknown schema format"));
	}

	@Test
	void shouldFailToDeserializeDataWhenUnknownSchemaTypeUsed() {
		final Schema<Object> unknownSchema = new Schema<>();
		unknownSchema.setType("unknownSchema");
		unknownSchema.addType("unknownSchema");
		final RestInternalError exception = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(unknownSchema, new TextNode("abc")));
		assertTrue(exception.getPublicMessage().startsWith("Unknown schema type"));
	}

	@Test
	void shouldFailToDeserializeArrayWhenSchemaIsNotArray() {
		final RestInvalidArgumentException exception = assertThrows(RestInvalidArgumentException.class, () -> this.dataDeserializer.deserializeArray(scalarFrom(String.class).toSchema(), new ArrayNode(JsonNodeFactory.instance)));
		assertTrue(exception.getPublicMessage().startsWith("Can't deserialize value, schema type is not array."));
	}

	@Test
	void shouldFailToDeserializeArrayWhenValueIsNotArray() {
		final RestInvalidArgumentException exception = assertThrows(RestInvalidArgumentException.class, () -> this.dataDeserializer.deserializeArray(arrayOf(scalarFrom(String.class)).toSchema(), new TextNode("abc")));
		assertTrue(exception.getPrivateMessage().startsWith("Can't get array of string if JsonNode is not instance of ArrayNode."));
	}

	@Test
	void shouldDeserializeShortObject() {
		assertEquals((short) 10, this.dataDeserializer.deserializeValue(Short.class, new IntNode(10)));
	}

	@Test
	void shouldDeserializeLongObject() {
		assertEquals(6598754L, this.dataDeserializer.deserializeValue(Long.class, new TextNode("6598754")));
	}

	@Test
	void shouldDeserializeBigDecimalObject() {
		assertEquals(new BigDecimal("5142.52"), this.dataDeserializer.deserializeValue(BigDecimal.class, new TextNode("5142.52")));
	}

	@Test
	void shouldDeserializeBooleanObject() {
		assertEquals(true, this.dataDeserializer.deserializeValue(Boolean.class, BooleanNode.getTrue()));
	}

	@Test
	void shouldDeserializeCharacterObject() {
		assertEquals('H', this.dataDeserializer.deserializeValue(Character.class, new TextNode("H")));
	}

	@Test
	void shouldDeserializeByteObject() {
		assertEquals("8", new String(new byte[]{this.dataDeserializer.deserializeValue(Byte.class, new TextNode("OA=="))}));
	}

	@Test
	void shouldFailToDeserializeArrayWhenJsonNodeIsNotAnArray() {
		final RestInternalError error = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(String[].class, new TextNode("H")));
		assertTrue(error.getPrivateMessage().startsWith("Target class is array"));
	}

	@Test
	void shouldFailToDeserializeArrayWhenTryingToDeserializeUnsupportedClass() {
		final RestInternalError error = assertThrows(RestInternalError.class, () -> this.dataDeserializer.deserializeValue(TestClass.class, new TextNode("H")));
		assertTrue(error.getPrivateMessage().startsWith("Deserialization of field of JavaType"));
	}

	@Test
	void shouldDeserializeJsonNodeTreeWithSingleNode() {
		final Components components = new Components();
		final Schema<?> myNumber = scalarFrom(Integer.class).toSchema();
		myNumber.name("myNumber");
		components.addSchemas(myNumber.getName(), myNumber);
		final OpenAPI openAPI = new OpenAPI();
		openAPI.components(components);
		final Integer testedNumber = 56;
		final Object deserialized = this.dataDeserializer.deserializeTree(myNumber, new JsonNodeFactory(false).numberNode(testedNumber));
		assertTrue(deserialized instanceof Integer);
		assertEquals(testedNumber, (Integer) deserialized);
	}

	@SuppressWarnings("unchecked")
	@Test
	void shouldDeserializeJsonNodeTreeWithNestedStructure() throws Exception {
		final Components components = new Components();
		final OpenAPI openAPI = new OpenAPI();
		openAPI.components(components);

		final Schema<Object> dataObject = new ObjectSchema();
		dataObject.name("dataObject");
		dataObject.addProperty("id", scalarFrom(Short.class).toSchema());
		dataObject.addProperty("description", scalarFrom(String.class).toSchema());
		dataObject.addProperty("locale", scalarFrom(Locale.class).toSchema());
		dataObject.addProperty("properties", arrayOf(scalarFrom(String.class)).toSchema());
		components.addSchemas(dataObject.getName(), dataObject);

		final JsonNodeFactory nodeFactory = new JsonNodeFactory(false);
		final ObjectNode dataNode = nodeFactory.objectNode();
		dataNode.putIfAbsent("id", nodeFactory.numberNode((short) 54));
		dataNode.putIfAbsent("description", nodeFactory.textNode("Popis"));
		dataNode.putIfAbsent("locale", nodeFactory.textNode("cs-CZ"));
		final ArrayNode propertyNode = nodeFactory.arrayNode();
		propertyNode.add("val1");
		propertyNode.add("val2");
		dataNode.putIfAbsent("properties", propertyNode);

		final Object deserialized = this.dataDeserializer.deserializeTree(dataObject, dataNode);
		assertTrue(deserialized instanceof Map<?, ?>);
		final Map<String, Object> data = (Map<String, Object>) deserialized;
		assertEquals((short) 54, data.get("id"));
		assertEquals("Popis", data.get("description"));
		assertEquals(new Locale("cs", "CZ"), data.get("locale"));
		assertTrue(data.get("properties") instanceof List<?>);
		final List<String> properties = (List<String>) data.get("properties");
		assertEquals("val1", properties.get(0));
		assertEquals("val2", properties.get(1));
	}

	@Test
	void shouldDeserializeJsonNodeTreeWhenPropertyNotFound() throws Exception {
		final Components components = new Components();
		final OpenAPI openAPI = new OpenAPI();
		openAPI.components(components);

		final Schema<Object> dataObject = new ObjectSchema();
		dataObject.name("dataObject");
		dataObject.addProperty("id", scalarFrom(Short.class).toSchema());

		final JsonNodeFactory nodeFactory = new JsonNodeFactory(false);
		final ObjectNode dataNode = nodeFactory.objectNode();
		dataNode.putIfAbsent("id", nodeFactory.numberNode((short) 54));
		dataNode.putIfAbsent("description", nodeFactory.textNode("Popis"));

		assertThrows(RestInvalidArgumentException.class, () -> this.dataDeserializer.deserializeTree(dataObject, dataNode), "Invalid property name: description");
	}

	private static class TestClass implements Serializable {
	}

}
