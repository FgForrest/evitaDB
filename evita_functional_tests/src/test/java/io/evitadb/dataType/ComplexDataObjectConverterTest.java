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
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.utils.ReflectionLookup;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.io.IOUtils;
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
 * handled by evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ComplexDataObjectConverterTest {
	private final ReflectionLookup reflectionLookup = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);

	@Test
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
	void shouldReturnComplexDataObjectWithoutDeserialization() {
		final ComplexDataObject cdo = new ComplexDataObject(
			new DataItemArray(
				new DataItem[]{
					new DataItemValue("ABC")
				}
			)
		);
		final ComplexDataObjectConverter<ComplexDataObject> converter = new ComplexDataObjectConverter<>(ComplexDataObject.class, this.reflectionLookup);
		final Serializable deserializedForm = converter.getOriginalForm(cdo);

		assertEquals(cdo, deserializedForm);
	}

	@Test
	void shouldSerializeSimpleObject() {
		final ComplexDataObjectConverter<String> converter = new ComplexDataObjectConverter<>("ABC");
		final Serializable serializedForm = converter.getSerializableForm();

		assertEquals("ABC", serializedForm);
	}

	@Test
	void shouldSerializeAndDeserializeSimpleObjectArray() {
		final ComplexDataObjectConverter<String[]> converter = new ComplexDataObjectConverter<>(new String[]{"ABC", "DEF"});
		final Serializable serializedForm = converter.getSerializableForm();

		assertArrayEquals(new String[]{"ABC", "DEF"}, ComplexDataObjectConverter.getOriginalForm(serializedForm, String[].class, this.reflectionLookup));
	}

	@Test
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
	void shouldEstimateSizeOfComplexObject() {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject> converter = new ComplexDataObjectConverter<>(veryComplexObject);
		final ComplexDataObject serializedForm = (ComplexDataObject) converter.getSerializableForm();

		assertTrue(serializedForm.estimateSize() > 0);
	}

	@Test
	void shouldSerializeAndDeserializeComplexObject() {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
		final Serializable serializedForm = serializer.getSerializableForm();

		final ComplexDataObjectConverter<TestComplexObject> deserializer = new ComplexDataObjectConverter<>(TestComplexObject.class, this.reflectionLookup);
		final TestComplexObject deserializedObject = deserializer.getOriginalForm(serializedForm);
		assertEquals(createVeryComplexObject(), deserializedObject);
	}

	@Test
	void shouldSerializeAndDeserializeComplexRecord() {
		final TestComplexRecord veryComplexRecord = createVeryComplexRecord();
		final ComplexDataObjectConverter<TestComplexRecord> serializer = new ComplexDataObjectConverter<>(veryComplexRecord);
		final Serializable serializedForm = serializer.getSerializableForm();

		final ComplexDataObjectConverter<TestComplexRecord> deserializer = new ComplexDataObjectConverter<>(TestComplexRecord.class, this.reflectionLookup);
		final TestComplexRecord deserializedObject = deserializer.getOriginalForm(serializedForm);
		assertEquals(createVeryComplexRecord(), deserializedObject);
	}

	@Test
	void shouldSerializeAndDeserializeComplexObjectArray() {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject[]> converter = new ComplexDataObjectConverter<>(new TestComplexObject[]{veryComplexObject, veryComplexObject});
		final Serializable serializedForm = converter.getSerializableForm();

		final ComplexDataObjectConverter<TestComplexObject[]> deserializer = new ComplexDataObjectConverter<>(TestComplexObject[].class, this.reflectionLookup);
		final TestComplexObject[] deserializedObject = deserializer.getOriginalForm(serializedForm);
		final TestComplexObject matrixObject = createVeryComplexObject();
		assertArrayEquals(new TestComplexObject[]{matrixObject, matrixObject}, deserializedObject);
	}

	@Test
	void shouldSerializeAndDeserializeComplexObjectWithFormattedStrings() {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
		final Serializable serializedForm = replaceAllValuesWithFormattedStrings((ComplexDataObject) serializer.getSerializableForm());

		final ComplexDataObjectConverter<TestComplexObject> deserializer = new ComplexDataObjectConverter<>(TestComplexObject.class, this.reflectionLookup);
		final TestComplexObject deserializedObject = deserializer.getOriginalForm(serializedForm);
		assertEquals(createVeryComplexObject(), deserializedObject);
	}

	@Test
	void shouldSerializeAndDeserializeComplexRecordWithFormattedStrings() {
		final TestComplexRecord veryComplexRecord = createVeryComplexRecord();
		final ComplexDataObjectConverter<TestComplexRecord> serializer = new ComplexDataObjectConverter<>(veryComplexRecord);
		final Serializable serializedForm = replaceAllValuesWithFormattedStrings((ComplexDataObject) serializer.getSerializableForm());

		final ComplexDataObjectConverter<TestComplexRecord> deserializer = new ComplexDataObjectConverter<>(TestComplexRecord.class, this.reflectionLookup);
		final TestComplexRecord deserializedObject = deserializer.getOriginalForm(serializedForm);
		assertEquals(createVeryComplexRecord(), deserializedObject);
	}

	@Test
	void shouldSerializeAndDeserializeComplexObjectArrayWithFormattedStrings() {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject[]> converter = new ComplexDataObjectConverter<>(new TestComplexObject[]{veryComplexObject, veryComplexObject});
		final Serializable serializedForm = replaceAllValuesWithFormattedStrings((ComplexDataObject) converter.getSerializableForm());

		final ComplexDataObjectConverter<TestComplexObject[]> deserializer = new ComplexDataObjectConverter<>(TestComplexObject[].class, this.reflectionLookup);
		final TestComplexObject[] deserializedObject = deserializer.getOriginalForm(serializedForm);
		final TestComplexObject matrixObject = createVeryComplexObject();
		assertArrayEquals(new TestComplexObject[]{matrixObject, matrixObject}, deserializedObject);
	}

	@Test
	void shouldHandleUnsupportedJavaNumericTypes() {
		final ContainerWithUnsupportedJavaTypes dto = new ContainerWithUnsupportedJavaTypes(1.2f, 1.7d);
		final Serializable serializedForm = new ComplexDataObjectConverter<>(dto).getSerializableForm();
		assertNotNull(serializedForm);

		final ContainerWithUnsupportedJavaTypes deserialized = ComplexDataObjectConverter.getOriginalForm(serializedForm, ContainerWithUnsupportedJavaTypes.class, this.reflectionLookup);
		assertEquals(1.2f, deserialized.getFFloat());
		assertEquals(1.7d, deserialized.getFDouble());
	}

	@Test
	void shouldFailToSerializeIncompatibleObject() {
		assertThrows(
			IllegalArgumentException.class,
			() -> ComplexDataObjectConverter.getSerializableForm(new IncompatibleObject())
		);
	}

	@Test
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
	void shouldDeserializeComplexImmutableObject() {
		final TestComplexImmutableObject veryComplexObject = createVeryComplexImmutableObject();
		final ComplexDataObjectConverter<TestComplexImmutableObject> serializer = new ComplexDataObjectConverter<>(veryComplexObject);
		final Serializable serializedForm = serializer.getSerializableForm();

		final ComplexDataObjectConverter<TestComplexImmutableObject> deserializer = new ComplexDataObjectConverter<>(TestComplexImmutableObject.class, this.reflectionLookup);
		final TestComplexImmutableObject deserializedObject = deserializer.getOriginalForm(serializedForm);
		assertEquals(createVeryComplexImmutableObject(), deserializedObject);
	}

	@Test
	void shouldDeserializeObjectWithRenamedField() throws MalformedURLException {
		final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
		final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

		final ClassAfterRename deserializedClass = new ComplexDataObjectConverter<>(ClassAfterRename.class, this.reflectionLookup).getOriginalForm(serializedForm);
		assertEquals("ABC", deserializedClass.getRenamedField());
		assertEquals(1, deserializedClass.getSomeNumber());
	}

	@Test
	void shouldDeserializeObjectWithRenamedFieldOnImmutableClass() throws MalformedURLException {
		final OriginalImmutableClass beforeRename = new OriginalImmutableClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
		final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

		final ImmutableClassAfterRename deserializedClass = new ComplexDataObjectConverter<>(ImmutableClassAfterRename.class, this.reflectionLookup).getOriginalForm(serializedForm);
		assertEquals("ABC", deserializedClass.getRenamedField());
		assertEquals(1, deserializedClass.getSomeNumber());
	}

	@Test
	void shouldFailToDeserializeWhenDataDoesNotFit() throws MalformedURLException {
		final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
		final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

		assertThrows(
			IncompleteDeserializationException.class,
			() -> new ComplexDataObjectConverter<>(ClassAfterNonDeclaredDiscard.class, this.reflectionLookup).getOriginalForm(serializedForm)
		);
	}

	@Test
	void shouldDeserializeObjectWithDiscardedField() throws MalformedURLException {
		final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
		final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

		final ClassAfterDiscard deserializedClass = new ComplexDataObjectConverter<>(ClassAfterDiscard.class, this.reflectionLookup).getOriginalForm(serializedForm);
		assertEquals(1, deserializedClass.getSomeNumber());
	}

	@Test
	void shouldDeserializeImmutableObjectWithDiscardedField() throws MalformedURLException {
		final OriginalClass beforeRename = new OriginalClass("ABC", 1, new URL("https", "www.fg.cz", 80, "/index.html"));
		final Serializable serializedForm = new ComplexDataObjectConverter<>(beforeRename).getSerializableForm();

		final ImmutableClassAfterDiscard deserializedClass = new ComplexDataObjectConverter<>(ImmutableClassAfterDiscard.class, this.reflectionLookup).getOriginalForm(serializedForm);
		assertEquals(1, deserializedClass.getSomeNumber());
	}

	@Test
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

	private String readFromClasspath(String path) throws IOException {
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

	private TestComplexObject createComplexObject(String id, InnerContainer innerContainer) {
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

	private TestComplexRecord createComplexRecord(String id, InnerContainer innerContainer) {
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

	private TestComplexImmutableObject createComplexImmutableObject(String id, InnerImmutableContainer innerContainer) {
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

}
