/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

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

		@ParameterizedTest
		@MethodSource("shouldSwitchCaseToCamelCaseArguments")
		void shouldSwitchCaseToCamelCase(String expected, String input) {
			assertEquals(expected, toCamelCase(input));
		}

		@Nonnull
		static Stream<Arguments> shouldSwitchCaseToCamelCaseArguments() {
			return Stream.of(
				Arguments.of("fooBar", "Foo.BAR"),
				Arguments.of("fooBar092", "FOO.BAR:::09 2"),
				Arguments.of("fooBar092", "FOO@BAR//09`2"),
				Arguments.of("fooBar", "fooBar"),
				Arguments.of("fooBar", "FooBar"),
				Arguments.of("fooBar", "foo-bar"),
				Arguments.of("fooBar", "foo_bar"),
				Arguments.of("fooBar", "FOO_BAR"),
				Arguments.of("fooBar09", "FOO_BAR09"),
				Arguments.of("fooBar09", "FOO_BAR_09"),
				Arguments.of("storeVisibleForB2c", "storeVisibleForB2C"),
				Arguments.of("fooBarAbc", "fooBarABC"),
				Arguments.of("fooAbBar", "fooABBar"),
				Arguments.of("fooABar", "fooABar")
			);
		}

		@ParameterizedTest
		@MethodSource("shouldSwitchCaseToPascalCaseArguments")
		void shouldSwitchCaseToPascalCase(String expected, String input) {
			assertEquals(expected, toPascalCase(input));
		}

		@Nonnull
		static Stream<Arguments> shouldSwitchCaseToPascalCaseArguments() {
			return Stream.of(
				Arguments.of("FooBar", "Foo.BAR"),
				Arguments.of("FooBar092", "FOO.BAR:::09 2"),
				Arguments.of("FooBar092", "FOO@BAR//09`2"),
				Arguments.of("FooBar", "fooBar"),
				Arguments.of("FooBar", "FooBar"),
				Arguments.of("FooBar", "foo-bar"),
				Arguments.of("FooBar", "foo_bar"),
				Arguments.of("FooBar", "FOO_BAR"),
				Arguments.of("FooBar09", "FOO_BAR09"),
				Arguments.of("FooBar09", "FOO_BAR_09"),
				Arguments.of("StoreVisibleForB2c", "storeVisibleForB2C"),
				Arguments.of("FooBarAbc", "fooBarABC"),
				Arguments.of("FooAbBar", "fooABBar"),
				Arguments.of("FooABar", "fooABar")
			);
		}

		@ParameterizedTest
		@MethodSource("shouldSwitchCaseToSnakeCaseArguments")
		void shouldSwitchCaseToSnakeCase(String expected, String input) {
			assertEquals(expected, toSnakeCase(input));
		}

		@Nonnull
		static Stream<Arguments> shouldSwitchCaseToSnakeCaseArguments() {
			return Stream.of(
				Arguments.of("foo_bar", "Foo.BAR"),
				Arguments.of("foo_bar_09_2", "FOO.BAR:::09 2"),
				Arguments.of("foo_bar_09_2", "FOO@BAR//09`2"),
				Arguments.of("foo_bar", "fooBar"),
				Arguments.of("foo_bar", "FooBar"),
				Arguments.of("foo_bar", "foo-bar"),
				Arguments.of("foo_bar", "foo_bar"),
				Arguments.of("foo_bar", "FOO_BAR"),
				Arguments.of("foo_bar09", "FooBar09"),
				Arguments.of("foo_bar09", "FOO_BAR09"),
				Arguments.of("foo_bar_09", "FOO_BAR_09"),
				Arguments.of("store_visible_for_b2c", "storeVisibleForB2C"),
				Arguments.of("foo_bar_abc", "fooBarABC"),
				Arguments.of("foo_ab_bar", "fooABBar"),
				Arguments.of("foo_a_bar", "fooABar")
			);
		}

		@ParameterizedTest
		@MethodSource("shouldSwitchCaseToUpperSnakeCaseArguments")
		void shouldSwitchCaseToUpperSnakeCase(String expected, String input) {
			assertEquals(expected, toUpperSnakeCase(input));
		}

		@Nonnull
		static Stream<Arguments> shouldSwitchCaseToUpperSnakeCaseArguments() {
			return Stream.of(
				Arguments.of("FOO_BAR", "Foo.BAR"),
				Arguments.of("FOO_BAR_09_2", "FOO.BAR:::09 2"),
				Arguments.of("FOO_BAR_09_2", "FOO@BAR//09`2"),
				Arguments.of("FOO_BAR", "fooBar"),
				Arguments.of("FOO_BAR", "FooBar"),
				Arguments.of("FOO_BAR", "foo-bar"),
				Arguments.of("FOO_BAR", "foo_bar"),
				Arguments.of("FOO_BAR", "FOO_BAR"),
				Arguments.of("FOO_BAR09", "FooBar09"),
				Arguments.of("FOO_BAR09", "FOO_BAR09"),
				Arguments.of("FOO_BAR_09", "FOO_BAR_09"),
				Arguments.of("STORE_VISIBLE_FOR_B2C", "storeVisibleForB2C"),
				Arguments.of("FOO_BAR_ABC", "fooBarABC"),
				Arguments.of("FOO_AB_BAR", "fooABBar"),
				Arguments.of("FOO_A_BAR", "fooABar")
			);
		}

		@ParameterizedTest
		@MethodSource("shouldSwitchCaseToKebabCaseArguments")
		void shouldSwitchCaseToKebabCase(String expected, String input) {
			assertEquals(expected, toKebabCase(input));
		}

		@Nonnull
		static Stream<Arguments> shouldSwitchCaseToKebabCaseArguments() {
			return Stream.of(
				Arguments.of("foo-bar", "Foo.BAR"),
				Arguments.of("foo-bar-09-2", "FOO.BAR:::09 2"),
				Arguments.of("foo-bar-09-2", "FOO@BAR//09`2"),
				Arguments.of("foo-bar", "fooBar"),
				Arguments.of("foo-bar", "FooBar"),
				Arguments.of("foo-bar", "foo-bar"),
				Arguments.of("foo-bar", "foo_bar"),
				Arguments.of("foo-bar", "FOO_BAR"),
				Arguments.of("foo-bar09", "FooBar09"),
				Arguments.of("foo-bar09", "FOO_BAR09"),
				Arguments.of("foo-bar-09", "FOO_BAR_09"),
				Arguments.of("store-visible-for-b2c", "storeVisibleForB2C"),
				Arguments.of("foo-bar-abc", "fooBarABC"),
				Arguments.of("foo-ab-bar", "fooABBar"),
				Arguments.of("foo-a-bar", "fooABar")
			);
		}

		@ParameterizedTest
		@MethodSource("shouldSplitStringWithCaseIntoWordsArguments")
		void shouldSplitStringWithCaseIntoWords(List<String> expectedWords, String inputString) {
			assertEquals(expectedWords, splitStringWithCaseIntoWords(inputString));
		}

		@Nonnull
		static Stream<Arguments> shouldSplitStringWithCaseIntoWordsArguments() {
			return Stream.of(
				Arguments.of(List.of("Foo", "BAR"), "Foo.BAR"),
				Arguments.of(List.of("FOO", "BAR", "09", "2"), "FOO.BAR:::09 2"),
				Arguments.of(List.of("FOO", "BAR", "09", "2"), "FOO@BAR//09`2"),
				Arguments.of(List.of("foo", "Bar"), "fooBar"),
				Arguments.of(List.of("Foo", "Bar"), "FooBar"),
				Arguments.of(List.of("foo", "bar"), "foo-bar"),
				Arguments.of(List.of("foo", "bar"), "foo_bar"),
				Arguments.of(List.of("FOO", "BAR"), "FOO_BAR"),
				Arguments.of(List.of("Foo", "Bar09"), "FooBar09"),
				Arguments.of(List.of("FOO", "BAR09"), "FOO_BAR09"),
				Arguments.of(List.of("FOO", "BAR", "09"), "FOO_BAR_09"),
				Arguments.of(List.of("store", "Visible", "For", "B2C"), "storeVisibleForB2C"),
				Arguments.of(List.of("foo", "Bar", "ABC"), "fooBarABC"),
				Arguments.of(List.of("foo", "AB", "Bar"), "fooABBar"),
				Arguments.of(List.of("foo", "A", "Bar"), "fooABar")
			);
		}

		@Nested
		@DisplayName("Diacritics removal tests")
		class DiacriticsRemovalTests {

			@Test
			@DisplayName("Should remove diacritics")
			void shouldRemoveDiacritics() {
				assertEquals(
					"Prilis zlutoucky kun skakal pres zive ploticky a behal po poli. @#$%^&*()escrzyaieESCRZYAIE",
					StringUtils.removeDiacritics(
						"Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ")
				);
			}

			@Test
			@DisplayName("Should remove diacritics for file name")
			void shouldRemoveDiacriticsForFileName() {
				assertEquals(
					"Prilis-zlutoucky-kun-skakal-pres-zive-ploticky-a-behal-po-poli.-escrzyaieESCRZYAIE",
					StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(
						"Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ",
						'-', "."
					)
				);
				assertEquals(
					"Toto-je-priklad-divneho-nazvu-souboru-atd.-kdovi-co-nesmysly-.-txt",
					StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(
						"Toto je : příklad / divného, názvu souboru & atd. @# + kdoví co, nesmysly     {}|<>/! . txt",
						'-', "."
					)
				);
			}

			@Test
			@DisplayName("Should remove diacritics for file name strip start and end")
			void shouldRemoveDiacriticsForFileNameStripStartAndEnd() {
				assertEquals(
					"Prilis-zlutoucky-kun-skakal-pres-zive-ploticky-a-behal-po-poli.-escrzyaieESCRZYAIE",
					StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(
						"Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ",
						'-', "."
					)
				);
				assertEquals(
					"Toto-je-priklad-divneho-nazvu-souboru-atd.-kdovi-co-nesmysly-.-txt",
					StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(
						"****Toto je : příklad / divného, názvu souboru & atd. @# + kdoví co, nesmysly     {}|<>/! . txt***",
						'-', "."
					)
				);
			}

			@Test
			@DisplayName("Should remove diacritics and all weird characters")
			void shouldRemoveDiacriticsAndAllWeirdCharacters() {
				assertEquals(
					"Prilis-zlutoucky-kun-skakal-pres-zive-ploticky-a-behal-po-poli-escrzyaieESCRZYAIE",
					StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(
						"Příliš žluťoučký kůň skákal přes živé plotíčky a běhal po poli. @#$%^&*()ěščřžýáíéĚŠČŘŽÝÁÍÉ",
						'-', ""
					)
				);
				assertEquals(
					"Toto-je-priklad-divneho-nazvu-souboru-atd-kdovi-co-nesmysly-txt",
					StringUtils.removeDiacriticsAndAllNonStandardCharactersExcept(
						"****Toto je : příklad / divného, názvu souboru & atd. @# + kdoví co, nesmysly     {}|<>/! . txt***",
						'-', ""
					)
				);
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
				final java.io.Serializable[] input = new java.io.Serializable[]{
					"X", 5, new java.math.BigDecimal("3.14")
				};
				assertEquals("X, 5, 3.14", StringUtils.serializableArrayToString(input));
			}
		}
	}
}
