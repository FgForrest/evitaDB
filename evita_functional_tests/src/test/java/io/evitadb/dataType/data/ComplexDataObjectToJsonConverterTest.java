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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter.SortingNodeFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies behaviour of {@link ComplexDataObjectToJsonConverter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class ComplexDataObjectToJsonConverterTest {
	private final ObjectMapper objectMapper = JsonMapper.builder()
		.nodeFactory(new SortingNodeFactory())
		.build();

	@Test
	void shouldSerializeComplexObjectToJson() throws IOException {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject> converter = new ComplexDataObjectConverter<>(veryComplexObject);
		final ComplexDataObject serializableForm = (ComplexDataObject) converter.getSerializableForm();

		final ComplexDataObjectToJsonConverter tested = new ComplexDataObjectToJsonConverter(this.objectMapper);
		serializableForm.accept(tested);

		assertEquals(readFromClasspath("testData/DataObjectConverterTest_complexObject.json"), tested.getJsonAsString());
	}

	@Test
	void shouldSerializeComplexArrayObjectToJson() throws IOException {
		final ArrayContainer veryComplexObject = createArrayComplexObject();
		final ComplexDataObjectConverter<ArrayContainer> converter = new ComplexDataObjectConverter<>(veryComplexObject);
		final ComplexDataObject serializableForm = (ComplexDataObject) converter.getSerializableForm();

		final ComplexDataObjectToJsonConverter complexDataObjectToJsonConverter = new ComplexDataObjectToJsonConverter(this.objectMapper);
		serializableForm.accept(complexDataObjectToJsonConverter);

		assertEquals(readFromClasspath("testData/DataObjectConverterTest_arrayComplexObject.json"), complexDataObjectToJsonConverter.getJsonAsString());
	}

	static String readFromClasspath(String path) throws IOException {
		return IOUtils.toString(
			Objects.requireNonNull(ComplexDataObjectToJsonConverterTest.class.getClassLoader().getResourceAsStream(path)),
			StandardCharsets.UTF_8
		);
	}

	static TestComplexObject createVeryComplexObject() {
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

	static ArrayContainer createArrayComplexObject() {
		return new ArrayContainer(
			new int[]{78, 65},
			new String[0],
			new ArrayContainer[]{
				new ArrayContainer(
					null,
					null,
					null
				),
				new ArrayContainer(
					new int[]{7},
					new String[]{"ABC", "DEF"},
					new ArrayContainer[0]
				)
			}
		);
	}

	static TestComplexObject createComplexObject(String id, InnerContainer innerContainer) {
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
		private final InnerImmutableContainer innerContainer;
		private final OffsetDateTime timestamp;
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
	public static class ArrayContainer implements Serializable {
		@Serial private static final long serialVersionUID = -2415085345520715434L;
		private int[] intArray;
		private String[] stringArray;
		private ArrayContainer[] pojoArray;
	}

}
