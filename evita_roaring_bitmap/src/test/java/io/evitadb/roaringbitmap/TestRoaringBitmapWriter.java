package io.evitadb.roaringbitmap;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.Integer.toUnsignedLong;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.roaringbitmap.RoaringBitmapWriter.writer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Regression tests for {@link RoaringBitmapWriter}, ported from the upstream RoaringBitmap test
 * suite. They verify that buffered writes are correctly materialized into the underlying bitmap
 * across implicit and manual flushes, key transitions, range writes, the maximum key range and
 * writer resets, exercised for every writer configuration supplied by {@link #params()}.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("RoaringBitmapWriter buffered writes and flushing")
public class TestRoaringBitmapWriter {

	public static Stream<Arguments> params() {
		return Stream.of(
			Arguments.of(writer().optimiseForArrays()),
			Arguments.of(writer().optimiseForRuns()),
			Arguments.of(writer().constantMemory()),
			Arguments.of(writer().expectedDensity(0.001)),
			Arguments.of(writer().expectedDensity(0.01)),
			Arguments.of(writer().expectedDensity(0.1)),
			Arguments.of(writer().expectedDensity(0.6)),
			Arguments.of(writer().initialCapacity(1)),
			Arguments.of(writer().initialCapacity(8)),
			Arguments.of(writer().initialCapacity(8192)),
			Arguments.of(writer().optimiseForArrays().expectedRange(0, toUnsignedLong(MIN_VALUE))),
			Arguments.of(writer().optimiseForRuns().expectedRange(0, toUnsignedLong(MIN_VALUE))),
			Arguments.of(writer().constantMemory().expectedRange(0, toUnsignedLong(MIN_VALUE))),
			Arguments.of(
				writer()
					.optimiseForArrays()
					.expectedRange(toUnsignedLong(MAX_VALUE), toUnsignedLong(MIN_VALUE))),
			Arguments.of(
				writer()
					.optimiseForRuns()
					.expectedRange(toUnsignedLong(MAX_VALUE), toUnsignedLong(MIN_VALUE))),
			Arguments.of(
				writer()
					.constantMemory()
					.expectedRange(toUnsignedLong(MAX_VALUE), toUnsignedLong(MIN_VALUE))),
			Arguments.of(writer().optimiseForArrays().runCompress(false)),
			Arguments.of(writer().optimiseForRuns().runCompress(false)),
			Arguments.of(writer().constantMemory().runCompress(false)),
			Arguments.of(writer().expectedDensity(0.001).runCompress(false)),
			Arguments.of(writer().expectedDensity(0.01).runCompress(false)),
			Arguments.of(writer().expectedDensity(0.1).runCompress(false)),
			Arguments.of(writer().expectedDensity(0.6).runCompress(false)),
			Arguments.of(writer().initialCapacity(1).runCompress(false)),
			Arguments.of(writer().initialCapacity(8).runCompress(false)),
			Arguments.of(writer().initialCapacity(8192).runCompress(false)),
			Arguments.of(
				writer()
					.optimiseForArrays()
					.expectedRange(0, toUnsignedLong(MIN_VALUE))
					.runCompress(false)),
			Arguments.of(
				writer()
					.optimiseForRuns()
					.expectedRange(0, toUnsignedLong(MIN_VALUE))
					.runCompress(false)),
			Arguments.of(
				writer()
					.constantMemory()
					.expectedRange(0, toUnsignedLong(MIN_VALUE))
					.runCompress(false)),
			Arguments.of(
				writer()
					.optimiseForArrays()
					.expectedRange(toUnsignedLong(MAX_VALUE), toUnsignedLong(MIN_VALUE))
					.runCompress(false)),
			Arguments.of(
				writer()
					.optimiseForRuns()
					.expectedRange(toUnsignedLong(MAX_VALUE), toUnsignedLong(MIN_VALUE))
					.runCompress(false)),
			Arguments.of(
				writer()
					.constantMemory()
					.expectedRange(toUnsignedLong(MAX_VALUE), toUnsignedLong(MIN_VALUE))
					.runCompress(false))
		);
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("Values added in reverse order are stored ascending after flush")
	public void addInReverseOrder(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		writer.add(1 << 17);
		writer.add(0);
		writer.flush();
		assertArrayEquals(
			PersistentRoaringBitmap.bitmapOf(0, 1 << 17).toArray(), writer.getUnderlying().toArray());
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("Underlying bitmap contains all added values after flush")
	public void bitmapShouldContainAllValuesAfterFlush(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		writer.add(0);
		writer.add(1 << 17);
		writer.flush();
		assertTrue(writer.getUnderlying().contains(0));
		assertTrue(writer.getUnderlying().contains(1 << 17));
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("Adding a value in a new key range triggers an implicit flush")
	public void newKeyShouldTriggerFlush(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		writer.add(0);
		writer.add(1 << 17);
		assertTrue(writer.getUnderlying().contains(0));
		writer.add(1 << 18);
		assertTrue(writer.getUnderlying().contains(1 << 17));
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("Values written to the same key across manual flushes are all retained")
	public void writeSameKeyAfterManualFlush(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		writer.add(0);
		writer.flush();
		writer.add(1);
		writer.flush();
		assertArrayEquals(PersistentRoaringBitmap.bitmapOf(0, 1).toArray(), writer.getUnderlying().toArray());
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("Individual values and a range are combined into the bitmap")
	public void writeRange(Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		writer.add(0);
		writer.add(65500L, 65600L);
		writer.add(1);
		writer.add(65610);
		writer.flush();
		PersistentRoaringBitmap expected = PersistentRoaringBitmap.bitmapOf(0, 1, 65610);
		expected.add(65500L, 65600L);
		assertArrayEquals(expected.toArray(), writer.getUnderlying().toArray());
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("Values in the maximum key range are written after flush")
	public void testWriteToMaxKeyAfterFlush(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter writer = supplier.get();
		writer.add(0);
		writer.add(-2);
		writer.flush();
		assertArrayEquals(PersistentRoaringBitmap.bitmapOf(0, -2).toArray(), writer.get().toArray());
		writer.add(-1);
		assertArrayEquals(PersistentRoaringBitmap.bitmapOf(0, -2, -1).toArray(), writer.get().toArray());
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("reset() discards buffered state before writing a new bitmap")
	public void testWriteBitmapAfterReset(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter writer = supplier.get();
		writer.add(0);
		writer.add(-2);
		assertArrayEquals(new int[]{0, -2}, writer.get().toArray());
		writer.reset();
		writer.add(100);
		writer.addMany(4, 5, 6);
		assertArrayEquals(new int[]{4, 5, 6, 100}, writer.get().toArray());
	}

	/**
	 * `addChunk` is an evita-local addition to the fork rather than upstream API (see
	 * `UPSTREAM_SYNC.md`), so it is re-applied by hand on every upstream re-sync. These three cases
	 * pin the parts of its contract a re-apply could get wrong silently, for every writer
	 * configuration - both the interface default that decomposes to {@link RoaringBitmapWriter#add}
	 * and the constant-memory override that ORs the words straight into its own buffer.
	 */
	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("addChunk reads only the words inside [fromWord, toWord)")
	public void addChunkHonoursItsWordRange(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		final long[] words = new long[1024];
		// words[i] carries the container-local offsets i * 64 .. i * 64 + 63
		words[0] = 1L;                    // offset 0     - below fromWord, must not be read
		words[5] = 1L | (1L << 63);       // offsets 320 and 383
		words[9] = 1L << 7;               // offset 583
		words[1023] = 1L;                 // offset 65472 - at or above toWord, must not be read
		writer.addChunk((char) 3, words, 5, 10);
		writer.flush();
		assertArrayEquals(
			new int[]{(3 << 16) + 320, (3 << 16) + 383, (3 << 16) + 583},
			writer.getUnderlying().toArray()
		);
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("addChunk below an already-written key lands through the per-value path")
	public void addChunkBelowTheCurrentKeyStillLands(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		// the constant-memory writer buffers ONE key at a time, so this leaves its mark at key 5 and the
		// chunk below it cannot go through the word buffer. No evita caller emits out of order - the
		// kernel walks chunks ascending - which is exactly why the branch needs a test of its own
		writer.add((5 << 16) + 7);
		final long[] words = new long[1024];
		words[1] = 1L << 3;               // offset 67
		writer.addChunk((char) 2, words, 1, 2);
		writer.flush();
		assertArrayEquals(
			new int[]{(2 << 16) + 67, (5 << 16) + 7},
			writer.getUnderlying().toArray()
		);
	}

	@ParameterizedTest
	@MethodSource("params")
	@DisplayName("addChunk ORs into the key it shares with add(), losing neither side")
	public void addChunkMergesWithSingleValueAddsOnTheSameKey(
		Supplier<RoaringBitmapWriter<? extends BitmapDataProvider>> supplier
	) {
		RoaringBitmapWriter<? extends BitmapDataProvider> writer = supplier.get();
		// the value added BEFORE the chunk is the one at risk: a constant-memory override that copied the
		// caller's words over its buffer instead of OR-ing them would drop it, and nothing else would notice.
		// It has to share a WORD with the chunk for that to bite - offset 40 and the chunk's offset 1 are both
		// in words[0] - or a copy of a disjoint word range leaves it standing and this proves nothing
		writer.add((7 << 16) + 40);
		final long[] words = new long[1024];
		words[0] = 1L << 1;               // offset 1, same word as the 40 above
		writer.addChunk((char) 7, words, 0, 1);
		writer.add((7 << 16) + 200);
		writer.flush();
		assertArrayEquals(
			new int[]{(7 << 16) + 1, (7 << 16) + 40, (7 << 16) + 200},
			writer.getUnderlying().toArray()
		);
	}
}
