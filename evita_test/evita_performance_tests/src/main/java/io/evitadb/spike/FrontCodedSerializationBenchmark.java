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

package io.evitadb.spike;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * JMH decision gate for {@code FrontCodedStringColumn}'s half of the generalized Option B proposal — the
 * "hard" column, since (unlike the primitive columns) it needs a bulk-encode-from-raw-bytes counterpart on
 * the read side, not just a primitive read/write swap. Reuses the encode / restart-walk reproduction
 * technique {@link FrontCodedFindKeyBenchmark} already established and proved faithful (self-checked decode
 * fidelity there); this benchmark reuses the same {@code encode}/{@code decodeAtBytes} shape rather than
 * re-deriving it.
 *
 * Four comparisons, not two, so the boxing/tag-elision win, the load-path win, and the format-vs-algorithm
 * split within that win are never conflated, and so the load-path comparison is fair (both sides build a real
 * column — see the note on {@code readTodayIntoColumn} below, a correction from an earlier draft):
 *
 * <ul>
 *     <li>{@code write_today_*} / {@code write_bypass_*}, {@code read_today_toStrings} /
 *     {@code read_bypass_toBytes} — isolates the pure boxing + per-entry-Kryo-tag elision cost, matching
 *     {@link PrimitiveColumnSerializationBenchmark}'s methodology exactly (decode-to-String-then-tag vs
 *     length-prefixed-raw-byte-write). Neither side builds a column, so this pair answers "is the raw byte
 *     format cheaper to produce/parse", not "is the load path faster" — see the next bullet for that.</li>
 *     <li>{@code read_today_intoColumn} / {@code read_bypass_intoColumn} — the fair load-path comparison,
 *     i.e. today's ACTUAL production behavior vs Option B's proposed replacement.
 *     {@code read_today_intoColumn} reproduces {@code insertKeyAt}'s real cost shape: for each entry,
 *     decode every already-inserted entry back to raw bytes (byte-level, matching {@code decodeAllToFlat} -
 *     no {@link String} round trip for existing entries, since production's own {@code insertKeyAt} doesn't
 *     do one either), append the new key, and re-encode via one {@code encodeFromFlat} call - O(current size)
 *     per insert, O(n²) total across {@code blockSize} inserts, exactly matching what {@code insertKeyAt}'s
 *     own doc comment describes it does. {@code read_bypass_intoColumn} does the same job in one bulk
 *     {@code encode} call, O(n) total. This is the comparison that decides whether the "hard, new scope" half
 *     of Option B for FrontCoded is worth building; {@code read_today_toStrings} vs {@code read_bypass_toBytes}
 *     is <b>not</b> a substitute for it (advisor caught an earlier draft making exactly that substitution and
 *     concluding "bypass loses" — {@code read_today_toStrings} never builds a column, so it does strictly less
 *     work than {@code read_bypass_intoColumn}; comparing them compares two different amounts of work, not two
 *     ways of doing the same job). Measured: 1,582,325 ns/op (today) vs 6,151 ns/op (bypass) = 257×.</li>
 *     <li>{@code read_today_bulkEncode} — decomposes that 257× into its two independent causes: the on-disk
 *     FORMAT (Kryo-tagged strings vs raw bytes) and the CONSTRUCTION ALGORITHM (n sequential
 *     {@code insertKeyAt} calls vs one bulk {@code encode}), which {@code read_today_intoColumn} vs
 *     {@code read_bypass_intoColumn} changes simultaneously. This variant keeps today's format but swaps in
 *     the bulk algorithm. Measured: 14,106 ns/op — compared against {@code read_today_intoColumn}
 *     (1,582,325 ns/op) this isolates the ALGORITHM delta at <b>112×</b> (format held constant); compared
 *     against {@code read_bypass_intoColumn} (6,151 ns/op) this isolates the FORMAT delta at roughly
 *     <b>2×</b> (measured 2.29×, but a 13-18% CI on both underlying numbers means treat this as "small
 *     next to 112×", not a precise figure) (algorithm held constant). 112 × 2.3 ≈ 257, consistent with the
 *     combined number above. <b>Conclusion: of the wall-clock nanoseconds saved between today's real load
 *     path and bypass, ~99% is attributable to the pre-existing O(n²) sequential-{@code insertKeyAt} load
 *     algorithm, not Option B's format change</b> — Option B's format-only contribution on the read side is
 *     real but much smaller. (112× is not literally "98% of 257×" under any single consistent framing —
 *     log-scale share is 85%, multiplicative share is 44% — the only framing that supports a "~99%"
 *     headline is share of nanoseconds saved; use that framing, not "% of the multiplier".) See
 *     {@code docs/design/2026-07-10-option-b-generalized-stage1-results.md} for the full writeup and what
 *     this means for scope.</li>
 * </ul>
 *
 * <p><b>Decision gate — necessary, not sufficient</b>: a meaningful ns/op AND B/op win for
 * {@code write_bypass}/{@code read_bypass_intoColumn} over {@code write_today}/{@code read_today_intoColumn}
 * (same bar as {@link PrimitiveColumnSerializationBenchmark}) authorizes only the next, small,
 * production-shaped confirmation step — NOT the SPI/refactor itself. See
 * {@code docs/design/2026-07-10-option-b-generalized-stage1-results.md}'s "decision gate must not repeat
 * H2's trap" reasoning: H2's own isolated JMH gate passed cleanly and the subsequent production remeasure
 * showed the win was small and misattributed to a different method entirely.</p>
 *
 * <p>Run with {@code -prof gc} to capture B/op alongside ns/op. Always pass the fully-qualified class name
 * regex as the first positional JMH argument ({@code "io\.evitadb\.spike\.FrontCodedSerializationBenchmark"})
 * via {@code java -cp target/benchmarks.jar org.openjdk.jmh.Main <regex> -prof gc} — NOT
 * {@code java -jar target/benchmarks.jar}, which invokes this module's {@code ArtificialTestRunner} main
 * class instead of JMH's own arg-parsing entry point and always runs the full
 * {@code io.evitadb.performance.externalApi.*} suite regardless of arguments given.</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class FrontCodedSerializationBenchmark {

	/** Mirrors {@code FrontCodedStringColumn.RESTART_INTERVAL}. */
	private static final int RESTART_INTERVAL = 16;
	/** Mirrors {@code FrontCodedStringColumn.MAX_ENTRY_HEADER_BYTES}. */
	private static final int MAX_ENTRY_HEADER_BYTES = 10;
	/** Mirrors {@code FrontCodedStringColumn.DECODE_SCRATCH_BYTES}. */
	private static final int DECODE_SCRATCH_BYTES = 48;

	/**
	 * Leaf block sizes actually used in production for String-attribute unique indexes: 256
	 * ({@code UniqueIndexBPlusTreeSupport.VALUE_BLOCK_SIZE}) for {@code GlobalUniqueIndex}/
	 * {@code OwnerUniqueIndex}. {@code InvertedIndex}'s 64-entry blocks are not String-keyed as often in
	 * practice for high-cardinality attributes (URLs/codes are typically unique-indexed), so this benchmark
	 * focuses on the unique-index shape; add 64 if the real workload profile calls for it.
	 */
	@Param({"256"})
	private int blockSize;

	private Kryo kryo;
	private byte[] cur;

	private byte[] data;
	private int[] restartOffsets;

	// scratch for readTodayIntoColumn's per-insert decode-all-existing pass - reused across the blockSize
	// inserts of a single invocation (sized once, generously, so no growth happens after @Setup's first call)
	private byte[] buildDecodeCur;
	private byte[] buildFlat;
	private int[] buildOffsets;

	private byte[] serializedToday;
	private byte[] serializedBypass;
	// dedicated, pre-sized, reused Output per write variant - see PrimitiveColumnSerializationBenchmark's
	// identical fields for why (advisor caught fresh-ByteArrayOutputStream-per-invocation as harness noise
	// that would swamp a real delta, and worse here since "today"/"bypass" produce different output sizes,
	// making toByteArray()'s cost differ between arms rather than being true common-mode noise)
	private Output todayOutput;
	private Output bypassOutput;

	@Setup(Level.Trial)
	public void setUp() {
		this.kryo = new Kryo();
		// Production's Kryo pre-registers String.class with a fixed ID (KryoFactory.initializeKryo,
		// setRegistrationRequired(true)) - see PrimitiveColumnSerializationBenchmark's identical fix. An
		// unregistered Kryo would pay a full "java.lang.String" class-name-string cost on every write_today
		// entry, an artifact production never incurs.
		this.kryo.setRegistrationRequired(true);
		this.kryo.register(String.class);
		this.cur = new byte[DECODE_SCRATCH_BYTES];
		this.buildDecodeCur = new byte[DECODE_SCRATCH_BYTES];
		// generously sized so readTodayIntoColumn's per-insert rebuild never resizes mid-loop: worst case is
		// every one of blockSize entries at generateKeys' ~34-byte length
		this.buildFlat = new byte[this.blockSize * 40];
		this.buildOffsets = new int[this.blockSize + 2];

		final String[] keys = generateKeys(this.blockSize);
		final EncodedColumn column = encode(keys);
		this.data = column.data();
		this.restartOffsets = column.restartOffsets();

		// self-check: the reproduction must decode back exactly what was encoded, mirroring
		// FrontCodedFindKeyBenchmark's @Setup discipline - fail fast rather than benchmark a broken port
		for (int i = 0; i < this.blockSize; i++) {
			final String decoded = decodeAtString(i);
			if (!decoded.equals(keys[i])) {
				throw new IllegalStateException(
					"Front-coded reproduction is broken at index " + i + ": expected '" + keys[i] + "' got '" + decoded + "'");
			}
		}

		// Both buffers grow-on-demand (maxBufferSize=-1) rather than trusting a hand-computed per-entry byte
		// budget: bypassOutput's "predictable" length-prefixed raw bytes still underestimated the decoded key
		// length (generateKeys' ~33-char keys + varint prefix exceeded the original 20B/entry budget), and
		// todayOutput carries a full Kryo class-name string on every entry (unregistered class, cache reset at
		// every top-level writeClassAndObject call) which a flat 32B/entry budget also undercounted - both
		// threw KryoBufferOverflowException on an actual run, not caught by hand-verification alone (see
		// PrimitiveColumnSerializationBenchmark's identical fix). Growth happens once here in @Setup, never
		// inside a timed @Benchmark method, so B/op still reflects only the write loop itself.
		this.bypassOutput = new Output(this.blockSize * 20, -1);
		this.todayOutput = new Output(this.blockSize * 32, -1);

		writeToday();
		this.serializedToday = this.todayOutput.toBytes();
		writeBypass();
		this.serializedBypass = this.bypassOutput.toBytes();
		checkRoundTrip(keys);
	}

	@Benchmark
	public void write_today(@Nonnull Blackhole bh) {
		bh.consume(writeToday());
	}

	@Benchmark
	public void write_bypass(@Nonnull Blackhole bh) {
		bh.consume(writeBypass());
	}

	@Benchmark
	public void read_today_toStrings(@Nonnull Blackhole bh) {
		bh.consume(readTodayToStrings(this.serializedToday));
	}

	@Benchmark
	public void read_bypass_toBytes(@Nonnull Blackhole bh) {
		bh.consume(readBypassToBytes(this.serializedBypass));
	}

	@Benchmark
	public void read_bypass_intoColumn(@Nonnull Blackhole bh) {
		bh.consume(readBypassIntoColumn(this.serializedBypass));
	}

	@Benchmark
	public void read_today_intoColumn(@Nonnull Blackhole bh) {
		bh.consume(readTodayIntoColumn(this.serializedToday));
	}

	@Benchmark
	public void read_today_bulkEncode(@Nonnull Blackhole bh) {
		bh.consume(readTodayBulkEncode(this.serializedToday));
	}

	/**
	 * Today's shape: decode each entry to a {@link String} (mirrors {@code keyAt}/{@code decodeAtString}), then
	 * {@code kryo.writeClassAndObject} into the reused {@link #todayOutput} — matches
	 * {@code GlobalUniqueIndexLeafPagePartSerializer#writePayload}'s per-entry loop exactly (minus the
	 * {@code long} payload write, irrelevant to this A/B).
	 *
	 * @return bytes written ({@link Output#position()}), consumed by the caller
	 */
	private int writeToday() {
		this.todayOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			final String value = decodeAtString(i);
			this.kryo.writeClassAndObject(this.todayOutput, value);
		}
		return this.todayOutput.position();
	}

	/**
	 * Option B prototype: length-prefixed raw UTF-8 bytes straight from {@link #decodeAtBytes}'s scratch buffer -
	 * same restart-walk, same scratch reuse H2 already proved correct and allocation-free, just no {@link String}
	 * and no per-entry Kryo class tag.
	 */
	private int writeBypass() {
		this.bypassOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			final int len = decodeAtBytes(i);
			this.bypassOutput.writeVarInt(len, true);
			this.bypassOutput.writeBytes(this.cur, 0, len);
		}
		return this.bypassOutput.position();
	}

	@Nonnull
	private String[] readTodayToStrings(@Nonnull byte[] bytes) {
		final String[] out = new String[this.blockSize];
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				out[i] = (String) this.kryo.readClassAndObject(input);
			}
		}
		return out;
	}

	/**
	 * Reads the raw byte runs back but stops short of wrapping each in a {@link String} - isolates the read-side
	 * cost. Grows {@link #cur} rather than clamping the read to its current length: a clamp would silently
	 * under-read a key longer than the scratch buffer and desync the stream for every subsequent entry in the
	 * page (advisor caught this — a real correctness bug, not just a style nit, since it would have produced
	 * silently wrong B/op numbers on any fixture with a key longer than {@link #DECODE_SCRATCH_BYTES}).
	 */
	private int readBypassToBytes(@Nonnull byte[] bytes) {
		int acc = 0;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				final int len = input.readVarInt(true);
				if (len > this.cur.length) {
					this.cur = Arrays.copyOf(this.cur, Math.max(len, this.cur.length << 1));
				}
				input.readBytes(this.cur, 0, len);
				acc += len;
			}
		}
		return acc;
	}

	/**
	 * Reads the raw byte runs and bulk-encodes them into a fresh reproduced column via ONE
	 * {@code encode(byte[], int[], int)} call - the actual Option B load path (write and read are format-coupled:
	 * once the on-disk bytes are the raw-byte-run format {@link #writeBypass} produces, this is the only way to
	 * read them back into a usable column, not an optional extra). Compare against {@link #readTodayIntoColumn},
	 * not {@link #readTodayToStrings} - the latter never builds a column, so comparing it to this method compares
	 * two different amounts of work (advisor caught this — an early draft claimed "bypass loses" against
	 * {@code readTodayToStrings}, which does strictly less work and was never a fair baseline).
	 */
	@Nonnull
	private EncodedColumn readBypassIntoColumn(@Nonnull byte[] bytes) {
		final byte[] flat = new byte[bytes.length];
		final int[] offsets = new int[this.blockSize + 1];
		int flatPos = 0;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				offsets[i] = flatPos;
				final int len = input.readVarInt(true);
				// readBytes (not read) - a partial read here would desync every subsequent entry, the same
				// class of bug fixed in readBypassToBytes above
				input.readBytes(flat, flatPos, len);
				flatPos += len;
			}
		}
		offsets[this.blockSize] = flatPos;
		return encodeFromFlat(flat, offsets, this.blockSize);
	}

	/**
	 * Today's ACTUAL load path: deserialize each {@link String} via Kryo, then insert it the way
	 * {@code FrontCodedStringColumn.insertKeyAt} really does - decode every already-inserted entry back to raw
	 * bytes (byte-level, no {@link String} round trip for the existing entries, matching
	 * {@code decodeAllToFlat}'s shape exactly), append the new key, and re-encode the whole thing via ONE
	 * {@link #encodeFromFlat} call. This is O(current size) work per insert, so O(n^2) total across all
	 * {@code blockSize} inserts - the real cost {@code insertKeyAt}'s own javadoc-equivalent comment describes,
	 * not a shortcut. This is the fair baseline for {@link #readBypassIntoColumn}: both build a real column;
	 * {@link #readTodayToStrings} does not and is not a valid comparison for the load-path question (advisor
	 * caught an early draft comparing bypass's column-build against today's non-column-build and concluding
	 * "bypass loses" - it doesn't do less work, it does the same work strictly faster).
	 */
	@Nonnull
	private EncodedColumn readTodayIntoColumn(@Nonnull byte[] bytes) {
		byte[] curData = new byte[0];
		int[] curRestarts = new int[0];
		int curSize = 0;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				final String value = (String) this.kryo.readClassAndObject(input);
				final byte[] newKeyBytes = value.getBytes(StandardCharsets.UTF_8);
				int flatPos = 0;
				for (int j = 0; j < curSize; j++) {
					this.buildOffsets[j] = flatPos;
					final int restart = j / RESTART_INTERVAL;
					final int base = restart * RESTART_INTERVAL;
					int pos = curRestarts[restart];
					byte[] c = this.buildDecodeCur;
					int curLen = 0;
					for (int k = base; k <= j; k++) {
						int shared = 0, shift = 0;
						byte b;
						do {
							b = curData[pos++];
							shared |= (b & 0x7F) << shift;
							shift += 7;
						} while ((b & 0x80) != 0);
						int suffixLen = 0;
						shift = 0;
						do {
							b = curData[pos++];
							suffixLen |= (b & 0x7F) << shift;
							shift += 7;
						} while ((b & 0x80) != 0);
						final int total = shared + suffixLen;
						if (total > c.length) {
							c = Arrays.copyOf(c, Math.max(total, c.length << 1));
						}
						System.arraycopy(curData, pos, c, shared, suffixLen);
						pos += suffixLen;
						curLen = total;
					}
					this.buildDecodeCur = c;
					System.arraycopy(c, 0, this.buildFlat, flatPos, curLen);
					flatPos += curLen;
				}
				this.buildOffsets[curSize] = flatPos;
				System.arraycopy(newKeyBytes, 0, this.buildFlat, flatPos, newKeyBytes.length);
				flatPos += newKeyBytes.length;
				this.buildOffsets[curSize + 1] = flatPos;
				final EncodedColumn rebuilt = encodeFromFlat(this.buildFlat, this.buildOffsets, curSize + 1);
				curData = rebuilt.data();
				curRestarts = rebuilt.restartOffsets();
				curSize++;
			}
		}
		return new EncodedColumn(curData, curRestarts);
	}

	/**
	 * Decomposes the 257× {@code read_today_intoColumn} vs {@code read_bypass_intoColumn} gap into its two
	 * independent components — the on-disk FORMAT (Kryo-tagged strings vs raw bytes) and the CONSTRUCTION
	 * ALGORITHM (n sequential {@code insertKeyAt} calls vs one bulk {@code encode}) — since the two were
	 * conflated in that comparison. This variant holds format at today's (Kryo-tagged strings) but swaps
	 * sequential inserts for one bulk {@link #encode(String[])} call, same as {@link #setUp}'s own fixture
	 * construction. Comparing this against {@link #readBypassIntoColumn} isolates the format delta with
	 * construction held constant (both bulk); comparing it against {@link #readTodayIntoColumn} isolates the
	 * algorithm delta with format held constant (both Kryo-tagged strings). Confirmed result: algorithm delta
	 * 112×, format delta 2.3× (112 × 2.3 ≈ 257, consistent) — most of the headline number is the O(n²)
	 * sequential-insert algorithm, not the format.
	 */
	@Nonnull
	private EncodedColumn readTodayBulkEncode(@Nonnull byte[] bytes) {
		return encode(readTodayToStrings(bytes));
	}

	private void checkRoundTrip(@Nonnull String[] keys) {
		final String[] todayValues = readTodayToStrings(this.serializedToday);
		for (int i = 0; i < this.blockSize; i++) {
			if (!keys[i].equals(todayValues[i])) {
				throw new IllegalStateException(
					"write_today/read_today round trip broken at index " + i + ": expected '" + keys[i]
						+ "' got '" + todayValues[i] + "'");
			}
		}
		final EncodedColumn reloaded = readBypassIntoColumn(this.serializedBypass);
		for (int i = 0; i < this.blockSize; i++) {
			final String decoded = decodeAtStringFrom(reloaded.data(), reloaded.restartOffsets(), i);
			if (!keys[i].equals(decoded)) {
				throw new IllegalStateException(
					"write_bypass/read_bypass_intoColumn round trip broken at index " + i + ": expected '" + keys[i]
						+ "' got '" + decoded + "'");
			}
		}
		final EncodedColumn reloadedToday = readTodayIntoColumn(this.serializedToday);
		for (int i = 0; i < this.blockSize; i++) {
			final String decoded = decodeAtStringFrom(reloadedToday.data(), reloadedToday.restartOffsets(), i);
			if (!keys[i].equals(decoded)) {
				throw new IllegalStateException(
					"write_today/read_today_intoColumn round trip broken at index " + i + ": expected '" + keys[i]
						+ "' got '" + decoded + "'");
			}
		}
		final EncodedColumn reloadedBulk = readTodayBulkEncode(this.serializedToday);
		for (int i = 0; i < this.blockSize; i++) {
			final String decoded = decodeAtStringFrom(reloadedBulk.data(), reloadedBulk.restartOffsets(), i);
			if (!keys[i].equals(decoded)) {
				throw new IllegalStateException(
					"write_today/read_today_bulkEncode round trip broken at index " + i + ": expected '" + keys[i]
						+ "' got '" + decoded + "'");
			}
		}
	}

	// ---- front-coded reproduction (same algorithm as FrontCodedFindKeyBenchmark / the production class) ----

	@Nonnull
	private String decodeAtString(int index) {
		final int len = decodeAtBytes(index);
		return new String(this.cur, 0, len, StandardCharsets.UTF_8);
	}

	@Nonnull
	private String decodeAtStringFrom(@Nonnull byte[] data, @Nonnull int[] restartOffsets, int index) {
		final int restart = index / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		int pos = restartOffsets[restart];
		byte[] localCur = new byte[DECODE_SCRATCH_BYTES];
		int curLen = 0;
		for (int j = base; j <= index; j++) {
			int shared = 0, shift = 0;
			byte b;
			do {
				b = data[pos++];
				shared |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			int suffixLen = 0;
			shift = 0;
			do {
				b = data[pos++];
				suffixLen |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			final int total = shared + suffixLen;
			if (total > localCur.length) {
				localCur = Arrays.copyOf(localCur, Math.max(total, localCur.length << 1));
			}
			System.arraycopy(data, pos, localCur, shared, suffixLen);
			pos += suffixLen;
			curLen = total;
		}
		return new String(localCur, 0, curLen, StandardCharsets.UTF_8);
	}

	private int decodeAtBytes(int index) {
		final int restart = index / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		int pos = this.restartOffsets[restart];
		byte[] c = this.cur;
		int curLen = 0;
		for (int j = base; j <= index; j++) {
			int shared = 0, shift = 0;
			byte b;
			do {
				b = this.data[pos++];
				shared |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			int suffixLen = 0;
			shift = 0;
			do {
				b = this.data[pos++];
				suffixLen |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			final int total = shared + suffixLen;
			if (total > c.length) {
				c = Arrays.copyOf(c, Math.max(total, c.length << 1));
			}
			System.arraycopy(this.data, pos, c, shared, suffixLen);
			pos += suffixLen;
			curLen = total;
		}
		this.cur = c;
		return curLen;
	}

	@Nonnull
	private static String[] generateKeys(int n) {
		final String[] keys = new String[n];
		for (int i = 0; i < n; i++) {
			keys[i] = "/category/sub/product-slug-" + pad(i, 6);
		}
		return keys;
	}

	@Nonnull
	private static String pad(int value, int width) {
		final String s = Integer.toString(value);
		if (s.length() >= width) {
			return s;
		}
		final StringBuilder sb = new StringBuilder(width);
		for (int i = s.length(); i < width; i++) {
			sb.append('0');
		}
		sb.append(s);
		return sb.toString();
	}

	@Nonnull
	private static EncodedColumn encode(@Nonnull String[] keys) {
		final int n = keys.length;
		final byte[][] rawKeys = new byte[n][];
		int totalRaw = 0;
		for (int i = 0; i < n; i++) {
			rawKeys[i] = keys[i].getBytes(StandardCharsets.UTF_8);
			totalRaw += rawKeys[i].length;
		}
		final byte[] flat = new byte[totalRaw];
		final int[] offsets = new int[n + 1];
		int flatPos = 0;
		for (int i = 0; i < n; i++) {
			offsets[i] = flatPos;
			System.arraycopy(rawKeys[i], 0, flat, flatPos, rawKeys[i].length);
			flatPos += rawKeys[i].length;
		}
		offsets[n] = flatPos;
		return encodeFromFlat(flat, offsets, n);
	}

	@Nonnull
	private static EncodedColumn encodeFromFlat(@Nonnull byte[] flat, @Nonnull int[] offsets, int n) {
		final int[] restarts = new int[(n + RESTART_INTERVAL - 1) / RESTART_INTERVAL];
		byte[] buf = new byte[Math.max(16, n * 4)];
		int len = 0;
		int prevStart = 0;
		int prevLen = 0;
		for (int i = 0; i < n; i++) {
			final int start = offsets[i];
			final int keyLen = offsets[i + 1] - start;
			final int shared;
			if (i % RESTART_INTERVAL == 0) {
				restarts[i / RESTART_INTERVAL] = len;
				shared = 0;
			} else {
				shared = commonPrefix(flat, prevStart, prevLen, start, keyLen);
			}
			final int suffixLen = keyLen - shared;
			buf = ensureCapacity(buf, len + MAX_ENTRY_HEADER_BYTES + suffixLen);
			len = writeVarInt(buf, len, shared);
			len = writeVarInt(buf, len, suffixLen);
			System.arraycopy(flat, start + shared, buf, len, suffixLen);
			len += suffixLen;
			prevStart = start;
			prevLen = keyLen;
		}
		return new EncodedColumn(Arrays.copyOf(buf, len), restarts);
	}

	private static int commonPrefix(@Nonnull byte[] arr, int aStart, int aLen, int bStart, int bLen) {
		final int min = Math.min(aLen, bLen);
		int i = 0;
		while (i < min && arr[aStart + i] == arr[bStart + i]) {
			i++;
		}
		return i;
	}

	private static int writeVarInt(@Nonnull byte[] buf, int pos, int value) {
		int v = value;
		while ((v & ~0x7F) != 0) {
			buf[pos++] = (byte) ((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		buf[pos++] = (byte) v;
		return pos;
	}

	@Nonnull
	private static byte[] ensureCapacity(@Nonnull byte[] buf, int required) {
		if (buf.length >= required) {
			return buf;
		}
		int newLength = buf.length << 1;
		if (newLength < required) {
			newLength = required;
		}
		return Arrays.copyOf(buf, newLength);
	}

	private record EncodedColumn(@Nonnull byte[] data, @Nonnull int[] restartOffsets) {
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
