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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static java.time.DayOfWeek.*;
import static java.time.temporal.TemporalAdjusters.next;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CronSchedule}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("CronSchedule")
class CronScheduleTest {

	@Test
	@DisplayName("should validate expressions correctly")
	void shouldValidateExpressions() {
		assertFalse(CronSchedule.isValid(null));
		assertFalse(CronSchedule.isValid(""));
		assertFalse(CronSchedule.isValid("*"));
		assertFalse(CronSchedule.isValid("* * * * *"));
		assertFalse(CronSchedule.isValid("* * * * * * *"));

		assertTrue(CronSchedule.isValid("* * * * * *"));
		assertTrue(CronSchedule.isValid("0 0 0 1 1 *"));
		assertTrue(CronSchedule.isValid("0 0 12 * * MON-FRI"));
	}

	@Test
	@DisplayName("should match all with wildcards")
	void shouldMatchAllWithWildcards() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * * * *");

		final LocalDateTime last = LocalDateTime.now();
		final LocalDateTime expected = last.plusSeconds(1).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should match last second of minute")
	void shouldMatchLastSecondOfMinute() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * * * *");

		final LocalDateTime last = LocalDateTime.now().withSecond(58);
		final LocalDateTime expected = last.plusSeconds(1).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should match specific second")
	void shouldMatchSpecificSecond() {
		final CronSchedule schedule = CronSchedule.fromExpression("10 * * * * *");

		final LocalDateTime now = LocalDateTime.now();
		final LocalDateTime last = now.withSecond(9);
		final LocalDateTime expected = last.withSecond(10).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment second by one")
	void shouldIncrementSecondByOne() {
		final CronSchedule schedule = CronSchedule.fromExpression("11 * * * * *");

		final LocalDateTime last = LocalDateTime.now().withSecond(10);
		final LocalDateTime expected = last.plusSeconds(1).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment second and rollover to next minute")
	void shouldIncrementSecondAndRollover() {
		final CronSchedule schedule = CronSchedule.fromExpression("10 * * * * *");

		final LocalDateTime last = LocalDateTime.now().withSecond(11);
		final LocalDateTime expected = last.plusMinutes(1).withSecond(10).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should handle second range")
	void shouldHandleSecondRange() {
		final CronSchedule schedule = CronSchedule.fromExpression("10-15 * * * * *");
		final LocalDateTime now = LocalDateTime.now();

		for (int i = 9; i < 15; i++) {
			final LocalDateTime last = now.withSecond(i);
			final LocalDateTime expected = last.plusSeconds(1).withNano(0);
			assertEquals(expected, schedule.calculateNext(last));
		}
	}

	@Test
	@DisplayName("should increment minute")
	void shouldIncrementMinute() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 * * * * *");

		final LocalDateTime last = LocalDateTime.now().withMinute(10);
		final LocalDateTime expected = last.plusMinutes(1).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusMinutes(1), next);
	}

	@Test
	@DisplayName("should increment minute by one")
	void shouldIncrementMinuteByOne() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 11 * * * *");

		final LocalDateTime last = LocalDateTime.now().withMinute(10);
		final LocalDateTime expected = last.plusMinutes(1).withSecond(0).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment minute and rollover to next hour")
	void shouldIncrementMinuteAndRollover() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 10 * * * *");

		final LocalDateTime last = LocalDateTime.now().withMinute(11).withSecond(0);
		final LocalDateTime expected = last.plusMinutes(59).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment hour")
	void shouldIncrementHour() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 * * * *");

		final int year = Year.now().getValue();
		final LocalDateTime last = LocalDateTime.of(year, 10, 30, 11, 1);
		final LocalDateTime expected = last.withHour(12).withMinute(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.withHour(13), next);
	}

	@Test
	@DisplayName("should increment hour and rollover to next day")
	void shouldIncrementHourAndRollover() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 * * * *");

		final int year = Year.now().getValue();
		final LocalDateTime last = LocalDateTime.of(year, 9, 10, 23, 1);
		final LocalDateTime expected = last.withDayOfMonth(11).withHour(0).withMinute(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment day of month")
	void shouldIncrementDayOfMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 * * *");

		final LocalDateTime last = LocalDateTime.now().withDayOfMonth(1);
		final LocalDateTime expected = last.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusDays(1), next);
	}

	@Test
	@DisplayName("should increment day of month by one")
	void shouldIncrementDayOfMonthByOne() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * 10 * *");

		final LocalDateTime last = LocalDateTime.now().withDayOfMonth(9);
		final LocalDateTime expected = last.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment day of month and rollover to next month")
	void shouldIncrementDayOfMonthAndRollover() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * 10 * *");

		final LocalDateTime last = LocalDateTime.now().withDayOfMonth(11);
		final LocalDateTime expected = last.plusMonths(1).withDayOfMonth(10)
			.withHour(0).withMinute(0).withSecond(0).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should handle daily trigger in short month")
	void shouldHandleDailyTriggerInShortMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 * * *");

		// September: 30 days
		final LocalDateTime last = LocalDateTime.now().withMonth(9).withDayOfMonth(30);
		final LocalDateTime expected = LocalDateTime.of(last.getYear(), 10, 1, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.withDayOfMonth(2), next);
	}

	@Test
	@DisplayName("should handle daily trigger in long month")
	void shouldHandleDailyTriggerInLongMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 * * *");

		// August: 31 days
		final LocalDateTime last = LocalDateTime.now().withMonth(8).withDayOfMonth(30);
		final LocalDateTime expected = last.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusDays(1), next);
	}

	@Test
	@DisplayName("should handle daily trigger on daylight saving boundary")
	void shouldHandleDailyTriggerOnDaylightSavingBoundary() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 * * *");

		// October: 31 days and a daylight saving boundary in CET
		final ZonedDateTime last = ZonedDateTime.now(ZoneId.of("CET")).withMonth(10).withDayOfMonth(30);
		final ZonedDateTime expected = last.withDayOfMonth(31).withHour(0).withMinute(0).withSecond(0).withNano(0);
		final ZonedDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final ZonedDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.withMonth(11).withDayOfMonth(1), next);
	}

	@Test
	@DisplayName("should increment month")
	void shouldIncrementMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 1 * *");

		final LocalDateTime last = LocalDateTime.now().withMonth(10).withDayOfMonth(30);
		final LocalDateTime expected = LocalDateTime.of(last.getYear(), 11, 1, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.withMonth(12), next);
	}

	@Test
	@DisplayName("should increment month and rollover to next year")
	void shouldIncrementMonthAndRollover() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 1 * *");

		final LocalDateTime last = LocalDateTime.now().withYear(2010).withMonth(12).withDayOfMonth(31);
		final LocalDateTime expected = LocalDateTime.of(2011, 1, 1, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusMonths(1), next);
	}

	@Test
	@DisplayName("should handle monthly trigger in long month")
	void shouldHandleMonthlyTriggerInLongMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 31 * *");

		final LocalDateTime last = LocalDateTime.now().withMonth(10).withDayOfMonth(30);
		final LocalDateTime expected = last.withDayOfMonth(31).withHour(0).withMinute(0).withSecond(0).withNano(0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should handle monthly trigger in short month")
	void shouldHandleMonthlyTriggerInShortMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 1 * *");

		final LocalDateTime last = LocalDateTime.now().withMonth(10).withDayOfMonth(30);
		final LocalDateTime expected = LocalDateTime.of(last.getYear(), 11, 1, 0, 0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should increment day of week by one")
	void shouldIncrementDayOfWeekByOne() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * * * 2");

		final LocalDateTime last = LocalDateTime.now().with(next(MONDAY));
		final LocalDateTime expected = last.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);
		assertEquals(TUESDAY, actual.getDayOfWeek());
	}

	@Test
	@DisplayName("should increment day of week and rollover")
	void shouldIncrementDayOfWeekAndRollover() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * * * 2");

		final LocalDateTime last = LocalDateTime.now().with(next(WEDNESDAY));
		final LocalDateTime expected = last.plusDays(6).withHour(0).withMinute(0).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);
		assertEquals(TUESDAY, actual.getDayOfWeek());
	}

	@Test
	@DisplayName("should handle specific minute and second")
	void shouldHandleSpecificMinuteAndSecond() {
		final CronSchedule schedule = CronSchedule.fromExpression("55 5 * * * *");

		final LocalDateTime last = LocalDateTime.now().withMinute(4).withSecond(54);
		final LocalDateTime expected = last.plusMinutes(1).withSecond(55).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusHours(1), next);
	}

	@Test
	@DisplayName("should handle specific hour and second")
	void shouldHandleSpecificHourAndSecond() {
		final CronSchedule schedule = CronSchedule.fromExpression("55 * 10 * * *");

		final LocalDateTime last = LocalDateTime.now().withHour(9).withSecond(54);
		final LocalDateTime expected = last.plusHours(1).withMinute(0).withSecond(55).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusMinutes(1), next);
	}

	@Test
	@DisplayName("should handle specific minute and hour")
	void shouldHandleSpecificMinuteAndHour() {
		final CronSchedule schedule = CronSchedule.fromExpression("* 5 10 * * *");

		final LocalDateTime last = LocalDateTime.now().withHour(9).withMinute(4);
		final LocalDateTime expected = last.plusHours(1).plusMinutes(1).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		// next trigger is in one second because second is wildcard
		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusSeconds(1), next);
	}

	@Test
	@DisplayName("should handle specific day of month and second")
	void shouldHandleSpecificDayOfMonthAndSecond() {
		final CronSchedule schedule = CronSchedule.fromExpression("55 * * 3 * *");

		final LocalDateTime last = LocalDateTime.now().withDayOfMonth(2).withSecond(54);
		final LocalDateTime expected = last.plusDays(1).withHour(0).withMinute(0).withSecond(55).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusMinutes(1), next);
	}

	@Test
	@DisplayName("should handle specific date")
	void shouldHandleSpecificDate() {
		final CronSchedule schedule = CronSchedule.fromExpression("* * * 3 11 *");

		final LocalDateTime last = LocalDateTime.now().withMonth(10).withDayOfMonth(2);
		final LocalDateTime expected = LocalDateTime.of(last.getYear(), 11, 3, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusSeconds(1), next);
	}

	@Test
	@DisplayName("should return null for non-existent date")
	void shouldReturnNullForNonExistentDate() {
		// June 31st doesn't exist
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 31 6 *");

		final LocalDateTime last = LocalDateTime.now().withMonth(3).withDayOfMonth(10);
		assertNull(schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should handle leap year specific date")
	void shouldHandleLeapYearSpecificDate() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 29 2 *");

		final LocalDateTime last = LocalDateTime.now().withYear(2007).withMonth(2).withDayOfMonth(10);
		final LocalDateTime expected = LocalDateTime.of(2008, 2, 29, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusYears(4), next);
	}

	@Test
	@DisplayName("should handle weekday sequence")
	void shouldHandleWeekdaySequence() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 7 * * MON-FRI");

		// This is a Saturday
		final LocalDateTime last = LocalDateTime.of(LocalDate.of(2009, 9, 26), LocalTime.now());
		final LocalDateTime expected = last.plusDays(2).withHour(7).withMinute(0).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		// Next day is a weekday so add one
		final LocalDateTime next = schedule.calculateNext(actual);
		assertEquals(expected.plusDays(1), next);
	}

	@Test
	@DisplayName("should handle month sequence with step")
	void shouldHandleMonthSequenceWithStep() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 30 23 30 1/3 *");

		final LocalDateTime last = LocalDateTime.of(LocalDate.of(2010, 12, 30), LocalTime.now());
		final LocalDateTime expected = last.plusMonths(1).withHour(23).withMinute(30).withSecond(0).withNano(0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		// Next trigger is 3 months later
		final LocalDateTime next = schedule.calculateNext(actual);
		assertNotNull(next);
		assertEquals(expected.plusMonths(3), next);
	}

	@Test
	@DisplayName("should handle fixed day of month and day of week")
	void shouldHandleFixedDayOfMonthAndDayOfWeek() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 29 2 WED");

		final LocalDateTime last = LocalDateTime.of(2012, 2, 29, 1, 0);
		assertEquals(WEDNESDAY, last.getDayOfWeek());

		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(29, actual.getDayOfMonth());
		assertEquals(WEDNESDAY, actual.getDayOfWeek());
	}

	@Test
	@DisplayName("should handle Friday the 13th")
	void shouldHandleFriday13th() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 13 * FRI");

		final LocalDateTime last = LocalDateTime.of(2018, 7, 31, 11, 47, 14);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(FRIDAY, actual.getDayOfWeek());
		assertEquals(13, actual.getDayOfMonth());

		final LocalDateTime next = schedule.calculateNext(actual);
		assertNotNull(next);
		assertEquals(FRIDAY, next.getDayOfWeek());
		assertEquals(13, next.getDayOfMonth());
	}

	@Test
	@DisplayName("should handle every 10 days with day of week")
	void shouldHandleEveryTenDaysWithDayOfWeek() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 15 12 */10 1-8 5");

		final LocalDateTime last = LocalDateTime.parse("2021-04-30T12:14:59");
		final LocalDateTime expected = LocalDateTime.parse("2021-05-21T12:15");
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		final LocalDateTime next = schedule.calculateNext(actual);
		assertNotNull(next);
		assertEquals(LocalDateTime.parse("2021-06-11T12:15"), next);
	}

	@Test
	@DisplayName("should handle Sunday to Friday range")
	void shouldHandleSundayToFridayRange() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 * * SUN-FRI");

		final LocalDateTime last = LocalDateTime.of(2021, 2, 25, 15, 0);
		final LocalDateTime expected = LocalDateTime.of(2021, 2, 26, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);
		assertEquals(FRIDAY, actual.getDayOfWeek());

		final LocalDateTime next = schedule.calculateNext(actual);
		assertNotNull(next);
		assertEquals(LocalDateTime.of(2021, 2, 28, 0, 0), next);
		assertEquals(SUNDAY, next.getDayOfWeek());
	}

	@Test
	@DisplayName("should handle daylight saving transitions")
	void shouldHandleDaylightSavingTransitions() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 9 * * *");

		ZonedDateTime last = ZonedDateTime.parse("2021-03-27T09:00:00+01:00[Europe/Amsterdam]");
		ZonedDateTime expected = ZonedDateTime.parse("2021-03-28T09:00:00+02:00[Europe/Amsterdam]");
		ZonedDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);

		last = ZonedDateTime.parse("2021-10-30T09:00:00+02:00[Europe/Amsterdam]");
		expected = ZonedDateTime.parse("2021-10-31T09:00:00+01:00[Europe/Amsterdam]");
		actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);
	}

	@Test
	@DisplayName("should handle complex expression")
	void shouldHandleComplexExpression() {
		final CronSchedule schedule = CronSchedule.fromExpression("3-57 13-28 17,18 1,15 3-12 6");
		final LocalDateTime last = LocalDateTime.of(2022, 9, 15, 17, 44, 11);
		final LocalDateTime expected = LocalDateTime.of(2022, 10, 1, 17, 13, 3);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);
	}

	@Test
	@DisplayName("should handle step with day of month")
	void shouldHandleStepWithDayOfMonth() {
		final CronSchedule schedule = CronSchedule.fromExpression("*/28 56 22 */6 * *");
		final LocalDateTime last = LocalDateTime.of(2022, 2, 27, 8, 0, 42);
		final LocalDateTime expected = LocalDateTime.of(2022, 3, 1, 22, 56, 0);
		final LocalDateTime actual = schedule.calculateNext(last);
		assertNotNull(actual);
		assertEquals(expected, actual);
	}

	@Test
	@DisplayName("should reject invalid field count")
	void shouldRejectInvalidFieldCount() {
		// too few fields
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("* * * * *"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 0 0 1"));

		// too many fields
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("* * * * * * *"));
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 0 0 1 1 * 2025"));
	}

	@Test
	@DisplayName("should reject blank expression")
	void shouldRejectBlankExpression() {
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression(""));
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("   "));
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("\t\n"));
	}

	@Test
	@DisplayName("should handle multiple spaces between fields")
	void shouldHandleMultipleSpacesBetweenFields() {
		// multiple spaces should be handled by split pattern
		final CronSchedule schedule = CronSchedule.fromExpression("0  0  0  *  *  *");
		final LocalDateTime last = LocalDateTime.of(2023, 6, 15, 12, 0, 0);
		final LocalDateTime expected = LocalDateTime.of(2023, 6, 16, 0, 0, 0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should return null for September 31")
	void shouldReturnNullForSeptember31() {
		// September has only 30 days
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 31 9 *");
		final LocalDateTime last = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
		assertNull(schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should return null for November 31")
	void shouldReturnNullForNovember31() {
		// November has only 30 days
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 31 11 *");
		final LocalDateTime last = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
		assertNull(schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should return null for February 30")
	void shouldReturnNullForFebruary30() {
		// February never has 30 days, not even in leap years
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 30 2 *");
		final LocalDateTime last = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
		assertNull(schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should handle year boundary transition")
	void shouldHandleYearBoundaryTransition() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 1 1 *");
		final LocalDateTime last = LocalDateTime.of(2023, 12, 31, 23, 59, 59);
		final LocalDateTime expected = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should verify equals and hashCode contract")
	void shouldVerifyEqualsAndHashCode() {
		final CronSchedule schedule1 = CronSchedule.fromExpression("0 0 12 * * MON-FRI");
		final CronSchedule schedule2 = CronSchedule.fromExpression("0 0 12 * * MON-FRI");
		final CronSchedule schedule3 = CronSchedule.fromExpression("0 0 12 * * *");

		// equals contract
		assertEquals(schedule1, schedule1, "reflexive");
		assertTrue(schedule1.equals(schedule2) && schedule2.equals(schedule1), "symmetric");
		assertNotEquals(schedule1, schedule3, "different schedules should not be equal");
		assertNotEquals(null, schedule1, "null check");
		assertNotEquals("string", schedule1, "different type");

		// hashCode contract
		assertEquals(schedule1.hashCode(), schedule2.hashCode(), "equal objects must have equal hashCodes");
	}

	@Test
	@DisplayName("should return original expression from toString")
	void shouldReturnOriginalExpressionFromToString() {
		final String expression = "0 0 12 * * MON-FRI";
		final CronSchedule schedule = CronSchedule.fromExpression(expression);
		assertEquals(expression, schedule.toString());
	}

	@Test
	@DisplayName("should use AND semantics for day-of-month and day-of-week")
	void shouldUseAndSemanticsForDayFields() {
		// schedule that requires BOTH 15th of month AND Saturday
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 15 * SAT");

		// starting from a date where 15th is not Saturday
		final LocalDateTime last = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);

		// should find a date where both conditions are met
		assertNotNull(actual);
		assertEquals(15, actual.getDayOfMonth());
		assertEquals(SATURDAY, actual.getDayOfWeek());
	}

	@Test
	@DisplayName("should handle equivalent expressions with different syntax")
	void shouldHandleEquivalentExpressions() {
		// these should produce the same schedule
		final CronSchedule range = CronSchedule.fromExpression("0 0 0 * * 1-5");
		final CronSchedule named = CronSchedule.fromExpression("0 0 0 * * MON-FRI");

		assertEquals(range, named, "range 1-5 should equal MON-FRI");
	}

	@Test
	@DisplayName("should handle every minute for a full hour")
	void shouldHandleEveryMinuteForFullHour() {
		final CronSchedule schedule = CronSchedule.fromExpression("0 * 10 * * *");

		LocalDateTime current = LocalDateTime.of(2023, 6, 15, 9, 59, 0);
		final LocalDateTime expected = LocalDateTime.of(2023, 6, 15, 10, 0, 0);
		assertEquals(expected, schedule.calculateNext(current));

		// verify it advances through all 60 minutes
		for (int minute = 0; minute < 59; minute++) {
			current = LocalDateTime.of(2023, 6, 15, 10, minute, 0);
			final LocalDateTime next = schedule.calculateNext(current);
			assertNotNull(next);
			assertEquals(minute + 1, next.getMinute());
		}
	}

	@Test
	@DisplayName("should handle schedule spanning multiple years")
	void shouldHandleScheduleSpanningMultipleYears() {
		// schedule for February 29 (leap year only)
		final CronSchedule schedule = CronSchedule.fromExpression("0 0 0 29 2 *");

		// starting from 2023 (not a leap year)
		final LocalDateTime last = LocalDateTime.of(2023, 3, 1, 0, 0, 0);
		final LocalDateTime actual = schedule.calculateNext(last);

		// should find next leap year (2024)
		assertNotNull(actual);
		assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0, 0), actual);
	}

	@Test
	@DisplayName("should handle tabs as field separators")
	void shouldHandleTabsAsFieldSeparators() {
		final CronSchedule schedule = CronSchedule.fromExpression("0\t0\t0\t*\t*\t*");
		final LocalDateTime last = LocalDateTime.of(2023, 6, 15, 12, 0, 0);
		final LocalDateTime expected = LocalDateTime.of(2023, 6, 16, 0, 0, 0);
		assertEquals(expected, schedule.calculateNext(last));
	}

	@Test
	@DisplayName("should reject out of range field values")
	void shouldRejectOutOfRangeFieldValues() {
		// second out of range
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("60 0 0 * * *"));

		// minute out of range
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 60 0 * * *"));

		// hour out of range
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 0 24 * * *"));

		// day of month out of range
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 0 0 32 * *"));

		// month out of range
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 0 0 * 13 *"));

		// day of week out of range
		assertThrows(EvitaInvalidUsageException.class, () -> CronSchedule.fromExpression("0 0 0 * * 8"));
	}
}
