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
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * JMH decision gate for the generalized Option B proposal
 * ({@code docs/design/2026-07-09-frontcoded-keyat-flush-path-plan.md}'s Option B, broadened to every
 * primitive {@code ValueColumn} — see {@code docs/design/2026-07-10-option-b-generalized-stage1-results.md}
 * for the results this benchmark produced) on
 * {@code InstantValueColumn} and {@code LongValueColumn}/{@code IntValueColumn}'s shared shape. Measures
 * whether skipping the box-then-Kryo-polymorphic-write round trip on the granular leaf-page flush/load path
 * is worth building, with {@code BoxedObjectColumn} included as the zero-alloc control (its "bypass" variant
 * is defined to equal its "today" variant, since there is no primitive form to fall back to — it establishes
 * the floor the primitive columns' bypass variants should approach).
 *
 * Each {@code write_*} benchmark serializes one full leaf page ({@code blockSize} entries) into a single
 * reused {@link Output}, mirroring {@code GlobalUniqueIndexLeafPagePartSerializer.writePayload}'s per-page
 * granularity (one {@code Output} amortized across many entries, not one per entry) — the same unit
 * {@code collectChangedPages}/{@code appendStorageParts} actually produces per dirty leaf, per flush.
 * Each {@code read_*} benchmark deserializes that same page back.
 *
 * <ul>
 *     <li>{@code write_today_*} — today's production shape: box the primitive (mirrors {@code keyAt}), then
 *     {@code kryo.writeClassAndObject(output, boxed)} per entry (a per-entry class tag, matching
 *     {@code GlobalUniqueIndexLeafPagePartSerializer#writePayload} exactly).</li>
 *     <li>{@code write_bypassNoTag_*} — the Option B prototype measured here: raw primitive value(s) straight
 *     from the backing array, zero boxing, zero class tag at all (neither per-entry nor per-page). This
 *     isolates the boxing-elision win combined with the tag-elision win as one number; the design note's
 *     separately-flagged "homogeneous tag written once per page" refinement (a smaller, secondary win layered
 *     on top) is NOT implemented as a distinct benchmark variant here — if the combined number below passes
 *     the decision gate, a follow-up spike can split the two if the distinction matters for the SPI design.</li>
 *     <li>{@code read_today_*} / {@code read_bypass_*} — the paired read-side counterparts.</li>
 * </ul>
 *
 * <p><b>Decision gate — necessary, not sufficient</b>: a meaningful ns/op AND B/op win for
 * {@code write_bypassNoTag}/{@code read_bypass} over {@code write_today}/{@code read_today} — for
 * {@code InstantValueColumn} in particular, since it has zero JDK boxing cache and is therefore the cleanest
 * signal ({@code IntValueColumn}'s small-value cache hits would otherwise dilute the picture) — authorizes
 * only the next, small, production-shaped confirmation step, NOT the SPI/refactor itself. See
 * {@code docs/design/2026-07-10-option-b-generalized-stage1-results.md}'s "decision gate must not repeat
 * H2's trap" reasoning: an isolated serialization microbenchmark can only prove that removing an allocation removes an allocation
 * — it cannot show whether that allocation was ever large enough to move flush-path wall-clock time or
 * GC-CPU%, which is what H2's own JMH-passed-but-production-remeasure-was-flat history warns against
 * assuming. If the win here is negligible once JIT inlines/escape-analyzes the boxing away, or if Kryo's own
 * {@code writeClassAndObject} overhead (not the boxing) turns out to dominate, stop — the SPI machinery
 * would not pay for itself even before reaching the production-confirmation stage.</p>
 *
 * <p>Run with {@code -prof gc} to capture B/op alongside ns/op, via
 * {@code java -cp target/benchmarks.jar org.openjdk.jmh.Main "io\.evitadb\.spike\.PrimitiveColumnSerializationBenchmark" -prof gc}
 * — NOT {@code java -jar target/benchmarks.jar}, which invokes this module's {@code ArtificialTestRunner}
 * main class (hardcoded to run the full {@code io.evitadb.performance.externalApi.*} suite) instead of
 * JMH's own arg-parsing {@code Main}, regardless of what class-name regex is passed.</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class PrimitiveColumnSerializationBenchmark {

	/**
	 * Leaf block sizes actually used in production: 256 for {@code GlobalUniqueIndex}/{@code OwnerUniqueIndex}
	 * ({@code UniqueIndexBPlusTreeSupport.VALUE_BLOCK_SIZE}), 64 for {@code InvertedIndex}/{@code FilterIndex}
	 * trees — see the memory note correcting the earlier blanket "block size = 64" assumption.
	 */
	@Param({"64", "256"})
	private int blockSize;

	private Kryo kryo;

	// --- Instant fixture: parallel (seconds, nanos) arrays, mirrors InstantValueColumn's backing exactly ---
	private long[] instantSeconds;
	private int[] instantNanos;
	private byte[] instantSerializedToday;
	private byte[] instantSerializedBypass;
	// dedicated, pre-sized, reused Output per write variant - reset() (an O(1) position rewind, not an
	// allocation) is the only per-invocation cost paid on top of the write loop itself. A shared
	// ByteArrayOutputStream+Output allocated fresh per @Benchmark invocation would have swamped a small
	// real delta with harness allocation noise, especially at blockSize=64 - advisor caught this.
	private Output instantTodayOutput;
	private Output instantBypassOutput;

	// --- Long fixture: mirrors LongValueColumn's backing (already-encoded long form) ---
	private long[] longKeys;
	private byte[] longSerializedToday;
	private byte[] longSerializedBypass;
	private Output longTodayOutput;
	private Output longBypassOutput;

	// --- Int fixture: mirrors IntValueColumn's backing ---
	private int[] intKeys;
	private byte[] intSerializedToday;
	private byte[] intSerializedBypass;
	private Output intTodayOutput;
	private Output intBypassOutput;

	// --- Boxed fixture (control): already-boxed Long, no primitive form to bypass to ---
	private Long[] boxedKeys;
	private byte[] boxedSerializedToday;
	private Output boxedTodayOutput;

	@Setup(Level.Trial)
	public void setUp() {
		this.kryo = new Kryo();
		// Production's Kryo (KryoFactory.initializeKryo) sets setRegistrationRequired(true) and pre-registers
		// Instant/Long/Integer/String with fixed IDs (confirmed by reading KryoFactory.java), so
		// writeClassAndObject writes a 1-2 byte varint class ID that survives Kryo's per-call autoReset,
		// never a class-name string. An unregistered new Kryo() (this benchmark's first draft) would have
		// every write_today_*/read_today_* entry pay a full "java.time.Instant"-style ASCII class-name cost
		// instead - inflating both ns/op and B/op with an artifact production never pays. Registering here
		// isolates the boxing allocation this benchmark actually exists to measure.
		this.kryo.setRegistrationRequired(true);
		this.kryo.register(Instant.class);
		this.kryo.register(Long.class);
		this.kryo.register(Integer.class);

		this.instantSeconds = new long[this.blockSize];
		this.instantNanos = new int[this.blockSize];
		this.longKeys = new long[this.blockSize];
		this.intKeys = new int[this.blockSize];
		this.boxedKeys = new Long[this.blockSize];
		final long baseEpoch = 1_750_000_000L; // an arbitrary but realistic 2026-ish epoch second
		for (int i = 0; i < this.blockSize; i++) {
			this.instantSeconds[i] = baseEpoch + i;
			this.instantNanos[i] = (i * 37) % 1_000_000_000;
			this.longKeys[i] = baseEpoch + i;
			// scaled by 50 so most values fall outside the JDK's -128..127 Integer cache window (only the
			// handful of i near blockSize/2 land inside it) - an unscaled i-(blockSize/2) stays entirely
			// within the cache for every blockSize tested here, silently testing only the free-boxing case
			this.intKeys[i] = (i - (this.blockSize / 2)) * 50;
			this.boxedKeys[i] = this.longKeys[i];
		}

		// "bypass" outputs write exact, predictable primitive widths (writeLong=8B, writeInt=4B) - fixed-size,
		// non-growable, generously pre-sized so the timed write loop never triggers buffer growth.
		this.instantBypassOutput = new Output(this.blockSize * 16);
		this.longBypassOutput = new Output(this.blockSize * 12);
		this.intBypassOutput = new Output(this.blockSize * 8);
		// "today" outputs carry a full Kryo class-name string on every entry (unregistered class, and
		// writeClassAndObject's implicit reset() at depth 0 clears the class-id cache between entries too -
		// this mirrors production's per-entry kryo.writeClassAndObject exactly, so the cost is real, not a
		// benchmark artifact) - a flat per-entry byte budget undercounted this and threw
		// KryoBufferOverflowException on the very first setUp() call. Grow-on-demand (maxBufferSize=-1) instead
		// of hand-computing a worst-case budget; growth happens once here in @Setup, never inside a timed
		// @Benchmark method, so B/op still reflects only the write loop itself.
		this.instantTodayOutput = new Output(this.blockSize * 24, -1);
		this.longTodayOutput = new Output(this.blockSize * 24, -1);
		this.intTodayOutput = new Output(this.blockSize * 24, -1);
		this.boxedTodayOutput = new Output(this.blockSize * 24, -1);

		// pre-serialize one "today" and one "bypass" page for each kind, self-checking round-trip fidelity
		// before trusting any timing number — same discipline as FrontCodedFindKeyBenchmark's @Setup checks.
		// toBytes() is called only here (setup time), never inside a timed @Benchmark method.
		writeInstantToday();
		this.instantSerializedToday = this.instantTodayOutput.toBytes();
		writeInstantBypass();
		this.instantSerializedBypass = this.instantBypassOutput.toBytes();
		checkInstantRoundTrip();

		writeLongToday();
		this.longSerializedToday = this.longTodayOutput.toBytes();
		writeLongBypass();
		this.longSerializedBypass = this.longBypassOutput.toBytes();
		checkLongRoundTrip();

		writeIntToday();
		this.intSerializedToday = this.intTodayOutput.toBytes();
		writeIntBypass();
		this.intSerializedBypass = this.intBypassOutput.toBytes();
		checkIntRoundTrip();

		writeBoxedToday();
		this.boxedSerializedToday = this.boxedTodayOutput.toBytes();
		checkBoxedRoundTrip();
	}

	// ============================== Instant ==============================

	@Benchmark
	public void write_today_instant(@Nonnull Blackhole bh) {
		bh.consume(writeInstantToday());
	}

	@Benchmark
	public void write_bypassNoTag_instant(@Nonnull Blackhole bh) {
		bh.consume(writeInstantBypass());
	}

	@Benchmark
	public void read_today_instant(@Nonnull Blackhole bh) {
		bh.consume(readInstantToday(this.instantSerializedToday));
	}

	@Benchmark
	public void read_bypass_instant(@Nonnull Blackhole bh) {
		bh.consume(readInstantBypass(this.instantSerializedBypass));
	}

	/**
	 * Today's shape: box each entry to {@link Instant} (mirrors {@code keyAt}), then {@code writeClassAndObject}
	 * into the reused, pre-sized {@link #instantTodayOutput} - {@code reset()} is the only per-invocation cost
	 * on top of the write loop, so B/op reflects the boxing + Kryo write, not harness allocation.
	 *
	 * @return the number of bytes written (via {@link Output#position()}), consumed by the caller so the JIT
	 *         cannot dead-code-eliminate the write loop
	 */
	private int writeInstantToday() {
		this.instantTodayOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			// the allocation under test: mirrors InstantValueColumn.keyAt's Instant.ofEpochSecond boxing
			final Instant boxed = Instant.ofEpochSecond(this.instantSeconds[i], this.instantNanos[i]);
			this.kryo.writeClassAndObject(this.instantTodayOutput, boxed);
		}
		return this.instantTodayOutput.position();
	}

	/** Option B prototype: raw (seconds, nanos) straight off the backing arrays, zero boxing, zero per-entry tag. */
	private int writeInstantBypass() {
		this.instantBypassOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			this.instantBypassOutput.writeLong(this.instantSeconds[i]);
			this.instantBypassOutput.writeInt(this.instantNanos[i]);
		}
		return this.instantBypassOutput.position();
	}

	private long readInstantToday(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				final Instant value = (Instant) this.kryo.readClassAndObject(input);
				acc += value.getEpochSecond() + value.getNano();
			}
		}
		return acc;
	}

	private long readInstantBypass(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				final long sec = input.readLong();
				final int nano = input.readInt();
				acc += sec + nano;
			}
		}
		return acc;
	}

	private void checkInstantRoundTrip() {
		for (int i = 0; i < this.blockSize; i++) {
			final Instant expected = Instant.ofEpochSecond(this.instantSeconds[i], this.instantNanos[i]);
			// spot-check via the today-path deserializer only (bypass path re-verified separately below via sum)
			if (i == 0) {
				try (final Input input = new Input(this.instantSerializedToday)) {
					final Instant actual = (Instant) this.kryo.readClassAndObject(input);
					if (!actual.equals(expected)) {
						throw new IllegalStateException(
							"Instant today-path round trip broken at index 0: expected " + expected + " got " + actual);
					}
				}
			}
		}
		final long todaySum = readInstantToday(this.instantSerializedToday);
		final long bypassSum = readInstantBypass(this.instantSerializedBypass);
		if (todaySum != bypassSum) {
			throw new IllegalStateException(
				"Instant today/bypass round-trip sums disagree: today=" + todaySum + " bypass=" + bypassSum);
		}
	}

	// ================================ Long ================================

	@Benchmark
	public void write_today_long(@Nonnull Blackhole bh) {
		bh.consume(writeLongToday());
	}

	@Benchmark
	public void write_bypassNoTag_long(@Nonnull Blackhole bh) {
		bh.consume(writeLongBypass());
	}

	@Benchmark
	public void read_today_long(@Nonnull Blackhole bh) {
		bh.consume(readLongToday(this.longSerializedToday));
	}

	@Benchmark
	public void read_bypass_long(@Nonnull Blackhole bh) {
		bh.consume(readLongBypass(this.longSerializedBypass));
	}

	private int writeLongToday() {
		this.longTodayOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			// mirrors LongValueColumn.keyAt's codec.decode boxing boundary (codec is identity here - the
			// production codec's own encode/decode cost is out of scope for this A/B, only the box matters)
			final Long boxed = this.longKeys[i];
			this.kryo.writeClassAndObject(this.longTodayOutput, boxed);
		}
		return this.longTodayOutput.position();
	}

	private int writeLongBypass() {
		this.longBypassOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			this.longBypassOutput.writeLong(this.longKeys[i]);
		}
		return this.longBypassOutput.position();
	}

	private long readLongToday(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				acc += (Long) this.kryo.readClassAndObject(input);
			}
		}
		return acc;
	}

	private long readLongBypass(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				acc += input.readLong();
			}
		}
		return acc;
	}

	private void checkLongRoundTrip() {
		final long todaySum = readLongToday(this.longSerializedToday);
		final long bypassSum = readLongBypass(this.longSerializedBypass);
		long expectedSum = 0L;
		for (int i = 0; i < this.blockSize; i++) {
			expectedSum += this.longKeys[i];
		}
		if (todaySum != expectedSum || bypassSum != expectedSum) {
			throw new IllegalStateException(
				"Long round-trip sums disagree: expected=" + expectedSum + " today=" + todaySum + " bypass=" + bypassSum);
		}
	}

	// ================================= Int =================================

	@Benchmark
	public void write_today_int(@Nonnull Blackhole bh) {
		bh.consume(writeIntToday());
	}

	@Benchmark
	public void write_bypassNoTag_int(@Nonnull Blackhole bh) {
		bh.consume(writeIntBypass());
	}

	@Benchmark
	public void read_today_int(@Nonnull Blackhole bh) {
		bh.consume(readIntToday(this.intSerializedToday));
	}

	@Benchmark
	public void read_bypass_int(@Nonnull Blackhole bh) {
		bh.consume(readIntBypass(this.intSerializedBypass));
	}

	private int writeIntToday() {
		this.intTodayOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			// mirrors IntValueColumn.keyAt's Integer.valueOf boxing boundary - deliberately spans the JDK's
			// -128..127 Integer cache (see setUp's intKeys generation) so this benchmark's own numbers show
			// how much the cache already hides, rather than assuming it away
			final Integer boxed = this.intKeys[i];
			this.kryo.writeClassAndObject(this.intTodayOutput, boxed);
		}
		return this.intTodayOutput.position();
	}

	private int writeIntBypass() {
		this.intBypassOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			this.intBypassOutput.writeInt(this.intKeys[i]);
		}
		return this.intBypassOutput.position();
	}

	private long readIntToday(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				acc += (Integer) this.kryo.readClassAndObject(input);
			}
		}
		return acc;
	}

	private long readIntBypass(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				acc += input.readInt();
			}
		}
		return acc;
	}

	private void checkIntRoundTrip() {
		final long todaySum = readIntToday(this.intSerializedToday);
		final long bypassSum = readIntBypass(this.intSerializedBypass);
		long expectedSum = 0L;
		for (int i = 0; i < this.blockSize; i++) {
			expectedSum += this.intKeys[i];
		}
		if (todaySum != expectedSum || bypassSum != expectedSum) {
			throw new IllegalStateException(
				"Int round-trip sums disagree: expected=" + expectedSum + " today=" + todaySum + " bypass=" + bypassSum);
		}
	}

	// ============================ Boxed (control) ============================

	@Benchmark
	public void write_today_boxed(@Nonnull Blackhole bh) {
		bh.consume(writeBoxedToday());
	}

	@Benchmark
	public void read_today_boxed(@Nonnull Blackhole bh) {
		bh.consume(readBoxedToday(this.boxedSerializedToday));
	}

	/**
	 * No {@code write_bypass_boxed}/{@code read_bypass_boxed} on purpose: {@code BoxedObjectColumn}'s {@code keyAt}
	 * is already a zero-allocation plain array read of an already-boxed reference — there is no more-primitive form
	 * to bypass to, so its SPI override would be a no-op falling straight back to {@code write_today_boxed}'s shape.
	 * These two benchmarks exist purely as the control: the SPI's default-fallback path (used by any column that
	 * doesn't override it) must cost exactly this, no more — a regression here would mean the SPI itself adds
	 * overhead even where there is nothing to optimize.
	 */
	private int writeBoxedToday() {
		this.boxedTodayOutput.reset();
		for (int i = 0; i < this.blockSize; i++) {
			this.kryo.writeClassAndObject(this.boxedTodayOutput, this.boxedKeys[i]);
		}
		return this.boxedTodayOutput.position();
	}

	private long readBoxedToday(@Nonnull byte[] bytes) {
		long acc = 0L;
		try (final Input input = new Input(bytes)) {
			for (int i = 0; i < this.blockSize; i++) {
				acc += (Long) this.kryo.readClassAndObject(input);
			}
		}
		return acc;
	}

	private void checkBoxedRoundTrip() {
		final long sum = readBoxedToday(this.boxedSerializedToday);
		long expected = 0L;
		for (int i = 0; i < this.blockSize; i++) {
			expected += this.boxedKeys[i];
		}
		if (sum != expected) {
			throw new IllegalStateException("Boxed round-trip sum disagrees: expected=" + expected + " actual=" + sum);
		}
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
