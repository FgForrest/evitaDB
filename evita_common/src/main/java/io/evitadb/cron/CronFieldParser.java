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
import java.time.DateTimeException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.ValueRange;
import java.util.Locale;

/**
 * Efficient bitmap-based parser for individual cron expression fields.
 *
 * This class uses a 64-bit long as a bitmap to represent which values are enabled
 * for a cron field. Each bit position corresponds to a possible value in the field's
 * range. This approach allows for O(1) membership testing and efficient iteration
 * over enabled values using bitwise operations.
 *
 * The maximum field range is 60 values (for seconds and minutes: 0-59),
 * which fits comfortably in a 64-bit long.
 *
 * Supported cron syntax:
 * - `*` - matches all values in the field's range
 * - `n` - matches a specific value
 * - `n-m` - matches a range of values (inclusive)
 * - `n,m,o` - matches a list of values
 * - `* /n` or `n-m/s` - matches values at step intervals
 *
 * Inspired by Spring Framework's cron implementation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class CronFieldParser {

	private static final String[] MONTH_NAMES = new String[] {
		"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
	};

	private static final String[] DAY_NAMES = new String[] {
		"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
	};

	/**
	 * Singleton instance for zero nanoseconds field.
	 */
	private static final CronFieldParser ZERO_NANOS = createZeroNanosParser();

	/**
	 * Mask for all bits set (used in bitwise operations).
	 */
	private static final long ALL_BITS_MASK = 0xFFFFFFFFFFFFFFFFL;

	/**
	 * The field type this parser handles.
	 */
	private final FieldType fieldType;

	/**
	 * Bitmap where each set bit represents an enabled value.
	 */
	private long enabledBits;

	private CronFieldParser(@Nonnull FieldType fieldType) {
		this.fieldType = fieldType;
	}

	/**
	 * Creates a parser for zero nanoseconds.
	 *
	 * @return parser configured to match only zero nanoseconds
	 */
	@Nonnull
	private static CronFieldParser createZeroNanosParser() {
		final CronFieldParser parser = new CronFieldParser(FieldType.NANO);
		parser.enableBit(0);
		return parser;
	}

	/**
	 * Creates a parser for 0 nanoseconds field.
	 * Used internally to ensure temporal values are reset to zero nanoseconds.
	 *
	 * @return parser that matches only zero nanoseconds
	 */
	@Nonnull
	static CronFieldParser forZeroNanos() {
		return ZERO_NANOS;
	}

	/**
	 * Creates a parser for the seconds field (first field in cron expression).
	 * Valid values: 0-59
	 *
	 * @param value the cron field expression to parse
	 * @return parser for the seconds field
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronFieldParser forSeconds(@Nonnull String value) {
		return parseExpression(value, FieldType.SECOND);
	}

	/**
	 * Creates a parser for the minutes field (second field in cron expression).
	 * Valid values: 0-59
	 *
	 * @param value the cron field expression to parse
	 * @return parser for the minutes field
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronFieldParser forMinutes(@Nonnull String value) {
		return parseExpression(value, FieldType.MINUTE);
	}

	/**
	 * Creates a parser for the hours field (third field in cron expression).
	 * Valid values: 0-23
	 *
	 * @param value the cron field expression to parse
	 * @return parser for the hours field
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronFieldParser forHours(@Nonnull String value) {
		return parseExpression(value, FieldType.HOUR);
	}

	/**
	 * Creates a parser for the day-of-month field (fourth field in cron expression).
	 * Valid values: 1-31
	 *
	 * @param value the cron field expression to parse
	 * @return parser for the day-of-month field
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronFieldParser forDaysOfMonth(@Nonnull String value) {
		return parseExpression(value, FieldType.DAY_OF_MONTH);
	}

	/**
	 * Creates a parser for the month field (fifth field in cron expression).
	 * Valid values: 1-12 or JAN-DEC
	 *
	 * @param value the cron field expression to parse
	 * @return parser for the month field
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronFieldParser forMonths(@Nonnull String value) {
		final String normalizedValue = substituteNamedValues(value, MONTH_NAMES);
		return parseExpression(normalizedValue, FieldType.MONTH);
	}

	/**
	 * Creates a parser for the day-of-week field (sixth field in cron expression).
	 * Valid values: 0-7 (0 and 7 both represent Sunday) or SUN-SAT
	 *
	 * @param value the cron field expression to parse
	 * @return parser for the day-of-week field
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	public static CronFieldParser forDaysOfWeek(@Nonnull String value) {
		final String normalizedValue = substituteNamedValues(value, DAY_NAMES);
		final CronFieldParser parser = parseExpression(normalizedValue, FieldType.DAY_OF_WEEK);
		// cron uses 0 for Sunday; java.time uses 7
		if (parser.isBitEnabled(0)) {
			parser.enableBit(7);
			parser.disableBit(0);
		}
		return parser;
	}

	/**
	 * Replaces named values (like JAN, MON) with their numeric equivalents.
	 *
	 * @param value the cron field expression potentially containing named values
	 * @param names array of named values where index+1 corresponds to the numeric replacement
	 * @return the expression with all named values replaced by their numeric equivalents
	 */
	@Nonnull
	private static String substituteNamedValues(@Nonnull String value, @Nonnull String[] names) {
		String result = value.toUpperCase(Locale.ROOT);
		for (int i = 0; i < names.length; i++) {
			final String numericValue = Integer.toString(i + 1);
			result = result.replace(names[i], numericValue);
		}
		return result;
	}

	/**
	 * Parses a cron field expression into a bitmap parser.
	 *
	 * @param expression the expression to parse
	 * @param fieldType the field type
	 * @return the configured parser
	 * @throws EvitaInvalidUsageException if the expression is invalid
	 */
	@Nonnull
	private static CronFieldParser parseExpression(@Nonnull String expression, @Nonnull FieldType fieldType) {
		Assert.isTrue(!expression.isBlank(), "Cron field expression must not be empty");
		Assert.notNull(fieldType, "Field type must not be null");

		try {
			final CronFieldParser parser = new CronFieldParser(fieldType);
			final String[] segments = expression.split(",");

			for (String segment : segments) {
				final int stepDelimiterPos = segment.indexOf('/');
				if (stepDelimiterPos == -1) {
					// no step - just a range or single value
					final ValueRange range = parseValueRange(segment, fieldType);
					parser.enableBitsInRange(range);
				} else {
					// has step value
					final String rangeStr = segment.substring(0, stepDelimiterPos);
					final String stepStr = segment.substring(stepDelimiterPos + 1);
					ValueRange range = parseValueRange(rangeStr, fieldType);

					// if no explicit range end, extend to field maximum
					if (rangeStr.indexOf('-') == -1) {
						range = ValueRange.of(range.getMinimum(), fieldType.getValueRange().getMaximum());
					}

					final int step = Integer.parseInt(stepStr);
					if (step <= 0) {
						throw new EvitaInvalidUsageException("Step value must be 1 or greater, got: " + step);
					}
					parser.enableBitsWithStep(range, step);
				}
			}
			return parser;
		} catch (DateTimeException | IllegalArgumentException ex) {
			throw new EvitaInvalidUsageException("Invalid cron field expression '" + expression + "': " + ex.getMessage());
		}
	}

	/**
	 * Parses a value or range expression.
	 *
	 * @param expression the expression (e.g., "*", "5", "1-10")
	 * @param fieldType the field type for validation
	 * @return the value range
	 */
	@Nonnull
	private static ValueRange parseValueRange(@Nonnull String expression, @Nonnull FieldType fieldType) {
		if ("*".equals(expression)) {
			return fieldType.getValueRange();
		}

		final int hyphenPos = expression.indexOf('-');
		if (hyphenPos == -1) {
			// single value
			final int value = fieldType.validateValue(Integer.parseInt(expression));
			return ValueRange.of(value, value);
		} else {
			// range
			int min = Integer.parseInt(expression.substring(0, hyphenPos));
			int max = Integer.parseInt(expression.substring(hyphenPos + 1));
			min = fieldType.validateValue(min);
			max = fieldType.validateValue(max);

			// handle Sunday as 7 when used as range minimum
			if (fieldType == FieldType.DAY_OF_WEEK && min == 7) {
				min = 0;
			}
			return ValueRange.of(min, max);
		}
	}

	/**
	 * Finds the next temporal value that matches this cron field.
	 *
	 * Given a seed temporal value, this method returns the same value if it matches
	 * the cron field, or advances to the next matching value. The method may return
	 * null if no matching value can be found within a reasonable number of iterations.
	 *
	 * @param temporal the seed temporal value to start from
	 * @param <T> the temporal type (must be both Temporal and Comparable)
	 * @return the next matching temporal, or null if none found
	 */
	@Nullable
	public <T extends Temporal & Comparable<? super T>> T findNextOrSame(@Nonnull T temporal) {
		int currentValue = this.fieldType.getValue(temporal);
		int nextValue = findNextEnabledValue(currentValue);

		if (nextValue == -1) {
			// no matching value found in current period, roll to next higher unit
			temporal = this.fieldType.rollToNextHigherUnit(temporal);
			nextValue = findNextEnabledValue(0);
		}

		if (nextValue == currentValue) {
			return temporal;
		}

		// advance to the next matching value
		int iterationCount = 0;
		currentValue = this.fieldType.getValue(temporal);

		while (currentValue != nextValue && iterationCount++ < CronSchedule.MAX_ITERATIONS) {
			temporal = this.fieldType.advanceToValue(temporal, nextValue);
			currentValue = this.fieldType.getValue(temporal);
			nextValue = findNextEnabledValue(currentValue);

			if (nextValue == -1) {
				temporal = this.fieldType.rollToNextHigherUnit(temporal);
				nextValue = findNextEnabledValue(0);
			}
		}

		if (iterationCount >= CronSchedule.MAX_ITERATIONS) {
			return null;
		}

		return this.fieldType.resetLowerOrderFields(temporal);
	}

	/**
	 * Checks if the bit at the given position is enabled.
	 *
	 * @param position the bit position
	 * @return true if the bit is set
	 */
	public boolean isBitEnabled(int position) {
		return (this.enabledBits & (1L << position)) != 0;
	}

	/**
	 * Finds the next enabled bit starting from the given position.
	 *
	 * @param fromPosition the position to start searching from (inclusive)
	 * @return the position of the next enabled bit, or -1 if none found
	 */
	private int findNextEnabledValue(int fromPosition) {
		final long maskedBits = this.enabledBits & (ALL_BITS_MASK << fromPosition);
		if (maskedBits != 0) {
			return Long.numberOfTrailingZeros(maskedBits);
		}
		return -1;
	}

	/**
	 * Enables all bits in the given range.
	 *
	 * @param range the range of bits to enable
	 */
	private void enableBitsInRange(@Nonnull ValueRange range) {
		if (range.getMinimum() == range.getMaximum()) {
			enableBit((int) range.getMinimum());
		} else {
			final long minMask = ALL_BITS_MASK << range.getMinimum();
			final long maxMask = ALL_BITS_MASK >>> -(range.getMaximum() + 1);
			this.enabledBits |= (minMask & maxMask);
		}
	}

	/**
	 * Enables bits in the given range at the specified step interval.
	 *
	 * @param range the range of bits
	 * @param step the step interval
	 */
	private void enableBitsWithStep(@Nonnull ValueRange range, int step) {
		if (step == 1) {
			enableBitsInRange(range);
		} else {
			for (int i = (int) range.getMinimum(); i <= range.getMaximum(); i += step) {
				enableBit(i);
			}
		}
	}

	/**
	 * Enables the bit at the given position.
	 *
	 * @param position the bit position
	 */
	private void enableBit(int position) {
		this.enabledBits |= (1L << position);
	}

	/**
	 * Disables the bit at the given position.
	 *
	 * @param position the bit position
	 */
	private void disableBit(int position) {
		this.enabledBits &= ~(1L << position);
	}

	/**
	 * Returns the field type this parser handles.
	 *
	 * @return the field type
	 */
	@Nonnull
	FieldType fieldType() {
		return this.fieldType;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof CronFieldParser that)) {
			return false;
		}
		return this.fieldType == that.fieldType && this.enabledBits == that.enabledBits;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(this.enabledBits);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(this.fieldType.toString());
		sb.append(" {");
		int position = findNextEnabledValue(0);
		if (position != -1) {
			sb.append(position);
			position = findNextEnabledValue(position + 1);
			while (position != -1) {
				sb.append(", ");
				sb.append(position);
				position = findNextEnabledValue(position + 1);
			}
		}
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Represents the type of cron field with its associated temporal unit and range.
	 *
	 * Each field type defines:
	 * - The corresponding Java temporal field
	 * - The higher-order unit for rollover operations
	 * - Lower-order fields to reset when this field changes
	 */
	enum FieldType {

		NANO(ChronoField.NANO_OF_SECOND, ChronoUnit.SECONDS),
		SECOND(ChronoField.SECOND_OF_MINUTE, ChronoUnit.MINUTES, ChronoField.NANO_OF_SECOND),
		MINUTE(ChronoField.MINUTE_OF_HOUR, ChronoUnit.HOURS, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND),
		HOUR(ChronoField.HOUR_OF_DAY, ChronoUnit.DAYS, ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND),
		DAY_OF_MONTH(ChronoField.DAY_OF_MONTH, ChronoUnit.MONTHS, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND),
		MONTH(ChronoField.MONTH_OF_YEAR, ChronoUnit.YEARS, ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND),
		DAY_OF_WEEK(ChronoField.DAY_OF_WEEK, ChronoUnit.WEEKS, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND);

		private final ChronoField chronoField;
		private final ChronoUnit higherOrderUnit;
		private final ChronoField[] lowerOrderFields;

		FieldType(
			@Nonnull ChronoField chronoField,
			@Nonnull ChronoUnit higherOrderUnit,
			@Nonnull ChronoField... lowerOrderFields
		) {
			this.chronoField = chronoField;
			this.higherOrderUnit = higherOrderUnit;
			this.lowerOrderFields = lowerOrderFields;
		}

		/**
		 * Extracts the value of this field type from the given temporal.
		 *
		 * @param temporal the temporal to extract value from
		 * @return the field value
		 */
		public int getValue(@Nonnull Temporal temporal) {
			return temporal.get(this.chronoField);
		}

		/**
		 * Returns the valid range of values for this field type.
		 *
		 * @return the value range
		 */
		@Nonnull
		public ValueRange getValueRange() {
			return this.chronoField.range();
		}

		/**
		 * Validates that the given value is within the valid range for this field type.
		 *
		 * @param value the value to validate
		 * @return the validated value
		 * @throws EvitaInvalidUsageException if the value is out of range
		 */
		public int validateValue(int value) {
			if (this == DAY_OF_WEEK && value == 0) {
				// cron allows 0 for Sunday
				return value;
			}
			try {
				return this.chronoField.checkValidIntValue(value);
			} catch (DateTimeException ex) {
				throw new EvitaInvalidUsageException(ex.getMessage());
			}
		}

		/**
		 * Advances the temporal to reach the target value for this field.
		 *
		 * @param temporal the temporal to advance
		 * @param targetValue the target value for this field
		 * @param <T> the temporal type
		 * @return the advanced temporal
		 */
		@SuppressWarnings("unchecked")
		@Nonnull
		public <T extends Temporal & Comparable<? super T>> T advanceToValue(@Nonnull T temporal, int targetValue) {
			final int currentValue = getValue(temporal);
			final ValueRange range = temporal.range(this.chronoField);
			if (currentValue < targetValue) {
				if (range.isValidIntValue(targetValue)) {
					return (T) temporal.with(this.chronoField, targetValue);
				} else {
					// target is invalid (e.g., Feb 29 in non-leap year), roll forward
					final long amount = range.getMaximum() - currentValue + 1;
					return this.chronoField.getBaseUnit().addTo(temporal, amount);
				}
			} else {
				final long amount = targetValue + range.getMaximum() - currentValue + 1 - range.getMinimum();
				return this.chronoField.getBaseUnit().addTo(temporal, amount);
			}
		}

		/**
		 * Rolls the temporal forward to the next higher-order unit.
		 *
		 * @param temporal the temporal to roll forward
		 * @param <T> the temporal type
		 * @return the rolled forward temporal
		 */
		@Nonnull
		public <T extends Temporal & Comparable<? super T>> T rollToNextHigherUnit(@Nonnull T temporal) {
			final T result = this.higherOrderUnit.addTo(temporal, 1);
			final ValueRange range = result.range(this.chronoField);
			return this.chronoField.adjustInto(result, range.getMinimum());
		}

		/**
		 * Resets this field and all lower-order fields to their minimum values.
		 *
		 * @param temporal the temporal to reset
		 * @param <T> the temporal type
		 * @return the reset temporal
		 */
		@Nonnull
		public <T extends Temporal> T resetLowerOrderFields(@Nonnull T temporal) {
			T result = temporal;
			for (ChronoField lowerField : this.lowerOrderFields) {
				if (result.isSupported(lowerField)) {
					result = lowerField.adjustInto(result, result.range(lowerField).getMinimum());
				}
			}
			return result;
		}

		@Override
		public String toString() {
			return this.chronoField.toString();
		}
	}
}
