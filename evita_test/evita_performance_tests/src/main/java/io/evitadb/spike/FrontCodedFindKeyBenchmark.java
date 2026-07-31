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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Phase 2 of {@code docs/design/2026-07-09-frontcoded-allocation-impl-plan.md} (H2 decision gate). Answers two
 * questions the ALIVE-churn alloc profile alone can't: (a) how much of {@code FrontCodedStringColumn.findKeyPosition}'s
 * cost is the per-hop {@code new String(...)} versus the restart-chain decode walk itself, and (b) whether an
 * unsigned byte-lexicographic compare actually beats {@code String.compareTo} on realistic keys.
 *
 * {@code FrontCodedStringColumn} is package-private to {@code evita_engine}, so — exactly as
 * {@link SortIndexResolvePositionsBenchmark} did for a private method — this benchmark reproduces a minimal
 * front-coded column (encode + restart-indexed decode) directly, mirroring
 * {@code io.evitadb.index.bPlusTree.FrontCodedStringColumn}'s varint layout, restart interval, and growth-on-demand
 * scratch buffers exactly (verified by the {@code @Setup} self-check below, not just by inspection).
 *
 * Three benchmark families, each run against a {@code hit} probe (present in the column) and a {@code miss} probe
 * (absent, forcing a full {@code log2(n)} descent):
 * <ul>
 *     <li>{@link #findKey_stringCompare_hit} / {@link #findKey_stringCompare_miss} — today's production path:
 *     decode each candidate to a {@link String}, compare via {@link String#compareTo}.</li>
 *     <li>{@link #findKey_byteCompare_hit} / {@link #findKey_byteCompare_miss} — the H2 prototype: decode each
 *     candidate to scratch bytes (no {@link String}), compare via unsigned byte-lexicographic order against the
 *     probe's UTF-8 bytes, encoded once per call (the real per-call cost H2 would pay in production — not
 *     pre-encoded in {@code @Setup}, which would hide it).</li>
 *     <li>{@link #findKey_decodeOnly_hit} / {@link #findKey_decodeOnly_miss} — isolates the restart-walk decode
 *     cost alone: replays the exact sequence of {@code mid} indices the byte-compare search visited (recorded once
 *     in {@code @Setup}), decoding each but never comparing. If this tracks close to the byte-compare variant's
 *     cost, the walk dominates and removing {@code String} barely helps; if it's much cheaper, the {@code new
 *     String} per hop is the real cost H2 would remove.</li>
 * </ul>
 *
 * Fixtures ({@code @Param}) span four realistic key shapes — product codes, EAN-13, URLs, and an accented-Latin
 * (BMP, natural-order) set exercising the BMP-safe predicate H2's correctness depends on — crossed with leaf fill
 * size 16 / 48 / 64 (one restart block, ~3 blocks, and the full default leaf capacity).
 *
 * <p><b>Decision gate</b> (end of Phase 2): proceed to H2 if the byte-compare variant shows a meaningful ns/op win
 * over string-compare <i>and</i> {@code -prof gc}'s B/op drop is attributable to the removed {@code new String}
 * (i.e. {@link #findKey_decodeOnly_hit}/{@code _miss} tracks byte-compare, not string-compare). Stop / rethink if
 * the decode-only variant is nearly as expensive as string-compare — then the restart-walk itself dominates and
 * byte-compare would not pay for its added correctness surface (BMP-safe scan, dual code paths).</p>
 *
 * <p>Run with {@code -prof gc} to capture the normalized allocation rate (B/op) alongside ns/op.</p>
 *
 * <p><b>Second use — the JDK-intrinsic byte comparisons.</b> Two further families A/B the hand-written per-byte
 * loops against their {@code java.util.Arrays} equivalents:</p>
 * <ul>
 *     <li>{@link #findKey_byteCompareIntrinsic_hit} / {@link #findKey_byteCompareIntrinsic_miss} — the same search
 *     as the byte-compare family, with {@link #compareUnsigned} swapped for {@link Arrays#compareUnsigned}. This is
 *     already shipped in {@code FrontCodedStringColumn#compareUnsignedBytes}; the pair exists to confirm it, not to
 *     gate it.</li>
 *     <li>{@link #commonPrefix_scalar} / {@link #commonPrefix_intrinsic} — the *encode*-side shared-prefix scan
 *     versus {@link Arrays#mismatch}. Not shipped; this is the open candidate.</li>
 * </ul>
 *
 * <p><b>Read the shared-prefix distribution before the ns/op.</b> {@code commonPrefix} is reached only from
 * {@code encode}, and only for non-restart entries — a restart entry assigns {@code shared = 0} outright rather than
 * making a short call — so <i>every</i> call compares two adjacent keys. Because
 * {@link #generateKeys} emits fixed-width zero-padded sequential keys, adjacent keys here share almost their whole
 * length, which is the most favourable distribution {@link Arrays#mismatch} could be handed. {@code @Setup} prints
 * the min / mean / max and a coarse histogram per fixture for exactly this reason: a win measured at 30 shared bytes
 * says nothing about an attribute corpus of brand or product names, whose neighbours diverge early. Treat the
 * printed distribution and the ns/op as a single result.</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
// pinned, not merely defaulted: {@link #cur} is shared mutable decode scratch that the decode path REPLACES on
// growth, so a multi-threaded run would corrupt it - and silently, since the @Setup self-checks all complete before
// any measurement thread starts
@Threads(1)
@State(Scope.Benchmark)
public class FrontCodedFindKeyBenchmark {

	/**
	 * Mirrors {@code FrontCodedStringColumn.RESTART_INTERVAL}: every 16th entry is stored in full.
	 */
	private static final int RESTART_INTERVAL = 16;
	/**
	 * Mirrors {@code FrontCodedStringColumn.MAX_ENTRY_HEADER_BYTES}: two length varints, 5 bytes each, worst case.
	 */
	private static final int MAX_ENTRY_HEADER_BYTES = 10;
	/**
	 * Mirrors {@code FrontCodedStringColumn.DECODE_SCRATCH_BYTES}: initial per-hop decode scratch size.
	 */
	private static final int DECODE_SCRATCH_BYTES = 48;
	/**
	 * Upper bound on binary-search hops for the largest fixture ({@code leafSize == 64}, {@code ceil(log2(64)) + 1
	 * == 7}); sized generously so {@link #recordByteCompareHops} never has to grow its scratch buffer.
	 */
	private static final int MAX_HOPS = 16;
	/**
	 * Bucket count of {@link #prefixLengthHistogram}: shared-prefix lengths are binned as
	 * {@code 0-3 / 4-7 / 8-15 / 16-31 / 32+}. The split at 8 is the one that matters — that is roughly where
	 * {@code Arrays.mismatch}'s vector setup starts to pay for itself against a scalar loop.
	 */
	private static final int PREFIX_HISTOGRAM_BUCKETS = 5;

	/**
	 * Realistic key shapes an indexed String attribute would hold. All four generate {@code n} distinct keys already
	 * in ascending natural (codepoint) order, matching the physical order a non-localized attribute's B+ tree column
	 * stores (front-coding requires physical order, not comparator order — see the production class javadoc).
	 */
	public enum KeyShape {
		/** Product-code-shaped keys, e.g. {@code "AB-00042"}. */
		CODE,
		/** EAN-13-shaped 13-digit numeric keys. */
		EAN13,
		/** URL-path-shaped keys, e.g. {@code "/category/sub/product-slug-00042"}. */
		URL,
		/** Accented-Latin BMP keys (é/ř/ů-class characters) — exercises H2's BMP-safe predicate. */
		ACCENTED
	}

	@Param({"CODE", "EAN13", "URL", "ACCENTED"})
	private KeyShape keyShape;

	@Param({"16", "48", "64"})
	private int leafSize;

	private byte[] data;
	private int[] restartOffsets;
	private int n;

	/**
	 * Decode scratch reused across every hop of every search in this benchmark instance, mirroring
	 * {@code FrontCodedStringColumn.DecodeScratch#cur}'s thread-local reuse (this class is single-threaded per JMH
	 * fork, so an instance field plays the same role).
	 */
	private byte[] cur;

	private String hitProbe;
	private String missProbe;

	/**
	 * The sequence of {@code mid} indices the byte-compare search visits for {@link #hitProbe} / {@link #missProbe},
	 * recorded once in {@link #setUp()} — replayed by {@link #findKey_decodeOnly_hit} / {@link #findKey_decodeOnly_miss}
	 * to isolate the restart-walk decode cost from the compare cost.
	 */
	private int[] hitHops;
	private int[] missHops;

	/**
	 * The raw (not front-coded) key bytes {@link #encode} indexed, retained so {@link #commonPrefix_scalar} /
	 * {@link #commonPrefix_intrinsic} can replay against the identical buffer.
	 */
	private byte[] prefixFlat;
	/**
	 * The exact argument quads {@code encode} passed to {@link #commonPrefix} for this fixture, flattened four ints
	 * per call as {@code (aStart, aLen, bStart, bLen)}.
	 *
	 * Note this is *not* one entry per key: {@code encode} skips the call entirely at every restart point
	 * ({@code i % RESTART_INTERVAL == 0} assigns {@code shared = 0} directly, mirroring production
	 * {@code FrontCodedStringColumn#encode}), so every recorded call compares two **adjacent** keys and there is no
	 * population of short-prefix calls from restarts. The count is therefore
	 * {@code n - ceil(n / RESTART_INTERVAL)}.
	 */
	private int[] prefixCalls;

	/**
	 * Coarse histogram of the shared-prefix lengths {@link #commonPrefix} returns for this fixture, printed once per
	 * trial by {@link #setUp()}. The decision this benchmark feeds is gated on the *distribution*, not on the ns/op
	 * alone — {@code Arrays.mismatch}'s setup cost is only amortized above roughly 8 shared bytes, so a timing number
	 * without the distribution it was measured at is uninterpretable.
	 */
	private int[] prefixLengthHistogram;

	@Setup(Level.Trial)
	public void setUp() {
		this.cur = new byte[DECODE_SCRATCH_BYTES];
		final String[] keys = generateKeys(this.keyShape, this.leafSize);
		final EncodedColumn column = encode(keys);
		this.data = column.data();
		this.restartOffsets = column.restartOffsets();
		this.n = this.leafSize;

		// self-check 1: the reproduction must decode back exactly what was encoded, or every number below is
		// meaningless - fail fast instead of silently benchmarking a broken port
		for (int i = 0; i < this.n; i++) {
			final String decoded = decodeAtString(this.data, this.restartOffsets, i);
			if (!decoded.equals(keys[i])) {
				throw new IllegalStateException(
					"Front-coded reproduction is broken at index " + i + ": expected '" + keys[i]
						+ "' got '" + decoded + "'"
				);
			}
		}

		this.hitProbe = keys[this.n / 2];
		// keys[n/2] followed by a low control char sorts strictly between keys[n/2] and keys[n/2 + 1]: any two
		// successive fixed-width zero-padded keys diverge at a real character before either string ends, so the
		// extra trailing char never changes the comparison outcome against the *next* key
		this.missProbe = this.hitProbe + "\u0001";

		// self-check 2: H2's core correctness claim - unsigned byte compare must agree with String.compareTo on
		// every shape, including BMP-accented - verified here, before a single timing number is trusted
		final int hitByString = findKeyPosition_stringCompare(this.data, this.restartOffsets, this.n, this.hitProbe);
		final int hitByByte = findKeyPosition_byteCompare(this.data, this.restartOffsets, this.n, this.hitProbe);
		if (hitByString != hitByByte) {
			throw new IllegalStateException(
				"Hit-probe search disagreement for " + this.keyShape + "/" + this.n
					+ ": string=" + hitByString + " byte=" + hitByByte
			);
		}
		final int expectedHit = (this.n / 2 << 1) | 1;
		if (hitByByte != expectedHit) {
			throw new IllegalStateException(
				"Hit-probe search did not find the probe for " + this.keyShape + "/" + this.n
					+ ": expected=" + expectedHit + " actual=" + hitByByte
			);
		}
		final int missByString = findKeyPosition_stringCompare(this.data, this.restartOffsets, this.n, this.missProbe);
		final int missByByte = findKeyPosition_byteCompare(this.data, this.restartOffsets, this.n, this.missProbe);
		if (missByString != missByByte) {
			throw new IllegalStateException(
				"Miss-probe search disagreement for " + this.keyShape + "/" + this.n
					+ ": string=" + missByString + " byte=" + missByByte
			);
		}
		final int expectedMiss = (this.n / 2 + 1) << 1;
		if (missByByte != expectedMiss) {
			throw new IllegalStateException(
				"Miss-probe search did not land at the expected insertion point for " + this.keyShape + "/" + this.n
					+ ": expected=" + expectedMiss + " actual=" + missByByte
			);
		}

		final int[] hopScratch = new int[MAX_HOPS];
		final byte[] hitProbeBytes = this.hitProbe.getBytes(StandardCharsets.UTF_8);
		final int hitHopCount = recordByteCompareHops(this.data, this.restartOffsets, this.n, hitProbeBytes, hopScratch);
		this.hitHops = Arrays.copyOf(hopScratch, hitHopCount);
		final byte[] missProbeBytes = this.missProbe.getBytes(StandardCharsets.UTF_8);
		final int missHopCount = recordByteCompareHops(this.data, this.restartOffsets, this.n, missProbeBytes, hopScratch);
		this.missHops = Arrays.copyOf(hopScratch, missHopCount);

		// self-check 3: the intrinsic search must return the identical packed result as the scalar one on both
		// probes - change B is only a drop-in if sign agreement holds at every hop, not just on average
		final int hitByIntrinsic = findKeyPosition_byteCompareIntrinsic(
			this.data, this.restartOffsets, this.n, this.hitProbe
		);
		final int missByIntrinsic = findKeyPosition_byteCompareIntrinsic(
			this.data, this.restartOffsets, this.n, this.missProbe
		);
		if (hitByIntrinsic != hitByByte || missByIntrinsic != missByByte) {
			throw new IllegalStateException(
				"Intrinsic byte-compare search disagreement for " + this.keyShape + "/" + this.n
					+ ": hit scalar=" + hitByByte + " intrinsic=" + hitByIntrinsic
					+ ", miss scalar=" + missByByte + " intrinsic=" + missByIntrinsic
			);
		}

		this.prefixFlat = column.flat();
		this.prefixCalls = recordCommonPrefixCalls(column.offsets(), this.n);

		// self-check 4: the two commonPrefix implementations must agree on every call this fixture actually makes,
		// and the shared-prefix distribution is captured in the same pass - the distribution is the decision gate,
		// so it is printed alongside the timings rather than inferred from them
		this.prefixLengthHistogram = new int[PREFIX_HISTOGRAM_BUCKETS];
		int minPrefix = Integer.MAX_VALUE;
		int maxPrefix = 0;
		long prefixSum = 0;
		for (int c = 0; c < this.prefixCalls.length; c += 4) {
			final int aStart = this.prefixCalls[c];
			final int aLen = this.prefixCalls[c + 1];
			final int bStart = this.prefixCalls[c + 2];
			final int bLen = this.prefixCalls[c + 3];
			final int scalar = commonPrefix(this.prefixFlat, aStart, aLen, bStart, bLen);
			final int intrinsic = commonPrefixIntrinsic(this.prefixFlat, aStart, aLen, bStart, bLen);
			if (scalar != intrinsic) {
				throw new IllegalStateException(
					"commonPrefix disagreement for " + this.keyShape + "/" + this.n + " at call " + (c >> 2)
						+ ": scalar=" + scalar + " intrinsic=" + intrinsic
				);
			}
			minPrefix = Math.min(minPrefix, scalar);
			maxPrefix = Math.max(maxPrefix, scalar);
			prefixSum += scalar;
			this.prefixLengthHistogram[prefixBucket(scalar)]++;
		}

		// self-check 5: Arrays.mismatch returns -1 rather than the common length when it finds no mismatch inside
		// the shorter range, which is exactly what the normalization in commonPrefixIntrinsic exists to repair.
		// Strictly-ascending distinct keys never hit that branch, so it is asserted deliberately here instead of
		// being left to a fixture that cannot reach it.
		final byte[] degenerate = "abcdefabc".getBytes(StandardCharsets.UTF_8);
		// "abcdef" vs "abc": the shorter range is a proper prefix of the longer one
		assertCommonPrefixAgrees(degenerate, 0, 6, 6, 3, 3);
		// "abc" vs "abc": fully equal ranges
		assertCommonPrefixAgrees(degenerate, 6, 3, 6, 3, 3);

		final int callCount = this.prefixCalls.length >> 2;
		System.out.printf(
			"[3A fixture] shape=%-8s leafSize=%2d commonPrefix calls=%2d  sharedLen min=%d mean=%.1f max=%d"
				+ "  histogram 0-3/4-7/8-15/16-31/32+ = %d/%d/%d/%d/%d%n",
			this.keyShape, this.n, callCount,
			callCount == 0 ? 0 : minPrefix,
			callCount == 0 ? 0.0 : (double) prefixSum / callCount,
			maxPrefix,
			this.prefixLengthHistogram[0], this.prefixLengthHistogram[1], this.prefixLengthHistogram[2],
			this.prefixLengthHistogram[3], this.prefixLengthHistogram[4]
		);
	}

	@Benchmark
	public void findKey_stringCompare_hit(@Nonnull Blackhole bh) {
		bh.consume(findKeyPosition_stringCompare(this.data, this.restartOffsets, this.n, this.hitProbe));
	}

	@Benchmark
	public void findKey_stringCompare_miss(@Nonnull Blackhole bh) {
		bh.consume(findKeyPosition_stringCompare(this.data, this.restartOffsets, this.n, this.missProbe));
	}

	@Benchmark
	public void findKey_byteCompare_hit(@Nonnull Blackhole bh) {
		bh.consume(findKeyPosition_byteCompare(this.data, this.restartOffsets, this.n, this.hitProbe));
	}

	@Benchmark
	public void findKey_byteCompare_miss(@Nonnull Blackhole bh) {
		bh.consume(findKeyPosition_byteCompare(this.data, this.restartOffsets, this.n, this.missProbe));
	}

	@Benchmark
	public void findKey_byteCompareIntrinsic_hit(@Nonnull Blackhole bh) {
		bh.consume(findKeyPosition_byteCompareIntrinsic(this.data, this.restartOffsets, this.n, this.hitProbe));
	}

	@Benchmark
	public void findKey_byteCompareIntrinsic_miss(@Nonnull Blackhole bh) {
		bh.consume(findKeyPosition_byteCompareIntrinsic(this.data, this.restartOffsets, this.n, this.missProbe));
	}

	/**
	 * Item 3A's A-side: the scalar {@link #commonPrefix} loop, replayed over the exact argument quads
	 * {@link #encode} produced for this fixture. Measured this way rather than by timing {@code encode} as a whole
	 * because encode's varint writes and {@code System.arraycopy} would dominate and dilute the signal to nothing.
	 */
	@Benchmark
	public void commonPrefix_scalar(@Nonnull Blackhole bh) {
		int acc = 0;
		final int[] calls = this.prefixCalls;
		for (int c = 0; c < calls.length; c += 4) {
			acc += commonPrefix(this.prefixFlat, calls[c], calls[c + 1], calls[c + 2], calls[c + 3]);
		}
		bh.consume(acc);
	}

	/**
	 * Item 3A's B-side: identical replay through {@link #commonPrefixIntrinsic}. Compare against
	 * {@link #commonPrefix_scalar} *only* alongside the shared-prefix distribution `setUp` prints — the two are one
	 * result, not two.
	 */
	@Benchmark
	public void commonPrefix_intrinsic(@Nonnull Blackhole bh) {
		int acc = 0;
		final int[] calls = this.prefixCalls;
		for (int c = 0; c < calls.length; c += 4) {
			acc += commonPrefixIntrinsic(this.prefixFlat, calls[c], calls[c + 1], calls[c + 2], calls[c + 3]);
		}
		bh.consume(acc);
	}

	@Benchmark
	public void findKey_decodeOnly_hit(@Nonnull Blackhole bh) {
		int acc = 0;
		for (final int mid : this.hitHops) {
			acc += decodeAtBytes(this.data, this.restartOffsets, mid);
		}
		bh.consume(acc);
	}

	@Benchmark
	public void findKey_decodeOnly_miss(@Nonnull Blackhole bh) {
		int acc = 0;
		for (final int mid : this.missHops) {
			acc += decodeAtBytes(this.data, this.restartOffsets, mid);
		}
		bh.consume(acc);
	}

	/**
	 * Today's production search: binary search decoding each candidate to a {@link String} and comparing via
	 * {@link String#compareTo}. Mirrors {@code FrontCodedStringColumn#findKeyPosition}'s natural-order branch.
	 *
	 * @return a packed result: {@code (position << 1) | (found ? 1 : 0)} — avoids allocating a result carrier on
	 *         the hot path (this benchmark measures allocation, so its own harness must not add any)
	 */
	private int findKeyPosition_stringCompare(
		@Nonnull byte[] data, @Nonnull int[] restartOffsets, int n, @Nonnull String probe
	) {
		int lo = 0;
		int hi = n - 1;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			final String candidate = decodeAtString(data, restartOffsets, mid);
			final int cmp = candidate.compareTo(probe);
			if (cmp < 0) {
				lo = mid + 1;
			} else if (cmp > 0) {
				hi = mid - 1;
			} else {
				return (mid << 1) | 1;
			}
		}
		return lo << 1;
	}

	/**
	 * The H2 prototype: binary search decoding each candidate to scratch bytes (no {@link String}) and comparing via
	 * unsigned byte-lexicographic order against the probe's UTF-8 bytes. The probe is encoded here, once per call —
	 * exactly the cost production H2 would pay per {@code findKeyPosition} invocation — not pre-encoded in
	 * {@code @Setup}, which would hide it from {@code -prof gc}.
	 *
	 * @return packed result, see {@link #findKeyPosition_stringCompare}
	 */
	private int findKeyPosition_byteCompare(
		@Nonnull byte[] data, @Nonnull int[] restartOffsets, int n, @Nonnull String probe
	) {
		final byte[] probeBytes = probe.getBytes(StandardCharsets.UTF_8);
		int lo = 0;
		int hi = n - 1;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			final int candLen = decodeAtBytes(data, restartOffsets, mid);
			final int cmp = compareUnsigned(this.cur, candLen, probeBytes, probeBytes.length);
			if (cmp < 0) {
				lo = mid + 1;
			} else if (cmp > 0) {
				hi = mid - 1;
			} else {
				return (mid << 1) | 1;
			}
		}
		return lo << 1;
	}

	/**
	 * Item 3B's shipped form, kept here as the measured B-side: byte-for-byte the same search as
	 * {@link #findKeyPosition_byteCompare}, differing only in that the per-hop comparison goes through
	 * {@link #compareUnsignedIntrinsic}.
	 *
	 * @return packed result, see {@link #findKeyPosition_stringCompare}
	 */
	private int findKeyPosition_byteCompareIntrinsic(
		@Nonnull byte[] data, @Nonnull int[] restartOffsets, int n, @Nonnull String probe
	) {
		final byte[] probeBytes = probe.getBytes(StandardCharsets.UTF_8);
		int lo = 0;
		int hi = n - 1;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			final int candLen = decodeAtBytes(data, restartOffsets, mid);
			final int cmp = compareUnsignedIntrinsic(this.cur, candLen, probeBytes, probeBytes.length);
			if (cmp < 0) {
				lo = mid + 1;
			} else if (cmp > 0) {
				hi = mid - 1;
			} else {
				return (mid << 1) | 1;
			}
		}
		return lo << 1;
	}

	/**
	 * Replays {@link #findKeyPosition_byteCompare}'s search but records every visited {@code mid} into
	 * {@code hopsOut} instead of allocating a probe encode — called once per fixture at {@code @Setup} time to build
	 * the hop sequence {@link #findKey_decodeOnly_hit} / {@link #findKey_decodeOnly_miss} replay.
	 *
	 * @return the number of hops recorded into {@code hopsOut}
	 */
	private int recordByteCompareHops(
		@Nonnull byte[] data, @Nonnull int[] restartOffsets, int n, @Nonnull byte[] probeBytes, @Nonnull int[] hopsOut
	) {
		int lo = 0;
		int hi = n - 1;
		int count = 0;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			hopsOut[count++] = mid;
			final int candLen = decodeAtBytes(data, restartOffsets, mid);
			final int cmp = compareUnsigned(this.cur, candLen, probeBytes, probeBytes.length);
			if (cmp < 0) {
				lo = mid + 1;
			} else if (cmp > 0) {
				hi = mid - 1;
			} else {
				break;
			}
		}
		return count;
	}

	/**
	 * Decodes the key at {@code index} into {@link #cur} (see {@link #decodeAtBytes}) and wraps it in a fresh
	 * {@link String} — mirrors {@code FrontCodedStringColumn#decodeAt} exactly (same restart-seek, same growth
	 * policy, same trailing {@code new String} allocation).
	 */
	@Nonnull
	private String decodeAtString(@Nonnull byte[] data, @Nonnull int[] restartOffsets, int index) {
		final int len = decodeAtBytes(data, restartOffsets, index);
		return new String(this.cur, 0, len, StandardCharsets.UTF_8);
	}

	/**
	 * Decodes the key at {@code index} by seeking its enclosing restart point and walking forward, leaving the
	 * result in {@link #cur} (grown on demand, written back, never trimmed) — mirrors
	 * {@code FrontCodedStringColumn#decodeAt}'s restart-walk exactly, minus the trailing {@code new String}. This
	 * is the {@code decodeAtBytes} H2's design (Phase 3, &#167;3.3) adds to the production class.
	 *
	 * @return the decoded key's length within {@link #cur}
	 */
	private int decodeAtBytes(@Nonnull byte[] data, @Nonnull int[] restartOffsets, int index) {
		final int restart = index / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		int pos = restartOffsets[restart];
		byte[] cur = this.cur;
		int curLen = 0;
		for (int j = base; j <= index; j++) {
			int shared = 0;
			int shift = 0;
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
			if (total > cur.length) {
				cur = Arrays.copyOf(cur, Math.max(total, cur.length << 1));
			}
			System.arraycopy(data, pos, cur, shared, suffixLen);
			pos += suffixLen;
			curLen = total;
		}
		this.cur = cur;
		return curLen;
	}

	/**
	 * Unsigned byte-lexicographic comparison of {@code a[0, aLen)} vs {@code b[0, bLen)} — the H2 prototype's
	 * candidate/probe comparator.
	 */
	private static int compareUnsigned(@Nonnull byte[] a, int aLen, @Nonnull byte[] b, int bLen) {
		final int min = Math.min(aLen, bLen);
		for (int i = 0; i < min; i++) {
			final int ai = a[i] & 0xFF;
			final int bi = b[i] & 0xFF;
			if (ai != bi) {
				return ai - bi;
			}
		}
		return aLen - bLen;
	}

	/**
	 * The JDK-intrinsic form of {@link #compareUnsigned} — item 3B, as shipped in
	 * {@code FrontCodedStringColumn#compareUnsignedBytes}. A true drop-in: on a mismatch the intrinsic returns
	 * {@code Byte.compareUnsigned} of the differing pair, and when one range is a prefix of the other it returns the
	 * difference of the range lengths, which is precisely {@code aLen - bLen}. Only the sign is consumed by either
	 * caller, and the two agree on sign everywhere.
	 */
	private static int compareUnsignedIntrinsic(@Nonnull byte[] a, int aLen, @Nonnull byte[] b, int bLen) {
		return Arrays.compareUnsigned(a, 0, aLen, b, 0, bLen);
	}

	/**
	 * The JDK-intrinsic form of {@link #commonPrefix} — item 3A, *not* shipped; this is the candidate under
	 * measurement.
	 *
	 * Two properties make this less of a drop-in than {@link #compareUnsignedIntrinsic}:
	 *
	 * - the same array is passed as both operands, because both ranges live in one flat buffer — that is intended,
	 *   not a bug to "fix" into two arrays;
	 * - {@code Arrays.mismatch} returns a **relative** index (from the start of each range, not an absolute offset
	 *   into {@code arr}) and yields {@code -1} — not the common length — when it finds no mismatch within the
	 *   shorter range. The {@code Math.min} normalization below repairs exactly that second case; see self-check 5
	 *   in {@link #setUp()}, which reaches it deliberately since ascending distinct keys never do.
	 */
	private static int commonPrefixIntrinsic(@Nonnull byte[] arr, int aStart, int aLen, int bStart, int bLen) {
		final int m = Arrays.mismatch(arr, aStart, aStart + aLen, arr, bStart, bStart + bLen);
		return m < 0 ? Math.min(aLen, bLen) : m;
	}

	/**
	 * Rebuilds the exact sequence of {@link #commonPrefix} argument quads {@link #encode} makes for a column of
	 * {@code n} keys with the given {@code offsets}, flattened four ints per call.
	 *
	 * Mirrors encode's loop precisely, including the detail that drives item 3A's whole gate: a restart entry
	 * ({@code i % RESTART_INTERVAL == 0}) assigns {@code shared = 0} **without calling** {@code commonPrefix}, while
	 * {@code prevStart} / {@code prevLen} still advance every iteration. So restarts contribute no short-prefix
	 * calls; they contribute no calls at all.
	 */
	@Nonnull
	private static int[] recordCommonPrefixCalls(@Nonnull int[] offsets, int n) {
		final int restarts = (n + RESTART_INTERVAL - 1) / RESTART_INTERVAL;
		final int[] calls = new int[(n - restarts) << 2];
		int at = 0;
		int prevStart = 0;
		int prevLen = 0;
		for (int i = 0; i < n; i++) {
			final int start = offsets[i];
			final int keyLen = offsets[i + 1] - start;
			if (i % RESTART_INTERVAL != 0) {
				calls[at++] = prevStart;
				calls[at++] = prevLen;
				calls[at++] = start;
				calls[at++] = keyLen;
			}
			prevStart = start;
			prevLen = keyLen;
		}
		return calls;
	}

	/**
	 * Bins a shared-prefix length into {@link #prefixLengthHistogram}: {@code 0-3 / 4-7 / 8-15 / 16-31 / 32+}.
	 */
	private static int prefixBucket(int sharedLength) {
		if (sharedLength < 4) {
			return 0;
		} else if (sharedLength < 8) {
			return 1;
		} else if (sharedLength < 16) {
			return 2;
		} else if (sharedLength < 32) {
			return 3;
		} else {
			return 4;
		}
	}

	/**
	 * Asserts both {@code commonPrefix} implementations return {@code expected} for one hand-built range pair —
	 * used by self-check 5 to reach the {@code Arrays.mismatch} {@code -1} branch the key fixtures cannot produce.
	 */
	private static void assertCommonPrefixAgrees(
		@Nonnull byte[] arr, int aStart, int aLen, int bStart, int bLen, int expected
	) {
		final int scalar = commonPrefix(arr, aStart, aLen, bStart, bLen);
		final int intrinsic = commonPrefixIntrinsic(arr, aStart, aLen, bStart, bLen);
		if (scalar != expected || intrinsic != expected) {
			throw new IllegalStateException(
				"commonPrefix disagreement on the no-mismatch case for range (" + aStart + "," + aLen + ") vs ("
					+ bStart + "," + bLen + "): expected=" + expected + " scalar=" + scalar
					+ " intrinsic=" + intrinsic
			);
		}
	}

	/**
	 * Generates {@code n} distinct keys of the given shape, already in ascending natural order (see {@link KeyShape}).
	 */
	@Nonnull
	private static String[] generateKeys(@Nonnull KeyShape shape, int n) {
		final String[] keys = new String[n];
		for (int i = 0; i < n; i++) {
			keys[i] = switch (shape) {
				case CODE -> "AB-" + pad(i, 5);
				case EAN13 -> "8590000" + pad(i, 6);
				case URL -> "/category/sub/product-slug-" + pad(i, 5);
				case ACCENTED -> "Přehlídka-Šňůra-" + pad(i, 5);
			};
		}
		return keys;
	}

	/**
	 * Zero-pads {@code value} to {@code width} digits.
	 */
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

	/**
	 * Front-codes {@code keys} (already in ascending physical order) into a blob + restart index, using the exact
	 * shared-prefix / varint / restart-interval algorithm as {@code FrontCodedStringColumn#encode(byte[], int[],
	 * int)}.
	 */
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
		return new EncodedColumn(Arrays.copyOf(buf, len), restarts, flat, offsets);
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

	/**
	 * A front-coded blob plus its restart index — the minimal state {@link #decodeAtBytes} needs.
	 */
	/**
	 * The encoded column plus the raw inputs {@link #encode} worked from. {@code flat} / {@code offsets} are handed
	 * back — rather than staying locals — because {@link #commonPrefix} is called *only* from inside {@code encode},
	 * so the only way to measure it is to replay its exact argument quads against the very buffer it indexed.
	 */
	private record EncodedColumn(
		@Nonnull byte[] data, @Nonnull int[] restartOffsets, @Nonnull byte[] flat, @Nonnull int[] offsets
	) {
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
