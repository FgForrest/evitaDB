package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

public class ValidationRangeConsumer implements RelativeRangeConsumer {

	public enum Value {
		UNINITIALISED,
		ABSENT,
		PRESENT
	}

	private final Value[] buffer;
	final boolean validateBuffer;
	private int numberOfValuesConsumed = 0;

	private ValidationRangeConsumer(Value[] buffer, boolean validateBuffer) {
		this.buffer = buffer;
		this.validateBuffer = validateBuffer;
	}

	public static ValidationRangeConsumer ofSize(int size) {
		Value[] buffer = new Value[size];
		Arrays.fill(buffer, Value.UNINITIALISED);
		return new ValidationRangeConsumer(buffer, false);
	}

	public static ValidationRangeConsumer validate(Value[] buffer) {
		for (Value b : buffer) {
			assertNotEquals(Value.UNINITIALISED, b, "Provide only fully initialised buffers!");
		}
		return new ValidationRangeConsumer(buffer, true);
	}

	public static ValidationRangeConsumer validateContinuous(int size, Value value) {
		Value[] buffer = new Value[size];
		Arrays.fill(buffer, value);
		return new ValidationRangeConsumer(buffer, true);
	}

	public Value[] getBuffer() {
		return this.buffer;
	}

	public int getNumberOfValuesConsumed() {
		return this.numberOfValuesConsumed;
	}

	@Override
	public void acceptPresent(final int relativePos) {
		this.numberOfValuesConsumed++;
		if (this.validateBuffer) {
			assertEquals(Value.PRESENT, this.buffer[relativePos], () -> "Mismatch at position " + relativePos);
		} else {
			this.buffer[relativePos] = Value.PRESENT;
		}
	}

	@Override
	public void acceptAbsent(final int relativePos) {
		this.numberOfValuesConsumed++;
		if (this.validateBuffer) {
			assertEquals(Value.ABSENT, this.buffer[relativePos], () -> "Mismatch at position " + relativePos);
		} else {
			this.buffer[relativePos] = Value.ABSENT;
		}
	}

	@Override
	public void acceptAllPresent(int relativeFrom, int relativeTo) {
		assertTrue(relativeFrom < relativeTo, "Only consume [start, end) ranges!");
		this.numberOfValuesConsumed += relativeTo - relativeFrom;
		if (this.validateBuffer) {
			for (int i = relativeFrom; i < relativeTo; i++) {
				final int finalI = i;
				assertEquals(Value.PRESENT, this.buffer[i], () -> "Mismatch at position " + finalI);
			}
		} else {
			Arrays.fill(this.buffer, relativeFrom, relativeTo, Value.PRESENT);
		}
	}

	@Override
	public void acceptAllAbsent(int relativeFrom, int relativeTo) {
		assertTrue(relativeFrom < relativeTo, "Only consume [start, end) ranges!");
		this.numberOfValuesConsumed += relativeTo - relativeFrom;
		if (this.validateBuffer) {
			for (int i = relativeFrom; i < relativeTo; i++) {
				final int finalI = i;
				assertEquals(Value.ABSENT, this.buffer[i], () -> "Mismatch at position " + finalI);
			}
		} else {
			Arrays.fill(this.buffer, relativeFrom, relativeTo, Value.ABSENT);
		}
	}

	public void assertAllAbsentExcept(char[] presentValues, int offset) {
		int[] shifted = new int[presentValues.length];
		for (int i = 0; i < presentValues.length; i++) {
			shifted[i] = ((int) presentValues[i]) + offset;
		}
		assertAllAbsentExcept(shifted);
	}

	public void assertAllAbsentExcept(int[] presentValues) {
		if (presentValues.length == 0) {
			assertAllAbsent();
			return;
		}
		int expectedValueIndex = 0;
		for (int i = 0; i < this.buffer.length; i++) {
			final int finalI = i;
			if (expectedValueIndex < presentValues.length) {
				int expectedValue = presentValues[expectedValueIndex];
				if (i != expectedValue) {
					assertEquals(Value.ABSENT, this.buffer[i], () -> "Mismatch at position " + finalI);
				} else {
					assertEquals(Value.PRESENT, this.buffer[i], () -> "Mismatch at position " + finalI);
					expectedValueIndex++;
				}
			} else {
				assertEquals(Value.ABSENT, this.buffer[i], () -> "Mismatch at position " + finalI);
			}
		}
		assertEquals(presentValues.length, expectedValueIndex);
	}

	public void assertAllAbsent() {
		for (int i = 0; i < this.buffer.length; i++) {
			final int finalI = i;
			assertEquals(Value.ABSENT, this.buffer[i], () -> "Mismatch at position " + finalI);
		}
	}

	public void assertAllPresent() {
		for (int i = 0; i < this.buffer.length; i++) {
			final int finalI = i;
			assertEquals(Value.PRESENT, this.buffer[i], () -> "Mismatch at position " + finalI);
		}
	}
}
