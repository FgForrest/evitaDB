/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.zip.CRC32C;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized test that verifies the CRC state injection contract of
 * {@link Crc32CWrapper}. This test continuously fuzzes constructor injection, reset/append cycles,
 * external checksum combination and cumulative `getValue` reads against a reference {@link CRC32C}
 * implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Long-running generational test of Crc32CWrapper")
@Tag(ENGINE)
@Tag(DATA_TYPE)
class LongRunningCrc32CWrapperTest implements TimeBoundedTestSupport {

	/**
	 * Computes the CRC32C checksum of the given byte array.
	 *
	 * @param bytes the byte array for which the CRC32C checksum is to be calculated;
	 *              must not be null or empty to avoid unexpected behavior.
	 * @return the computed CRC32C value as a long.
	 */
	private static long crc32c(@Nonnull byte[] bytes) {
		CRC32C crc = new CRC32C();
		crc.update(bytes, 0, bytes.length);
		return crc.getValue();
	}

	@ParameterizedTest(name = "Crc32CWrapper should survive generational randomized test verifying CRC state injection")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Crc32CWrapper calculator = new Crc32CWrapper();

		runFor(
			input,
			10_000,
			(Object) null,
			(random, state) -> {
				// pick a random scenario
				final int scenario = random.nextInt(5);
				try {
					switch (scenario) {
						case 0 -> verifyConstructorInjection(random);
						case 1 -> verifyResetWithInitialValue(random, calculator);
						case 2 -> verifyResetAndAccumulate(random, calculator);
						case 3 -> verifyWithAnotherChecksum(random, calculator);
						case 4 -> verifyCumulativeGetValueCalls(random, calculator);
					}
				} catch (AssertionError | Exception ex) {
					fail("Failed in scenario " + scenario + " with seed " + input.randomSeed(), ex);
				}
				return null;
			}
		);
	}

	/**
	 * Verifies that constructing {@link Crc32CWrapper} with a random initial checksum produces
	 * correct cumulative CRC when additional random data is appended.
	 *
	 * @param random the random number generator used to produce test data
	 */
	private void verifyConstructorInjection(@Nonnull Random random) {
		// generate random "original data" and compute its CRC
		final byte[] originalData = randomBytes(random, 1, 1024);
		final long originalCrc = crc32c(originalData);

		// construct wrapper with initial checksum
		final Crc32CWrapper wrapper = new Crc32CWrapper(originalCrc);
		assertEquals(originalCrc, wrapper.getValue(), "getValue() should return initial checksum immediately");

		// append random data
		final byte[] appendedData = randomBytes(random, 1, 512);
		wrapper.withByteArray(appendedData);
		final long actualResult = wrapper.getValue();

		// compute expected: CRC of (originalData || appendedData)
		final CRC32C expected = new CRC32C();
		expected.update(originalData);
		expected.update(appendedData);

		assertEquals(expected.getValue(), actualResult);
	}

	/**
	 * Verifies that {@link Crc32CWrapper#reset(long)} correctly injects the CRC state and
	 * subsequent data additions produce the correct cumulative CRC.
	 *
	 * @param random     the random number generator used to produce test data
	 * @param calculator the reusable calculator instance to test
	 */
	private static void verifyResetWithInitialValue(@Nonnull Random random, @Nonnull Crc32CWrapper calculator) {
		// generate random base data and its CRC
		final byte[] baseData = randomBytes(random, 1, 1024);
		final long baseCrc = crc32c(baseData);

		// pollute calculator with some data, then reset with initial value
		calculator.withLong(random.nextLong());
		calculator.reset(baseCrc);
		assertEquals(baseCrc, calculator.getValue(), "getValue() should return reset value");

		// append random data
		final byte[] appendedData = randomBytes(random, 1, 512);
		calculator.withByteArray(appendedData);
		final long actualResult = calculator.getValue();

		// compute expected
		final CRC32C expected = new CRC32C();
		expected.update(baseData);
		expected.update(appendedData);

		assertEquals(expected.getValue(), actualResult);

		// clean up
		calculator.reset();
	}

	/**
	 * Verifies that {@link Crc32CWrapper#reset()} followed by data additions matches a fresh
	 * {@link CRC32C} computation, then {@link Crc32CWrapper#reset(long)} followed by more data
	 * also matches the expected concatenated CRC.
	 *
	 * @param random     the random number generator used to produce test data
	 * @param calculator the reusable calculator instance to test
	 */
	private static void verifyResetAndAccumulate(@Nonnull Random random, @Nonnull Crc32CWrapper calculator) {
		// first: compute CRC of chunk1 fresh
		final byte[] chunk1 = randomBytes(random, 1, 256);
		calculator.reset();
		calculator.withByteArray(chunk1);
		final long chunk1Crc = calculator.getValue();
		assertEquals(crc32c(chunk1), chunk1Crc);

		// reset with chunk1's CRC, add chunk2
		final byte[] chunk2 = randomBytes(random, 1, 256);
		calculator.reset(chunk1Crc);
		calculator.withByteArray(chunk2);
		final long cumulativeResult = calculator.getValue();

		// compute expected: CRC of (chunk1 || chunk2)
		final CRC32C expected = new CRC32C();
		expected.update(chunk1);
		expected.update(chunk2);

		assertEquals(expected.getValue(), cumulativeResult);

		// clean up
		calculator.reset();
	}

	/**
	 * Verifies that {@link Crc32CWrapper#withAnotherChecksum(long, long)} correctly combines
	 * independently computed CRC values with locally accumulated data.
	 *
	 * @param random     the random number generator used to produce test data
	 * @param calculator the reusable calculator instance to test
	 */
	private static void verifyWithAnotherChecksum(@Nonnull Random random, @Nonnull Crc32CWrapper calculator) {
		// compute CRC of two independent chunks
		final byte[] chunk1 = randomBytes(random, 1, 512);
		final byte[] chunk2 = randomBytes(random, 1, 512);
		final long crc2 = crc32c(chunk2);

		// add chunk1 data, then combine with chunk2's CRC
		calculator.reset();
		calculator.withByteArray(chunk1);
		calculator.withAnotherChecksum(crc2, chunk2.length);
		final long combinedResult = calculator.getValue();

		// compute expected: CRC of (chunk1 || chunk2)
		final CRC32C expected = new CRC32C();
		expected.update(chunk1);
		expected.update(chunk2);

		assertEquals(expected.getValue(), combinedResult);

		// clean up
		calculator.reset();
	}

	/**
	 * Verifies that calling {@link Crc32CWrapper#getValue()} multiple times between data
	 * additions produces correct cumulative results, ensuring the CRC state machine remains
	 * consistent across intermediate reads.
	 *
	 * @param random     the random number generator used to produce test data
	 * @param calculator the reusable calculator instance to test
	 */
	private static void verifyCumulativeGetValueCalls(@Nonnull Random random, @Nonnull Crc32CWrapper calculator) {
		// build a reference CRC32C and the wrapper in parallel
		final CRC32C reference = new CRC32C();
		calculator.reset();

		final int numChunks = 2 + random.nextInt(5);
		for (int i = 0; i < numChunks; i++) {
			final byte[] chunk = randomBytes(random, 1, 256);
			calculator.withByteArray(chunk);
			reference.update(chunk);

			// call getValue between additions — should not corrupt state
			final long intermediateActual = calculator.getValue();
			assertEquals(reference.getValue(), intermediateActual,
				"Intermediate getValue() mismatch at chunk " + i);
		}

		// clean up
		calculator.reset();
	}

	/**
	 * Generates a random byte array with length between min and max (inclusive).
	 *
	 * @param random    the random number generator to use
	 * @param minLength the minimum length of the generated array (inclusive)
	 * @param maxLength the maximum length of the generated array (inclusive)
	 * @return a new byte array filled with random data
	 */
	@Nonnull
	private static byte[] randomBytes(@Nonnull Random random, int minLength, int maxLength) {
		final int length = minLength + random.nextInt(maxLength - minLength + 1);
		final byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}

}
