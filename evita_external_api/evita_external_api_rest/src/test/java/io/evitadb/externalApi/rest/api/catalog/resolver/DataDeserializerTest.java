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

package io.evitadb.externalApi.rest.api.catalog.resolver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.rest.exception.RESTApiInternalError;
import io.evitadb.externalApi.rest.exception.RESTApiInvalidArgumentException;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class DataDeserializerTest {

	@Test
	void shouldDeserializeString() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createStringSchema(), new String[]{"abc"});
		if (deserialized instanceof String val) {
			assertEquals("abc", val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeArrayOfStrings() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createArraySchemaOf(createStringSchema()), new String[]{"abc", "def"});
		if (deserialized instanceof String[] val) {
			assertEquals("abc", val[0]);
			assertEquals("def", val[1]);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeEmptyStringArray() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createArraySchemaOf(createStringSchema()), new String[]{});
		if (deserialized instanceof String[] val) {
			assertEquals(0, val.length);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeEmptyArrayNode() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createArraySchemaOf(createStringSchema()), new ArrayNode(JsonNodeFactory.instance));
		if (deserialized instanceof String[] val) {
			assertEquals(0, val.length);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeChar() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createCharacterSchema(), new String[]{"D"});
		if (deserialized instanceof Character val) {
			assertEquals(Character.valueOf('D'), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeByteNumber() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createByteSchema(), new String[]{"6"});
		if (deserialized instanceof Byte val) {
			assertEquals(6, ((Byte) deserialized).intValue());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLocale() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createLocaleSchema(), new String[]{"cs-CZ"});
		if (deserialized instanceof Locale val) {
			assertEquals(new Locale("cs", "CZ"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeCurrency() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createCurrencySchema(), new String[]{"CZK"});
		if (deserialized instanceof Currency val) {
			assertEquals(Currency.getInstance("CZK"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeInteger() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createIntegerSchema(), new String[]{"28"});
		if (deserialized instanceof Integer val) {
			assertEquals(Integer.valueOf(28), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeShort() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createShortSchema(), new String[]{"28"});
		if (deserialized instanceof Short val) {
			assertEquals(Short.valueOf((short) 28), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLong() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createLongSchema(), new String[]{"568794"});
		if (deserialized instanceof Long val) {
			assertEquals(Long.valueOf(568794), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfIntegers() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createIntegerSchema()), new String[]{"755", "5648"});
		if (deserialized instanceof IntegerNumberRange val) {
			assertEquals(755, val.getPreciseFrom());
			assertEquals(5648, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfIntegersWithFromOnly() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createIntegerSchema()), new String[]{"755", null});
		if (deserialized instanceof IntegerNumberRange val) {
			assertEquals(755, val.getPreciseFrom());
			assertNull(val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfIntegersWithToOnly() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createIntegerSchema()), new String[]{null, "5648"});
		if (deserialized instanceof IntegerNumberRange val) {
			assertNull(val.getPreciseFrom());
			assertEquals(5648, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldFailToDeserializeRangeOfIntegersWhenArrayIsTooShort() {
		final RESTApiInternalError error = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createIntegerSchema()), new String[]{"5648"}));
		assertTrue(error.getPublicMessage().startsWith("Array of two values is required"));
	}

	@Test
	void shouldFailToDeserializeRangeOfIntegersWhenArrayContainsNullValues() {
		final RESTApiInternalError error = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createIntegerSchema()), new String[]{null, null}));
		assertTrue(error.getPublicMessage().startsWith("Both values for range data type are null"));
	}

	@Test
	void shouldDeserializeRangeOfIntegersFromJsonNode() {
		final ArrayNode arrayNode = new ArrayNode(JsonNodeFactory.instance, 2);
		arrayNode.add(new IntNode(755));
		arrayNode.add(new IntNode(5648));
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createIntegerSchema()), arrayNode);
		if (deserialized instanceof IntegerNumberRange val) {
			assertEquals(755, val.getPreciseFrom());
			assertEquals(5648, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfBigDecimals() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createBigDecimalSchema()), new String[]{"755", "5648"});
		if (deserialized instanceof BigDecimalNumberRange val) {
			assertEquals(new BigDecimal("755"), val.getPreciseFrom());
			assertEquals(new BigDecimal("5648"), val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfBigDecimalsWithScale() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createBigDecimalSchema()), new String[]{"755.54", "5648.63"});
		if (deserialized instanceof BigDecimalNumberRange val) {
			assertEquals(new BigDecimal("755.54"), val.getPreciseFrom());
			assertEquals(new BigDecimal("5648.63"), val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfDateTimes() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createOffsetDateTimeSchema()), new String[]{"2022-09-27T13:28:27.357442951+02:00", "2022-10-27T13:28:27.357442951+02:00"});
		if (deserialized instanceof DateTimeRange val) {
			assertEquals(OffsetDateTime.parse("2022-09-27T13:28:27.357442951+02:00"), val.getPreciseFrom());
			assertEquals(OffsetDateTime.parse("2022-10-27T13:28:27.357442951+02:00"), val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfLongs() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createLongSchema()), new String[]{"75587", "564865"});
		if (deserialized instanceof LongNumberRange val) {
			assertEquals(75587L, val.getPreciseFrom());
			assertEquals(564865L, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfShorts() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createShortSchema()), new String[]{"75", "564"});
		if (deserialized instanceof ShortNumberRange val) {
			assertEquals((short) 75, val.getPreciseFrom());
			assertEquals((short) 564, val.getPreciseTo());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeRangeOfBytes() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createRangeSchemaOf(createByteSchema()), new String[]{"6", "8"});
		if (deserialized instanceof ByteNumberRange val) {
			assertEquals(6, val.getPreciseFrom().intValue());
			assertEquals(8, val.getPreciseTo().intValue());
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeArrayOfIntegers() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createArraySchemaOf(createIntegerSchema()), new String[]{"54", "63"});
		if (deserialized instanceof Integer[] val) {
			assertEquals(Integer.valueOf(54), val[0]);
			assertEquals(Integer.valueOf("63"), val[1]);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeBigDecimal() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createBigDecimalSchema(), new String[]{"56.23"});
		if (deserialized instanceof BigDecimal val) {
			assertEquals(0, new BigDecimal("56.23").compareTo(val));
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeBoolean() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createBooleanSchema(), new String[]{"true"});
		if (deserialized instanceof Boolean val) {
			assertEquals(Boolean.TRUE, val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeOffsetDateTime() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createOffsetDateTimeSchema(), new String[]{"2022-09-27T13:28:27.357442951+02:00"});
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

		final Schema<Object> firstSchema = createStringSchema();
		firstSchema.setName("firstSchema");
		components.addSchemas(firstSchema.getName(), firstSchema);

		final ArrayNode jsonNodes = new ArrayNode(JsonNodeFactory.instance, Arrays.asList(new TextNode("ABC"), new TextNode("DEF")));

		final Object[] objects = DataDeserializer.deserializeArray(openAPI, createArraySchemaOf(createReferenceSchema(firstSchema)), jsonNodes);
		assertEquals("ABC", (String) objects[0]);
		assertEquals("DEF", (String) objects[1]);
	}

	@Test
	void shouldDeserializeLocalDateTime() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createLocalDateTimeSchema(), new String[]{"2022-09-27T13:28:27.357442951"});
		if (deserialized instanceof LocalDateTime val) {
			assertEquals(LocalDateTime.parse("2022-09-27T13:28:27.357442951"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLocalDate() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createLocalDateSchema(), new String[]{"2022-09-27"});
		if (deserialized instanceof LocalDate val) {
			assertEquals(LocalDate.parse("2022-09-27"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldDeserializeLocalTime() {
		final Object deserialized = DataDeserializer.deserialize(new OpenAPI(), createLocalTimeSchema(), new String[]{"13:28:27.357442951"});
		if (deserialized instanceof LocalTime val) {
			assertEquals(LocalTime.parse("13:28:27.357442951"), val);
		} else {
			fail();
		}
	}

	@Test
	void shouldFailToDeserializeDataWhenUnknownStringFormatUsed() {
		final Schema<Object> stringSchema = createStringSchema();
		stringSchema.setFormat("UnknownFormat");
		final RESTApiInternalError exception = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserialize(new OpenAPI(), stringSchema, new TextNode("abc")));
		assertTrue(exception.getPublicMessage().startsWith("Unknown schema format"));
	}

	@Test
	void shouldFailToDeserializeDataWhenUnknownIntegerFormatUsed() {
		final Schema<Object> stringSchema = createIntegerSchema();
		stringSchema.setFormat("UnknownFormat");
		final RESTApiInternalError exception = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserialize(new OpenAPI(), stringSchema, new TextNode("abc")));
		assertTrue(exception.getPublicMessage().startsWith("Unknown schema format"));
	}

	@Test
	void shouldFailToDeserializeDataWhenUnknownSchemaTypeUsed() {
		final Schema<Object> unknownSchema = createSchema("unknownSchema");
		final RESTApiInternalError exception = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserialize(new OpenAPI(), unknownSchema, new TextNode("abc")));
		assertTrue(exception.getPublicMessage().startsWith("Unknown schema type"));
	}

	@Test
	void shouldFailToDeserializeArrayWhenSchemaIsNotArray() {
		final RESTApiInvalidArgumentException exception = assertThrows(RESTApiInvalidArgumentException.class, () -> DataDeserializer.deserializeArray(new OpenAPI(), createStringSchema(), new ArrayNode(JsonNodeFactory.instance)));
		assertTrue(exception.getPublicMessage().startsWith("Can't deserialize value, schema type is not array."));
	}

	@Test
	void shouldFailToDeserializeArrayWhenValueIsNotArray() {
		final RESTApiInvalidArgumentException exception = assertThrows(RESTApiInvalidArgumentException.class, () -> DataDeserializer.deserializeArray(new OpenAPI(), createArraySchemaOf(createStringSchema()), new TextNode("abc")));
		assertTrue(exception.getPrivateMessage().startsWith("Can't get array of string if JsonNode is not instance of ArrayNode."));
	}

	@Test
	void shouldDeserializeShortObject() {
		assertEquals((short) 10, DataDeserializer.deserializeObject(Short.class, new IntNode(10)));
	}

	@Test
	void shouldDeserializeLongObject() {
		assertEquals(6598754L, DataDeserializer.deserializeObject(Long.class, new TextNode("6598754")));
	}

	@Test
	void shouldDeserializeBigDecimalObject() {
		assertEquals(new BigDecimal("5142.52"), DataDeserializer.deserializeObject(BigDecimal.class, new TextNode("5142.52")));
	}

	@Test
	void shouldDeserializeBooleanObject() {
		assertEquals(true, DataDeserializer.deserializeObject(Boolean.class, BooleanNode.getTrue()));
	}

	@Test
	void shouldDeserializeCharacterObject() {
		assertEquals('H', DataDeserializer.deserializeObject(Character.class, new TextNode("H")));
	}

	@Test
	void shouldDeserializeByteObject() {
		assertEquals("8", new String(new byte[]{DataDeserializer.deserializeObject(Byte.class, new TextNode("OA=="))}));
	}

	@Test
	void shouldFailToDeserializeArrayWhenJsonNodeIsNotAnArray() {
		final RESTApiInternalError error = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserializeObject(String[].class, new TextNode("H")));
		assertTrue(error.getPrivateMessage().startsWith("Target class is array"));
	}

	@Test
	void shouldFailToDeserializeArrayWhenTryingToDeserializeUnsupportedClass() {
		final RESTApiInternalError error = assertThrows(RESTApiInternalError.class, () -> DataDeserializer.deserializeObject(TestClass.class, new TextNode("H")));
		assertTrue(error.getPrivateMessage().startsWith("Deserialization of field of JavaType"));
	}

	@Test
	void shouldDeserializeJsonNodeTreeWithSingleNode() {
		final Components components = new Components();
		final Schema<Object> myNumber = createIntegerSchema();
		myNumber.name("myNumber");
		components.addSchemas(myNumber.getName(), myNumber);
		final OpenAPI openAPI = new OpenAPI();
		openAPI.components(components);
		final Integer testedNumber = 56;
		final Object deserialized = DataDeserializer.deserializeJsonNodeTree(openAPI, myNumber, new JsonNodeFactory(false).numberNode(testedNumber));
		assertTrue(deserialized instanceof Integer);
		assertEquals(testedNumber, (Integer) deserialized);
	}

	@SuppressWarnings("unchecked")
	@Test
	void shouldDeserializeJsonNodeTreeWithNestedStructure() throws Exception {
		final Components components = new Components();
		final OpenAPI openAPI = new OpenAPI();
		openAPI.components(components);

		final Schema<Object> dataObject = createObjectSchema();
		dataObject.name("dataObject");
		dataObject.addProperty("id", createShortSchema());
		dataObject.addProperty("description", createStringSchema());
		dataObject.addProperty("locale", createLocaleSchema());
		dataObject.addProperty("properties", createArraySchemaOf(createStringSchema()));
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

		final Object deserialized = DataDeserializer.deserializeJsonNodeTree(openAPI, dataObject, dataNode);
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

		final Schema<Object> dataObject = createObjectSchema();
		dataObject.name("dataObject");
		dataObject.addProperty("id", createShortSchema());

		final JsonNodeFactory nodeFactory = new JsonNodeFactory(false);
		final ObjectNode dataNode = nodeFactory.objectNode();
		dataNode.putIfAbsent("id", nodeFactory.numberNode((short) 54));
		dataNode.putIfAbsent("description", nodeFactory.textNode("Popis"));

		assertThrows(RESTApiInvalidArgumentException.class, () -> DataDeserializer.deserializeJsonNodeTree(openAPI, dataObject, dataNode), "Invalid property name: description");
	}

	private static class TestClass implements Serializable {
	}

}