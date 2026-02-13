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

package io.evitadb.dataType.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter.SortingNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSON conversion layer: {@link ComplexDataObjectToJsonConverter} (CDO → JSON)
 * and {@link JsonToComplexDataObjectConverter} (JSON → CDO). Covers type conversion,
 * structure handling, round-trip identity, and error cases.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ComplexDataObject JSON converters")
class ComplexDataObjectJsonConverterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ObjectMapper sortingObjectMapper = new ObjectMapper()
		.setNodeFactory(new SortingNodeFactory());

	/**
	 * Converts a {@link ComplexDataObject} to a {@link JsonNode} using the given mapper.
	 */
	private JsonNode toJson(ComplexDataObject cdo, ObjectMapper mapper) {
		final ComplexDataObjectToJsonConverter converter = new ComplexDataObjectToJsonConverter(mapper);
		cdo.accept(converter);
		return converter.getRootNode();
	}

	/**
	 * Converts a {@link ComplexDataObject} to a {@link JsonNode} using the default mapper.
	 */
	private JsonNode toJson(ComplexDataObject cdo) {
		return toJson(cdo, this.objectMapper);
	}

	@Nested
	@DisplayName("ComplexDataObjectToJsonConverter")
	class ToJsonConverterTest {

		@Nested
		@DisplayName("Basic type conversion in ObjectNode context")
		class BasicTypeConversion {

			@Test
			@DisplayName("should convert Short to JSON number")
			void shouldConvertShortToJsonNumber() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue((short) 42));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isNumber());
				assertEquals(42, json.get("value").shortValue());
			}

			@Test
			@DisplayName("should convert Byte to JSON number")
			void shouldConvertByteToJsonNumber() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue((byte) 7));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isNumber());
				assertEquals(7, json.get("value").intValue());
			}

			@Test
			@DisplayName("should convert Integer to JSON number")
			void shouldConvertIntegerToJsonNumber() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(12345));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isInt());
				assertEquals(12345, json.get("value").intValue());
			}

			@Test
			@DisplayName("should convert Long to JSON string for JavaScript precision safety")
			void shouldConvertLongToJsonString() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(9007199254740992L));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isTextual());
				assertEquals("9007199254740992", json.get("value").textValue());
			}

			@Test
			@DisplayName("should convert String to JSON string")
			void shouldConvertStringToJsonString() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue("hello"));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isTextual());
				assertEquals("hello", json.get("value").textValue());
			}

			@Test
			@DisplayName("should convert BigDecimal to JSON string in plain format")
			void shouldConvertBigDecimalToJsonString() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(new BigDecimal("12345678901234567890.123456789")));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isTextual());
				assertEquals("12345678901234567890.123456789", json.get("value").textValue());
			}

			@Test
			@DisplayName("should convert Boolean to JSON boolean")
			void shouldConvertBooleanToJsonBoolean() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(true));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isBoolean());
				assertTrue(json.get("value").booleanValue());
			}

			@Test
			@DisplayName("should convert Character to JSON string")
			void shouldConvertCharacterToJsonString() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue('X'));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isTextual());
				assertEquals("X", json.get("value").textValue());
			}

			@Test
			@DisplayName("should convert Locale to JSON language tag")
			void shouldConvertLocaleToJsonLanguageTag() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(Locale.CANADA));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isTextual());
				assertEquals("en-CA", json.get("value").textValue());
			}

			@Test
			@DisplayName("should convert null value to JSON null")
			void shouldConvertNullToJsonNull() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(null));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isNull());
			}

			@Test
			@DisplayName("should convert other types via EvitaDataTypes.formatValue")
			void shouldConvertOtherTypesViaEvitaDataTypesFormat() {
				final OffsetDateTime dateTime = OffsetDateTime.of(2021, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
				final Map<String, DataItem> props = new HashMap<>();
				props.put("value", new DataItemValue(dateTime));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.get("value").isTextual());
				assertEquals(EvitaDataTypes.formatValue(dateTime), json.get("value").textValue());
			}
		}

		@Nested
		@DisplayName("Array conversion")
		class ArrayConversion {

			@Test
			@DisplayName("should convert empty DataItemArray to empty JSON array")
			void shouldConvertEmptyArrayToJsonArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[0])
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isArray());
				assertEquals(0, json.size());
			}

			@Test
			@DisplayName("should convert DataItemArray with values to JSON array")
			void shouldConvertArrayWithValuesToJsonArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{
						new DataItemValue("hello"),
						new DataItemValue(42),
						new DataItemValue(true)
					})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isArray());
				assertEquals(3, json.size());
				assertEquals("hello", json.get(0).textValue());
				assertEquals(42, json.get(1).intValue());
				assertTrue(json.get(2).booleanValue());
			}

			@Test
			@DisplayName("should convert DataItemArray with null elements to JSON array with nulls")
			void shouldConvertArrayWithNullsToJsonArrayWithNulls() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{
						new DataItemValue("A"),
						null,
						new DataItemValue("B")
					})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isArray());
				assertEquals(3, json.size());
				assertEquals("A", json.get(0).textValue());
				assertTrue(json.get(1).isNull());
				assertEquals("B", json.get(2).textValue());
			}

			@Test
			@DisplayName("should convert nested DataItemArray to nested JSON array")
			void shouldConvertNestedArrayToJsonNestedArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{
						new DataItemArray(new DataItem[]{
							new DataItemValue(1),
							new DataItemValue(2)
						}),
						new DataItemArray(new DataItem[]{
							new DataItemValue(3)
						})
					})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isArray());
				assertEquals(2, json.size());
				assertTrue(json.get(0).isArray());
				assertEquals(2, json.get(0).size());
				assertEquals(1, json.get(0).get(0).intValue());
				assertEquals(2, json.get(0).get(1).intValue());
				assertTrue(json.get(1).isArray());
				assertEquals(1, json.get(1).size());
			}

			@Test
			@DisplayName("should convert DataItemArray as root node")
			void shouldConvertArrayAsRootNode() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{
						new DataItemValue("only")
					})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isArray());
				assertEquals(1, json.size());
				assertEquals("only", json.get(0).textValue());
			}
		}

		@Nested
		@DisplayName("Map conversion")
		class MapConversion {

			@Test
			@DisplayName("should convert empty DataItemMap to empty JSON object")
			void shouldConvertEmptyMapToJsonObject() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemMap(Collections.emptyMap())
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isObject());
				assertEquals(0, json.size());
			}

			@Test
			@DisplayName("should convert DataItemMap with values to JSON object")
			void shouldConvertMapWithValuesToJsonObject() {
				final Map<String, DataItem> props = new LinkedHashMap<>();
				props.put("name", new DataItemValue("test"));
				props.put("count", new DataItemValue(5));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.isObject());
				assertEquals("test", json.get("name").textValue());
				assertEquals(5, json.get("count").intValue());
			}

			@Test
			@DisplayName("should convert DataItemMap with null value to JSON object with null")
			void shouldConvertMapWithNullValueToJsonObjectWithNull() {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("present", new DataItemValue("yes"));
				props.put("absent", null);
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final JsonNode json = toJson(cdo);
				assertTrue(json.isObject());
				assertEquals("yes", json.get("present").textValue());
				assertTrue(json.get("absent").isNull());
			}

			@Test
			@DisplayName("should convert nested DataItemMap to nested JSON object")
			void shouldConvertNestedMapToJsonNestedObject() {
				final Map<String, DataItem> inner = new HashMap<>();
				inner.put("innerKey", new DataItemValue("innerValue"));
				final Map<String, DataItem> outer = new HashMap<>();
				outer.put("nested", new DataItemMap(inner));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(outer));

				final JsonNode json = toJson(cdo);
				assertTrue(json.isObject());
				assertTrue(json.get("nested").isObject());
				assertEquals("innerValue", json.get("nested").get("innerKey").textValue());
			}

			@Test
			@DisplayName("should convert DataItemMap inside DataItemArray to JSON object inside array")
			void shouldConvertMapInsideArrayToJsonObjectInsideArray() {
				final Map<String, DataItem> mapProps = new HashMap<>();
				mapProps.put("key", new DataItemValue("value"));
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{
						new DataItemMap(mapProps),
						new DataItemValue("plain")
					})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.isArray());
				assertEquals(2, json.size());
				assertTrue(json.get(0).isObject());
				assertEquals("value", json.get(0).get("key").textValue());
				assertEquals("plain", json.get(1).textValue());
			}
		}

		@Nested
		@DisplayName("Value types in ArrayNode context")
		class ArrayValueTypes {

			@Test
			@DisplayName("should add Short value to array as number")
			void shouldAddShortToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue((short) 10)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isNumber());
				assertEquals(10, json.get(0).shortValue());
			}

			@Test
			@DisplayName("should add Byte value to array as number")
			void shouldAddByteToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue((byte) 3)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isNumber());
				assertEquals(3, json.get(0).intValue());
			}

			@Test
			@DisplayName("should add Integer value to array as number")
			void shouldAddIntegerToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue(999)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isInt());
				assertEquals(999, json.get(0).intValue());
			}

			@Test
			@DisplayName("should add Long value to array as string")
			void shouldAddLongAsStringToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue(123456789012345L)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isTextual());
				assertEquals("123456789012345", json.get(0).textValue());
			}

			@Test
			@DisplayName("should add BigDecimal value to array as string")
			void shouldAddBigDecimalAsStringToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue(new BigDecimal("99.99"))})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isTextual());
				assertEquals("99.99", json.get(0).textValue());
			}

			@Test
			@DisplayName("should add Boolean value to array")
			void shouldAddBooleanToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue(false)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isBoolean());
				assertFalse(json.get(0).booleanValue());
			}

			@Test
			@DisplayName("should add Character value to array as string")
			void shouldAddCharacterAsStringToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue('Z')})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isTextual());
				assertEquals("Z", json.get(0).textValue());
			}

			@Test
			@DisplayName("should add Locale value to array as language tag")
			void shouldAddLocaleAsLanguageTagToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue(Locale.FRANCE)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isTextual());
				assertEquals("fr-FR", json.get(0).textValue());
			}

			@Test
			@DisplayName("should add null value to array")
			void shouldAddNullToArray() {
				final ComplexDataObject cdo = new ComplexDataObject(
					new DataItemArray(new DataItem[]{new DataItemValue(null)})
				);

				final JsonNode json = toJson(cdo);
				assertTrue(json.get(0).isNull());
			}
		}

		@Nested
		@DisplayName("SortingNodeFactory")
		class SortingNodeFactoryTest {

			@Test
			@DisplayName("should produce alphabetically sorted JSON keys")
			void shouldProduceSortedJsonKeys() throws JsonProcessingException {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("zebra", new DataItemValue("z"));
				props.put("apple", new DataItemValue("a"));
				props.put("mango", new DataItemValue("m"));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final ComplexDataObjectToJsonConverter converter =
					new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);
				cdo.accept(converter);
				final String jsonString = converter.getJsonAsString();

				final int appleIdx = jsonString.indexOf("\"apple\"");
				final int mangoIdx = jsonString.indexOf("\"mango\"");
				final int zebraIdx = jsonString.indexOf("\"zebra\"");

				assertTrue(appleIdx < mangoIdx, "apple should come before mango");
				assertTrue(mangoIdx < zebraIdx, "mango should come before zebra");
			}
		}

		@Nested
		@DisplayName("getJsonAsString")
		class GetJsonAsStringTest {

			@Test
			@DisplayName("should return pretty-printed JSON")
			void shouldReturnPrettyPrintedJson() throws JsonProcessingException {
				final Map<String, DataItem> props = new HashMap<>();
				props.put("key", new DataItemValue("value"));
				final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));

				final ComplexDataObjectToJsonConverter converter =
					new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.objectMapper);
				cdo.accept(converter);
				final String jsonString = converter.getJsonAsString();

				assertNotNull(jsonString);
				assertTrue(jsonString.contains("\n"), "Pretty-printed JSON should contain newlines");
				assertTrue(jsonString.contains("\"key\""), "JSON should contain the key");
				assertTrue(jsonString.contains("\"value\""), "JSON should contain the value");
			}

			@Test
			@DisplayName("should return null root node before any visit")
			void shouldReturnNullWhenRootNotSet() {
				final ComplexDataObjectToJsonConverter converter =
					new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.objectMapper);
				assertNull(converter.getRootNode());
			}
		}

		@Nested
		@DisplayName("Error cases")
		class ErrorCases {

			@Test
			@DisplayName("should throw when DataItemValue is visited as first item")
			void shouldThrowWhenValueItemIsRoot() {
				final ComplexDataObjectToJsonConverter converter =
					new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.objectMapper);
				final DataItemValue value = new DataItemValue("root");

				assertThrows(IllegalStateException.class, () -> converter.visit(value));
			}
		}
	}

	@Nested
	@DisplayName("JsonToComplexDataObjectConverter")
	class FromJsonConverterTest {

		private final JsonToComplexDataObjectConverter converter =
			new JsonToComplexDataObjectConverter(ComplexDataObjectJsonConverterTest.this.objectMapper);

		@Nested
		@DisplayName("fromJson(String) parsing")
		class FromJsonString {

			@Test
			@DisplayName("should parse simple JSON object")
			void shouldParseJsonObjectToComplexDataObject() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{\"key\":\"value\"}");

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				assertInstanceOf(DataItemValue.class, root.getProperty("key"));
				assertEquals("value", ((DataItemValue) root.getProperty("key")).value());
			}

			@Test
			@DisplayName("should parse JSON array")
			void shouldParseJsonArrayToComplexDataObject() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("[1, 2, 3]");

				assertInstanceOf(DataItemArray.class, cdo.root());
				final DataItemArray root = (DataItemArray) cdo.root();
				assertEquals(3, root.children().length);
				assertEquals(1, ((DataItemValue) root.children()[0]).value());
				assertEquals(2, ((DataItemValue) root.children()[1]).value());
				assertEquals(3, ((DataItemValue) root.children()[2]).value());
			}

			@Test
			@DisplayName("should parse nested JSON with objects and arrays")
			void shouldParseNestedJsonToComplexDataObject() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson(
					"{\"items\":[{\"name\":\"A\"},{\"name\":\"B\"}],\"count\":2}"
				);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();

				final DataItemArray items = (DataItemArray) root.getProperty("items");
				assertNotNull(items);
				assertEquals(2, items.children().length);

				final DataItemMap firstItem = (DataItemMap) items.children()[0];
				assertEquals("A", ((DataItemValue) firstItem.getProperty("name")).value());

				assertEquals(2, ((DataItemValue) root.getProperty("count")).value());
			}

			@Test
			@DisplayName("should parse boolean values")
			void shouldParseBooleanValues() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{\"flag\":true}");

				final DataItemMap root = (DataItemMap) cdo.root();
				assertEquals(true, ((DataItemValue) root.getProperty("flag")).value());
			}

			@Test
			@DisplayName("should parse integer values")
			void shouldParseIntegerValues() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{\"num\":42}");

				final DataItemMap root = (DataItemMap) cdo.root();
				final Object value = ((DataItemValue) root.getProperty("num")).value();
				assertInstanceOf(Integer.class, value);
				assertEquals(42, value);
			}

			@Test
			@DisplayName("should parse long values beyond integer range")
			void shouldParseLongValues() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{\"big\":9007199254740992}");

				final DataItemMap root = (DataItemMap) cdo.root();
				final Object value = ((DataItemValue) root.getProperty("big")).value();
				assertInstanceOf(Long.class, value);
				assertEquals(9007199254740992L, value);
			}

			@Test
			@DisplayName("should parse string values")
			void shouldParseStringValues() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{\"text\":\"hello world\"}");

				final DataItemMap root = (DataItemMap) cdo.root();
				assertEquals("hello world", ((DataItemValue) root.getProperty("text")).value());
			}

			@Test
			@DisplayName("should parse null values as null DataItem")
			void shouldParseNullValues() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{\"empty\":null}");

				final DataItemMap root = (DataItemMap) cdo.root();
				assertNull(root.getProperty("empty"));
			}

			@Test
			@DisplayName("should parse empty JSON object")
			void shouldParseEmptyObject() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("{}");

				assertInstanceOf(DataItemMap.class, cdo.root());
				assertTrue(cdo.root().isEmpty());
			}

			@Test
			@DisplayName("should parse empty JSON array")
			void shouldParseEmptyArray() throws JsonProcessingException {
				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromJson("[]");

				assertInstanceOf(DataItemArray.class, cdo.root());
				assertEquals(0, ((DataItemArray) cdo.root()).children().length);
			}
		}

		@Nested
		@DisplayName("fromMap(Map) conversion")
		class FromMap {

			@Test
			@DisplayName("should convert simple Map to ComplexDataObject")
			void shouldConvertSimpleMapToComplexDataObject() throws JsonProcessingException {
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("name", "test");
				map.put("count", 5);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				assertEquals("test", ((DataItemValue) root.getProperty("name")).value());
				assertEquals(5, ((DataItemValue) root.getProperty("count")).value());
			}

			@Test
			@DisplayName("should convert nested Map to ComplexDataObject")
			void shouldConvertNestedMapToComplexDataObject() throws JsonProcessingException {
				final Map<String, Object> inner = Map.of("innerKey", "innerValue");
				final Map<String, Object> outer = Map.of("nested", inner);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(outer);

				final DataItemMap root = (DataItemMap) cdo.root();
				assertInstanceOf(DataItemMap.class, root.getProperty("nested"));
				final DataItemMap nestedMap = (DataItemMap) root.getProperty("nested");
				assertEquals("innerValue", ((DataItemValue) nestedMap.getProperty("innerKey")).value());
			}

			@Test
			@DisplayName("should convert Map with List values to ComplexDataObject")
			void shouldConvertMapWithListValues() throws JsonProcessingException {
				final Map<String, Object> map = Map.of("items", List.of("a", "b", "c"));

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				final DataItemMap root = (DataItemMap) cdo.root();
				assertInstanceOf(DataItemArray.class, root.getProperty("items"));
				final DataItemArray items = (DataItemArray) root.getProperty("items");
				assertEquals(3, items.children().length);
			}

			@Test
			@DisplayName("should convert Map with BigDecimal value to ComplexDataObject")
			void shouldConvertMapWithBigDecimalValue() throws JsonProcessingException {
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("price", new BigDecimal("123.456"));

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				final DataItemValue priceValue = (DataItemValue) root.getProperty("price");
				assertNotNull(priceValue, "BigDecimal property should be present");
				assertInstanceOf(BigDecimal.class, priceValue.value());
				assertEquals(new BigDecimal("123.456"), priceValue.value());
			}

			@Test
			@DisplayName("should convert Map with negative Double value to BigDecimal")
			void shouldConvertMapWithNegativeDoubleValue() throws JsonProcessingException {
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("temperature", -3.14);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				final DataItemValue tempValue = (DataItemValue) root.getProperty("temperature");
				assertNotNull(tempValue, "Double property should be present");
				// negative double values should be stored as BigDecimal, not String
				assertInstanceOf(
					BigDecimal.class, tempValue.value(),
					"Negative double should be converted to BigDecimal, not " +
						tempValue.value().getClass().getSimpleName()
				);
			}

			@Test
			@DisplayName("should convert Map with positive Double value to BigDecimal")
			void shouldConvertMapWithPositiveDoubleValue() throws JsonProcessingException {
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("weight", 75.5);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				final DataItemValue weightValue = (DataItemValue) root.getProperty("weight");
				assertNotNull(weightValue, "Double property should be present");
				// positive double values should also be stored as BigDecimal, not String
				assertInstanceOf(
					BigDecimal.class, weightValue.value(),
					"Positive double should be converted to BigDecimal, not " +
						weightValue.value().getClass().getSimpleName()
				);
			}

			@Test
			@DisplayName("should convert Map with negative integer value to Long")
			void shouldConvertMapWithNegativeIntegerAsLong() throws JsonProcessingException {
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("offset", -42L);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				final DataItemValue offsetValue = (DataItemValue) root.getProperty("offset");
				assertNotNull(offsetValue, "Long property should be present");
				// Jackson creates LongNode for Long values which is handled by isLong()
				// so this should work already -- it goes through the isLong() branch
				assertEquals(-42L, offsetValue.value());
			}

			@Test
			@DisplayName("should convert Map with Float value to BigDecimal")
			void shouldConvertMapWithFloatValue() throws JsonProcessingException {
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("ratio", 0.5f);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				final DataItemValue ratioValue = (DataItemValue) root.getProperty("ratio");
				assertNotNull(ratioValue, "Float property should be present");
				// float values should be stored as BigDecimal, not String
				assertInstanceOf(
					BigDecimal.class, ratioValue.value(),
					"Float should be converted to BigDecimal, not " +
						ratioValue.value().getClass().getSimpleName()
				);
			}

			@Test
			@DisplayName("should convert Map with BigInteger value exceeding Long range to numeric type")
			void shouldConvertMapWithBigIntegerToNumericType() throws JsonProcessingException {
				final BigInteger hugeValue = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
				final Map<String, Object> map = new LinkedHashMap<>();
				map.put("huge", hugeValue);

				final ComplexDataObject cdo = FromJsonConverterTest.this.converter.fromMap(map);

				assertInstanceOf(DataItemMap.class, cdo.root());
				final DataItemMap root = (DataItemMap) cdo.root();
				final DataItemValue hugeDataValue = (DataItemValue) root.getProperty("huge");
				assertNotNull(hugeDataValue, "BigInteger property should be present");
				// BigInteger exceeding Long range should be stored as BigDecimal, not String
				assertInstanceOf(
					BigDecimal.class, hugeDataValue.value(),
					"BigInteger exceeding Long range should be converted to BigDecimal, not " +
						hugeDataValue.value().getClass().getSimpleName()
				);
				assertEquals(
					new BigDecimal(hugeValue),
					hugeDataValue.value()
				);
			}
		}

		@Nested
		@DisplayName("Error cases")
		class ErrorCases {

			@Test
			@DisplayName("should throw on invalid JSON syntax")
			void shouldThrowOnInvalidJsonSyntax() {
				assertThrows(
					JsonProcessingException.class,
					() -> FromJsonConverterTest.this.converter.fromJson("{invalid json}")
				);
			}
		}
	}

	@Nested
	@DisplayName("Round-trip: CDO → JSON → CDO")
	class RoundTripTest {

		private final JsonToComplexDataObjectConverter fromJsonConverter =
			new JsonToComplexDataObjectConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);

		@Test
		@DisplayName("should round-trip simple DataItemMap through JSON")
		void shouldRoundTripSimpleMapThroughJson() throws JsonProcessingException {
			final Map<String, DataItem> props = new LinkedHashMap<>();
			props.put("name", new DataItemValue("test"));
			props.put("count", new DataItemValue(42));
			props.put("active", new DataItemValue(true));
			final ComplexDataObject original = new ComplexDataObject(new DataItemMap(props));

			final ComplexDataObjectToJsonConverter toJsonConverter =
				new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);
			original.accept(toJsonConverter);
			final String jsonString = toJsonConverter.getJsonAsString();

			final ComplexDataObject restored = RoundTripTest.this.fromJsonConverter.fromJson(jsonString);

			// verify structure matches
			assertInstanceOf(DataItemMap.class, restored.root());
			final DataItemMap restoredRoot = (DataItemMap) restored.root();
			assertEquals("test", ((DataItemValue) restoredRoot.getProperty("name")).value());
			assertEquals(42, ((DataItemValue) restoredRoot.getProperty("count")).value());
			assertEquals(true, ((DataItemValue) restoredRoot.getProperty("active")).value());
		}

		@Test
		@DisplayName("should round-trip DataItemArray through JSON")
		void shouldRoundTripArrayThroughJson() throws JsonProcessingException {
			final ComplexDataObject original = new ComplexDataObject(
				new DataItemArray(new DataItem[]{
					new DataItemValue("hello"),
					new DataItemValue(42),
					new DataItemValue(true)
				})
			);

			final ComplexDataObjectToJsonConverter toJsonConverter =
				new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);
			original.accept(toJsonConverter);
			final String jsonString = toJsonConverter.getJsonAsString();

			final ComplexDataObject restored = RoundTripTest.this.fromJsonConverter.fromJson(jsonString);

			assertInstanceOf(DataItemArray.class, restored.root());
			final DataItemArray restoredRoot = (DataItemArray) restored.root();
			assertEquals(3, restoredRoot.children().length);
			assertEquals("hello", ((DataItemValue) restoredRoot.children()[0]).value());
			assertEquals(42, ((DataItemValue) restoredRoot.children()[1]).value());
			assertEquals(true, ((DataItemValue) restoredRoot.children()[2]).value());
		}

		@Test
		@DisplayName("should round-trip nested structure through JSON")
		void shouldRoundTripNestedStructureThroughJson() throws JsonProcessingException {
			final Map<String, DataItem> innerProps = new HashMap<>();
			innerProps.put("innerKey", new DataItemValue("innerValue"));

			final Map<String, DataItem> outerProps = new LinkedHashMap<>();
			outerProps.put("nested", new DataItemMap(innerProps));
			outerProps.put("items", new DataItemArray(new DataItem[]{
				new DataItemValue("a"),
				new DataItemValue("b")
			}));
			final ComplexDataObject original = new ComplexDataObject(new DataItemMap(outerProps));

			final ComplexDataObjectToJsonConverter toJsonConverter =
				new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);
			original.accept(toJsonConverter);
			final String jsonString = toJsonConverter.getJsonAsString();

			final ComplexDataObject restored = RoundTripTest.this.fromJsonConverter.fromJson(jsonString);

			final DataItemMap restoredRoot = (DataItemMap) restored.root();
			assertInstanceOf(DataItemMap.class, restoredRoot.getProperty("nested"));
			final DataItemMap nestedMap = (DataItemMap) restoredRoot.getProperty("nested");
			assertEquals("innerValue", ((DataItemValue) nestedMap.getProperty("innerKey")).value());

			assertInstanceOf(DataItemArray.class, restoredRoot.getProperty("items"));
			final DataItemArray items = (DataItemArray) restoredRoot.getProperty("items");
			assertEquals(2, items.children().length);
		}

		@Test
		@DisplayName("should round-trip null values through JSON")
		void shouldRoundTripNullValuesThroughJson() throws JsonProcessingException {
			final Map<String, DataItem> props = new LinkedHashMap<>();
			props.put("present", new DataItemValue("yes"));
			props.put("absent", null);
			final ComplexDataObject original = new ComplexDataObject(new DataItemMap(props));

			final ComplexDataObjectToJsonConverter toJsonConverter =
				new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);
			original.accept(toJsonConverter);
			final String jsonString = toJsonConverter.getJsonAsString();

			final ComplexDataObject restored = RoundTripTest.this.fromJsonConverter.fromJson(jsonString);

			final DataItemMap restoredRoot = (DataItemMap) restored.root();
			assertEquals("yes", ((DataItemValue) restoredRoot.getProperty("present")).value());
			// JSON null → null DataItem (absent from the map)
			assertNull(restoredRoot.getProperty("absent"));
		}

		@Test
		@DisplayName("should note that Long values become strings in JSON and parse back as strings")
		void shouldConvertLongToStringAndBackAsString() throws JsonProcessingException {
			final Map<String, DataItem> props = new HashMap<>();
			props.put("bigNumber", new DataItemValue(9007199254740992L));
			final ComplexDataObject original = new ComplexDataObject(new DataItemMap(props));

			final ComplexDataObjectToJsonConverter toJsonConverter =
				new ComplexDataObjectToJsonConverter(ComplexDataObjectJsonConverterTest.this.sortingObjectMapper);
			original.accept(toJsonConverter);
			final String jsonString = toJsonConverter.getJsonAsString();

			// Long is serialized as JSON string, so when parsed back it remains a string
			// (unless it matches the LONG_NUMBER pattern which it does since "9007199254740992" is all digits)
			final ComplexDataObject restored = RoundTripTest.this.fromJsonConverter.fromJson(jsonString);

			final DataItemMap restoredRoot = (DataItemMap) restored.root();
			final DataItemValue restoredValue = (DataItemValue) restoredRoot.getProperty("bigNumber");
			// the value should be textual in JSON, but Jackson will parse it as text node
			// and then the converter checks if it's a LONG_NUMBER pattern match
			assertNotNull(restoredValue);
			// It comes back as a String because it's a JSON string (quoted)
			assertEquals("9007199254740992", restoredValue.value());
		}
	}
}
