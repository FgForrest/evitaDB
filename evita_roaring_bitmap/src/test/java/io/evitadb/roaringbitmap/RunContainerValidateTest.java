package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins structural run validation independently of encoding size, including public operations and
 * serialization that preserve valid runs even when another container type would be smaller.
 */
public class RunContainerValidateTest {

	private static final int CHUNK_SIZE = 1 << 16;
	private static final long UNIVERSE_END = 1L << 32;
	private static final int[] KEYS = {0, 1, 2, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF};

	/**
	 * Removing values can leave a singleton run; its larger encoding must remain valid on the wire.
	 */
	@ParameterizedTest(name = "roundTrip={0}")
	@ValueSource(booleans = {false, true})
	public void validatesSingletonAfterRemovals(final boolean roundTrip) throws IOException {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(0, 3);
		bitmap.remove(0);
		bitmap.remove(1);
		final PersistentRoaringBitmap actual = roundTrip ? roundTrip(bitmap) : bitmap;

		assertArrayEquals(new int[]{2}, actual.toArray());
		assertInstanceOf(RunContainer.class, actual.highLowContainer.getContainerAtIndex(0));
		assertTrue(actual.validate(), "roundTrip=" + roundTrip + ": remove() leaves a valid singleton run");
	}

	/**
	 * Limiting a range to one value retains its run encoding, which must validate before and after IO.
	 */
	@ParameterizedTest(name = "roundTrip={0}")
	@ValueSource(booleans = {false, true})
	public void validatesSingletonAfterLimit(final boolean roundTrip) throws IOException {
		final PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOfRange(0, 100).limit(1);
		final PersistentRoaringBitmap actual = roundTrip ? roundTrip(bitmap) : bitmap;

		assertArrayEquals(new int[]{0}, actual.toArray());
		assertInstanceOf(RunContainer.class, actual.highLowContainer.getContainerAtIndex(0));
		assertTrue(actual.validate(), "roundTrip=" + roundTrip + ": limit() leaves a valid singleton run");
	}

	/**
	 * An overflowing final run is invalid even when its start and stored length each fit in a char.
	 */
	@ParameterizedTest(name = "start={0}, length={1}")
	@CsvSource({"65535, 1", "65520, 32"})
	public void rejectsRunPastUnsignedMaximum(final int start, final int length) {
		final RunContainer container = new RunContainer(new char[]{0, 9, (char) start, (char) length}, 2);

		assertFalse(container.validate(), "run start=" + start + ", length=" + length + " exceeds 0xFFFF");
	}

	/**
	 * Ending exactly at the unsigned maximum is valid, including a full chunk and a singleton run.
	 */
	@ParameterizedTest(name = "start={0}, length={1}")
	@CsvSource({"65535, 0", "65520, 15", "0, 65535"})
	public void acceptsRunEndingAtUnsignedMaximum(final int start, final int length) {
		final RunContainer container = new RunContainer(new char[]{(char) start, (char) length}, 1);

		assertTrue(container.validate(), "a run may end exactly at 0xFFFF");
	}

	/**
	 * The size check must also be absent when both the bitmap and array encodings would be smaller.
	 */
	@Test
	public void acceptsFragmentedRunsLargerThanBitmapEncoding() {
		final char[] valueslength = new char[2 * 4096];
		for (int run = 0; run < 4096; run++) {
			valueslength[2 * run] = (char) (2 * run);
		}
		final RunContainer container = new RunContainer(valueslength, 4096);

		assertTrue(container.validate(), "separated singleton runs are valid regardless of encoding size");
	}

	/**
	 * A container registered in a bitmap must contain at least one run.
	 */
	@Test
	public void rejectsEmptyRunContainer() {
		assertFalse(new RunContainer().validate(), "an empty run container is structurally invalid");
	}

	/**
	 * A manually constructed negative run count must not bypass the requirement for at least one run.
	 */
	@Test
	public void rejectsNegativeRunCount() {
		assertFalse(new RunContainer(new char[0], -1).validate(), "the run count must be positive");
	}

	/**
	 * Duplicate, descending, overlapping and adjacent runs must remain structurally invalid.
	 */
	@ParameterizedTest(name = "firstStart={0}, secondStart={1}")
	@CsvSource({"0, 0", "20, 0", "0, 9", "0, 10"})
	public void rejectsRunsWithoutStrictSeparation(final int firstStart, final int secondStart) {
		final RunContainer container = new RunContainer(new char[]{(char) firstStart, 9, (char) secondStart, 9}, 2);

		assertFalse(container.validate(), "runs must be ascending, non-overlapping and non-adjacent");
	}

	/**
	 * A single missing value is sufficient separation, including for non-minimal singleton runs.
	 */
	@Test
	public void acceptsSeparatedSingletonRuns() {
		final RunContainer container = new RunContainer(new char[]{0, 0, 2, 0, 0xFFFF, 0}, 3);

		assertTrue(container.validate(), "one absent value separates adjacent singleton runs");
	}

	/**
	 * Random public operation sequences must preserve validity after every step and serialization;
	 * fixed seeds and step numbers identify failures across array, bitmap and run container shapes.
	 */
	@ParameterizedTest(name = "seed={0}")
	@ValueSource(longs = {0L, 1L, 0x1754E6L, 0xC0FFEEL})
	public void randomPublicOperationsRemainValid(final long seed) throws IOException {
		final Random random = new Random(seed);
		PersistentRoaringBitmap bitmap = randomBitmap(random);
		assertValidBeforeAndAfterRoundTrip(bitmap, "seed=" + seed + ", initial bitmap");
		for (int step = 0; step < 256; step++) {
			final int operation = random.nextInt(11);
			final long start = randomValue(random);
			final long end = Math.min(UNIVERSE_END, start + random.nextInt(2 * CHUNK_SIZE + 1));
			switch (operation) {
				case 0 -> bitmap.add((int) start);
				case 1 -> bitmap.add(start, end);
				case 2 -> bitmap.remove((int) start);
				case 3 -> bitmap.remove(start, end);
				case 4 -> bitmap = bitmap.limit(random.nextBoolean() ? 1 : random.nextInt(bitmap.getCardinality() + 1));
				case 5 -> bitmap.runOptimize();
				case 6 -> bitmap.and(randomBitmap(random));
				case 7 -> bitmap.or(randomBitmap(random));
				case 8 -> bitmap.andNot(randomBitmap(random));
				case 9 -> bitmap.xor(randomBitmap(random));
				case 10 -> bitmap.flip(start, end);
			}
			assertValidBeforeAndAfterRoundTrip(bitmap, "seed=" + seed + ", step=" + step + ", operation=" + operation);
		}
	}

	/**
	 * Builds operands exclusively through public additions, with sparse, dense fragmented and run
	 * chunks around zero, the signed boundary and the unsigned maximum.
	 */
	private static PersistentRoaringBitmap randomBitmap(final Random random) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		final long base = (long) KEYS[random.nextInt(KEYS.length)] * CHUNK_SIZE;
		switch (random.nextInt(3)) {
			case 0 -> {
				for (int value = 0; value < 32; value++) {
					bitmap.add((int) (base + random.nextInt(CHUNK_SIZE)));
				}
			}
			case 1 -> {
				final int offset = random.nextInt(CHUNK_SIZE - 12000);
				for (int value = 0; value < 6000; value++) {
					bitmap.add((int) (base + offset + 2 * value));
				}
			}
			case 2 -> bitmap.add(base, Math.min(UNIVERSE_END, base + 1 + random.nextInt(2 * CHUNK_SIZE)));
		}
		return bitmap;
	}

	/**
	 * Chooses unsigned values near keys that the operands use, so ranges intersect and cross chunks.
	 */
	private static long randomValue(final Random random) {
		return (long) KEYS[random.nextInt(KEYS.length)] * CHUNK_SIZE + random.nextInt(CHUNK_SIZE);
	}

	/**
	 * Checks the public validity contract and round-trip contents, identifying invalid container
	 * types in failures so unrelated representation defects can be reported separately.
	 */
	private static void assertValidBeforeAndAfterRoundTrip(final PersistentRoaringBitmap bitmap, final String context)
		throws IOException {
		assertTrue(bitmap.validate(), () -> context + ", before serialization: " + invalidContainers(bitmap));
		final PersistentRoaringBitmap restored = roundTrip(bitmap);
		assertTrue(restored.validate(), () -> context + ", after serialization: " + invalidContainers(restored));
		assertEquals(bitmap, restored, context + ": serialization preserves contents");
	}

	/**
	 * Lists invalid container keys and types without modifying the bitmap being diagnosed.
	 */
	private static String invalidContainers(final PersistentRoaringBitmap bitmap) {
		final StringBuilder description = new StringBuilder();
		for (int index = 0; index < bitmap.highLowContainer.size(); index++) {
			final Container container = bitmap.highLowContainer.getContainerAtIndex(index);
			if (!container.validate()) {
				description.append("key=").append((int) bitmap.highLowContainer.getKeyAtIndex(index))
					.append(", type=").append(container.getClass().getSimpleName())
					.append(", cardinality=").append(container.getCardinality()).append(';');
			}
		}
		return description.toString();
	}

	/**
	 * Round-trips through the module's DataOutput/DataInput format without optimizing the encoding.
	 */
	private static PersistentRoaringBitmap roundTrip(final PersistentRoaringBitmap bitmap) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream(bitmap.serializedSizeInBytes());
		try (final DataOutputStream output = new DataOutputStream(bytes)) {
			bitmap.serialize(output);
		}
		final PersistentRoaringBitmap restored = new PersistentRoaringBitmap();
		try (final DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			restored.deserialize(input);
		}
		return restored;
	}

}
