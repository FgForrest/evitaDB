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

package io.evitadb.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static io.evitadb.utils.StringUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This class verifies behaviour of {@link StringUtils}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("StringUtils contract tests")
class StringUtilsTest {
	private static final Locale SYSTEM_DEFAULT_LOCALE = Locale.getDefault();

	@BeforeEach
	void setUp() {
		Locale.setDefault(Locale.ENGLISH);
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(SYSTEM_DEFAULT_LOCALE);
	}

	@Nested
	@DisplayName("Formatting tests")
	class FormattingTests {

		@Test
		@DisplayName("Should format nano")
		void shouldFormatNano() {
			assertEquals("106751d 23h 47m 16s", StringUtils.formatNano(Long.MAX_VALUE));
			assertEquals("14s", StringUtils.formatNano(14587877547L));
			assertEquals("0.000001ms", StringUtils.formatNano(1L));
		}

		@Test
		@DisplayName("Should format precise nano")
		void shouldFormatPreciseNano() {
			assertEquals("106751d 23h 47m 16.854775807s", StringUtils.formatPreciseNano(Long.MAX_VALUE));
			assertEquals("14.587877547s", StringUtils.formatPreciseNano(14587877547L));
		}

		@Test
		@DisplayName("Should format requests per sec")
		void shouldFormatRequestsPerSec() {
			assertEquals("114461.18 reqs/s", StringUtils.formatRequestsPerSec(457897, 4_000_456_879L));
		}

		@Test
		@DisplayName("Should format byte size")
		void shouldFormatByteSize() {
			assertEquals("1.04 GB", StringUtils.formatByteSize(1_120_000_000));
			assertEquals("1.15 GB", StringUtils.formatByteSize(1_240_000_000));
			assertEquals("1.07 MB", StringUtils.formatByteSize(1_120_000));
			assertEquals("1.18 MB", StringUtils.formatByteSize(1_240_000));
			assertEquals("1 KB", StringUtils.formatByteSize(1_240));
			assertEquals("240 B", StringUtils.formatByteSize(240));
			assertEquals("371.83 MB", StringUtils.formatByteSize(389_888_429));
		}

		@Test
		@DisplayName("Should format count int")
		void shouldFormatCountInt() {
			assertEquals("1.12 bil.", StringUtils.formatCount(1_120_000_000));
			assertEquals("1.12 mil.", StringUtils.formatCount(1_120_000));
			assertEquals("1.24 thousands", StringUtils.formatCount(1_240));
			assertEquals("240", StringUtils.formatCount(240));
			assertEquals("389.89 mil.", StringUtils.formatCount(389_888_429));
		}

		@Test
		@DisplayName("Should format count long")
		void shouldFormatCountLong() {
			assertEquals("1.12 bil.", StringUtils.formatCount(1_120_000_000L));
			assertEquals("1.12 mil.", StringUtils.formatCount(1_120_000L));
			assertEquals("1.24 thousands", StringUtils.formatCount(1_240L));
			assertEquals("240", StringUtils.formatCount(240L));
			assertEquals("389.89 mil.", StringUtils.formatCount(389_888_429L));
		}

		@Test
		@DisplayName("Should format duration less than a day")
		void shouldFormatDurationLessThanADay() {
			Duration duration = Duration.ofHours(5).plusMinutes(30).plusSeconds(15);
			String formattedDuration = StringUtils.formatDuration(duration);
			assertEquals("5h 30m 15s", formattedDuration);
		}

		@Test
		@DisplayName("Should format duration exactly one day")
		void shouldFormatDurationExactlyOneDay() {
			Duration duration = Duration.ofDays(1);
			String formattedDuration = StringUtils.formatDuration(duration);
			assertEquals("1d 0h 0m 0s", formattedDuration);
		}

		@Test
		@DisplayName("Should format duration more than one day")
		void shouldFormatDurationMoreThanOneDay() {
			Duration duration = Duration.ofDays(2).plusHours(5).plusMinutes(30).plusSeconds(15);
			String formattedDuration = StringUtils.formatDuration(duration);
			assertEquals("2d 5h 30m 15s", formattedDuration);
		}

		@Test
		@DisplayName("Should format duration minutes only")
		void shouldFormatDurationMinutesOnly() {
			Duration duration = Duration.ofMinutes(30).plusSeconds(15);
			String formattedDuration = StringUtils.formatDuration(duration);
			assertEquals("30m 15s", formattedDuration);
		}

		@Test
		@DisplayName("Should format zero duration")
		void shouldFormatZeroDuration() {
			Duration duration = Duration.ZERO;
			String formattedDuration = StringUtils.formatDuration(duration);
			assertEquals("0ms", formattedDuration);
		}
	}

	@Nested
	@DisplayName("Case conversion tests")
	class CaseConversionTests {

		@Test
		@DisplayName("Should uncapitalize string")
		void shouldUncapitalizeString() {
			assertEquals("abc", StringUtils.uncapitalize("Abc"));
			assertEquals("aBC", StringUtils.uncapitalize("ABC"));
			assertEquals("abc", StringUtils.uncapitalize("abc"));
		}

		@Test
		@DisplayName("Should switch case to target case")
		void shouldSwitchCaseToTargetCase() {
			assertEquals("fooBar", toSpecificCase("fooBar", NamingConvention.CAMEL_CASE));
			assertEquals("FooBar", toSpecificCase("fooBar", NamingConvention.PASCAL_CASE));
			assertEquals("foo_bar", toSpecificCase("fooBar", NamingConvention.SNAKE_CASE));
			assertEquals("FOO_BAR", toSpecificCase("fooBar", NamingConvention.UPPER_SNAKE_CASE));
			assertEquals("foo-bar", toSpecificCase("fooBar", NamingConvention.KEBAB_CASE));
		}

		@Test
		@DisplayName("Should switch case to camel case")
		void shouldSwitchCaseToCamelCase() {
			assertEquals("fooBar", toCamelCase("Foo.BAR"));
			assertEquals("fooBar092", toCamelCase("FOO.BAR:::09 2"));
			assertEquals("fooBar092", toCamelCase("FOO@BAR//09`2"));
			assertEquals("fooBar", toCamelCase("fooBar"));
			assertEquals("fooBar", toCamelCase("FooBar"));
			assertEquals("fooBar", toCamelCase("foo-bar"));
			assertEquals("fooBar", toCamelCase("foo_bar"));
			assertEquals("fooBar", toCamelCase("FOO_BAR"));
			assertEquals("fooBar09", toCamelCase("FOO_BAR09"));
			assertEquals("fooBar09", toCamelCase("FOO_BAR_09"));
		}

		@Test
		@DisplayName("Should switch case to pascal case")
		void shouldSwitchCaseToPascalCase() {
			assertEquals("FooBar", toPascalCase("Foo.BAR"));
			assertEquals("FooBar092", toPascalCase("FOO.BAR:::09 2"));
			assertEquals("FooBar092", toPascalCase("FOO@BAR//09`2"));
			assertEquals("FooBar", toPascalCase("fooBar"));
			assertEquals("FooBar", toPascalCase("FooBar"));
			assertEquals("FooBar", toPascalCase("foo-bar"));
			assertEquals("FooBar", toPascalCase("foo_bar"));
			assertEquals("FooBar", toPascalCase("FOO_BAR"));
			assertEquals("FooBar09", toPascalCase("FOO_BAR09"));
			assertEquals("FooBar09", toPascalCase("FOO_BAR_09"));
		}

		@Test
		@DisplayName("Should switch case to snake case")
		void shouldSwitchCaseToSnakeCase() {
			assertEquals("foo_bar", toSnakeCase("Foo.BAR"));
			assertEquals("foo_bar_09_2", toSnakeCase("FOO.BAR:::09 2"));
			assertEquals("foo_bar_09_2", toSnakeCase("FOO@BAR//09`2"));
			assertEquals("foo_bar", toSnakeCase("fooBar"));
			assertEquals("foo_bar", toSnakeCase("FooBar"));
			assertEquals("foo_bar", toSnakeCase("foo-bar"));
			assertEquals("foo_bar", toSnakeCase("foo_bar"));
			assertEquals("foo_bar", toSnakeCase("FOO_BAR"));
			assertEquals("foo_bar09", toSnakeCase("FooBar09"));
			assertEquals("foo_bar09", toSnakeCase("FOO_BAR09"));
			assertEquals("foo_bar_09", toSnakeCase("FOO_BAR_09"));
		}

		@Test
		@DisplayName("Should switch case to upper snake case")
		void shouldSwitchCaseToUpperSnakeCase() {
			assertEquals("FOO_BAR", toUpperSnakeCase("Foo.BAR"));
			assertEquals("FOO_BAR_09_2", toUpperSnakeCase("FOO.BAR:::09 2"));
			assertEquals("FOO_BAR_09_2", toUpperSnakeCase("FOO@BAR//09`2"));
			assertEquals("FOO_BAR", toUpperSnakeCase("fooBar"));
			assertEquals("FOO_BAR", toUpperSnakeCase("FooBar"));
			assertEquals("FOO_BAR", toUpperSnakeCase("foo-bar"));
			assertEquals("FOO_BAR", toUpperSnakeCase("foo_bar"));
			assertEquals("FOO_BAR", toUpperSnakeCase("FOO_BAR"));
			assertEquals("FOO_BAR09", toUpperSnakeCase("FooBar09"));
			assertEquals("FOO_BAR09", toUpperSnakeCase("FOO_BAR09"));
			assertEquals("FOO_BAR_09", toUpperSnakeCase("FOO_BAR_09"));
		}

		@Test
		@DisplayName("Should switch case to kebab case")
		void shouldSwitchCaseToKebabCase() {
			assertEquals("foo-bar", toKebabCase("Foo.BAR"));
			assertEquals("foo-bar-09-2", toKebabCase("FOO.BAR:::09 2"));
			assertEquals("foo-bar-09-2", toKebabCase("FOO@BAR//09`2"));
			assertEquals("foo-bar", toKebabCase("fooBar"));
			assertEquals("foo-bar", toKebabCase("FooBar"));
			assertEquals("foo-bar", toKebabCase("foo-bar"));
			assertEquals("foo-bar", toKebabCase("foo_bar"));
			assertEquals("foo-bar", toKebabCase("FOO_BAR"));
			assertEquals("foo-bar09", toKebabCase("FooBar09"));
			assertEquals("foo-bar09", toKebabCase("FOO_BAR09"));
			assertEquals("foo-bar-09", toKebabCase("FOO_BAR_09"));
		}
	}

	@Nested
	@DisplayName("Diacritics removal tests")
	class DiacriticsRemovalTests {

		@Test
		@DisplayName("Should remove diacritics")
		void shouldRemoveDiacritics() {
			assertEquals("Prilis zlutoucky kun skakal pres zive ploticky a behal po poli. @#$%^&*()escrzyaieESCRZYAIE",
				StringUtils.removeDiacritics("Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ"));
		}

		@Test
		@DisplayName("Should remove diacritics for file name")
		void shouldRemoveDiacriticsForFileName() {
			assertEquals("Prilis-zlutoucky-kun-skakal-pres-zive-ploticky-a-behal-po-poli.-escrzyaieESCRZYAIE",
				StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept("Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ", '-', "."));
			assertEquals("Toto-je-priklad-divneho-nazvu-souboru-atd.-kdovi-co-nesmysly-.-txt",
				StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept("Toto je : příklad / divného, názvu souboru & atd. @# + kdoví co, nesmysly     {}|<>/! . txt", '-', "."));
		}

		@Test
		@DisplayName("Should remove diacritics for file name strip start and end")
		void shouldRemoveDiacriticsForFileNameStripStartAndEnd() {
			assertEquals("Prilis-zlutoucky-kun-skakal-pres-zive-ploticky-a-behal-po-poli.-escrzyaieESCRZYAIE",
				StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept("Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ", '-', "."));
			assertEquals("Toto-je-priklad-divneho-nazvu-souboru-atd.-kdovi-co-nesmysly-.-txt",
				StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept("****Toto je : příklad / divného, názvu souboru & atd. @# + kdoví co, nesmysly     {}|<>/! . txt***", '-', "."));
		}

		@Test
		@DisplayName("Should remove diacritics and all weird characters")
		void shouldRemoveDiacriticsAndAllWeirdCharacters() {
			assertEquals("Prilis-zlutoucky-kun-skakal-pres-zive-ploticky-a-behal-po-poli-escrzyaieESCRZYAIE",
				StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept("Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ", '-', ""));
			assertEquals("Toto-je-priklad-divneho-nazvu-souboru-atd-kdovi-co-nesmysly-txt",
				StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept("****Toto je : příklad / divného, názvu souboru & atd. @# + kdoví co, nesmysly     {}|<>/! . txt***", '-', ""));
		}
	}

	@Nested
	@DisplayName("Padding tests")
	class PaddingTests {

		@Test
		@DisplayName("Should add right padding")
		void shouldAddRightPadding() {
			assertEquals("          ", StringUtils.rightPad("", " ", 10));
			assertEquals("a         ", StringUtils.rightPad("a", " ", 10));
			assertEquals("dsfadfsadfsadfd", StringUtils.rightPad("dsfadfsadfsadfd", " ", 10));
		}

		@Test
		@DisplayName("Should add left padding")
		void shouldAddLeftPadding() {
			assertEquals("          ", StringUtils.leftPad("", " ", 10));
			assertEquals("         a", StringUtils.leftPad("a", " ", 10));
			assertEquals("dsfadfsadfsadfd", StringUtils.leftPad("dsfadfsadfsadfd", " ", 10));
		}
	}

	@Nested
	@DisplayName("Utility tests")
	class UtilityTests {

		@Test
		@DisplayName("Should return null string when value is null")
		void shouldReturnNullStringWhenValueIsNull() {
			assertEquals("NULL", StringUtils.toString(null));
		}

		@Test
		@DisplayName("Should return string value when value is string")
		void shouldReturnStringValueWhenValueIsString() {
			assertEquals("Hello", StringUtils.toString("Hello"));
		}

		@Test
		@DisplayName("Should return array representation when value is array")
		void shouldReturnArrayRepresentationWhenValueIsArray() {
			assertEquals("[1, 2, 3]", StringUtils.toString(new int[]{1, 2, 3}));
		}

		@Test
		@DisplayName("Should return empty array representation when value is empty array")
		void shouldReturnEmptyArrayRepresentationWhenValueIsEmptyArray() {
			assertEquals("[]", StringUtils.toString(new int[]{}));
		}

		@Test
		@DisplayName("Should return array representation when value is multi-dimensional array")
		void shouldReturnArrayRepresentationWhenValueIsMultiDimensionalArray() {
			assertEquals("[[1, 2], [3, 4]]", StringUtils.toString(new int[][]{{1, 2}, {3, 4}}));
		}

		@Test
		@DisplayName("Should return object representation when value is object")
		void shouldReturnObjectRepresentationWhenValueIsObject() {
			Object obj = new Object() {
				@Override
				public String toString() {
					return "Test Object";
				}
			};
			assertEquals("Test Object", StringUtils.toString(obj));
		}

		@Test
		@DisplayName("Should convert serializable array to string for empty array")
		void shouldConvertSerializableArrayToStringForEmptyArray() {
			final java.io.Serializable[] input = new java.io.Serializable[]{};
			assertEquals("", StringUtils.serializableArrayToString(input));
		}

		@Test
		@DisplayName("Should convert serializable array to string for single element")
		void shouldConvertSerializableArrayToStringForSingleElement() {
			final java.io.Serializable[] input = new java.io.Serializable[]{"A"};
			assertEquals("A", StringUtils.serializableArrayToString(input));
		}

		@Test
		@DisplayName("Should convert serializable array to string for multiple elements")
		void shouldConvertSerializableArrayToStringForMultipleElements() {
			final java.io.Serializable[] input = new java.io.Serializable[]{"A", "B", "C"};
			assertEquals("A, B, C", StringUtils.serializableArrayToString(input));
		}

		@Test
		@DisplayName("Should convert serializable array to string with null elements")
		void shouldConvertSerializableArrayToStringWithNullElements() {
			final java.io.Serializable[] input = new java.io.Serializable[]{"A", null, "C"};
			assertEquals("A, NULL, C", StringUtils.serializableArrayToString(input));
		}

		@Test
		@DisplayName("Should convert serializable array to string with mixed types")
		void shouldConvertSerializableArrayToStringWithMixedTypes() {
			final java.io.Serializable[] input = new java.io.Serializable[]{"X", Integer.valueOf(5), new java.math.BigDecimal("3.14")};
			assertEquals("X, 5, 3.14", StringUtils.serializableArrayToString(input));
		}
	}
}
