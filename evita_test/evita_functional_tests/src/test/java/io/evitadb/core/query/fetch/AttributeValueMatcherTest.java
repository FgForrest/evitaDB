/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.fetch;

import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.translator.attribute.AttributeBetweenTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeInRangeTranslator;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntPredicate;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that {@link AttributeValueMatcher} - the per-leaf test of the row filter of the fetched references - gives
 * the verdict the attribute indexes give for the same values.
 *
 * The parity tests load the very values into a real {@link OwnerFilterIndex} (and its range index), the way the
 * write path loads them ({@link NumberUtils#normalizeForIndexing}), probe it the way the attribute translators probe
 * it, and compare the records it answers with the records whose stored value the matcher accepts. The remaining
 * tests pin what the index cannot show: values the `int` encoding of a `BigDecimal` cannot hold, and the refusals.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute value matcher of the reference row filter")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
class AttributeValueMatcherTest {
	/**
	 * The key of a non-localized attribute.
	 */
	private static final AttributeIndexKey KEY = new AttributeIndexKey(null, "a", null);
	/**
	 * The comparison operators of the comparison leaves, with the filter index method their translators probe.
	 */
	private static final List<Operator> COMPARISONS = List.of(
		new Operator("lessThan", result -> result < 0, FilterIndex::getRecordsLesserThanFormula),
		new Operator("lessThanEquals", result -> result <= 0, FilterIndex::getRecordsLesserThanEqFormula),
		new Operator("greaterThan", result -> result > 0, FilterIndex::getRecordsGreaterThanFormula),
		new Operator("greaterThanEquals", result -> result >= 0, FilterIndex::getRecordsGreaterThanEqFormula)
	);
	/**
	 * The moment `attributeInRange` without its own moment is evaluated at.
	 */
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-01T00:00:00Z");

	/**
	 * A comparison operator: its name, the test of the comparison result and the filter index method its translator
	 * probes.
	 *
	 * @param name            the name of the constraint
	 * @param resultPredicate the test of the comparison result
	 * @param indexLookup     the filter index method
	 */
	private record Operator(
		@Nonnull String name,
		@Nonnull IntPredicate resultPredicate,
		@Nonnull BiFunction<FilterIndex, Serializable, Formula> indexLookup
	) {
	}

	/**
	 * Loads the values into a filter index of the attribute, record `i + 1` holding value `i`, as the write path does.
	 *
	 * @param key                  the key of the attribute
	 * @param attributeType        the declared type of the attribute, array-aware
	 * @param indexedDecimalPlaces the decimal places of the attribute
	 * @param stored               the stored values
	 * @return the index
	 */
	@Nonnull
	private static OwnerFilterIndex createIndex(
		@Nonnull AttributeIndexKey key,
		@Nonnull Class<?> attributeType,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] stored
	) {
		final OwnerFilterIndex index = new OwnerFilterIndex(key, attributeType, indexedDecimalPlaces);
		for (int i = 0; i < stored.length; i++) {
			index.addRecord(i + 1, NumberUtils.normalizeForIndexing(stored[i], indexedDecimalPlaces));
		}
		return index;
	}

	/**
	 * Returns the records whose stored value the matcher accepts, record `i + 1` holding value `i`.
	 *
	 * @param matcher the matcher
	 * @param stored  the stored values
	 * @return the accepted records, ascending
	 */
	@Nonnull
	private static int[] matchedRecords(@Nonnull AttributeValueMatcher matcher, @Nonnull Serializable[] stored) {
		final List<Integer> result = new ArrayList<>(stored.length);
		for (int i = 0; i < stored.length; i++) {
			if (matcher.matches(stored[i])) {
				result.add(i + 1);
			}
		}
		return result.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Returns the records any of the formulas of the index answers - the union `attributeInSet` makes of the lookups
	 * of its values.
	 *
	 * @param formulas the formulas of the index
	 * @return the records, ascending
	 */
	@Nonnull
	private static Formula union(@Nonnull Formula... formulas) {
		final TreeSet<Integer> records = new TreeSet<>();
		for (Formula formula : formulas) {
			for (int recordId : formula.compute().getArray()) {
				records.add(recordId);
			}
		}
		return records.isEmpty() ?
			EmptyFormula.INSTANCE :
			new ConstantFormula(new BaseBitmap(records.stream().mapToInt(Integer::intValue).toArray()));
	}

	/**
	 * Asserts the matcher accepts exactly the records the index answers.
	 *
	 * @param description what is compared
	 * @param indexAnswer the formula of the index
	 * @param matcher     the matcher
	 * @param stored      the stored values
	 * @return true when the index selected some, but not all, records - the answer discriminates
	 */
	private static boolean assertSameRecords(
		@Nonnull String description,
		@Nonnull Formula indexAnswer,
		@Nonnull AttributeValueMatcher matcher,
		@Nonnull Serializable[] stored
	) {
		final int[] indexRecords = indexAnswer.compute().getArray();
		assertArrayEquals(
			indexRecords, matchedRecords(matcher, stored), () -> description + " over " + Arrays.deepToString(stored)
		);
		return indexRecords.length > 0 && indexRecords.length < stored.length;
	}

	/**
	 * Compares every scalar leaf - equality, the set, the four comparisons and the interval with closed and open
	 * bounds - with the filter index, for every probe and every pair of probes.
	 *
	 * @param key                  the key of the attribute, whose locale selects the comparator
	 * @param attributeType        the declared type of the attribute, array-aware
	 * @param plainType            the plain type of the attribute
	 * @param indexedDecimalPlaces the decimal places of the attribute
	 * @param stored               the stored values
	 * @param probes               the constraint values
	 */
	private static void assertScalarParity(
		@Nonnull AttributeIndexKey key,
		@Nonnull Class<?> attributeType,
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] stored,
		@Nonnull Serializable[] probes
	) {
		boolean discriminating = false;
		final OwnerFilterIndex index = createIndex(key, attributeType, indexedDecimalPlaces, stored);
		final Function<Object, Serializable> indexNormalizer =
			FilterIndex.getNormalizer(plainType, indexedDecimalPlaces);
		for (Serializable probe : probes) {
			final Serializable converted = EvitaDataTypes.toTargetType(probe, plainType);
			// `AttributeEqualsTranslator` probes the filter index with the normalized converted value
			discriminating |= assertSameRecords(
				"equals " + probe,
				index.getRecordsEqualToFormula(indexNormalizer.apply(converted)),
				AttributeValueMatcher.comparing(key, plainType, indexedDecimalPlaces, probe, result -> result == 0),
				stored
			);
			// `AbstractAttributeComparisonTranslator` probes it with the converted value
			for (Operator operator : COMPARISONS) {
				discriminating |= assertSameRecords(
					operator.name() + " " + probe,
					operator.indexLookup().apply(index, converted),
					AttributeValueMatcher.comparing(
						key, plainType, indexedDecimalPlaces, probe, operator.resultPredicate()
					),
					stored
				);
			}
			discriminating |= assertSameRecords(
				"between " + probe + " and nothing",
				index.getRecordsGreaterThanEqFormula(converted),
				AttributeValueMatcher.between(key, plainType, indexedDecimalPlaces, probe, null),
				stored
			);
			discriminating |= assertSameRecords(
				"between nothing and " + probe,
				index.getRecordsLesserThanEqFormula(converted),
				AttributeValueMatcher.between(key, plainType, indexedDecimalPlaces, null, probe),
				stored
			);
			for (Serializable otherProbe : probes) {
				final Serializable otherConverted = EvitaDataTypes.toTargetType(otherProbe, plainType);
				discriminating |= assertSameRecords(
					"inSet " + probe + ", " + otherProbe,
					union(
						index.getRecordsEqualToFormula(indexNormalizer.apply(converted)),
						index.getRecordsEqualToFormula(indexNormalizer.apply(otherConverted))
					),
					AttributeValueMatcher.equalToAny(
						key, plainType, indexedDecimalPlaces, new Serializable[]{probe, otherProbe}
					),
					stored
				);
				discriminating |= assertBetweenParity(
					index, key, plainType, indexedDecimalPlaces, stored, probe, converted, otherProbe, otherConverted
				);
			}
		}
		// the parity of empty or full answers alone would prove nothing
		assertTrue(discriminating, "No compared answer selected some but not all records!");
	}

	/**
	 * Compares a closed interval with the filter index - including an inverted one, which both refuse with the same
	 * message.
	 *
	 * @param index                the index
	 * @param key                  the key of the attribute
	 * @param plainType            the plain type of the attribute
	 * @param indexedDecimalPlaces the decimal places of the attribute
	 * @param stored               the stored values
	 * @param from                 the lower bound
	 * @param convertedFrom        the lower bound converted to the attribute type
	 * @param to                   the upper bound
	 * @param convertedTo          the upper bound converted to the attribute type
	 * @return true when the answer of the index discriminates
	 */
	private static boolean assertBetweenParity(
		@Nonnull OwnerFilterIndex index,
		@Nonnull AttributeIndexKey key,
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] stored,
		@Nonnull Serializable from,
		@Nonnull Serializable convertedFrom,
		@Nonnull Serializable to,
		@Nonnull Serializable convertedTo
	) {
		Formula indexAnswer = null;
		EvitaInvalidUsageException indexRefusal = null;
		try {
			indexAnswer = index.getRecordsBetweenFormula(convertedFrom, convertedTo);
		} catch (EvitaInvalidUsageException ex) {
			indexRefusal = ex;
		}
		if (indexRefusal == null) {
			return assertSameRecords(
				"between " + from + " and " + to,
				indexAnswer,
				AttributeValueMatcher.between(key, plainType, indexedDecimalPlaces, from, to),
				stored
			);
		} else {
			final EvitaInvalidUsageException matcherRefusal = assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeValueMatcher.between(key, plainType, indexedDecimalPlaces, from, to),
				"between " + from + " and " + to + " is refused by the index"
			);
			assertEquals(indexRefusal.getMessage(), matcherRefusal.getMessage());
			return false;
		}
	}

	/**
	 * Compares the three string searches with the filter index.
	 *
	 * @param key           the key of the attribute, whose locale selects the comparator
	 * @param attributeType the declared type of the attribute, array-aware
	 * @param stored        the stored values
	 * @param texts         the searched texts
	 */
	private static void assertStringSearchParity(
		@Nonnull AttributeIndexKey key,
		@Nonnull Class<?> attributeType,
		@Nonnull Serializable[] stored,
		@Nonnull String[] texts
	) {
		boolean discriminating = false;
		final OwnerFilterIndex index = createIndex(key, attributeType, 0, stored);
		for (String text : texts) {
			discriminating |= assertSearchParity(
				"startsWith " + text, index.getRecordsWhoseValuesStartWith(text), text, String::startsWith, stored
			);
			discriminating |= assertSearchParity(
				"endsWith " + text, index.getRecordsWhoseValuesEndsWith(text), text, String::endsWith, stored
			);
			discriminating |= assertSearchParity(
				"contains " + text, index.getRecordsWhoseValuesContains(text), text, String::contains, stored
			);
		}
		// the parity of empty or full answers alone would prove nothing
		assertTrue(discriminating, "No compared answer selected some but not all records!");
	}

	/**
	 * Compares one string search with the filter index.
	 *
	 * @param description what is compared
	 * @param indexAnswer the formula of the index
	 * @param text        the searched text
	 * @param predicate   the string predicate
	 * @param stored      the stored values
	 * @return true when the answer of the index discriminates
	 */
	private static boolean assertSearchParity(
		@Nonnull String description,
		@Nonnull Formula indexAnswer,
		@Nonnull String text,
		@Nonnull BiPredicate<String, String> predicate,
		@Nonnull Serializable[] stored
	) {
		return assertSameRecords(description, indexAnswer, AttributeValueMatcher.searching(text, predicate), stored);
	}

	/**
	 * Compares `attributeBetween` and `attributeInRange` over a range-typed attribute with the range index.
	 *
	 * @param attributeType        the declared type of the attribute, array-aware
	 * @param plainType            the plain range type of the attribute
	 * @param indexedDecimalPlaces the decimal places of the attribute
	 * @param stored               the stored ranges
	 * @param bounds               the bounds of the intervals, pairs of from and to, either may be NULL
	 * @param inRangeProbes        the constraints of `attributeInRange`
	 */
	private static void assertRangeParity(
		@Nonnull Class<?> attributeType,
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] stored,
		@Nonnull Serializable[][] bounds,
		@Nonnull AttributeInRange[] inRangeProbes
	) {
		boolean discriminating = false;
		final OwnerFilterIndex index = createIndex(KEY, attributeType, indexedDecimalPlaces, stored);
		for (Serializable[] bound : bounds) {
			// `AttributeBetweenTranslator` probes the range index with the encoded bounds
			discriminating |= assertSameRecords(
				"between " + bound[0] + " and " + bound[1],
				index.getRecordsOverlappingFormula(
					AttributeBetweenTranslator.encodeRangeBound(
						plainType, indexedDecimalPlaces, bound[0], Long.MIN_VALUE
					),
					AttributeBetweenTranslator.encodeRangeBound(
						plainType, indexedDecimalPlaces, bound[1], Long.MAX_VALUE
					)
				),
				AttributeValueMatcher.between(KEY, plainType, indexedDecimalPlaces, bound[0], bound[1]),
				stored
			);
		}
		for (AttributeInRange inRange : inRangeProbes) {
			final long point = AttributeInRangeTranslator.getComparableValue(
				inRange, () -> NOW, plainType, indexedDecimalPlaces
			);
			discriminating |= assertSameRecords(
				inRange.toString(),
				index.getRecordsValidInFormula(point),
				AttributeValueMatcher.enveloping(plainType, indexedDecimalPlaces, point),
				stored
			);
		}
		// the parity of empty or full answers alone would prove nothing
		assertTrue(discriminating, "No compared answer selected some but not all records!");
	}

	/**
	 * Returns a `BigDecimal` of the text.
	 *
	 * @param text the text
	 * @return the number
	 */
	@Nonnull
	private static BigDecimal bd(@Nonnull String text) {
		return new BigDecimal(text);
	}

	/**
	 * Returns an `OffsetDateTime` of the text.
	 *
	 * @param text the text
	 * @return the moment
	 */
	@Nonnull
	private static OffsetDateTime odt(@Nonnull String text) {
		return OffsetDateTime.parse(text);
	}

	/**
	 * Returns a pair of bounds.
	 *
	 * @param from the lower bound, NULL for an open one
	 * @param to   the upper bound, NULL for an open one
	 * @return the pair
	 */
	@Nonnull
	private static Serializable[] bounds(@Nullable Serializable from, @Nullable Serializable to) {
		return new Serializable[]{from, to};
	}

	@Nested
	@DisplayName("Parity with the filter index")
	class FilterIndexParity {

		@Test
		@DisplayName("integers, including conversion of the constraint value")
		void shouldMatchIntegersAsFilterIndex() {
			assertScalarParity(
				KEY, Integer.class, Integer.class, 0,
				new Serializable[]{1, 5, 10, -3, 5},
				new Serializable[]{5, -3, 0, 11, "10"}
			);
		}

		@Test
		@DisplayName("integer arrays, including an empty one - any element matches")
		void shouldMatchIntegerArraysAsFilterIndex() {
			assertScalarParity(
				KEY, Integer[].class, Integer.class, 0,
				new Serializable[]{new Integer[]{1, 2}, new Integer[]{5}, new Integer[0], new Integer[]{2, 9}},
				new Serializable[]{2, 5, 0, 9, 3}
			);
		}

		@Test
		@DisplayName("longs")
		void shouldMatchLongsAsFilterIndex() {
			assertScalarParity(
				KEY, Long.class, Long.class, 0,
				new Serializable[]{1L, 1_000_000_000_000L, -5L},
				new Serializable[]{1, 1_000_000_000_000L, 0L}
			);
		}

		@Test
		@DisplayName("BigDecimal scaled half up to two decimal places")
		void shouldMatchBigDecimalsAtTwoDecimalPlacesAsFilterIndex() {
			assertScalarParity(
				KEY, BigDecimal.class, BigDecimal.class, 2,
				new Serializable[]{
					bd("1.01"), bd("1.005"), bd("2.5"), bd("-0.004"), bd("100"), bd("-1.005"), bd("1.0049"), bd("1.010")
				},
				new Serializable[]{bd("1.005"), bd("1.01"), bd("1.0049"), bd("0"), bd("-1.005"), bd("2.50"), "1.004"}
			);
		}

		@Test
		@DisplayName("BigDecimal without decimal places")
		void shouldMatchBigDecimalsWithoutDecimalPlacesAsFilterIndex() {
			assertScalarParity(
				KEY, BigDecimal.class, BigDecimal.class, 0,
				new Serializable[]{bd("1"), bd("1.5"), bd("2.4"), bd("-2.5")},
				new Serializable[]{bd("2"), bd("1.5"), bd("-3"), 2}
			);
		}

		@Test
		@DisplayName("BigDecimal arrays")
		void shouldMatchBigDecimalArraysAsFilterIndex() {
			assertScalarParity(
				KEY, BigDecimal[].class, BigDecimal.class, 2,
				new Serializable[]{
					new BigDecimal[]{bd("1.005"), bd("7")}, new BigDecimal[0], new BigDecimal[]{bd("3.333")}
				},
				new Serializable[]{bd("1.01"), bd("3.33"), bd("7.00"), bd("4")}
			);
		}

		@Test
		@DisplayName("strings in NFD and NFC, ordered by code points")
		void shouldMatchStringsAsFilterIndex() {
			assertScalarParity(
				KEY, String.class, String.class, 0,
				new Serializable[]{"apple", "Banana", "café", "café", "Zebra", ""},
				new Serializable[]{"café", "café", "apple", "b", "Zz", ""}
			);
		}

		@Test
		@DisplayName("localized strings ordered by the collation of the locale")
		void shouldMatchLocalizedStringsAsFilterIndex() {
			final Locale czech = Locale.forLanguageTag("cs");
			assertScalarParity(
				new AttributeIndexKey(null, "a", czech), String.class, String.class, 0,
				new Serializable[]{"chata", "hrad", "cibule", "Žába", "zebra", "čaj", "chá"},
				new Serializable[]{"hrad", "ch", "c", "z", "čaj", "chá"}
			);
		}

		@Test
		@DisplayName("string arrays")
		void shouldMatchStringArraysAsFilterIndex() {
			assertScalarParity(
				KEY, String[].class, String.class, 0,
				new Serializable[]{new String[]{"x", "y"}, new String[0], new String[]{"café"}},
				new Serializable[]{"y", "café", "a"}
			);
		}

		@Test
		@DisplayName("offset date times compared as the instant they denote")
		void shouldMatchOffsetDateTimesAsFilterIndex() {
			assertScalarParity(
				KEY, OffsetDateTime.class, OffsetDateTime.class, 0,
				new Serializable[]{
					odt("2026-01-01T10:00:00+02:00"), odt("2026-01-01T08:00:00Z"), odt("2026-06-01T00:00:00Z"),
					odt("2025-12-31T23:59:59.999Z")
				},
				new Serializable[]{
					odt("2026-01-01T08:00:00Z"), odt("2026-01-01T09:00:00+01:00"), odt("2026-03-01T00:00:00Z")
				}
			);
		}

		@Test
		@DisplayName("local date times, dates and times")
		void shouldMatchLocalTemporalsAsFilterIndex() {
			assertScalarParity(
				KEY, LocalDateTime.class, LocalDateTime.class, 0,
				new Serializable[]{
					LocalDateTime.parse("2026-01-01T08:00:00"), LocalDateTime.parse("2026-06-01T00:00:00")
				},
				new Serializable[]{
					LocalDateTime.parse("2026-01-01T08:00:00"), LocalDateTime.parse("2026-03-01T00:00:00")
				}
			);
			assertScalarParity(
				KEY, LocalDate.class, LocalDate.class, 0,
				new Serializable[]{LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01")},
				new Serializable[]{LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01")}
			);
			assertScalarParity(
				KEY, LocalTime.class, LocalTime.class, 0,
				new Serializable[]{LocalTime.parse("08:00:00"), LocalTime.parse("12:30:15.123")},
				new Serializable[]{LocalTime.parse("12:30:15.123"), LocalTime.parse("10:00:00")}
			);
		}

		@Test
		@DisplayName("currencies, locales and booleans")
		void shouldMatchCurrenciesLocalesAndBooleansAsFilterIndex() {
			assertScalarParity(
				KEY, Currency.class, Currency.class, 0,
				new Serializable[]{
					Currency.getInstance("CZK"), Currency.getInstance("EUR"), Currency.getInstance("USD")
				},
				new Serializable[]{Currency.getInstance("EUR"), "CZK"}
			);
			assertScalarParity(
				KEY, Locale.class, Locale.class, 0,
				new Serializable[]{Locale.forLanguageTag("cs"), Locale.ENGLISH, Locale.GERMAN},
				new Serializable[]{Locale.ENGLISH, "cs"}
			);
			assertScalarParity(
				KEY, Boolean.class, Boolean.class, 0,
				new Serializable[]{true, false, true},
				new Serializable[]{true, false}
			);
		}

		@Test
		@DisplayName("string searches over NFD and NFC strings, plain and localized")
		void shouldSearchStringsAsFilterIndex() {
			final Serializable[] stored = {"apple", "café", "café", "Cafeteria", "", "mocha"};
			final String[] texts = {"ca", "é", "é", "Ca", "", "a", "café"};
			assertStringSearchParity(KEY, String.class, stored, texts);
			assertStringSearchParity(
				new AttributeIndexKey(null, "a", Locale.forLanguageTag("cs")), String.class, stored, texts
			);
			assertStringSearchParity(
				KEY, String[].class, new Serializable[]{new String[]{"x", "café"}, new String[0]}, texts
			);
		}

		@Test
		@DisplayName("integer ranges - overlap and envelope, inverted interval and arrays")
		void shouldMatchIntegerRangesAsRangeIndex() {
			final Serializable[][] bounds = {
				bounds(2, 4), bounds(5, 9), bounds(6, 9), bounds(null, 0), bounds(11, null), bounds(9, 5), bounds(3, 3)
			};
			final AttributeInRange[] inRange = {
				new AttributeInRange("a", 3), new AttributeInRange("a", 5), new AttributeInRange("a", 6),
				new AttributeInRange("a", 0), new AttributeInRange("a", 100), new AttributeInRange("a", bd("4.6"))
			};
			assertRangeParity(
				IntegerNumberRange.class, IntegerNumberRange.class, 0,
				new Serializable[]{
					IntegerNumberRange.between(1, 5), IntegerNumberRange.from(10), IntegerNumberRange.to(0),
					IntegerNumberRange.between(3, 3)
				},
				bounds, inRange
			);
			assertRangeParity(
				IntegerNumberRange[].class, IntegerNumberRange.class, 0,
				new Serializable[]{
					new IntegerNumberRange[]{IntegerNumberRange.between(1, 2), IntegerNumberRange.between(10, 20)},
					new IntegerNumberRange[0],
					new IntegerNumberRange[]{IntegerNumberRange.between(4, 6), IntegerNumberRange.between(5, 8)}
				},
				bounds, inRange
			);
			assertRangeParity(
				LongNumberRange.class, LongNumberRange.class, 0,
				new Serializable[]{LongNumberRange.between(1L, 5L), LongNumberRange.from(10L)},
				bounds, inRange
			);
		}

		@Test
		@DisplayName("BigDecimal ranges rescaled to the indexed decimal places")
		void shouldMatchBigDecimalRangesAsRangeIndex() {
			assertRangeParity(
				BigDecimalNumberRange.class, BigDecimalNumberRange.class, 2,
				new Serializable[]{
					BigDecimalNumberRange.between(bd("1.5"), bd("2.5")),
					BigDecimalNumberRange.between(bd("1.005"), bd("1.01")),
					BigDecimalNumberRange.from(bd("10.123")),
					BigDecimalNumberRange.to(bd("-1"))
				},
				new Serializable[][]{
					bounds(bd("2.0"), bd("3.0")), bounds(bd("1.01"), bd("1.01")), bounds(bd("10.12"), null),
					bounds(null, bd("-1.004")), bounds(bd("3"), bd("2"))
				},
				new AttributeInRange[]{
					new AttributeInRange("a", bd("2.0")), new AttributeInRange("a", bd("1.005")),
					new AttributeInRange("a", bd("10.125")), new AttributeInRange("a", bd("10.12")),
					new AttributeInRange("a", 2)
				}
			);
		}

		@Test
		@DisplayName("date time ranges, including the moment of the query")
		void shouldMatchDateTimeRangesAsRangeIndex() {
			assertRangeParity(
				DateTimeRange.class, DateTimeRange.class, 0,
				new Serializable[]{
					DateTimeRange.between(odt("2026-01-01T00:00:00Z"), odt("2026-12-31T23:59:59Z")),
					DateTimeRange.since(odt("2026-06-01T00:00:00+02:00")),
					DateTimeRange.until(odt("2025-01-01T00:00:00Z"))
				},
				new Serializable[][]{
					bounds(odt("2026-05-31T22:00:00Z"), odt("2026-05-31T22:00:00Z")),
					bounds(null, odt("2025-06-01T00:00:00Z")),
					bounds(odt("2027-01-01T00:00:00Z"), null),
					bounds(odt("2027-01-01T00:00:00Z"), odt("2026-01-01T00:00:00Z"))
				},
				new AttributeInRange[]{
					new AttributeInRange("a"),
					new AttributeInRange("a", odt("2024-06-01T00:00:00Z")),
					new AttributeInRange("a", odt("2026-12-31T23:59:59Z"))
				}
			);
		}

	}

	@Nested
	@DisplayName("Values the index cannot hold and refusals")
	class BeyondTheIndex {

		@Test
		@DisplayName("a BigDecimal beyond the int encoding is compared without overflowing")
		void shouldCompareBigDecimalBeyondIntRange() {
			final BigDecimal huge = bd("12345678901.23");
			// the index encoding overflows on this very value - the row filter must not
			assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt(huge, 2));
			assertTrue(AttributeValueMatcher.comparing(KEY, BigDecimal.class, 2, huge, r -> r == 0).matches(huge));
			assertTrue(AttributeValueMatcher.comparing(KEY, BigDecimal.class, 2, 1, r -> r > 0).matches(huge));
			assertFalse(
				AttributeValueMatcher.comparing(KEY, BigDecimal.class, 2, bd("12345678901.235"), r -> r == 0)
					.matches(huge)
			);
			assertTrue(
				AttributeValueMatcher.comparing(KEY, BigDecimal.class, 2, bd("99999999999"), r -> r < 0)
					.matches(bd("5"))
			);
			assertTrue(
				AttributeValueMatcher.between(KEY, BigDecimal.class, 2, bd("12345678901.225"), null).matches(huge)
			);
		}

		@Test
		@DisplayName("1.005 equals a stored 1.01 at two decimal places, as both scale half up to 101")
		void shouldScaleBigDecimalHalfUp() {
			assertTrue(
				AttributeValueMatcher.comparing(KEY, BigDecimal.class, 2, bd("1.005"), r -> r == 0)
					.matches(bd("1.01"))
			);
			assertFalse(
				AttributeValueMatcher.comparing(KEY, BigDecimal.class, 2, bd("1.0049"), r -> r == 0)
					.matches(bd("1.01"))
			);
			assertEquals(bd("101"), NumberUtils.scaleToInteger(bd("1.005"), 2));
			assertEquals(bd("-101"), NumberUtils.scaleToInteger(bd("-1.005"), 2));
			assertEquals(101, NumberUtils.convertToInt(bd("1.005"), 2));
		}

		@Test
		@DisplayName("no value and an empty array match nothing")
		void shouldMatchNoMissingValue() {
			final AttributeValueMatcher matcher = AttributeValueMatcher.comparing(
				KEY, Integer.class, 0, 1, r -> r != 0
			);
			assertFalse(matcher.matches(null));
			assertFalse(matcher.matches(new Integer[0]));
			assertTrue(matcher.matches(new Integer[]{1, 2}));
			assertFalse(matcher.matches(new Integer[]{1}));
		}

		@Test
		@DisplayName("an inverted scalar interval is refused with the message of the index, whatever the data")
		void shouldRefuseInvertedScalarInterval() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeValueMatcher.between(KEY, Integer.class, 0, 10, 5)
			);
			assertEquals("From must be lower than to: 10 vs. 5", exception.getMessage());
		}

		@Test
		@DisplayName("an inverted range interval matches nothing, as the range index answers it")
		void shouldMatchNothingForInvertedRangeInterval() {
			final AttributeValueMatcher matcher = AttributeValueMatcher.between(
				KEY, IntegerNumberRange.class, 0, 10, 5
			);
			assertFalse(matcher.matches(IntegerNumberRange.between(0, 100)));
		}

		@Test
		@DisplayName("a value of a type without order is refused")
		void shouldRefuseTypeWithoutOrder() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeValueMatcher.comparing(KEY, Predecessor.class, 0, Predecessor.HEAD, r -> r == 0)
			);
		}

		@Test
		@DisplayName("a constraint value that cannot be converted to the attribute type is refused")
		void shouldRefuseInconvertibleValue() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> AttributeValueMatcher.comparing(KEY, Integer.class, 0, "not a number", r -> r == 0)
			);
		}

		@Test
		@DisplayName("an offset date time with another offset equals the instant it denotes")
		void shouldCompareOffsetDateTimesAsInstants() {
			assertTrue(
				AttributeValueMatcher.comparing(
					KEY, OffsetDateTime.class, 0, odt("2026-01-01T08:00:00Z"), r -> r == 0
				).matches(odt("2026-01-01T10:00:00+02:00"))
			);
			assertTrue(
				AttributeValueMatcher.between(
					KEY, LocalDateTime.class, 0, LocalDateTime.parse("2026-01-01T00:00:00"), null
				).matches(LocalDateTime.of(2026, 1, 1, 0, 0).atOffset(ZoneOffset.UTC).toLocalDateTime())
			);
		}

	}

}
