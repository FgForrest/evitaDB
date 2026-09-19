/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.spike.roaring;

import io.evitadb.roaringbitmap.Util;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * A/B of every candidate SIMD kernel for the vendored roaring bitmap against the scalar arm that runs today
 * (issues #1541 - #1544).
 *
 * Four families, each with its own fixture state so one family can be run on its own:
 *
 * - `bw*` - word kernels over a `BitmapContainer`'s 1024-word array (#1542)
 * - `ai*` / `aim*` - `ArrayContainer` intersection, counting and materialising (#1543)
 * - `pr*` - array-against-bitmap probe (#1544)
 * - `ex*` - set-bit extraction, `Util.fillArray` and `Util.fillArrayAND` (#1544)
 *
 * ## Two rules the fixtures follow
 *
 * **The fixture names itself in the result.** Every shape is a `@Param`, so it is part of the benchmark's
 * identity, reaches the forked JVM and is written into the result JSON. A shape taken from a system property
 * would not reach the fork, and the run would silently measure something else -
 * `RangeCountKernelBenchmark` carries the same rule for the same reason.
 *
 * **A fixture that cannot be built is an error.** An unparseable shape throws rather than falling back to a
 * default, and every variant is checked against the arm that runs in production at `@Setup`: a kernel that
 * disagrees never gets to produce a number.
 *
 * Run one family:
 * {@code java --add-modules jdk.incubator.vector -jar evita_test/evita_performance_tests/target/benchmarks.jar
 * RoaringKernelBenchmark\.ai -jvmArgsAppend "--add-modules jdk.incubator.vector"}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RoaringKernelBenchmark {

	/**
	 * Values a single roaring container addresses.
	 */
	private static final int UNIVERSE = 65536;
	/**
	 * Slack appended to every materialising destination buffer. Both the branch-free merge and the compress
	 * kernel write speculatively - the merge stores the candidate before it knows it matched, the compress
	 * kernel stores a whole vector under a lane mask - so a destination sized to the exact intersection
	 * cardinality would be written one element (or one vector) past its end.
	 */
	private static final int OUTPUT_SLACK = 8;

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== family 1 == */

	/**
	 * Population count of one container - today's `computeCardinality()`, run on every `repairAfterLazy`.
	 *
	 * @param words the fixture
	 * @return the cardinality
	 */
	@Benchmark
	public int bwCardinalityScalar(@Nonnull final Words words) {
		return BitmapWordKernels.scalarCardinality(words.a);
	}

	/**
	 * Population count through lane-wise `VPOPCNTQ`.
	 *
	 * @param words the fixture
	 * @return the cardinality
	 */
	@Benchmark
	public int bwCardinalityVector(@Nonnull final Words words) {
		return BitmapWordKernels.vectorCardinality(words.a);
	}

	/**
	 * Intersection cardinality without materialising - today's `andCardinality(BitmapContainer)`.
	 *
	 * @param words the fixture
	 * @return the cardinality
	 */
	@Benchmark
	public int bwAndCardinalityScalar(@Nonnull final Words words) {
		return BitmapWordKernels.scalarAndCardinality(words.a, words.b);
	}

	/**
	 * Intersection cardinality with the AND and the population count both lane-wise.
	 *
	 * @param words the fixture
	 * @return the cardinality
	 */
	@Benchmark
	public int bwAndCardinalityVector(@Nonnull final Words words) {
		return BitmapWordKernels.vectorAndCardinality(words.a, words.b);
	}

	/**
	 * Today's AND into an existing destination: a count pass followed by a store pass.
	 *
	 * @param words     the fixture
	 * @param blackhole consumes both the count and the destination
	 */
	@Benchmark
	public void bwAndStoreCountTwoPassScalar(@Nonnull final Words words, @Nonnull final Blackhole blackhole) {
		blackhole.consume(BitmapWordKernels.scalarAndStoreCountTwoPass(words.a, words.b, words.out));
		blackhole.consume(words.out);
	}

	/**
	 * The two passes fused into one, still scalar - isolates fusion from vector width.
	 *
	 * @param words     the fixture
	 * @param blackhole consumes both the count and the destination
	 */
	@Benchmark
	public void bwAndStoreCountFusedScalar(@Nonnull final Words words, @Nonnull final Blackhole blackhole) {
		blackhole.consume(BitmapWordKernels.scalarAndStoreCountFused(words.a, words.b, words.out));
		blackhole.consume(words.out);
	}

	/**
	 * The fused pass done lane-wise: one load pair, one store, one population count.
	 *
	 * @param words     the fixture
	 * @param blackhole consumes both the count and the destination
	 */
	@Benchmark
	public void bwAndStoreCountFusedVector(@Nonnull final Words words, @Nonnull final Blackhole blackhole) {
		blackhole.consume(BitmapWordKernels.vectorAndStoreCountFused(words.a, words.b, words.out));
		blackhole.consume(words.out);
	}

	/**
	 * Today's `BitmapContainer.or(BitmapContainer)`: allocate, copy, OR, count. The allocation happens in the
	 * benchmark method so both OR arms pay it at the same place and `-prof gc` prices it.
	 *
	 * @param words     the fixture
	 * @param blackhole consumes both the count and the destination
	 */
	@Benchmark
	public void bwOrCloneStoreCountThreePassScalar(@Nonnull final Words words, @Nonnull final Blackhole blackhole) {
		final long[] destination = new long[BitmapWordKernels.WORDS];
		blackhole.consume(BitmapWordKernels.scalarOrCloneStoreCountThreePass(words.a, words.b, destination));
		blackhole.consume(destination);
	}

	/**
	 * The same result from one scalar pass into a fresh array - the copy disappears because the OR reads the
	 * source directly.
	 *
	 * @param words     the fixture
	 * @param blackhole consumes both the count and the destination
	 */
	@Benchmark
	public void bwOrStoreCountFusedScalar(@Nonnull final Words words, @Nonnull final Blackhole blackhole) {
		final long[] destination = new long[BitmapWordKernels.WORDS];
		blackhole.consume(BitmapWordKernels.scalarOrStoreCountFused(words.a, words.b, destination));
		blackhole.consume(destination);
	}

	/**
	 * The same, lane-wise.
	 *
	 * @param words     the fixture
	 * @param blackhole consumes both the count and the destination
	 */
	@Benchmark
	public void bwOrStoreCountFusedVector(@Nonnull final Words words, @Nonnull final Blackhole blackhole) {
		final long[] destination = new long[BitmapWordKernels.WORDS];
		blackhole.consume(BitmapWordKernels.vectorOrStoreCountFused(words.a, words.b, destination));
		blackhole.consume(destination);
	}

	/**
	 * Population count over a mid-array bit range - today's `Util.cardinalityInBitmapRange`.
	 *
	 * @param words the fixture
	 * @return the cardinality inside the range
	 */
	@Benchmark
	public int bwCardinalityInRangeScalar(@Nonnull final Words words) {
		return Util.cardinalityInBitmapRange(words.a, words.rangeStart, words.rangeEnd);
	}

	/**
	 * The same range with the fully covered interior counted lane-wise.
	 *
	 * @param words the fixture
	 * @return the cardinality inside the range
	 */
	@Benchmark
	public int bwCardinalityInRangeVector(@Nonnull final Words words) {
		return BitmapWordKernels.vectorCardinalityInRange(words.a, words.rangeStart, words.rangeEnd);
	}

	/* =========================================================================================== family 2 == */

	/**
	 * Variant A - today's branchy two-pointer merge, `ArrayContainer.andCardinality(ArrayContainer)`.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiMergeBranchyA(@Nonnull final ArrayPair pair) {
		return Util.unsignedLocalIntersect2by2Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant B1 - branch-free merge written with conditional expressions.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiMergeBranchFreeSelectB1(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.branchFreeSelectCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant B2 - branch-free merge written as arithmetic on the sign bit of the difference.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiMergeBranchFreeArithmeticB2(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.branchFreeArithmeticCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant C - paint the shorter side into a pooled scratch bitmap, probe the longer side.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiScratchProbeC(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.scratchProbeCardinality(pair.a, pair.la, pair.b, pair.lb, pair.scratch);
	}

	/**
	 * Variant D - the all-pairs kernel issue #1543 specifies, 8 `short` lanes.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsShort128D(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsShort128Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant E - the same at 32 `short` lanes.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsShort512E(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsShort512Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant F - the same at 16 `int` lanes after widening.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsInt512F(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsInt512Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant G - variant D with a block range-skip in front of the comparison.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsSkippingG(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsShort128SkippingCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant Du - D with the rotation loop unrolled onto constant shuffles.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsUnrolledDu(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsUnrolledCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant Dt - Du with the mask ORs reassociated into a balanced tree.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsTreeDt(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsTreeCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant Ds - the eight compares with their lane counts summed and no mask combination at all.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsSummedDs(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsSummedCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant Es - the summed formulation at 32 `short` lanes.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsShort512SummedEs(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsShort512SummedCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant Gs - the block range-skip in front of the summed formulation.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiAllPairsSkippingSummedGs(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.allPairsSkippingSummedCardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant H at 32 `short` lanes - one A element broadcast against a resident block of B.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiBroadcastProbeH512(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.broadcastProbeShort512Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant H at 16 `short` lanes - the same with a narrower resident block, so the block advances twice as
	 * often but each compare is half the width.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiBroadcastProbeH256(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.broadcastProbeShort256Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Materialising variant A - today's `ArrayContainer.and(ArrayContainer)`, which gallops when the inputs are
	 * more than 25x apart in size and merges otherwise.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimMergeBranchyA(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(Util.unsignedIntersect2by2(pair.a, pair.la, pair.b, pair.lb, pair.out));
		blackhole.consume(pair.out);
	}

	/**
	 * Materialising variant B - branch-free merge with a speculative store.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimMergeBranchFreeB(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ArrayIntersectKernels.branchFreeArithmeticIntersect(pair.a, pair.la, pair.b, pair.lb, pair.out)
		);
		blackhole.consume(pair.out);
	}

	/**
	 * Materialising variant C - scratch-bitmap probe with a speculative store.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimScratchProbeC(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ArrayIntersectKernels.scratchProbeIntersect(pair.a, pair.la, pair.b, pair.lb, pair.scratch, pair.out)
		);
		blackhole.consume(pair.out);
	}

	/**
	 * Materialising variant D - the all-pairs kernel emitting through `VPCOMPRESSW`.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimAllPairsShort128D(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ArrayIntersectKernels.allPairsShort128Intersect(pair.a, pair.la, pair.b, pair.lb, pair.out)
		);
		blackhole.consume(pair.out);
	}

	/**
	 * Variant H512b - the broadcast probe with the block reloaded per probe instead of carried across the loop.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiBroadcastReloadingH512b(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.broadcastProbeReloadingShort512Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant H256b - the reloading formulation at 16 lanes.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiBroadcastReloadingH256b(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.broadcastProbeReloadingShort256Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant H512c - the reloading formulation with the block maximum cached in a local.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiBroadcastCachedH512c(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.broadcastProbeCachedShort512Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Variant H256c - the cached formulation at 16 lanes.
	 *
	 * @param pair the fixture
	 * @return the intersection cardinality
	 */
	@Benchmark
	public int aiBroadcastCachedH256c(@Nonnull final ArrayPair pair) {
		return ArrayIntersectKernels.broadcastProbeCachedShort256Cardinality(pair.a, pair.la, pair.b, pair.lb);
	}

	/**
	 * Materialising variant Dt - the tree-OR mask feeding `VPCOMPRESSW`.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimAllPairsTreeDt(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ArrayIntersectKernels.allPairsTreeIntersect(pair.a, pair.la, pair.b, pair.lb, pair.out)
		);
		blackhole.consume(pair.out);
	}

	/**
	 * Materialising variant H at 32 `short` lanes. Unlike the compress-emitting all-pairs arm, this one never
	 * writes past the read cursor, so it can run in place the way `ArrayContainer.iand` needs.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimBroadcastProbeH512(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ArrayIntersectKernels.broadcastProbeShort512Intersect(pair.a, pair.la, pair.b, pair.lb, pair.out)
		);
		blackhole.consume(pair.out);
	}

	/**
	 * Materialising variant H at 16 `short` lanes.
	 *
	 * @param pair      the fixture
	 * @param blackhole consumes the count and the destination
	 */
	@Benchmark
	public void aimBroadcastProbeH256(@Nonnull final ArrayPair pair, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ArrayIntersectKernels.broadcastProbeShort256Intersect(pair.a, pair.la, pair.b, pair.lb, pair.out)
		);
		blackhole.consume(pair.out);
	}

	/* =========================================================================================== family 3 == */

	/**
	 * Today's probe of a sorted `char[]` against a bitmap - `BitmapContainer.andCardinality(ArrayContainer)`.
	 *
	 * @param probe the fixture
	 * @return how many probed values are set
	 */
	@Benchmark
	public int prScalar(@Nonnull final ProbeFixture probe) {
		return ProbeKernels.scalarProbeCardinality(probe.words, probe.values, probe.count);
	}

	/**
	 * The gather probe: eight values, one `VPGATHERQQ`, one variable shift.
	 *
	 * @param probe the fixture
	 * @return how many probed values are set
	 */
	@Benchmark
	public int prGather(@Nonnull final ProbeFixture probe) {
		return ProbeKernels.gatherProbeCardinality(probe.words, probe.values, probe.count, probe.indices);
	}

	/* =========================================================================================== family 4 == */

	/**
	 * Today's extraction, as the copy this class owns - one `tzcnt`/`blsr` step per set bit. The copy exists so
	 * a `-XX:-UseSuperWord` arm prices the identical loop; `exScalarUtil` proves it is the same loop.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the count and the destination
	 */
	@Benchmark
	public void exScalar(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		blackhole.consume(ExtractKernels.scalarExtract(extraction.words, extraction.out));
		blackhole.consume(extraction.out);
	}

	/**
	 * `Util.fillArray` itself, so the copy above can be shown to cost the same as the method that ships. It
	 * returns nothing, so only the destination is consumed.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the destination
	 */
	@Benchmark
	public void exScalarUtil(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		Util.fillArray(extraction.words, extraction.out);
		blackhole.consume(extraction.out);
	}

	/**
	 * CRoaring's `bitset_extract_setbits_avx512_uint16` shape - one `VPCOMPRESSB` per word plus two widened
	 * masked stores.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the count and the destination
	 */
	@Benchmark
	public void exVectorByteCompress(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		blackhole.consume(ExtractKernels.vectorExtractByteCompress(extraction.words, extraction.out));
		blackhole.consume(extraction.out);
	}

	/**
	 * The same idea as two `VPCOMPRESSW` per word, which needs no widening step.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the count and the destination
	 */
	@Benchmark
	public void exVectorShortCompress(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		blackhole.consume(ExtractKernels.vectorExtractShortCompress(extraction.words, extraction.out));
		blackhole.consume(extraction.out);
	}

	/**
	 * Today's fused extraction - `Util.fillArrayAND`, called directly. It returns nothing, so only the
	 * destination is consumed.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the destination
	 */
	@Benchmark
	public void exAndScalar(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		Util.fillArrayAND(extraction.out, extraction.words, extraction.other);
		blackhole.consume(extraction.out);
	}

	/**
	 * The compress extraction fused with the AND, so the intersection never reaches memory.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the count and the destination
	 */
	@Benchmark
	public void exAndVectorShortCompress(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		blackhole.consume(
			ExtractKernels.vectorExtractAndShortCompress(extraction.words, extraction.other, extraction.out)
		);
		blackhole.consume(extraction.out);
	}

	/**
	 * Today's 32-bit extraction - `BitmapContainer.fillLeastSignificant16bits`, the uncapped site behind
	 * `toArray()`.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the count and the destination
	 */
	@Benchmark
	public void ex32Scalar(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		blackhole.consume(ExtractKernels.scalarExtract32(extraction.words, extraction.out32, extraction.base));
		blackhole.consume(extraction.out32);
	}

	/**
	 * The same through `VPCOMPRESSB` and four widened masked stores per word.
	 *
	 * @param extraction the fixture
	 * @param blackhole  consumes the count and the destination
	 */
	@Benchmark
	public void ex32VectorByteCompress(@Nonnull final Extraction extraction, @Nonnull final Blackhole blackhole) {
		blackhole.consume(ExtractKernels.vectorExtract32(extraction.words, extraction.out32, extraction.base));
		blackhole.consume(extraction.out32);
	}

	/* =========================================================================================== fixtures == */

	/**
	 * Two word arrays at a chosen density, plus the destination the store kernels write into and the bit range
	 * the range kernels count over.
	 */
	@State(Scope.Benchmark)
	public static class Words {

		/**
		 * `d<percent>` - the share of set bits in both word arrays.
		 */
		@Param({"d50", "d5"})
		public String shape;

		/**
		 * First word array.
		 */
		public long[] a;
		/**
		 * Second word array.
		 */
		public long[] b;
		/**
		 * Destination for the in-place store kernels.
		 */
		public long[] out;
		/**
		 * First bit of the counted range - deliberately not word-aligned, so the partial first word is exercised.
		 */
		public int rangeStart;
		/**
		 * One past the last bit of the counted range - deliberately not word-aligned.
		 */
		public int rangeEnd;

		@Setup
		public void setUp() {
			if (!this.shape.matches("d\\d+")) {
				throw new IllegalArgumentException("Shape `" + this.shape + "` is not `d<percent>`!");
			}
			final int density = Integer.parseInt(this.shape.substring(1));
			final Random random = new Random(20260918L);
			this.a = randomWords(random, density);
			this.b = randomWords(random, density);
			this.out = new long[BitmapWordKernels.WORDS];
			this.rangeStart = 4001;
			this.rangeEnd = 60007;
			System.out.printf(
				"# words: shape=%s density=%d%% cardinality=%d/%d intersection=%d range=[%d,%d) lanes=%d%n",
				this.shape, density, BitmapWordKernels.scalarCardinality(this.a),
				BitmapWordKernels.scalarCardinality(this.b),
				BitmapWordKernels.scalarAndCardinality(this.a, this.b), this.rangeStart, this.rangeEnd,
				BitmapWordKernels.laneCount()
			);
		}

		/**
		 * Builds one word array with roughly the requested share of bits set.
		 *
		 * @param random the source of randomness
		 * @param density percentage of bits to set
		 * @return the word array
		 */
		@Nonnull
		private static long[] randomWords(@Nonnull final Random random, final int density) {
			final long[] words = new long[BitmapWordKernels.WORDS];
			for (int bit = 0; bit < UNIVERSE; bit++) {
				if (random.nextInt(100) < density) {
					words[bit >>> 6] |= 1L << bit;
				}
			}
			return words;
		}
	}

	/**
	 * Two sorted `char[]` inputs whose sizes and intersection share are named by the shape, plus the pooled
	 * scratch bitmap variant C paints into and the destination the materialising arms write.
	 */
	@State(Scope.Benchmark)
	public static class ArrayPair {

		/**
		 * `eq<n>_<percent>` - both inputs hold `n` values and the intersection is about `percent` of `n`;
		 * `skew<n>_<m>` - the inputs hold `n` and `m` values with half the shorter one shared.
		 *
		 * Two of the skew shapes (`skew64_1024`, `skew256_4096`, both 16x) sit below the galloping threshold and
		 * reach the linear merge; `skew16_4096` and `skew64_4096` are 256x and 64x and are above it.
		 *
		 * The threshold only ever applies to the **materialising** arm. `ArrayContainer.andCardinality` calls
		 * `Util.unsignedLocalIntersect2by2Cardinality` directly and never consults the dispatcher at all, so for
		 * the counting grid every shape - skewed or not - is measured against the plain linear merge.
		 *
		 * `Util.unsignedIntersect2by2` branches on the backing arrays' **capacity**
		 * rather than on the logical lengths, so every input here is allocated at exactly its logical length and
		 * the two are the same number.
		 */
		@Param({
			"eq256_0", "eq256_5", "eq256_50",
			"eq1024_0", "eq1024_5", "eq1024_50",
			"eq4096_0", "eq4096_5", "eq4096_50", "eq4096_100",
			"skew16_4096", "skew64_1024", "skew256_4096", "skew64_4096"
		})
		public String shape;

		/**
		 * First input, ascending.
		 */
		public char[] a;
		/**
		 * Number of entries of `a` to consider - always its full length here.
		 */
		public int la;
		/**
		 * Second input, ascending.
		 */
		public char[] b;
		/**
		 * Number of entries of `b` to consider.
		 */
		public int lb;
		/**
		 * Pooled scratch bitmap for variant C, zeroed on entry and on exit.
		 */
		public long[] scratch;
		/**
		 * Destination for the materialising arms.
		 */
		public char[] out;
		/**
		 * Intersection cardinality the arm that runs in production computes - every other arm is checked
		 * against it before any of them is timed.
		 */
		public int expected;

		@Setup
		public void setUp() {
			final Random random = new Random(20260918L);
			if (this.shape.matches("eq\\d+_\\d+")) {
				final int underscore = this.shape.indexOf('_');
				final int size = Integer.parseInt(this.shape.substring(2, underscore));
				final int percent = Integer.parseInt(this.shape.substring(underscore + 1));
				build(random, size, size, Math.round(size * percent / 100.0f));
			} else if (this.shape.matches("skew\\d+_\\d+")) {
				final int underscore = this.shape.indexOf('_');
				final int first = Integer.parseInt(this.shape.substring(4, underscore));
				final int second = Integer.parseInt(this.shape.substring(underscore + 1));
				build(random, first, second, Math.min(first, second) / 2);
			} else {
				throw new IllegalArgumentException(
					"Shape `" + this.shape + "` is neither `eq<n>_<percent>` nor `skew<n>_<m>`!"
				);
			}
			this.scratch = new long[ArrayIntersectKernels.SCRATCH_WORDS];
			this.out = new char[Math.min(this.la, this.lb) + OUTPUT_SLACK];
			this.expected = Util.unsignedLocalIntersect2by2Cardinality(this.a, this.la, this.b, this.lb);
			verify();
			// `Util.unsignedIntersect2by2` dispatches on capacity; the arrays are allocated at exactly their
			// logical length here, so this is the branch the materialising production arm really takes.
			final boolean gallops = this.a.length * 25 < this.b.length || this.b.length * 25 < this.a.length;
			System.out.printf(
				"# arrays: shape=%s |a|=%d |b|=%d intersection=%d (%.1f%% of the shorter) branch=%s%n",
				this.shape, this.la, this.lb, this.expected,
				100.0 * this.expected / Math.max(1, Math.min(this.la, this.lb)),
				gallops ? "galloping" : "merge"
			);
		}

		/**
		 * Builds the two inputs: the shared values first, then disjoint fillers, so the intersection size is
		 * exactly what the shape asks for rather than whatever two random draws happened to share.
		 *
		 * @param random the source of randomness
		 * @param sizeA  entries in the first input
		 * @param sizeB  entries in the second input
		 * @param shared entries the two inputs have in common
		 */
		private void build(@Nonnull final Random random, final int sizeA, final int sizeB, final int shared) {
			if (shared + (sizeA - shared) + (sizeB - shared) > UNIVERSE) {
				throw new IllegalArgumentException("Shape `" + this.shape + "` does not fit a 16-bit universe!");
			}
			final boolean[] used = new boolean[UNIVERSE];
			final TreeSet<Integer> valuesA = new TreeSet<>();
			final TreeSet<Integer> valuesB = new TreeSet<>();
			for (int i = 0; i < shared; i++) {
				final int value = draw(random, used);
				valuesA.add(value);
				valuesB.add(value);
			}
			for (int i = shared; i < sizeA; i++) {
				valuesA.add(draw(random, used));
			}
			for (int i = shared; i < sizeB; i++) {
				valuesB.add(draw(random, used));
			}
			this.a = toCharArray(valuesA);
			this.la = this.a.length;
			this.b = toCharArray(valuesB);
			this.lb = this.b.length;
		}

		/**
		 * Draws a value not yet handed out, marking it used.
		 *
		 * @param random the source of randomness
		 * @param used   which values are already taken
		 * @return a fresh value
		 */
		private static int draw(@Nonnull final Random random, @Nonnull final boolean[] used) {
			int value = random.nextInt(UNIVERSE);
			while (used[value]) {
				value = value + 1 == UNIVERSE ? 0 : value + 1;
			}
			used[value] = true;
			return value;
		}

		/**
		 * Converts an ascending set into the `char[]` the kernels take.
		 *
		 * @param values the values
		 * @return them as an ascending `char[]`
		 */
		@Nonnull
		private static char[] toCharArray(@Nonnull final TreeSet<Integer> values) {
			final char[] result = new char[values.size()];
			int index = 0;
			for (final Integer value : values) {
				result[index++] = (char) value.intValue();
			}
			return result;
		}

		/**
		 * Runs every variant once and refuses the fixture if any of them disagrees with the production arm -
		 * a kernel that computes the wrong answer must never produce a timing anyone can quote.
		 */
		private void verify() {
			agree("B1", ArrayIntersectKernels.branchFreeSelectCardinality(this.a, this.la, this.b, this.lb));
			agree("B2", ArrayIntersectKernels.branchFreeArithmeticCardinality(this.a, this.la, this.b, this.lb));
			agree("C", ArrayIntersectKernels.scratchProbeCardinality(this.a, this.la, this.b, this.lb, this.scratch));
			agree("D", ArrayIntersectKernels.allPairsShort128Cardinality(this.a, this.la, this.b, this.lb));
			agree("E", ArrayIntersectKernels.allPairsShort512Cardinality(this.a, this.la, this.b, this.lb));
			agree("F", ArrayIntersectKernels.allPairsInt512Cardinality(this.a, this.la, this.b, this.lb));
			agree("G", ArrayIntersectKernels.allPairsShort128SkippingCardinality(this.a, this.la, this.b, this.lb));
			agree("Du", ArrayIntersectKernels.allPairsUnrolledCardinality(this.a, this.la, this.b, this.lb));
			agree("Dt", ArrayIntersectKernels.allPairsTreeCardinality(this.a, this.la, this.b, this.lb));
			agree("Ds", ArrayIntersectKernels.allPairsSummedCardinality(this.a, this.la, this.b, this.lb));
			agree("Es", ArrayIntersectKernels.allPairsShort512SummedCardinality(this.a, this.la, this.b, this.lb));
			agree("Gs", ArrayIntersectKernels.allPairsSkippingSummedCardinality(this.a, this.la, this.b, this.lb));
			agree("H512", ArrayIntersectKernels.broadcastProbeShort512Cardinality(this.a, this.la, this.b, this.lb));
			agree("H512b", ArrayIntersectKernels.broadcastProbeReloadingShort512Cardinality(
				this.a, this.la, this.b, this.lb));
			agree("H256b", ArrayIntersectKernels.broadcastProbeReloadingShort256Cardinality(
				this.a, this.la, this.b, this.lb));
			agree("H512c", ArrayIntersectKernels.broadcastProbeCachedShort512Cardinality(
				this.a, this.la, this.b, this.lb));
			agree("H256c", ArrayIntersectKernels.broadcastProbeCachedShort256Cardinality(
				this.a, this.la, this.b, this.lb));
			agree("H256", ArrayIntersectKernels.broadcastProbeShort256Cardinality(this.a, this.la, this.b, this.lb));
			final char[] reference = new char[this.out.length];
			final int referenceCount = Util.unsignedIntersect2by2(this.a, this.la, this.b, this.lb, reference);
			agree("A materialising", referenceCount);
			agreeOn("B materialising", reference, referenceCount,
				ArrayIntersectKernels.branchFreeArithmeticIntersect(this.a, this.la, this.b, this.lb, this.out));
			agreeOn("C materialising", reference, referenceCount,
				ArrayIntersectKernels.scratchProbeIntersect(this.a, this.la, this.b, this.lb, this.scratch, this.out));
			agreeOn("D materialising", reference, referenceCount,
				ArrayIntersectKernels.allPairsShort128Intersect(this.a, this.la, this.b, this.lb, this.out));
			agreeOn("Dt materialising", reference, referenceCount,
				ArrayIntersectKernels.allPairsTreeIntersect(this.a, this.la, this.b, this.lb, this.out));
			agreeOn("H512 materialising", reference, referenceCount,
				ArrayIntersectKernels.broadcastProbeShort512Intersect(this.a, this.la, this.b, this.lb, this.out));
			agreeOn("H256 materialising", reference, referenceCount,
				ArrayIntersectKernels.broadcastProbeShort256Intersect(this.a, this.la, this.b, this.lb, this.out));
		}

		/**
		 * Asserts one counting variant agrees with the production arm.
		 *
		 * @param variant the variant letter, for the message
		 * @param actual  what it computed
		 */
		private void agree(@Nonnull final String variant, final int actual) {
			if (actual != this.expected) {
				throw new IllegalStateException(
					"Variant " + variant + " disagrees on shape `" + this.shape + "`: expected " + this.expected +
						", got " + actual + "!"
				);
			}
		}

		/**
		 * Asserts one materialising variant produced the same values as the production arm.
		 *
		 * @param variant        the variant letter, for the message
		 * @param reference      what the production arm wrote
		 * @param referenceCount how much of it it wrote
		 * @param actualCount    how much the variant wrote into `out`
		 */
		private void agreeOn(
			@Nonnull final String variant, @Nonnull final char[] reference, final int referenceCount,
			final int actualCount) {
			agree(variant, actualCount);
			if (!Arrays.equals(reference, 0, referenceCount, this.out, 0, actualCount)) {
				throw new IllegalStateException(
					"Variant " + variant + " wrote different values on shape `" + this.shape + "`!"
				);
			}
		}
	}

	/**
	 * A 50 % dense bitmap and a sorted `char[]` of values to look up in it.
	 */
	@State(Scope.Benchmark)
	public static class ProbeFixture {

		/**
		 * How many values are probed.
		 */
		@Param({"256", "1024", "4096"})
		public String size;

		/**
		 * The bitmap being probed.
		 */
		public long[] words;
		/**
		 * The values to look up, ascending.
		 */
		public char[] values;
		/**
		 * Number of entries of `values` to consider.
		 */
		public int count;
		/**
		 * Scratch the gather kernel writes its word indices into.
		 */
		public int[] indices;

		@Setup
		public void setUp() {
			if (!this.size.matches("\\d+")) {
				throw new IllegalArgumentException("Size `" + this.size + "` is not a number!");
			}
			this.count = Integer.parseInt(this.size);
			final Random random = new Random(20260918L);
			this.words = new long[BitmapWordKernels.WORDS];
			for (int bit = 0; bit < UNIVERSE; bit++) {
				if (random.nextBoolean()) {
					this.words[bit >>> 6] |= 1L << bit;
				}
			}
			final TreeSet<Integer> values = new TreeSet<>();
			while (values.size() < this.count) {
				values.add(random.nextInt(UNIVERSE));
			}
			this.values = ArrayPair.toCharArray(values);
			this.indices = new int[ProbeKernels.PROBE_BLOCK];
			final int scalar = ProbeKernels.scalarProbeCardinality(this.words, this.values, this.count);
			final int gather = ProbeKernels.gatherProbeCardinality(this.words, this.values, this.count, this.indices);
			if (scalar != gather) {
				throw new IllegalStateException("Gather probe disagrees: " + scalar + " vs " + gather + "!");
			}
			System.out.printf(
				"# probe: values=%d bitmap cardinality=%d hits=%d%n",
				this.count, BitmapWordKernels.scalarCardinality(this.words), scalar
			);
		}
	}

	/**
	 * A bitmap at a chosen density, a second bitmap for the fused AND form, and the two destinations the
	 * extraction writes into.
	 *
	 * The densities are the ones the callers actually sit at. Every `fillArray` / `fillArrayAND` site is on the
	 * branch that has already decided the result fits an array container, so it never sees more than 4096 values
	 * out of 65536 - 6.25 %. The only uncapped extraction site is `fillLeastSignificant16bits`, the 32-bit form
	 * behind `toArray()`, which is why the higher densities are carried and why that form has its own arms.
	 *
	 * The second bitmap is built as a **superset** of the first, so `words AND other` has exactly the same
	 * cardinality as `words`. That keeps the fused arms on the same output density as the plain ones, which is
	 * the axis being swept; the AND itself costs one load and one AND per word whatever the operands hold.
	 */
	@State(Scope.Benchmark)
	public static class Extraction {

		/**
		 * Percentage of bits set in the extracted bitmap, as a decimal - 6.25 is the cap every `char[]`
		 * extraction site sits under.
		 */
		@Param({"1.6", "3.1", "6.25", "12.5", "25", "50"})
		public String density;

		/**
		 * The bitmap being extracted.
		 */
		public long[] words;
		/**
		 * The second operand of the fused AND form, a superset of `words`.
		 */
		public long[] other;
		/**
		 * Destination of the 16-bit forms, a whole universe wide plus the slack a masked vector store needs.
		 */
		public char[] out;
		/**
		 * Destination of the 32-bit forms.
		 */
		public int[] out32;
		/**
		 * The container's high 16 bits, the value `fillLeastSignificant16bits` adds to every position.
		 */
		public int base;

		@Setup
		public void setUp() {
			final double percent;
			try {
				percent = Double.parseDouble(this.density);
			} catch (final NumberFormatException e) {
				throw new IllegalArgumentException("Density `" + this.density + "` is not a number!", e);
			}
			final Random random = new Random(20260918L);
			this.words = new long[BitmapWordKernels.WORDS];
			this.other = new long[BitmapWordKernels.WORDS];
			for (int bit = 0; bit < UNIVERSE; bit++) {
				if (random.nextDouble() * 100.0 < percent) {
					this.words[bit >>> 6] |= 1L << bit;
					this.other[bit >>> 6] |= 1L << bit;
				} else if (random.nextInt(4) == 0) {
					// padding that only `other` carries, so the AND result is exactly `words`
					this.other[bit >>> 6] |= 1L << bit;
				}
			}
			this.out = new char[UNIVERSE + 64];
			this.out32 = new int[UNIVERSE + 64];
			this.base = 0x12340000;
			verify();
			System.out.printf(
				"# extraction: density=%s%% cardinality=%d (%.2f%%) fused AND cardinality=%d%n",
				this.density, BitmapWordKernels.scalarCardinality(this.words),
				100.0 * BitmapWordKernels.scalarCardinality(this.words) / UNIVERSE,
				BitmapWordKernels.scalarAndCardinality(this.words, this.other)
			);
		}

		/**
		 * Runs every extraction variant once and refuses the fixture if any of them writes something other than
		 * what `Util` writes today.
		 */
		private void verify() {
			final char[] reference = new char[this.out.length];
			final int referenceCount = ExtractKernels.scalarExtract(this.words, reference);
			same("byte compress", reference, referenceCount,
				ExtractKernels.vectorExtractByteCompress(this.words, this.out));
			same("short compress", reference, referenceCount,
				ExtractKernels.vectorExtractShortCompress(this.words, this.out));
			final char[] fusedReference = new char[this.out.length];
			Util.fillArrayAND(fusedReference, this.words, this.other);
			final int fusedCount = BitmapWordKernels.scalarAndCardinality(this.words, this.other);
			same("fused AND copy", fusedReference, fusedCount,
				ExtractKernels.scalarExtractAnd(this.words, this.other, this.out));
			same("fused AND compress", fusedReference, fusedCount,
				ExtractKernels.vectorExtractAndShortCompress(this.words, this.other, this.out));
			final int[] reference32 = new int[this.out32.length];
			final int count32 = ExtractKernels.scalarExtract32(this.words, reference32, this.base);
			final int vector32 = ExtractKernels.vectorExtract32(this.words, this.out32, this.base);
			if (count32 != vector32
				|| !Arrays.equals(reference32, 0, count32, this.out32, 0, vector32)) {
				throw new IllegalStateException(
					"Extraction variant 32-bit compress disagrees at density " + this.density + "%!"
				);
			}
		}

		/**
		 * Asserts one extraction variant produced the same values as `Util`.
		 *
		 * @param variant        the variant name, for the message
		 * @param reference      what `Util` wrote
		 * @param referenceCount how much of it it wrote
		 * @param actualCount    how much the variant wrote into `out`
		 */
		private void same(
			@Nonnull final String variant, @Nonnull final char[] reference, final int referenceCount,
			final int actualCount) {
			if (referenceCount != actualCount
				|| !Arrays.equals(reference, 0, referenceCount, this.out, 0, actualCount)) {
				throw new IllegalStateException(
					"Extraction variant " + variant + " disagrees at density " + this.density + "%!"
				);
			}
		}
	}

}
