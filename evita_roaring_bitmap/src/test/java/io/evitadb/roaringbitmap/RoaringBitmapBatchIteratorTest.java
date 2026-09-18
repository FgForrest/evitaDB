package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.roaringbitmap.RoaringBitmapWriter.writer;
import static io.evitadb.roaringbitmap.SeededTestData.TestDataSet.testCase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Regression tests for the {@link RoaringBatchIterator} returned by {@link PersistentRoaringBitmap},
 * ported from the upstream RoaringBitmap test suite. They exercise batch draining and
 * `advanceIfNeeded` positioning over a broad matrix of bitmap shapes (array / run / bitmap
 * containers) and batch sizes, plus targeted single-bitmap termination and buffer-spanning
 * scenarios.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Roaring batch iterator")
public class RoaringBitmapBatchIteratorTest {

	private static PersistentRoaringBitmap[] BITMAPS;
	private static final int[] SIZES = {128, 256, 1024, 8192, 5, 127, 1023};

	private static void initBitmaps() {
		BITMAPS =
			new PersistentRoaringBitmap[]{
				testCase()
					.withArrayAt(0)
					.withArrayAt(2)
					.withArrayAt(4)
					.withArrayAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withRunAt(0)
					.withRunAt(2)
					.withRunAt(4)
					.withRunAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withBitmapAt(0)
					.withRunAt(2)
					.withBitmapAt(4)
					.withBitmapAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withArrayAt(0)
					.withBitmapAt(2)
					.withRunAt(4)
					.withBitmapAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withRunAt(0)
					.withArrayAt(2)
					.withBitmapAt(4)
					.withRunAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withBitmapAt(0)
					.withRunAt(2)
					.withArrayAt(4)
					.withBitmapAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withArrayAt(0)
					.withBitmapAt(2)
					.withRunAt(4)
					.withArrayAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withBitmapAt(0)
					.withArrayAt(2)
					.withBitmapAt(4)
					.withRunAt((1 << 15) | (1 << 14))
					.build(),
				testCase()
					.withRunAt((1 << 15) | (1 << 11))
					.withBitmapAt((1 << 15) | (1 << 12))
					.withArrayAt((1 << 15) | (1 << 13))
					.withBitmapAt((1 << 15) | (1 << 14))
					.build(),
				PersistentRoaringBitmap.bitmapOf(
					IntStream.range(1 << 10, 1 << 26).filter(i -> (i & 1) == 0).toArray()),
				PersistentRoaringBitmap.bitmapOf(
					IntStream.range(1 << 10, 1 << 25).filter(i -> ((i >>> 8) & 1) == 0).toArray()),
				PersistentRoaringBitmap.bitmapOf(IntStream.range(0, 127).toArray()),
				PersistentRoaringBitmap.bitmapOf(IntStream.range(0, 1024).toArray()),
				PersistentRoaringBitmap.bitmapOf(
					IntStream.concat(IntStream.range(0, 256), IntStream.range(1 << 16, (1 << 16) | 256))
						.toArray()),
				PersistentRoaringBitmap.bitmapOf(8511),
				new PersistentRoaringBitmap()
			};
	}

	@BeforeAll
	public static void beforeAll() throws InterruptedException {
		int tryIndex = 0;
		int maxTryIndex = 3;
		while (++tryIndex < maxTryIndex) {
			try {
				initBitmaps();
			} catch (OutOfMemoryError e) {
				if (tryIndex == maxTryIndex) {
					throw e;
				}
				e.printStackTrace();
				System.out.println(
					"RoaringBitmapBatchIteratorTest.beforeAll Issue on try #"
						+ tryIndex
						+ ". Sleeping 5s for other tests to complete");
				TimeUnit.SECONDS.sleep(5);
			}
		}
	}

	@AfterAll
	public static void clear() {
		BITMAPS = null;
	}

	public static Stream<Arguments> params() {
		return Stream.of(BITMAPS)
			.flatMap(bitmap -> IntStream.of(SIZES).mapToObj(i -> Arguments.of(bitmap, i)));
	}

	@ParameterizedTest(name = "offset={1}")
	@MethodSource("params")
	@DisplayName("Batch iterator exposed as an int iterator reconstructs the bitmap")
	public void testBatchIteratorAsIntIterator(PersistentRoaringBitmap bitmap, int size) {
		IntIterator it = bitmap.getBatchIterator().asIntIterator(new int[size]);
		RoaringBitmapWriter<PersistentRoaringBitmap> w =
			writer().constantMemory().initialCapacity(bitmap.highLowContainer.size).get();
		while (it.hasNext()) {
			w.add(it.next());
		}
		PersistentRoaringBitmap copy = w.get();
		assertEquals(bitmap, copy);
	}

	@ParameterizedTest(name = "offset={1}")
	@MethodSource("params")
	@DisplayName("Draining the batch iterator reconstructs the bitmap and its cardinality")
	public void test(PersistentRoaringBitmap bitmap, int batchSize) {
		int[] buffer = new int[batchSize];
		PersistentRoaringBitmap result = new PersistentRoaringBitmap();
		RoaringBatchIterator it = bitmap.getBatchIterator();
		int cardinality = 0;
		while (it.hasNext()) {
			int batch = it.nextBatch(buffer);
			for (int i = 0; i < batch; ++i) {
				result.add(buffer[i]);
			}
			cardinality += batch;
		}
		assertEquals(bitmap, result);
		assertEquals(bitmap.getCardinality(), cardinality);
	}

	@ParameterizedTest(name = "offset={1}")
	@MethodSource("params")
	@DisplayName("advanceIfNeeded to the midpoint drops the lower half of the bitmap")
	public void testBatchIteratorAdvancedIfNeeded(PersistentRoaringBitmap bitmap, int batchSize) {
		final int cardinality = bitmap.getCardinality();
		if (cardinality < 2) {
			return;
		}
		int midpoint = bitmap.select(cardinality / 2);
		int[] buffer = new int[batchSize];
		PersistentRoaringBitmap result = new PersistentRoaringBitmap();
		RoaringBatchIterator it = bitmap.getBatchIterator();
		it.advanceIfNeeded(midpoint);
		int consumed = 0;
		while (it.hasNext()) {
			int batch = it.nextBatch(buffer);
			for (int i = 0; i < batch; ++i) {
				result.add(buffer[i]);
			}
			consumed += batch;
		}
		PersistentRoaringBitmap expected = bitmap.clone();
		expected.remove(0, midpoint & 0xFFFFFFFFL);
		assertEquals(expected, result);
		assertEquals(expected.getCardinality(), consumed);
	}

	@ParameterizedTest(name = "offset={1}")
	@MethodSource("params")
	@DisplayName("advanceIfNeeded to the first absent value drops all preceding values")
	public void testBatchIteratorAdvancedIfNeededToAbsentValue(PersistentRoaringBitmap bitmap, int batchSize) {
		long firstAbsent = bitmap.nextAbsentValue(0);
		int[] buffer = new int[batchSize];
		PersistentRoaringBitmap result = new PersistentRoaringBitmap();
		BatchIterator it = bitmap.getBatchIterator();
		it.advanceIfNeeded((int) firstAbsent);
		int consumed = 0;
		while (it.hasNext()) {
			int batch = it.nextBatch(buffer);
			for (int i = 0; i < batch; ++i) {
				result.add(buffer[i]);
			}
			consumed += batch;
		}
		PersistentRoaringBitmap expected = bitmap.clone();
		expected.remove(0, firstAbsent & 0xFFFFFFFFL);
		assertEquals(expected, result);
		assertEquals(expected.getCardinality(), consumed);
	}

	@ParameterizedTest(name = "offset={1}")
	@MethodSource("params")
	@DisplayName("advanceIfNeeded beyond the last value drains the iterator")
	public void testBatchIteratorAdvancedIfNeededBeyondLastValue(
		PersistentRoaringBitmap bitmap, int batchSize) {
		long advanceTo = bitmap.isEmpty() ? 0 : bitmap.last() + 1;
		int[] buffer = new int[batchSize];
		PersistentRoaringBitmap result = new PersistentRoaringBitmap();
		BatchIterator it = bitmap.getBatchIterator();
		it.advanceIfNeeded((int) advanceTo);
		int consumed = 0;
		while (it.hasNext()) {
			int batch = it.nextBatch(buffer);
			for (int i = 0; i < batch; ++i) {
				result.add(buffer[i]);
			}
			consumed += batch;
		}
		assertEquals(0, consumed);
		assertTrue(result.isEmpty());
	}

	@Test
	@DisplayName("Single-value batch terminates immediately after the one batch")
	public void testTimelyTermination() {
		PersistentRoaringBitmap bm = PersistentRoaringBitmap.bitmapOf(8511);
		BatchIterator bi = bm.getBatchIterator();
		int[] batch = new int[10];
		assertTrue(bi.hasNext());
		int n = bi.nextBatch(batch);
		assertEquals(1, n);
		assertEquals(8511, batch[0]);
		assertFalse(bi.hasNext());
	}

	@Test
	@DisplayName("advanceIfNeeded past the only value terminates immediately")
	public void testTimelyTerminationAfterAdvanceIfNeeded() {
		PersistentRoaringBitmap bm = PersistentRoaringBitmap.bitmapOf(8511);
		BatchIterator bi = bm.getBatchIterator();
		assertTrue(bi.hasNext());
		bi.advanceIfNeeded(8512);
		assertFalse(bi.hasNext());
	}

	@Test
	@DisplayName("advanceIfNeeded below the first value keeps all values in one batch")
	public void testBatchIteratorWithAdvanceIfNeeded() {
		PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(3 << 16, (3 << 16) + 5, (3 << 16) + 10);
		BatchIterator it = bitmap.getBatchIterator();
		it.advanceIfNeeded(6);
		assertTrue(it.hasNext());
		int[] batch = new int[10];
		int n = it.nextBatch(batch);
		assertEquals(3, n);
		assertEquals(3 << 16, batch[0]);
		assertEquals((3 << 16) + 5, batch[1]);
		assertEquals((3 << 16) + 10, batch[2]);
	}

	@ParameterizedTest
	@ValueSource(ints = {10, 11, 12, 13, 14, 15, 18, 20, 21, 23, 24})
	@DisplayName("advanceIfNeeded finds each key across zero-length runs")
	public void testBatchIteratorWithAdvancedIfNeededWithZeroLengthRun(int number) {
		PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(
			10, 11, 12, 13, 14, 15, 18, 20, 21, 22, 23, 24);
		bitmap.runOptimize();
		BatchIterator it = bitmap.getBatchIterator();
		it.advanceIfNeeded(number);
		assertTrue(it.hasNext());
		int[] batch = new int[10];
		int n = it.nextBatch(batch);
		int i = Arrays.binarySearch(batch, 0, n, number);
		assertTrue(i >= 0, "key " + number + " not found");
		assertEquals(batch[i], number);
	}

	@Test
	@DisplayName("Batches fill the buffer across multiple containers")
	public void testBatchIteratorFillsBufferAcrossContainers() {
		PersistentRoaringBitmap bitmap =
			PersistentRoaringBitmap.bitmapOf(3 << 4, 3 << 8, 3 << 12, 3 << 16, 3 << 20, 3 << 24, 3 << 28);
		assertEquals(5, bitmap.highLowContainer.size());
		BatchIterator it = bitmap.getBatchIterator();
		int[] batch = new int[3];
		int n = it.nextBatch(batch);
		assertEquals(3, n);
		assertArrayEquals(new int[]{3 << 4, 3 << 8, 3 << 12}, batch);
		n = it.nextBatch(batch);
		assertEquals(3, n);
		assertArrayEquals(new int[]{3 << 16, 3 << 20, 3 << 24}, batch);
		n = it.nextBatch(batch);
		assertEquals(1, n);
		assertArrayEquals(new int[]{3 << 28}, Arrays.copyOfRange(batch, 0, 1));
		n = it.nextBatch(batch);
		assertEquals(0, n);
	}

	/**
	 * `nextBatch(buffer, offset, length)` is an evita-local addition to the fork rather than upstream
	 * API (see `UPSTREAM_SYNC.md`) and is re-applied by hand on every upstream re-sync. The bound is
	 * the whole point of it: the unbounded form fills to `buffer.length`, so a caller that owns only
	 * a slice of a shared buffer cannot use it. These two cases pin the bound being honoured on both
	 * sides of the seam that carries it - the container-crossing loop in {@link RoaringBatchIterator}
	 * and the per-container `limit` the three {@link ContainerBatchIterator}s now take.
	 */
	@Test
	@DisplayName("A bounded batch writes only inside [offset, offset + length)")
	public void testBoundedNextBatchHonoursOffsetAndLength() {
		PersistentRoaringBitmap bitmap = PersistentRoaringBitmap.bitmapOf(
			1, 2, 3, (1 << 16) + 1, (1 << 16) + 2, (2 << 16) + 1);
		assertEquals(3, bitmap.highLowContainer.size());
		BatchIterator it = bitmap.getBatchIterator();
		int[] buffer = new int[16];
		Arrays.fill(buffer, -1);

		// four values requested, and the fourth only exists in the NEXT container - so the bound has to
		// survive the container-crossing loop, which is where an unbounded iterator would run to
		// buffer.length and write six
		int n = it.nextBatch(buffer, 4, 4);
		assertEquals(4, n);
		assertArrayEquals(new int[]{1, 2, 3, (1 << 16) + 1}, Arrays.copyOfRange(buffer, 4, 8));
		for (int i = 0; i < 4; i++) {
			assertEquals(-1, buffer[i], "slot " + i + " sits before the offset and must be untouched");
		}
		for (int i = 8; i < buffer.length; i++) {
			assertEquals(-1, buffer[i], "slot " + i + " sits past the bound and must be untouched");
		}

		n = it.nextBatch(buffer, 4, 4);
		assertEquals(2, n);
		assertArrayEquals(new int[]{(1 << 16) + 2, (2 << 16) + 1}, Arrays.copyOfRange(buffer, 4, 6));
		assertEquals(0, it.nextBatch(buffer, 4, 4));
	}

	@Test
	@DisplayName("A bounded batch stops part-way through a run instead of filling the buffer")
	public void testBoundedNextBatchTruncatesInsideARunContainer() {
		PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		bitmap.add(100L, 1_000L);
		bitmap.runOptimize();
		BatchIterator it = bitmap.getBatchIterator();
		int[] buffer = new int[64];
		Arrays.fill(buffer, -1);

		// one run holds 900 consecutive values, so an unbounded drain would fill every slot from the offset
		// on. RunBatchIterator's usable length is what has to shrink to the bound here
		int n = it.nextBatch(buffer, 8, 10);
		assertEquals(10, n);
		assertArrayEquals(IntStream.range(100, 110).toArray(), Arrays.copyOfRange(buffer, 8, 18));
		assertEquals(-1, buffer[18], "the run kept writing past the bound");

		n = it.nextBatch(buffer, 8, 10);
		assertEquals(10, n);
		assertArrayEquals(IntStream.range(110, 120).toArray(), Arrays.copyOfRange(buffer, 8, 18));
	}
}
