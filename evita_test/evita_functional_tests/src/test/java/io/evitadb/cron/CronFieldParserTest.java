/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.cron;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CronFieldParser}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("CronFieldParser")
class CronFieldParserTest {

	@Test
	@DisplayName("should parse single values correctly")
	void shouldParseSingleValues() {
		final CronFieldParser seconds = CronFieldParser.forSeconds("42");
		assertBitsClearedInRange(seconds, 0, 41);
		assertBitsSet(seconds, 42);
		assertBitsClearedInRange(seconds, 43, 59);

		final CronFieldParser minutes = CronFieldParser.forMinutes("30");
		assertBitsSet(minutes, 30);
		assertBitsClearedInRange(minutes, 1, 29);
		assertBitsClearedInRange(minutes, 31, 59);

		final CronFieldParser hours = CronFieldParser.forHours("23");
		assertBitsSet(hours, 23);
		assertBitsClearedInRange(hours, 0, 22);

		final CronFieldParser daysOfMonth = CronFieldParser.forDaysOfMonth("1");
		assertBitsSet(daysOfMonth, 1);
		assertBitsClearedInRange(daysOfMonth, 2, 31);

		final CronFieldParser months = CronFieldParser.forMonths("1");
		assertBitsSet(months, 1);
		assertBitsClearedInRange(months, 2, 12);

		// Sunday (0 in cron) is converted to 7 for java.time
		final CronFieldParser daysOfWeek = CronFieldParser.forDaysOfWeek("0");
		assertBitsSet(daysOfWeek, 7);
		assertBitsClearedInRange(daysOfWeek, 0, 6);
	}

	@Test
	@DisplayName("should parse ranges correctly")
	void shouldParseRanges() {
		final CronFieldParser range1 = CronFieldParser.forSeconds("0-4");
		assertBitsSetInRange(range1, 0, 4);
		assertBitsClearedInRange(range1, 5, 59);

		final CronFieldParser range2 = CronFieldParser.forSeconds("8-12");
		assertBitsClearedInRange(range2, 0, 7);
		assertBitsSetInRange(range2, 8, 12);
		assertBitsClearedInRange(range2, 13, 59);

		// wrap-around range for days of week (7-5 means Sun through Fri)
		final CronFieldParser wrapRange = CronFieldParser.forDaysOfWeek("7-5");
		assertBitsCleared(wrapRange, 0);
		assertBitsSetInRange(wrapRange, 1, 5);
		assertBitsCleared(wrapRange, 6);
		assertBitsSet(wrapRange, 7);
	}

	@Test
	@DisplayName("should parse step values correctly")
	void shouldParseStepValues() {
		final CronFieldParser step1 = CronFieldParser.forSeconds("57/2");
		assertBitsClearedInRange(step1, 0, 56);
		assertBitsSet(step1, 57);
		assertBitsCleared(step1, 58);
		assertBitsSet(step1, 59);

		final CronFieldParser step2 = CronFieldParser.forHours("0-23/2");
		assertBitsSet(step2, 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22);
		assertBitsCleared(step2, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23);
	}

	@Test
	@DisplayName("should parse lists correctly")
	void shouldParseLists() {
		final CronFieldParser list1 = CronFieldParser.forSeconds("15,30");
		assertBitsSet(list1, 15, 30);
		assertBitsClearedInRange(list1, 0, 14);
		assertBitsClearedInRange(list1, 16, 29);
		assertBitsClearedInRange(list1, 31, 59);

		final CronFieldParser list2 = CronFieldParser.forMinutes("1,2,5,9");
		assertBitsSet(list2, 1, 2, 5, 9);
		assertBitsCleared(list2, 0);
		assertBitsClearedInRange(list2, 3, 4);
		assertBitsClearedInRange(list2, 6, 8);
		assertBitsClearedInRange(list2, 10, 59);

		final CronFieldParser list3 = CronFieldParser.forHours("1,2,3");
		assertBitsSet(list3, 1, 2, 3);
		assertBitsClearedInRange(list3, 4, 23);

		final CronFieldParser list4 = CronFieldParser.forDaysOfMonth("1,2,3");
		assertBitsSet(list4, 1, 2, 3);
		assertBitsClearedInRange(list4, 4, 31);

		final CronFieldParser list5 = CronFieldParser.forMonths("1,2,3");
		assertBitsSet(list5, 1, 2, 3);
		assertBitsClearedInRange(list5, 4, 12);

		final CronFieldParser list6 = CronFieldParser.forDaysOfWeek("1,2,3");
		assertBitsSet(list6, 1, 2, 3);
		assertBitsClearedInRange(list6, 4, 7);
	}

	@Test
	@DisplayName("should parse complex lists with ranges and steps")
	void shouldParseComplexLists() {
		final CronFieldParser complex1 = CronFieldParser.forSeconds("0-4,8-12");
		assertBitsSetInRange(complex1, 0, 4);
		assertBitsClearedInRange(complex1, 5, 7);
		assertBitsSetInRange(complex1, 8, 12);
		assertBitsClearedInRange(complex1, 13, 59);

		final CronFieldParser complex2 = CronFieldParser.forMinutes("5,10-30/2");
		assertBitsClearedInRange(complex2, 0, 4);
		assertBitsSet(complex2, 5);
		assertBitsClearedInRange(complex2, 6, 9);
		assertBitsSet(complex2, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30);
		assertBitsCleared(complex2, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29);
		assertBitsClearedInRange(complex2, 31, 59);
	}

	@Test
	@DisplayName("should parse wildcards correctly")
	void shouldParseWildcards() {
		final CronFieldParser seconds = CronFieldParser.forSeconds("*");
		assertBitsSetInRange(seconds, 0, 59);

		final CronFieldParser minutes = CronFieldParser.forMinutes("*");
		assertBitsSetInRange(minutes, 0, 59);

		final CronFieldParser hours = CronFieldParser.forHours("*");
		assertBitsSetInRange(hours, 0, 23);

		final CronFieldParser daysOfMonth = CronFieldParser.forDaysOfMonth("*");
		assertBitsCleared(daysOfMonth, 0);
		assertBitsSetInRange(daysOfMonth, 1, 31);

		final CronFieldParser months = CronFieldParser.forMonths("*");
		assertBitsCleared(months, 0);
		assertBitsSetInRange(months, 1, 12);

		final CronFieldParser daysOfWeek = CronFieldParser.forDaysOfWeek("*");
		assertBitsCleared(daysOfWeek, 0);
		assertBitsSetInRange(daysOfWeek, 1, 7);
	}

	@Test
	@DisplayName("should parse named months correctly")
	void shouldParseNamedMonths() {
		final CronFieldParser allMonths = CronFieldParser.forMonths("JAN,FEB,MAR,APR,MAY,JUN,JUL,AUG,SEP,OCT,NOV,DEC");
		assertBitsCleared(allMonths, 0);
		assertBitsSetInRange(allMonths, 1, 12);

		final CronFieldParser q1 = CronFieldParser.forMonths("jan,feb,mar");
		assertBitsSet(q1, 1, 2, 3);
		assertBitsClearedInRange(q1, 4, 12);
	}

	@Test
	@DisplayName("should parse named days of week correctly")
	void shouldParseNamedDaysOfWeek() {
		final CronFieldParser allDays = CronFieldParser.forDaysOfWeek("SUN,MON,TUE,WED,THU,FRI,SAT");
		assertBitsCleared(allDays, 0);
		assertBitsSetInRange(allDays, 1, 7);

		final CronFieldParser weekdays = CronFieldParser.forDaysOfWeek("MON-FRI");
		assertBitsCleared(weekdays, 0);
		assertBitsSetInRange(weekdays, 1, 5);
		assertBitsClearedInRange(weekdays, 6, 7);
	}

	@Test
	@DisplayName("should reject invalid expressions")
	void shouldRejectInvalidExpressions() {
		// empty expression
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds(""));

		// zero step
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("0-12/0"));

		// out of range values
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("60"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMinutes("60"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forDaysOfMonth("0"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forDaysOfMonth("32"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMonths("0"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMonths("13"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forDaysOfWeek("8"));

		// invalid format
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("abc"));
	}

	@Test
	@DisplayName("should reject whitespace in comma-separated segments")
	void shouldRejectWhitespaceInSegments() {
		// whitespace after comma causes NumberFormatException which is wrapped
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("1, 3"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds(" 5"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMinutes("10 ,20"));
	}

	@Test
	@DisplayName("should handle very large step values")
	void shouldHandleVeryLargeStepValues() {
		// step of 100 on seconds (0-59) should only match 0
		final CronFieldParser largeStep = CronFieldParser.forSeconds("*/100");
		assertBitsSet(largeStep, 0);
		assertBitsClearedInRange(largeStep, 1, 59);

		// step of 50 on minutes should match 0 and 50
		final CronFieldParser step50 = CronFieldParser.forMinutes("*/50");
		assertBitsSet(step50, 0, 50);
		assertBitsClearedInRange(step50, 1, 49);
		assertBitsClearedInRange(step50, 51, 59);
	}

	@Test
	@DisplayName("should reject invalid step syntax")
	void shouldRejectInvalidStepSyntax() {
		// leading slash without range
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("/5"));

		// trailing slash without step value
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("5/"));

		// double slash
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("5//2"));

		// negative step value
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("0-59/-1"));
	}

	@Test
	@DisplayName("should handle Sunday as explicit value 7")
	void shouldHandleSundayAsExplicitSeven() {
		// explicit 7 should be treated as Sunday (mapped to bit 7 in java.time)
		final CronFieldParser sunday7 = CronFieldParser.forDaysOfWeek("7");
		assertBitsCleared(sunday7, 0);
		assertBitsClearedInRange(sunday7, 1, 6);
		assertBitsSet(sunday7, 7);

		// range starting with 7 (Sunday)
		final CronFieldParser sundayToTuesday = CronFieldParser.forDaysOfWeek("7-2");
		assertBitsCleared(sundayToTuesday, 0);
		assertBitsSetInRange(sundayToTuesday, 1, 2);
		assertBitsClearedInRange(sundayToTuesday, 3, 6);
		assertBitsSet(sundayToTuesday, 7);
	}

	@Test
	@DisplayName("should handle mixed named and numeric values")
	void shouldHandleMixedNamedAndNumericValues() {
		// mix of named months and numbers
		final CronFieldParser mixedMonths = CronFieldParser.forMonths("JAN,6,DEC");
		assertBitsSet(mixedMonths, 1, 6, 12);
		assertBitsClearedInRange(mixedMonths, 2, 5);
		assertBitsClearedInRange(mixedMonths, 7, 11);

		// mix of named days and numbers
		final CronFieldParser mixedDays = CronFieldParser.forDaysOfWeek("MON,3,FRI");
		assertBitsSet(mixedDays, 1, 3, 5);
		assertBitsCleared(mixedDays, 0, 2, 4, 6, 7);
	}

	@Test
	@DisplayName("should reject hour value 24")
	void shouldRejectHour24() {
		// hour must be 0-23, not 24
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forHours("24"));
	}

	@Test
	@DisplayName("should reject negative values")
	void shouldRejectNegativeValues() {
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("-1"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMinutes("-5"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forHours("-1"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forDaysOfMonth("-1"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMonths("-1"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forDaysOfWeek("-1"));
	}

	@Test
	@DisplayName("should handle step of 1 from specific value")
	void shouldHandleStepOfOneFromValue() {
		// 5/1 should match 5 through end of range
		final CronFieldParser step1 = CronFieldParser.forSeconds("5/1");
		assertBitsClearedInRange(step1, 0, 4);
		assertBitsSetInRange(step1, 5, 59);

		// 20/1 for hours should match 20-23
		final CronFieldParser hourStep1 = CronFieldParser.forHours("20/1");
		assertBitsClearedInRange(hourStep1, 0, 19);
		assertBitsSetInRange(hourStep1, 20, 23);
	}

	@Test
	@DisplayName("should verify equals and hashCode contract")
	void shouldVerifyEqualsAndHashCode() {
		final CronFieldParser parser1 = CronFieldParser.forSeconds("0,15,30,45");
		final CronFieldParser parser2 = CronFieldParser.forSeconds("0,15,30,45");
		final CronFieldParser parser3 = CronFieldParser.forSeconds("0,15,30");

		// equals contract
		assertEquals(parser1, parser1, "reflexive");
		assertTrue(parser1.equals(parser2) && parser2.equals(parser1), "symmetric");
		assertNotEquals(parser1, parser3, "different values should not be equal");
		assertNotEquals(null, parser1, "null check");
		assertNotEquals("string", parser1, "different type");

		// hashCode contract
		assertEquals(parser1.hashCode(), parser2.hashCode(), "equal objects must have equal hashCodes");

		// different field types with same bits should not be equal
		final CronFieldParser seconds = CronFieldParser.forSeconds("5");
		final CronFieldParser minutes = CronFieldParser.forMinutes("5");
		assertNotEquals(seconds, minutes, "different field types should not be equal");
	}

	@Test
	@DisplayName("should produce readable toString output")
	void shouldProduceReadableToString() {
		final CronFieldParser single = CronFieldParser.forSeconds("42");
		assertTrue(single.toString().contains("42"), "toString should contain enabled value");

		final CronFieldParser range = CronFieldParser.forMinutes("10-15");
		final String rangeStr = range.toString();
		assertTrue(rangeStr.contains("10") && rangeStr.contains("15"), "toString should contain range boundaries");

		final CronFieldParser wildcard = CronFieldParser.forHours("*");
		final String wildcardStr = wildcard.toString();
		assertTrue(wildcardStr.contains("0") && wildcardStr.contains("23"), "toString should show full range for wildcard");
	}

	@Test
	@DisplayName("should reject invalid named values")
	void shouldRejectInvalidNamedValues() {
		// typo in month name should result in invalid number
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMonths("JNA"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forMonths("JANUARY"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forDaysOfWeek("MONDAY"));
	}

	@Test
	@DisplayName("should reject empty segments in list")
	void shouldRejectEmptySegmentsInList() {
		// empty segment between commas
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds("1,,3"));

		// leading comma creates empty segment
		assertThrows(EvitaInvalidUsageException.class, () -> CronFieldParser.forSeconds(",1,2"));
	}

	@Test
	@DisplayName("should accept trailing comma silently")
	void shouldAcceptTrailingCommaSilently() {
		// Java's split() removes trailing empty strings, so trailing comma is silently ignored
		final CronFieldParser parser = CronFieldParser.forSeconds("1,2,");
		assertBitsSet(parser, 1, 2);
		assertBitsClearedInRange(parser, 3, 59);
		assertBitsCleared(parser, 0);
	}

	/**
	 * Asserts that specific bits are set (enabled) in the parser.
	 */
	private static void assertBitsSet(CronFieldParser parser, int... positions) {
		for (int pos : positions) {
			assertTrue(parser.isBitEnabled(pos), "Expected bit " + pos + " to be set");
		}
	}

	/**
	 * Asserts that all bits in a range are set (enabled).
	 */
	private static void assertBitsSetInRange(CronFieldParser parser, int min, int max) {
		for (int i = min; i <= max; i++) {
			assertTrue(parser.isBitEnabled(i), "Expected bit " + i + " to be set in range " + min + "-" + max);
		}
	}

	/**
	 * Asserts that specific bits are cleared (disabled) in the parser.
	 */
	private static void assertBitsCleared(CronFieldParser parser, int... positions) {
		for (int pos : positions) {
			assertFalse(parser.isBitEnabled(pos), "Expected bit " + pos + " to be cleared");
		}
	}

	/**
	 * Asserts that all bits in a range are cleared (disabled).
	 */
	private static void assertBitsClearedInRange(CronFieldParser parser, int min, int max) {
		for (int i = min; i <= max; i++) {
			assertFalse(parser.isBitEnabled(i), "Expected bit " + i + " to be cleared in range " + min + "-" + max);
		}
	}
}
