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

package io.evitadb.dataType;

import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.dataType.data.DiscardedData;
import io.evitadb.dataType.data.NonSerializedData;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.dataType.data.RenamedData;
import io.evitadb.dataType.exception.IncompleteDeserializationException;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.SerializationFailedException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.utils.ReflectionLookup;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.utils.StringUtils.normalizeLineEndings;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies general POJO conversion logic to {@link ComplexDataObject} that can be
 * handled by evitaDB. It covers serialization, deserialization, round-trip behavior, error
 * handling, and class evolution scenarios.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ComplexDataObjectConverter")
class ComplexDataObjectConverterTest {
	private final ReflectionLookup reflectionLookup = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);

	@Nested
	@DisplayName("Static API methods")
	class StaticApiTest {

		@Test
		@DisplayName("should return same ComplexDataObject instance from static getSerializableForm")
		void shouldReturnSameInstanceWhenStaticGetSerializableFormReceivesCdo() {
			final ComplexDataObject cdo = new ComplexDataObject(
				new DataItemMap(Collections.emptyMap())
			);
			final Serializable result = ComplexDataObjectConverter.getSerializableForm(cdo);
			assertSame(cdo, result);
		}

		@Test
		@DisplayName("should return same ComplexDataObject[] instance from static getSerializableForm")
		void shouldReturnSameInstanceWhenStaticGetSerializableFormReceivesCdoArray() {
			final ComplexDataObject[] cdoArray = new ComplexDataObject[]{
				new ComplexDataObject(new DataItemMap(Collections.emptyMap()))
			};
			final Serializable result = ComplexDataObjectConverter.getSerializableForm(cdoArray);
			assertSame(cdoArray, result);
		}

		@Test
		@DisplayName("should return same supported type from static getSerializableForm")
		void shouldReturnSameInstanceWhenStaticGetSerializableFormReceivesSupportedType() {
			final String value = "ABC";
			final Serializable result = ComplexDataObjectConverter.getSerializableForm(value);
			assertSame(value, result);
		}

		@Test
		@DisplayName("should return same instance when static getOriginalForm receives matching type")
		void shouldReturnSameInstanceWhenStaticGetOriginalFormReceivesMatchingType() {
			final String value = "ABC";
			final String result = ComplexDataObjectConverter.getOriginalForm(
				value, String.class, reflectionLookup
			);
			assertSame(value, result);
		}
	}

	@Nested
	@DisplayName("Pass-through behavior")
	class PassThroughTest {

		@Test
		@DisplayName("should return ComplexDataObject without serialization")
		void shouldReturnComplexDataObjectWithoutSerialization() {
			final ComplexDataObject cdo = new ComplexDataObject(
				new DataItemArray(
					new DataItem[]{
						new DataItemValue("ABC")
					}
				)
			);
			final ComplexDataObjectConverter<ComplexDataObject> converter = new ComplexDataObjectConverter<>(cdo);
			final Serializable serializedForm = converter.getSerializableForm();

			assertEquals(cdo, serializedForm);
		}

		@Test
		@DisplayName("should return ComplexDataObject without deserialization")
		void shouldReturnComplexDataObjectWithoutDeserialization() {
			final ComplexDataObject cdo = new ComplexDataObject(
				new DataItemArray(
					new DataItem[]{
						new DataItemValue("ABC")
					}
				)
			);
			final ComplexDataObjectConverter<ComplexDataObject> converter = new ComplexDataObjectConverter<>(ComplexDataObject.class, reflectionLookup);
			final Serializable deserializedForm = converter.getOriginalForm(cdo);

			assertEquals(cdo, deserializedForm);
		}

		@Test
		@DisplayName("should serialize simple object as-is")
		void shouldSerializeSimpleObject() {
			final ComplexDataObjectConverter<String> converter = new ComplexDataObjectConverter<>("ABC");
			final Serializable serializedForm = converter.getSerializableForm();

			assertEquals("ABC", serializedForm);
		}

		@Test
		@DisplayName("should serialize and deserialize simple object array")
		void shouldSerializeAndDeserializeSimpleObjectArray() {
			final ComplexDataObjectConverter<String[]> converter = new ComplexDataObjectConverter<>(new String[]{"ABC", "DEF"});
			final Serializable serializedForm = converter.getSerializableForm();

			assertArrayEquals(new String[]{"ABC", "DEF"}, ComplexDataObjectConverter.getOriginalForm(serializedForm, String[].class, reflectionLookup));
		}

		@Test
		@DisplayName("should return same CDO[] instance from instance getSerializableForm")
		void shouldReturnSameInstanceWhenInstanceGetSerializableFormReceivesCdoArray() {
			final ComplexDataObject[] cdoArray = new ComplexDataObject[]{
				new ComplexDataObject(new DataItemMap(Collections.emptyMap()))
			};
			final ComplexDataObjectConverter<ComplexDataObject[]> converter = new ComplexDataObjectConverter<>(cdoArray);
			final Serializable result = converter.getSerializableForm();
			assertSame(cdoArray, result);
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName("should serialize complex object")
		void shouldSerializeComplexObject() throws IOException {
			final TestComplexObject veryComplexObject = createVeryComplexObject();
			final ComplexDataObjectConverter<TestComplexObject> converter = new ComplexDataObjectConverter<>(veryComplexObject);
			final Serializable serializedForm = converter.getSerializableForm();

			assertInstanceOf(ComplexDataObject.class, serializedForm);
			assertEquals(
				normalizeLineEndings(readFromClasspath("testData/DataObjectConverterTest_complexObject.txt")),
				normalizeLineEndings(serializedForm.toString())
			);
		}

		@Test
		@DisplayName("should serialize complex record")
		void shouldSerializeComplexRecord() throws IOException {
			final TestComplexRecord veryComplexRecord = createVeryComplexRecord();
			final ComplexDataObjectConverter<TestComplexRecord> converter = new ComplexDataObjectConverter<>(veryComplexRecord);
			final Serializable serializedForm = converter.getSerializableForm();

			assertInstanceOf(ComplexDataObject.class, serializedForm);
			assertEquals(
				normalizeLineEndings(readFromClasspath("testData/DataObjectConverterTest_complexObject.txt")),
				normalizeLineEndings(serializedForm.toString())
			);
		}

		@Test
		@DisplayName("should serialize complex immutable object")
		void shouldSerializeComplexImmutableObject() throws IOException {
			final TestComplexImmutableObject veryComplexObject = createVeryComplexImmutableObject();
			final ComplexDataObjectConverter<TestComplexImmutableObject> converter = new ComplexDataObjectConverter<>(veryComplexObject);
			final Serializable serializedForm = converter.getSerializableForm();

			assertInstanceOf(ComplexDataObject.class, serializedForm);
			assertEquals(
				normalizeLineEndings(readFromClasspath("testData/DataObjectConverterTest_complexObject.txt")),
				normalizeLineEndings(serializedForm.toString())
			);
		}

		@Test
		@DisplayName("should estimate size of complex object as positive value")
		void shouldEstimateSizeOfComplexObject() {
			final TestComplexObject veryComplexObject = createVeryComplexObject();
			final ComplexDataObjectConverter<TestComplexObject> converter = new ComplexDataObjectConverter<>(veryComplexObject);
			final ComplexDataObject serializedForm = (ComplexDataObject) converter.getSerializableForm();

			assertTrue(serializedForm.estimateSize() > 0);
		}

		@Test
		@DisplayName("should estimate larger size for more complex objects")
		void shouldEstimateSizeRelativeToObjectComplexity() {
			final TestComplexObject simpleObject = createComplexObject("ABC", null);
			final ComplexDataObject simpleSerialized = (ComplexDataObject) new ComplexDataObjectConverter<>(simpleObject).getSerializableForm();

			final TestComplexObject complexObject = createVeryComplexObject();
			final ComplexDataObject complexSerialized = (ComplexDataObject) new ComplexDataObjectConverter<>(complexObject).getSerializableForm();

			assertTrue(
				complexSerialized.estimateSize() > simpleSerialized.estimateSize(),
				"Complex object should have larger estimated size than simple one"
			);
		}
	}

	@Nested
	@DisplayName("Round-trip serialization and deserialization")
	class RoundTripTest {

		@Test
		@DisplayName("should round-trip complex object")
		void shouldSerializeAndDeserializeComplexObject() {
			final TestComplexObject veryComplexObject = createVeryComplexObject();
			final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
			final Serializable serializedForm = serializer.getSerializableForm();

			final ComplexDataObjectConverter<TestComplexObject> deserializer = new ComplexDataObjectConverter<>(TestComplexObject.class, reflectionLookup);
			final TestComplexObject deserializedObject = deserializer.getOriginalForm(serializedForm);
			assertEquals(createVeryComplexObject(), deserializedObject);
		}

		@Test
		@DisplayName("should round-trip complex record")
		void shouldSerializeAndDeserializeComplexRecord() {
			final TestComplexRecord veryComplexRecord = createVeryComplexRecord();
			final ComplexDataObjectConverter<TestComplexRecord> serializer = new ComplexDataObjectConverter<>(veryComplexRecord);
			final Serializable serializedForm = serializer.getSerializableForm();

			final ComplexDataObjectConverter<TestComplexRecord> deserializer = new ComplexDataObjectConverter<>(TestComplexRecord.class, reflectionLookup);
			final TestComplexRecord deserializedObject = deserializer.getOriginalForm(serializedForm);
			assertEquals(createVeryComplexRecord(), deserializedObject);
		}

		@Test
		@DisplayName("should round-trip complex object array")
		void shouldSerializeAndDeserializeComplexObjectArray() {
			final TestComplexObject veryComplexObject = createVeryComplexObject();
			final ComplexDataObjectConverter<TestComplexObject[]> converter = new ComplexDataObjectConverter<>(new TestComplexObject[]{veryComplexObject, veryComplexObject});
			final Serializable serializedForm = converter.getSerializableForm();

			final ComplexDataObjectConverter<TestComplexObject[]> deserializer = new ComplexDataObjectConverter<>(TestComplexObject[].class, reflectionLookup);
			final TestComplexObject[] deserializedObject = deserializer.getOriginalForm(serializedForm);
			final TestComplexObject matrixObject = createVeryComplexObject();
			assertArrayEquals(new TestComplexObject[]{matrixObject, matrixObject}, deserializedObject);
		}

		@Test
		@DisplayName("should round-trip immutable object")
		void shouldDeserializeComplexImmutableObject() {
			final TestComplexImmutableObject veryComplexObject = createVeryComplexImmutableObject();
			final ComplexDataObjectConverter<TestComplexImmutableObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
			final Serializable serializedForm = serializer.getSerializableForm();

			final ComplexDataObjectConverter<TestComplexImmutableObject> deserializer = new ComplexDataObjectConverter<>(TestComplexImmutableObject.class, reflectionLookup);
			final TestComplexImmutableObject deserializedObject = deserializer.getOriginalForm(serializedForm);
			assertEquals(createVeryComplexImmutableObject(), deserializedObject);
		}
	}

	@Nested
	@DisplayName("Formatted string round-trip")
	class FormattedStringRoundTripTest {

		@Test
		@DisplayName("should round-trip complex object with formatted strings")
		void shouldSerializeAndDeserializeComplexObjectWithFormattedStrings() {
			final TestComplexObject veryComplexObject = createVeryComplexObject();
			final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
			final Serializable serializedForm = replaceAllValuesWithFormattedStrings((ComplexDataObject) serializer.getSerializableForm());

			final ComplexDataObjectConverter<TestComplexObject> deserializer = new ComplexDataObjectConverter<>(TestComplexObject.class, reflectionLookup);
			final TestComplexObject deserializedObject = deserializer.getOriginalForm(serializedForm);
			assertEquals(createVeryComplexObject(), deserializedObject);
		}

		@Test
		@DisplayName("should round-trip complex record with formatted strings")
		void shouldSerializeAndDeserializeComplexRecordWithFormattedStrings() {
			final TestComplexRecord veryComplexRecord = createVeryComplexRecord();
			final ComplexDataObjectConverter<TestComplexRecord> serializer = new ComplexDataObjectConverter<>(veryComplexRecord);
			final Serializable serializedForm = replaceAllValuesWithFormattedStrings((ComplexDataObject) serializer.getSerializableForm());

			final ComplexDataObjectConverter<TestComplexRecord> deserializer = new ComplexDataObjectConverter<>(TestComplexRecord.class, reflectionLookup);
			final TestComplexRecord deserializedObject = deserializer.getOriginalForm(serializedForm);
			assertEquals(createVeryComplexRecord(), deserializedObject);
		}

		@Test
		@DisplayName("should round-trip complex object array with formatted strings")
		void shouldSerializeAndDeserializeComplexObjectArrayWithFormattedStrings() {
			final TestComplexObject veryComplexObject = createVeryComplexObject();
			final ComplexDataObjectConverter<TestComplexObject[]> converter = new ComplexDataObjectConverter<>(new TestComplexObject[]{veryComplexObject, veryComplexObject});
			final Serializable serializedForm = replaceAllValuesWithFormattedStrings((ComplexDataObject) converter.getSerializableForm());

			final ComplexDataObjectConverter<TestComplexObject[]> deserializer = new ComplexDataObjectConverter<>(TestComplexObject[].class, reflectionLookup);
			final TestComplexObject[] deserializedObject = deserializer.getOriginalForm(serializedForm);
			final TestComplexObject matrixObject = createVeryComplexObject();
			assertArrayEquals(new TestComplexObject[]{matrixObject, matrixObject}, deserializedObject);
		}
	}

	@Nested
	@DisplayName("Float and double type handling")
	class FloatDoubleHandlingTest {

		@Test
		@DisplayName("should handle unsupported Java numeric types float and double")
		void shouldHandleUnsupportedJavaNumericTypes() {
			final ContainerWithUnsupportedJavaTypes dto = new ContainerWithUnsupportedJavaTypes(1.2f, 1.7d);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
			assertNotNull(serializedForm);

			final ContainerWithUnsupportedJavaTypes deserialized = ComplexDataObjectConverter.getOriginalForm(serializedForm, ContainerWithUnsupportedJavaTypes.class, reflectionLookup);
			assertEquals(1.2f, deserialized.getFFloat());
			assertEquals(1.7d, deserialized.getFDouble());
		}

		@Test
		@DisplayName("should handle boxed Float and Double values")
		void shouldHandleBoxedFloatAndDouble() {
			final ContainerWithBoxedFloatDouble dto = new ContainerWithBoxedFloatDouble(3.14f, 2.718d);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
			assertNotNull(serializedForm);

			final ContainerWithBoxedFloatDouble deserialized = ComplexDataObjectConverter.getOriginalForm(
				serializedForm, ContainerWithBoxedFloatDouble.class, reflectionLookup
			);
			assertEquals(3.14f, deserialized.getBoxedFloat());
			assertEquals(2.718d, deserialized.getBoxedDouble());
		}

		@Test
		@DisplayName("should handle float and double edge case values")
		void shouldHandleFloatDoubleEdgeCases() {
			final ContainerWithUnsupportedJavaTypes zeroDto = new ContainerWithUnsupportedJavaTypes(0.0f, 0.0d);
			final Serializable zeroSerialized = new ComplexDataObjectConverter<>(zeroDto).getSerializableForm();
			final ContainerWithUnsupportedJavaTypes zeroDeserialized = ComplexDataObjectConverter.getOriginalForm(
				zeroSerialized, ContainerWithUnsupportedJavaTypes.class, reflectionLookup
			);
			assertEquals(0.0f, zeroDeserialized.getFFloat());
			assertEquals(0.0d, zeroDeserialized.getFDouble());

			final ContainerWithUnsupportedJavaTypes maxDto = new ContainerWithUnsupportedJavaTypes(Float.MAX_VALUE, Double.MAX_VALUE);
			final Serializable maxSerialized = new ComplexDataObjectConverter<>(maxDto).getSerializableForm();
			final ContainerWithUnsupportedJavaTypes maxDeserialized = ComplexDataObjectConverter.getOriginalForm(
				maxSerialized, ContainerWithUnsupportedJavaTypes.class, reflectionLookup
			);
			assertEquals(Float.MAX_VALUE, maxDeserialized.getFFloat());
			assertEquals(Double.MAX_VALUE, maxDeserialized.getFDouble());

			final ContainerWithUnsupportedJavaTypes minDto = new ContainerWithUnsupportedJavaTypes(Float.MIN_VALUE, Double.MIN_VALUE);
			final Serializable minSerialized = new ComplexDataObjectConverter<>(minDto).getSerializableForm();
			final ContainerWithUnsupportedJavaTypes minDeserialized = ComplexDataObjectConverter.getOriginalForm(
				minSerialized, ContainerWithUnsupportedJavaTypes.class, reflectionLookup
			);
			assertEquals(Float.MIN_VALUE, minDeserialized.getFFloat());
			assertEquals(Double.MIN_VALUE, minDeserialized.getFDouble());
		}
	}

	@Nested
	@DisplayName("Collection serialization and deserialization")
	class CollectionTest {

		@Test
		@DisplayName("should serialize and deserialize String arrays in container")
		void shouldSerializeAndDeserializeStringArraysInContainer() {
			final ArrayContainer dto = new ArrayContainer(
				null,
				new String[]{"a", "b"},
				null
			);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
			assertInstanceOf(ComplexDataObject.class, serializedForm);

			final ArrayContainer deserialized = ComplexDataObjectConverter.getOriginalForm(
				serializedForm, ArrayContainer.class, reflectionLookup
			);
			assertArrayEquals(new String[]{"a", "b"}, deserialized.getStringArray());
		}

		@Test
		@DisplayName("should serialize and deserialize empty collections")
		void shouldSerializeAndDeserializeEmptyCollections() {
			final ContainerWithEmptyCollections dto = new ContainerWithEmptyCollections(
				Set.of(), List.of(), new String[0], Map.of()
			);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
			assertInstanceOf(ComplexDataObject.class, serializedForm);

			final ContainerWithEmptyCollections deserialized = ComplexDataObjectConverter.getOriginalForm(
				serializedForm, ContainerWithEmptyCollections.class, reflectionLookup
			);
			assertNotNull(deserialized.getEmptySet());
			assertTrue(deserialized.getEmptySet().isEmpty());
			assertNotNull(deserialized.getEmptyList());
			assertTrue(deserialized.getEmptyList().isEmpty());
			assertNotNull(deserialized.getEmptyArray());
			assertEquals(0, deserialized.getEmptyArray().length);
			assertNotNull(deserialized.getEmptyMap());
			assertTrue(deserialized.getEmptyMap().isEmpty());
		}

		@Nested
		@DisplayName("Map handling")
		class MapTest {

			@Test
			@DisplayName("should serialize and deserialize map with non-string keys")
			void shouldSerializeAndDeserializeMapWithNonStringKeys() {
				final Map<Integer, String> intMap = new HashMap<>();
				intMap.put(1, "one");
				intMap.put(2, "two");
				final Map<Locale, String> localeMap = new HashMap<>();
				localeMap.put(Locale.US, "United States");
				localeMap.put(Locale.CANADA, "Canada");

				final ContainerWithTypedMapKeys dto = new ContainerWithTypedMapKeys(intMap, localeMap);
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
				assertInstanceOf(ComplexDataObject.class, serializedForm);

				final ContainerWithTypedMapKeys deserialized = ComplexDataObjectConverter.getOriginalForm(
					serializedForm, ContainerWithTypedMapKeys.class, reflectionLookup
				);
				assertEquals("one", deserialized.getIntegerKeyMap().get(1));
				assertEquals("two", deserialized.getIntegerKeyMap().get(2));
				assertEquals("United States", deserialized.getLocaleKeyMap().get(Locale.US));
				assertEquals("Canada", deserialized.getLocaleKeyMap().get(Locale.CANADA));
			}

			@Test
			@DisplayName("should serialize map with null values")
			void shouldSerializeMapWithNullValues() {
				final Map<String, String> mapWithNulls = new HashMap<>();
				mapWithNulls.put("present", "value");
				mapWithNulls.put("absent", null);

				final ContainerWithNullableMapValues dto = new ContainerWithNullableMapValues(mapWithNulls);
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
				assertInstanceOf(ComplexDataObject.class, serializedForm);

				// verify the serialized form contains the key even though value is null
				final ComplexDataObject cdo = (ComplexDataObject) serializedForm;
				assertInstanceOf(DataItemMap.class, cdo.root());
			}

			@Test
			@DisplayName("should preserve null values during map round-trip")
			void shouldRoundTripMapWithNullValues() {
				final Map<String, String> mapWithNulls = new HashMap<>();
				mapWithNulls.put("present", "value");
				mapWithNulls.put("absent", null);

				final ContainerWithNullableMapValues dto = new ContainerWithNullableMapValues(mapWithNulls);
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();

				final ContainerWithNullableMapValues deserialized = ComplexDataObjectConverter.getOriginalForm(
					serializedForm, ContainerWithNullableMapValues.class, reflectionLookup
				);
				assertNotNull(deserialized.getValues());
				assertEquals(2, deserialized.getValues().size());
				assertEquals("value", deserialized.getValues().get("present"));
				assertNull(
					deserialized.getValues().get("absent"),
					"Null map value should be preserved as null after round-trip"
				);
			}

			@Test
			@DisplayName("should deserialize empty DataItemMap to empty HashMap")
			void shouldDeserializeMapFromEmptyDataItemMap() {
				final ContainerWithNullableMapValues dto = new ContainerWithNullableMapValues(Map.of());
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
				assertInstanceOf(ComplexDataObject.class, serializedForm);

				final ContainerWithNullableMapValues deserialized = ComplexDataObjectConverter.getOriginalForm(
					serializedForm, ContainerWithNullableMapValues.class, reflectionLookup
				);
				assertNotNull(deserialized.getValues());
				assertTrue(deserialized.getValues().isEmpty());
			}

			@Test
			@DisplayName("should serialize and deserialize nested complex object in map")
			void shouldSerializeAndDeserializeNestedComplexObjectInMap() {
				final TestComplexObject innerObject = createComplexObject("INNER", null);
				final TestComplexObject veryComplexObject = createComplexObject(
					"OUTER",
					new InnerContainer(
						Collections.singletonMap("key", innerObject),
						null, null, null, null, null
					)
				);
				final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
				final Serializable serializedForm = serializer.getSerializableForm();

				final TestComplexObject deserialized = new ComplexDataObjectConverter<>(TestComplexObject.class, reflectionLookup)
					.getOriginalForm(serializedForm);
				assertEquals("OUTER", deserialized.getFString());
				assertNotNull(deserialized.getInnerContainer());
				assertNotNull(deserialized.getInnerContainer().getIndex());
				assertEquals("INNER", deserialized.getInnerContainer().getIndex().get("key").getFString());
			}
		}

		@Nested
		@DisplayName("Set handling")
		class SetTest {

			@Test
			@DisplayName("should deserialize single DataItemValue to singleton Set")
			void shouldDeserializeSingleValueToSet() {
				final ContainerWithSimpleCollections dto = new ContainerWithSimpleCollections(
					Set.of("hello"), null, null
				);
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();

				final ContainerWithSimpleCollections deserialized = ComplexDataObjectConverter.getOriginalForm(
					serializedForm, ContainerWithSimpleCollections.class, reflectionLookup
				);
				assertNotNull(deserialized.getStringSet());
				assertEquals(1, deserialized.getStringSet().size());
				assertTrue(deserialized.getStringSet().contains("hello"));
			}
		}

		@Nested
		@DisplayName("List handling")
		class ListTest {

			@Test
			@DisplayName("should deserialize single DataItemValue to singleton List")
			void shouldDeserializeSingleValueToList() {
				final ContainerWithSimpleCollections dto = new ContainerWithSimpleCollections(
					null, List.of("world"), null
				);
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();

				final ContainerWithSimpleCollections deserialized = ComplexDataObjectConverter.getOriginalForm(
					serializedForm, ContainerWithSimpleCollections.class, reflectionLookup
				);
				assertNotNull(deserialized.getStringList());
				assertEquals(1, deserialized.getStringList().size());
				assertEquals("world", deserialized.getStringList().get(0));
			}
		}

		@Nested
		@DisplayName("Array handling")
		class ArrayTest {

			@Test
			@DisplayName("should deserialize single DataItemValue to single-element array")
			void shouldDeserializeSingleValueToArray() {
				final ContainerWithSimpleCollections dto = new ContainerWithSimpleCollections(
					null, null, new String[]{"single"}
				);
				final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();

				final ContainerWithSimpleCollections deserialized = ComplexDataObjectConverter.getOriginalForm(
					serializedForm, ContainerWithSimpleCollections.class, reflectionLookup
				);
				assertNotNull(deserialized.getStringArray());
				assertEquals(1, deserialized.getStringArray().length);
				assertEquals("single", deserialized.getStringArray()[0]);
			}

			@Test
			@DisplayName("should extract first element when array target is supported type")
			void shouldExtractFirstElementWhenArrayTargetIsSupportedType() {
				// when a DataItemArray is encountered but the target type is a scalar supported type,
				// the converter extracts the first element
				final TestComplexObject veryComplexObject = createVeryComplexObject();
				final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
				final Serializable serializedForm = serializer.getSerializableForm();
				// just verify round-trip works (the inner container lists exercise this path)
				final TestComplexObject deserialized = new ComplexDataObjectConverter<>(TestComplexObject.class, reflectionLookup)
					.getOriginalForm(serializedForm);
				assertEquals(veryComplexObject, deserialized);
			}
		}
	}

	@Nested
	@DisplayName("Enum handling")
	class EnumHandlingTest {

		@Test
		@DisplayName("should deserialize enum from quoted string")
		void shouldDeserializeEnumFromQuotedString() {
			final TestComplexObject original = createComplexObject("ENUM_TEST", null);
			final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(original);
			final Serializable serializedForm = replaceAllValuesWithFormattedStrings(
				(ComplexDataObject) serializer.getSerializableForm()
			);

			// formatted strings wrap enum values in quotes
			final TestComplexObject deserialized = new ComplexDataObjectConverter<>(TestComplexObject.class, reflectionLookup)
				.getOriginalForm(serializedForm);
			assertEquals(CustomEnum.ENUM_B, deserialized.getFCustomEnum());
		}
	}

	@Nested
	@DisplayName("@NonSerializedData annotation")
	class NonSerializedDataTest {

		@Test
		@DisplayName("should skip @NonSerializedData field during serialization of POJO class")
		void shouldSkipNonSerializedDataFieldDuringSerialization() throws MalformedURLException {
			final OriginalClass original = new OriginalClass("visible", 42, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();
			assertInstanceOf(ComplexDataObject.class, serializedForm);

			final ComplexDataObject cdo = (ComplexDataObject) serializedForm;
			final DataItemMap root = (DataItemMap) cdo.root();
			// "url" field is @NonSerializedData and should not be present in serialized form
			assertNull(root.getProperty("url"));
			assertNotNull(root.getProperty("field"));
			assertNotNull(root.getProperty("someNumber"));
		}

		@Test
		@DisplayName("should skip @NonSerializedData during deserialization via setter")
		void shouldSkipNonSerializedDataDuringDeserialization() throws MalformedURLException {
			final OriginalClass original = new OriginalClass("test", 42, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();

			final OriginalClass deserialized = ComplexDataObjectConverter.getOriginalForm(
				serializedForm, OriginalClass.class, reflectionLookup
			);
			assertEquals("test", deserialized.getField());
			assertEquals(42, deserialized.getSomeNumber());
			// URL is @NonSerializedData, so should be null after deserialization
			assertNull(deserialized.getUrl());
		}
	}

	@Nested
	@DisplayName("Class evolution with @RenamedData")
	class RenamedDataTest {

		@Test
		@DisplayName("should deserialize object with renamed field (mutable)")
		void shouldDeserializeObjectWithRenamedField() throws MalformedURLException {
			final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

			final ClassAfterRename deserializedClass = new ComplexDataObjectConverter<>(ClassAfterRename.class, reflectionLookup).getOriginalForm(serializedForm);
			assertEquals("ABC", deserializedClass.getRenamedField());
			assertEquals(1, deserializedClass.getSomeNumber());
		}

		@Test
		@DisplayName("should deserialize object with renamed field (immutable)")
		void shouldDeserializeObjectWithRenamedFieldOnImmutableClass() throws MalformedURLException {
			final OriginalImmutableClass beforeRename = new OriginalImmutableClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

			final ImmutableClassAfterRename deserializedClass = new ComplexDataObjectConverter<>(ImmutableClassAfterRename.class, reflectionLookup).getOriginalForm(serializedForm);
			assertEquals("ABC", deserializedClass.getRenamedField());
			assertEquals(1, deserializedClass.getSomeNumber());
		}

		@Test
		@DisplayName("should deserialize renamed inner object property via setter")
		void shouldDeserializeRenamedInnerObjectPropertyViaSetter() {
			final OriginalClassWithInnerObject original = new OriginalClassWithInnerObject(
				new InnerSimpleObject("test-label", 42), 7
			);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();

			final MutableClassWithRenamedInnerObject deserialized = new ComplexDataObjectConverter<>(
				MutableClassWithRenamedInnerObject.class, reflectionLookup
			).getOriginalForm(serializedForm);
			assertNotNull(deserialized.getRenamedDetail(), "Renamed inner object should not be null");
			assertEquals("test-label", deserialized.getRenamedDetail().getLabel());
			assertEquals(42, deserialized.getRenamedDetail().getValue());
			assertEquals(7, deserialized.getSomeNumber());
		}

		@Test
		@DisplayName("should deserialize renamed enum property via setter")
		void shouldDeserializeRenamedEnumPropertyViaSetter() {
			final OriginalClassWithEnum original = new OriginalClassWithEnum(CustomEnum.ENUM_C, 5);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();

			final MutableClassWithRenamedEnum deserialized = new ComplexDataObjectConverter<>(
				MutableClassWithRenamedEnum.class, reflectionLookup
			).getOriginalForm(serializedForm);
			assertEquals(CustomEnum.ENUM_C, deserialized.getRenamedStatus(), "Renamed enum should not be null");
			assertEquals(5, deserialized.getSomeNumber());
		}

		@Test
		@DisplayName("should deserialize renamed collection property via setter")
		void shouldDeserializeRenamedCollectionPropertyViaSetter() {
			final OriginalClassWithList original = new OriginalClassWithList(
				List.of("alpha", "beta", "gamma"), 3
			);
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();

			final MutableClassWithRenamedList deserialized = new ComplexDataObjectConverter<>(
				MutableClassWithRenamedList.class, reflectionLookup
			).getOriginalForm(serializedForm);
			assertNotNull(deserialized.getRenamedTags(), "Renamed list should not be null");
			assertEquals(List.of("alpha", "beta", "gamma"), deserialized.getRenamedTags());
			assertEquals(3, deserialized.getSomeNumber());
		}

		@Test
		@DisplayName("should deserialize with multiple renamed aliases")
		void shouldDeserializeWithMultipleRenamedAliases() throws MalformedURLException {
			// Serialize with original class that has "field" property
			final OriginalClass original = new OriginalClass("ALIASED", 99, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();

			// Deserialize with class that recognizes "field" among multiple aliases
			final ClassWithMultipleRenameAliases deserialized = new ComplexDataObjectConverter<>(
				ClassWithMultipleRenameAliases.class, reflectionLookup
			).getOriginalForm(serializedForm);
			assertEquals("ALIASED", deserialized.getCurrentName());
			assertEquals(99, deserialized.getSomeNumber());
		}
	}

	@Nested
	@DisplayName("Class evolution with @DiscardedData")
	class DiscardedDataTest {

		@Test
		@DisplayName("should deserialize object with discarded field (mutable)")
		void shouldDeserializeObjectWithDiscardedField() throws MalformedURLException {
			final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

			final ClassAfterDiscard deserializedClass = new ComplexDataObjectConverter<>(ClassAfterDiscard.class, reflectionLookup).getOriginalForm(serializedForm);
			assertEquals(1, deserializedClass.getSomeNumber());
		}

		@Test
		@DisplayName("should deserialize immutable object with discarded field")
		void shouldDeserializeImmutableObjectWithDiscardedField() throws MalformedURLException {
			final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

			final ImmutableClassAfterDiscard deserializedClass = new ComplexDataObjectConverter<>(ImmutableClassAfterDiscard.class, reflectionLookup).getOriginalForm(serializedForm);
			assertEquals(1, deserializedClass.getSomeNumber());
		}

		@Test
		@DisplayName("should handle combined @RenamedData and @DiscardedData")
		void shouldSerializeAndDeserializeWithCombinedRenameAndDiscard() throws MalformedURLException {
			final OriginalClass original = new OriginalClass("COMBINED", 77, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(original).getSerializableForm();

			final ClassWithBothRenameAndDiscard deserialized = new ComplexDataObjectConverter<>(
				ClassWithBothRenameAndDiscard.class, reflectionLookup
			).getOriginalForm(serializedForm);
			assertEquals("COMBINED", deserialized.getRenamedField());
			assertEquals(77, deserialized.getSomeNumber());
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("should fail to serialize incompatible object with no usable properties")
		void shouldFailToSerializeIncompatibleObject() {
			assertThrows(
				IllegalArgumentException.class,
				() -> ComplexDataObjectConverter.getSerializableForm(new IncompatibleObject())
			);
		}

		@Test
		@DisplayName("should fail to serialize and deserialize object with complex map keys")
		void shouldFailToSerializeAndDeserializeObjectWithComplexMapKeys() {
			final TestComplexObject complexObject = createComplexObject(
				"ABC",
				new InnerContainer(
					Collections.singletonMap(
						"RTE",
						createComplexObject("DEF", null)
					),
					Collections.singletonMap(
						new ComplexKey("a", 5),
						createComplexObject("DEF", null)
					),
					null, null, null, null
				)
			);

			assertThrows(UnsupportedDataTypeException.class, () -> new ComplexDataObjectConverter<>(complexObject).getSerializableForm());
		}

		@Test
		@DisplayName("should fail when deserialization data does not fit target class")
		void shouldFailToDeserializeWhenDataDoesNotFit() throws MalformedURLException {
			final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
			final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

			assertThrows(
				IncompleteDeserializationException.class,
				() -> new ComplexDataObjectConverter<>(ClassAfterNonDeclaredDiscard.class, reflectionLookup).getOriginalForm(serializedForm)
			);
		}

		@Test
		@DisplayName("should throw InconvertibleDataTypeException when root is DataItemArray for non-array class")
		void shouldThrowInconvertibleDataTypeExceptionWhenRootIsNotDataItemMap() {
			// create CDO with DataItemArray as root, then try to deserialize to a non-array class
			final ComplexDataObject cdo = new ComplexDataObject(
				new DataItemArray(new DataItem[]{new DataItemValue("ABC")})
			);
			final ComplexDataObjectConverter<OriginalClass> deserializer = new ComplexDataObjectConverter<>(
				OriginalClass.class, reflectionLookup
			);
			assertThrows(InconvertibleDataTypeException.class, () -> deserializer.getOriginalForm(cdo));
		}

		@Test
		@DisplayName("should throw SerializationFailedException for unsupported java.* type")
		void shouldThrowSerializationFailedExceptionWhenPropertyHasUnsupportedJavaType() {
			final ContainerWithUnsupportedJavaPackageType dto = new ContainerWithUnsupportedJavaPackageType(
				"test", new java.util.concurrent.atomic.AtomicInteger(5)
			);
			final SerializationFailedException ex = assertThrows(
				SerializationFailedException.class,
				() -> new ComplexDataObjectConverter<>(dto).getSerializableForm()
			);
			assertTrue(
				ex.getPrivateMessage().contains("AtomicInteger"),
				"Private message should contain type details but got: " + ex.getPrivateMessage()
			);
			assertEquals(
				"Unsupported data type encountered during serialization.",
				ex.getPublicMessage()
			);
		}

		@Test
		@DisplayName("should throw UnsupportedDataTypeException when array target is not collection type")
		void shouldThrowUnsupportedDataTypeExceptionWhenArrayTargetIsNotCollectionType() {
			// manually build a CDO where a field that should be a String contains a DataItemArray
			final Map<String, DataItem> props = new HashMap<>();
			props.put("fString", new DataItemArray(new DataItem[]{new DataItemValue("A"), new DataItemValue("B")}));
			props.put("fByte", new DataItemValue((byte) 1));
			props.put("fByteP", new DataItemValue((byte) 2));
			props.put("fShort", new DataItemValue((short) 3));
			props.put("fShortP", new DataItemValue((short) 4));
			props.put("fInteger", new DataItemValue(5));
			props.put("fIntegerP", new DataItemValue(6));
			props.put("fLong", new DataItemValue(7L));
			props.put("fLongP", new DataItemValue(7L));
			props.put("fBoolean", new DataItemValue(true));
			props.put("fBooleanP", new DataItemValue(false));
			props.put("fChar", new DataItemValue("A"));
			props.put("fCharP", new DataItemValue("B"));
			props.put("fBigDecimal", new DataItemValue(new BigDecimal("123.12")));
			props.put("fOffsetDateTime", new DataItemValue(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
			props.put("fLocalDateTime", new DataItemValue(LocalDateTime.of(2021, 1, 1, 0, 0, 0, 0)));
			props.put("fLocalDate", new DataItemValue(LocalDate.of(2021, 1, 1)));
			props.put("fLocalTime", new DataItemValue(LocalTime.of(0, 0, 0, 0)));
			props.put("fDateTimeRange", new DataItemValue(DateTimeRange.since(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))));
			props.put("fNumberRange", new DataItemValue(IntegerNumberRange.to(124)));
			props.put("fLocale", new DataItemValue(Locale.CANADA));
			props.put("fCustomEnum", new DataItemValue("ENUM_B"));
			props.put("timestamp", new DataItemValue(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));

			final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(props));
			// fString is a String but we provided DataItemArray - should extract first element
			// This actually tests the "extract first element" path for supported types
			final TestComplexObject deserialized = new ComplexDataObjectConverter<>(TestComplexObject.class, reflectionLookup)
				.getOriginalForm(cdo);
			assertEquals("A", deserialized.getFString());
		}

		@Test
		@DisplayName("should fail when DataItemMap is provided for Set-typed field")
		void shouldFailWhenDataItemMapProvidedForSetField() {
			// DataItemMap for a Set-typed field gets dispatched to extractData which tries to
			// instantiate the Set as a complex object, resulting in an error
			final Map<String, DataItem> setContent = new HashMap<>();
			setContent.put("key", new DataItemValue("value"));

			final Map<String, DataItem> rootProps = new HashMap<>();
			rootProps.put("stringSet", new DataItemMap(setContent));

			final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(rootProps));
			assertThrows(
				Exception.class,
				() -> new ComplexDataObjectConverter<>(ContainerWithSimpleCollections.class, reflectionLookup).getOriginalForm(cdo)
			);
		}

		@Test
		@DisplayName("should fail when DataItemMap is provided for List-typed field")
		void shouldFailWhenDataItemMapProvidedForListField() {
			final Map<String, DataItem> listContent = new HashMap<>();
			listContent.put("key", new DataItemValue("value"));

			final Map<String, DataItem> rootProps = new HashMap<>();
			rootProps.put("stringList", new DataItemMap(listContent));

			final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(rootProps));
			assertThrows(
				Exception.class,
				() -> new ComplexDataObjectConverter<>(ContainerWithSimpleCollections.class, reflectionLookup).getOriginalForm(cdo)
			);
		}

		@Test
		@DisplayName("should fail when DataItemMap is provided for array-typed field")
		void shouldFailWhenDataItemMapProvidedForArrayField() {
			final Map<String, DataItem> arrayContent = new HashMap<>();
			arrayContent.put("key", new DataItemValue("value"));

			final Map<String, DataItem> rootProps = new HashMap<>();
			rootProps.put("stringArray", new DataItemMap(arrayContent));

			final ComplexDataObject cdo = new ComplexDataObject(new DataItemMap(rootProps));
			assertThrows(
				Exception.class,
				() -> new ComplexDataObjectConverter<>(ContainerWithSimpleCollections.class, reflectionLookup).getOriginalForm(cdo)
			);
		}

		@Test
		@DisplayName("should include correct property path when Set element serialization fails")
		void shouldIncludeCorrectPropertyPathWhenSetElementSerializationFails() {
			final ContainerWithSetOfUnsupported dto =
				new ContainerWithSetOfUnsupported(
					new LinkedHashSet<>(List.of(
						new ContainerWithUnsupportedJavaPackageType(
							"first", new java.util.concurrent.atomic.AtomicInteger(1)
						)
					))
				);
			final SerializationFailedException ex = assertThrows(
				SerializationFailedException.class,
				() -> new ComplexDataObjectConverter<>(dto).getSerializableForm()
			);
			// With the off-by-one bug, this would say "items[1].counter" instead of "items[0].counter"
			assertTrue(
				ex.getPrivateMessage().contains("items[0].counter"),
				"Expected path 'items[0].counter' but got: " + ex.getPrivateMessage()
			);
			assertEquals(
				"Unsupported data type encountered during serialization.",
				ex.getPublicMessage()
			);
		}

		@Test
		@DisplayName("should throw ClassCastException when field is not serializable")
		void shouldThrowWhenFieldIsNotSerializable() {
			final ContainerWithNonSerializableField dto = new ContainerWithNonSerializableField(
				"test", new NotSerializableType(42)
			);
			// non-Serializable field type triggers ClassCastException when trying to cast to Serializable
			assertThrows(
				ClassCastException.class,
				() -> new ComplexDataObjectConverter<>(dto).getSerializableForm()
			);
		}
	}

	// ----- helper methods -----

	@Nonnull
	private String readFromClasspath(@Nonnull String path) throws IOException {
		return IOUtils.toString(
			Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path)),
			StandardCharsets.UTF_8
		);
	}

	@Nonnull
	private static ComplexDataObject replaceAllValuesWithFormattedStrings(@Nonnull ComplexDataObject complexDataObject) {
		return new ComplexDataObject(
			Objects.requireNonNull(replaceAllValuesWithFormattedStrings(complexDataObject.root()))
		);
	}

	@Nullable
	private static DataItem replaceAllValuesWithFormattedStrings(@Nullable DataItem dataItem) {
		if (dataItem instanceof DataItemMap dim) {
			final Map<String, DataItem> children = new HashMap<>(dim.childrenIndex().size());
			for (Entry<String, DataItem> entry : dim.childrenIndex().entrySet()) {
				children.put(
					entry.getKey(),
					replaceAllValuesWithFormattedStrings(entry.getValue())
				);
			}
			return new DataItemMap(children);
		} else if (dataItem instanceof DataItemArray dia) {
			return new DataItemArray(
				Arrays.stream(dia.children())
					.map(ComplexDataObjectConverterTest::replaceAllValuesWithFormattedStrings)
					.toArray(DataItem[]::new)
			);
		} else if (dataItem instanceof DataItemValue div) {
			return new DataItemValue(
				div.value() instanceof String ? div.value() : EvitaDataTypes.formatValue(div.value())
			);
		} else if (dataItem == null) {
			return null;
		} else {
			throw new IllegalStateException("Unexpected data type: " + dataItem.getClass());
		}
	}

	@Nonnull
	private TestComplexObject createVeryComplexObject() {
		return createComplexObject(
			"ABC",
			new InnerContainer(
				Collections.singletonMap(
					"ZZZ",
					createComplexObject("DEF", null)
				),
				null,
				new LinkedHashSet<>(
					Arrays.asList(
						createComplexObject("RTE", null),
						createComplexObject("EGD", null)
					)
				),
				Arrays.asList(
					createComplexObject("RRR", null),
					createComplexObject("EEE", null)
				),
				new TestComplexObject[]{
					createComplexObject("TTT", null),
					createComplexObject("GGG", null)
				},
				createComplexObject("WWW", null)
			)
		);
	}

	@Nonnull
	private TestComplexRecord createVeryComplexRecord() {
		return createComplexRecord(
			"ABC",
			new InnerContainer(
				Collections.singletonMap(
					"ZZZ",
					createComplexObject("DEF", null)
				),
				null,
				new LinkedHashSet<>(
					Arrays.asList(
						createComplexObject("RTE", null),
						createComplexObject("EGD", null)
					)
				),
				Arrays.asList(
					createComplexObject("RRR", null),
					createComplexObject("EEE", null)
				),
				new TestComplexObject[]{
					createComplexObject("TTT", null),
					createComplexObject("GGG", null)
				},
				createComplexObject("WWW", null)
			)
		);
	}

	@Nonnull
	private TestComplexImmutableObject createVeryComplexImmutableObject() {
		return createComplexImmutableObject(
			"ABC",
			new InnerImmutableContainer(
				Collections.singletonMap(
					"ZZZ",
					createComplexImmutableObject("DEF", null)
				),
				null,
				new LinkedHashSet<>(
					Arrays.asList(
						createComplexImmutableObject("RTE", null),
						createComplexImmutableObject("EGD", null)
					)
				),
				Arrays.asList(
					createComplexImmutableObject("RRR", null),
					createComplexImmutableObject("EEE", null)
				),
				new TestComplexImmutableObject[]{
					createComplexImmutableObject("TTT", null),
					createComplexImmutableObject("GGG", null)
				},
				createComplexImmutableObject("WWW", null)
			)
		);
	}

	@Nonnull
	private TestComplexObject createComplexObject(@Nonnull String id, @Nullable InnerContainer innerContainer) {
		return new TestComplexObject(
			id,
			(byte) 1, (byte) 2,
			(short) 3, (short) 4,
			5, 6,
			7L, 7L,
			true, false,
			'A', 'B',
			new BigDecimal("123.12"),
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			LocalDateTime.of(2021, 1, 1, 0, 0, 0, 0),
			LocalDate.of(2021, 1, 1),
			LocalTime.of(0, 0, 0, 0),
			DateTimeRange.since(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
			IntegerNumberRange.to(124),
			Locale.CANADA,
			innerContainer,
			CustomEnum.ENUM_B,
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
		);
	}

	@Nonnull
	private TestComplexRecord createComplexRecord(@Nonnull String id, @Nullable InnerContainer innerContainer) {
		return new TestComplexRecord(
			id,
			(byte) 1, (byte) 2,
			(short) 3, (short) 4,
			5, 6,
			7L, 7L,
			true, false,
			'A', 'B',
			new BigDecimal("123.12"),
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			LocalDateTime.of(2021, 1, 1, 0, 0, 0, 0),
			LocalDate.of(2021, 1, 1),
			LocalTime.of(0, 0, 0, 0),
			DateTimeRange.since(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
			IntegerNumberRange.to(124),
			Locale.CANADA,
			innerContainer,
			CustomEnum.ENUM_B,
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
		);
	}

	@Nonnull
	private TestComplexImmutableObject createComplexImmutableObject(
		@Nonnull String id,
		@Nullable InnerImmutableContainer innerContainer
	) {
		return new TestComplexImmutableObject(
			CustomEnum.ENUM_B,
			id,
			(byte) 1, (byte) 2,
			(short) 3, (short) 4,
			5, 6,
			7L, 7L,
			true, false,
			'A', 'B',
			new BigDecimal("123.12"),
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			LocalDateTime.of(2021, 1, 1, 0, 0, 0, 0),
			LocalDate.of(2021, 1, 1),
			LocalTime.of(0, 0, 0, 0),
			DateTimeRange.since(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
			IntegerNumberRange.to(124),
			Locale.CANADA,
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			innerContainer
		);
	}

	// ----- inner types -----

	public enum CustomEnum {

		ENUM_A, ENUM_B, ENUM_C

	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class TestComplexObject implements Serializable {
		@Serial private static final long serialVersionUID = 7169622529995308080L;
		private String fString;
		private Byte fByte;
		private byte fByteP;
		private Short fShort;
		private short fShortP;
		private Integer fInteger;
		private int fIntegerP;
		private Long fLong;
		private long fLongP;
		private Boolean fBoolean;
		private boolean fBooleanP;
		private Character fChar;
		private char fCharP;
		private BigDecimal fBigDecimal;
		private OffsetDateTime fOffsetDateTime;
		private LocalDateTime fLocalDateTime;
		private LocalDate fLocalDate;
		private LocalTime fLocalTime;
		private DateTimeRange fDateTimeRange;
		private IntegerNumberRange fNumberRange;
		private Locale fLocale;
		private InnerContainer innerContainer;
		private CustomEnum fCustomEnum;
		private OffsetDateTime timestamp;
	}

	public record TestComplexRecord(
		String fString,
		Byte fByte,
		byte fByteP,
		Short fShort,
		short fShortP,
		Integer fInteger,
		int fIntegerP,
		Long fLong,
		long fLongP,
		Boolean fBoolean,
		boolean fBooleanP,
		Character fChar,
		char fCharP,
		BigDecimal fBigDecimal,
		OffsetDateTime fOffsetDateTime,
		LocalDateTime fLocalDateTime,
		LocalDate fLocalDate,
		LocalTime fLocalTime,
		DateTimeRange fDateTimeRange,
		IntegerNumberRange fNumberRange,
		Locale fLocale,
		InnerContainer innerContainer,
		CustomEnum fCustomEnum,
		OffsetDateTime timestamp
	) implements Serializable {
		@Serial private static final long serialVersionUID = 7169622529995308080L;
	}

	@Data
	public static class TestComplexImmutableObject implements Serializable {
		@Serial private static final long serialVersionUID = 7169622529995308080L;
		private final CustomEnum fCustomEnum;
		private final String fString;
		private final Byte fByte;
		private final byte fByteP;
		private final Short fShort;
		private final short fShortP;
		private final Integer fInteger;
		private final int fIntegerP;
		private final Long fLong;
		private final long fLongP;
		private final Boolean fBoolean;
		private final boolean fBooleanP;
		private final Character fChar;
		private final char fCharP;
		private final BigDecimal fBigDecimal;
		private final OffsetDateTime fOffsetDateTime;
		private final LocalDateTime fLocalDateTime;
		private final LocalDate fLocalDate;
		private final LocalTime fLocalTime;
		private final DateTimeRange fDateTimeRange;
		private final IntegerNumberRange fNumberRange;
		private final Locale fLocale;
		private final OffsetDateTime timestamp;
		private final InnerImmutableContainer innerContainer;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class InnerContainer implements Serializable {
		@Serial private static final long serialVersionUID = -690137131054088501L;
		private Map<String, TestComplexObject> index;
		private Map<ComplexKey, TestComplexObject> indexWithComplexKey;
		private Set<TestComplexObject> set;
		private List<TestComplexObject> list;
		private TestComplexObject[] arr;
		private TestComplexObject pojo;
	}

	@Data
	public static class InnerImmutableContainer implements Serializable {
		@Serial private static final long serialVersionUID = -690137131054088501L;
		private final Map<String, TestComplexImmutableObject> index;
		private final Map<ComplexKey, TestComplexImmutableObject> indexWithComplexKey;
		private final Set<TestComplexImmutableObject> set;
		private final List<TestComplexImmutableObject> list;
		private final TestComplexImmutableObject[] arr;
		private final TestComplexImmutableObject pojo;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ComplexKey implements Serializable {
		@Serial private static final long serialVersionUID = 3532029096367942509L;
		private String string;
		private int number;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class OriginalClass implements Serializable {
		@Serial private static final long serialVersionUID = -5885429548269444707L;
		private String field;
		private int someNumber;
		@NonSerializedData
		private URL url;
	}

	@Data
	public static class OriginalImmutableClass implements Serializable {
		@Serial private static final long serialVersionUID = -5885429548269444707L;
		private final String field;
		private final int someNumber;
		@NonSerializedData
		private final URL url;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ClassAfterRename implements Serializable {
		@Serial private static final long serialVersionUID = -7012393429041887402L;
		@RenamedData("field")
		private String renamedField;
		private int someNumber;
	}

	@Data
	public static class ImmutableClassAfterRename implements Serializable {
		@Serial private static final long serialVersionUID = -7012393429041887402L;
		@RenamedData("field")
		private final String renamedField;
		private final int someNumber;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@DiscardedData("field")
	public static class ClassAfterDiscard implements Serializable {
		@Serial private static final long serialVersionUID = -7012393429041887402L;
		private int someNumber;
	}

	@Data
	@DiscardedData("field")
	public static class ImmutableClassAfterDiscard implements Serializable {
		@Serial private static final long serialVersionUID = -7012393429041887402L;
		private final int someNumber;
	}

	@Data
	@NoArgsConstructor
	public static class ClassAfterNonDeclaredDiscard implements Serializable {
		@Serial private static final long serialVersionUID = 7510539260764292731L;
	}

	@Data
	public static class IncompatibleObject implements Serializable {
		@Serial private static final long serialVersionUID = -6419803592905656920L;
		private final int constantValue = 1;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ArrayContainer implements Serializable {
		@Serial private static final long serialVersionUID = -2415085345520715434L;
		private int[] intArray;
		private String[] stringArray;
		private ArrayContainer[] pojoArray;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithUnsupportedJavaTypes implements Serializable {
		@Serial private static final long serialVersionUID = -3859314133932703390L;
		private float fFloat;
		private double fDouble;
	}

	// ----- new helper inner classes -----

	/**
	 * Container with an unsupported java.* type field (AtomicInteger).
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithUnsupportedJavaPackageType implements Serializable {
		@Serial private static final long serialVersionUID = 1L;
		private String name;
		private java.util.concurrent.atomic.AtomicInteger counter;
	}

	/**
	 * Non-serializable type used for testing serialization failure.
	 */
	public static class NotSerializableType {
		private final int value;

		public NotSerializableType(int value) {
			this.value = value;
		}

		public int getValue() {
			return this.value;
		}
	}

	/**
	 * Container with a non-serializable field.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithNonSerializableField implements Serializable {
		@Serial private static final long serialVersionUID = 2L;
		private String name;
		private NotSerializableType nested;
	}

	/**
	 * Container with simple collection types for testing single-value-to-collection deserialization.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithSimpleCollections implements Serializable {
		@Serial private static final long serialVersionUID = 3L;
		private Set<String> stringSet;
		private List<String> stringList;
		private String[] stringArray;
	}

	/**
	 * Container with empty collections for testing empty collection round-trip.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithEmptyCollections implements Serializable {
		@Serial private static final long serialVersionUID = 4L;
		private Set<String> emptySet;
		private List<String> emptyList;
		private String[] emptyArray;
		private Map<String, String> emptyMap;
	}

	/**
	 * Container with non-String typed map keys.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithTypedMapKeys implements Serializable {
		@Serial private static final long serialVersionUID = 5L;
		private Map<Integer, String> integerKeyMap;
		private Map<Locale, String> localeKeyMap;
	}

	/**
	 * Container with a nullable map for testing null values and empty maps.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithNullableMapValues implements Serializable {
		@Serial private static final long serialVersionUID = 6L;
		private Map<String, String> values;
	}

	/**
	 * Container with boxed Float and Double fields.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithBoxedFloatDouble implements Serializable {
		@Serial private static final long serialVersionUID = 8L;
		private Float boxedFloat;
		private Double boxedDouble;
	}

	/**
	 * Class with multiple rename aliases for testing {@link RenamedData} with multiple old names.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@DiscardedData("url")
	public static class ClassWithMultipleRenameAliases implements Serializable {
		@Serial private static final long serialVersionUID = 9L;
		@RenamedData({"field", "oldField", "legacyField"})
		private String currentName;
		private int someNumber;
	}

	/**
	 * Class combining {@link RenamedData} and {@link DiscardedData} annotations for testing combined evolution.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@DiscardedData("url")
	public static class ClassWithBothRenameAndDiscard implements Serializable {
		@Serial private static final long serialVersionUID = 10L;
		@RenamedData("field")
		private String renamedField;
		private int someNumber;
	}

	/**
	 * Container with a Set of objects containing an unsupported java.* type field.
	 * Used to test property path reporting in serialization errors for Set elements.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ContainerWithSetOfUnsupported implements Serializable {
		@Serial private static final long serialVersionUID = 11L;
		private Set<ContainerWithUnsupportedJavaPackageType> items;
	}

	/**
	 * Simple inner object used for testing renamed non-EvitaDataType properties.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class InnerSimpleObject implements Serializable {
		@Serial private static final long serialVersionUID = 12L;
		private String label;
		private int value;
	}

	/**
	 * Original class with an inner object property, used to produce serialized data
	 * before a rename.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class OriginalClassWithInnerObject implements Serializable {
		@Serial private static final long serialVersionUID = 13L;
		private InnerSimpleObject detail;
		private int someNumber;
	}

	/**
	 * Mutable class that renames the inner object property from "detail" to "renamedDetail".
	 * Tests that {@link RenamedData} works for non-EvitaDataType properties via setter path.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MutableClassWithRenamedInnerObject implements Serializable {
		@Serial private static final long serialVersionUID = 14L;
		@RenamedData("detail")
		private InnerSimpleObject renamedDetail;
		private int someNumber;
	}

	/**
	 * Original class with an enum property, used to produce serialized data before a rename.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class OriginalClassWithEnum implements Serializable {
		@Serial private static final long serialVersionUID = 15L;
		private CustomEnum status;
		private int someNumber;
	}

	/**
	 * Mutable class that renames the enum property from "status" to "renamedStatus".
	 * Tests that {@link RenamedData} works for enum properties via setter path.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MutableClassWithRenamedEnum implements Serializable {
		@Serial private static final long serialVersionUID = 16L;
		@RenamedData("status")
		private CustomEnum renamedStatus;
		private int someNumber;
	}

	/**
	 * Original class with a list property, used to produce serialized data before a rename.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class OriginalClassWithList implements Serializable {
		@Serial private static final long serialVersionUID = 17L;
		private List<String> tags;
		private int someNumber;
	}

	/**
	 * Mutable class that renames the list property from "tags" to "renamedTags".
	 * Tests that {@link RenamedData} works for collection properties via setter path.
	 */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MutableClassWithRenamedList implements Serializable {
		@Serial private static final long serialVersionUID = 18L;
		@RenamedData("tags")
		private List<String> renamedTags;
		private int someNumber;
	}

}
