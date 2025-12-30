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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Represents a parsed cron schedule that can calculate future execution times.
 *
 * This class parses a standard 6-field cron expression and provides methods
 * to calculate the next execution time from any given temporal value.
 *
 * ## Expression Format
 *
 * The expression consists of 6 space-separated fields:
 *
 * ```
 * second minute hour day-of-month month day-of-week
 * 0-59   0-59   0-23 1-31         1-12  0-7
 * ```
 *
 * ## Supported Syntax
 *
 * - `*` - matches all values in the field's range
 * - `n` - matches a specific value
 * - `n-m` - matches a range of values (inclusive)
 * - `n,m,o` - matches a list of values
 * - `* /n` or `n-m/s` - matches values at step intervals
 *
 * ## Named Values
 *
 * - Months: JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC
 * - Days of week: SUN, MON, TUE, WED, THU, FRI, SAT (SUN is both 0 and 7)
 *
 * ## Examples
 *
 * - `0 0 * * * *` - every hour at minute 0
 * - `0 * /10 * * * *` - every 10 minutes
 * - `0 0 8-10 * * *` - 8, 9, and 10 o'clock every day
 * - `0 0 6,19 * * *` - 6:00 AM and 7:00 PM every day
 * - `0 0/30 8-10 * * *` - 8:00, 8:30, 9:00, 9:30, 10:00, 10:30 every day
 * - `0 0 9-17 * * MON-FRI` - every hour from 9 to 17 on weekdays
 * - `0 0 0 25 12 *` - Christmas Day at midnight
 *
 * Inspired by Spring Framework's cron implementation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class CronSchedule {

	/**
	 * Maximum number of iterations when searching for next matching time.
	 * This prevents infinite loops for impossible expressions.
	 */
	static final int MAX_ITERATIONS = 255;
	/**
	 * Pattern for splitting cron expression into fields.
	 */
	private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s+");

	/**
	 * The field parsers in reverse order (day-of-week to seconds, plus nano reset).
	 * Reverse order ensures larger time units are processed first.
	 */
	private final CronFieldParser[] fieldParsers;

	/**
	 * The original expression string.
	 */
	private final String expression;

	private CronSchedule(
		@Nonnull CronFieldParser seconds,
		@Nonnull CronFieldParser minutes,
		@Nonnull CronFieldParser hours,
		@Nonnull CronFieldParser daysOfMonth,
		@Nonnull CronFieldParser months,
		@Nonnull CronFieldParser daysOfWeek,
		@Nonnull String expression
	) {
		// store in reverse order for processing (larger units first)
		// add nano reset to ensure we land on exact seconds
		this.fieldParsers = new CronFieldParser[] {
			daysOfWeek, months, daysOfMonth, hours, minutes, seconds, CronFieldParser.forZeroNanos()
		};
		this.expression = expression;
	}

	/**
	 * Parses a cron expression string into a CronSchedule.
	 *
	 * The expression must consist of exactly 6 space-separated fields:
	 * second, minute, hour, day-of-month, month, day-of-week.
	 *
	 * @param expression the cron expression to parse
	 * @return the parsed CronSchedule
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronSchedule fromExpression(@Nonnull String expression) {
		Assert.isTrue(!expression.isBlank(), "Cron expression must not be empty");

		final String[] fields = SPLIT_PATTERN.split(expression.trim());
		if (fields.length != 6) {
			throw new EvitaInvalidUsageException(
				"Cron expression must consist of 6 fields (found " + fields.length + " in \"" + expression + "\")"
			);
		}

		try {
			final CronFieldParser seconds = CronFieldParser.forSeconds(fields[0]);
			final CronFieldParser minutes = CronFieldParser.forMinutes(fields[1]);
			final CronFieldParser hours = CronFieldParser.forHours(fields[2]);
			final CronFieldParser daysOfMonth = CronFieldParser.forDaysOfMonth(fields[3]);
			final CronFieldParser months = CronFieldParser.forMonths(fields[4]);
			final CronFieldParser daysOfWeek = CronFieldParser.forDaysOfWeek(fields[5]);

			return new CronSchedule(seconds, minutes, hours, daysOfMonth, months, daysOfWeek, expression);
		} catch (EvitaInvalidUsageException ex) {
			throw new EvitaInvalidUsageException(ex.getPublicMessage() + " in cron expression \"" + expression + "\"");
		}
	}

	/**
	 * Checks whether the given string is a valid cron expression.
	 *
	 * @param expression the expression to validate
	 * @return true if the expression is valid, false otherwise
	 */
	public static boolean isValid(@Nullable String expression) {
		if (expression == null) {
			return false;
		}
		try {
			fromExpression(expression);
			return true;
		} catch (EvitaInvalidUsageException ex) {
			return false;
		}
	}

	/**
	 * Calculates the next time that matches this cron schedule after the given temporal.
	 *
	 * The returned temporal will be at least 1 nanosecond after the provided seed value.
	 * If no matching time can be found within a reasonable number of iterations
	 * (e.g., for impossible expressions like February 31st), null is returned.
	 *
	 * @param fromTime the seed temporal to start searching from
	 * @param <T> the temporal type (must be both Temporal and Comparable)
	 * @return the next matching temporal, or null if none found
	 */
	@Nullable
	public <T extends Temporal & Comparable<? super T>> T calculateNext(@Nonnull T fromTime) {
		return findNextOrSame(ChronoUnit.NANOS.addTo(fromTime, 1));
	}

	/**
	 * Finds the next temporal that matches or is the same as the given temporal.
	 *
	 * @param temporal the temporal to start searching from
	 * @param <T> the temporal type (must be both Temporal and Comparable)
	 * @return the next matching temporal, or null if none found within max iterations
	 */
	@Nullable
	private <T extends Temporal & Comparable<? super T>> T findNextOrSame(@Nonnull T temporal) {
		for (int i = 0; i < MAX_ITERATIONS; i++) {
			final T result = findNextOrSameInternal(temporal);
			if (result == null || result.equals(temporal)) {
				return result;
			}
			temporal = result;
		}
		return null;
	}

	/**
	 * Single iteration of finding the next matching temporal.
	 *
	 * @param temporal the temporal to evaluate and potentially advance
	 * @param <T> the temporal type (must be both Temporal and Comparable)
	 * @return the adjusted temporal matching all field parsers, or null if no match possible
	 */
	@Nullable
	private <T extends Temporal & Comparable<? super T>> T findNextOrSameInternal(@Nonnull T temporal) {
		for (CronFieldParser parser : this.fieldParsers) {
			temporal = parser.findNextOrSame(temporal);
			if (temporal == null) {
				return null;
			}
		}
		return temporal;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof CronSchedule that)) {
			return false;
		}
		return Arrays.equals(this.fieldParsers, that.fieldParsers);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.fieldParsers);
	}

	/**
	 * Returns the original expression string used to create this schedule.
	 *
	 * @return the cron expression string
	 */
	@Override
	public String toString() {
		return this.expression;
	}

}
