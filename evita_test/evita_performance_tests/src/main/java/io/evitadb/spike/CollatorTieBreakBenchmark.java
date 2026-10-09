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

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Prices the collator tie-break proposed for {@link LocalizedStringComparator}.
 *
 * # The change being priced
 *
 * A localized `String` attribute keys its value tree with a `Collator` at default strength and no tie-break, so
 * two strings the collator equates share ONE bucket even when they are not `equals` (a zero-width space, a
 * joiner, a directional mark). Everything keyed by `equals` — the reference-attribute cardinality counter above
 * all — therefore disagrees with the tree it describes. Making the comparator a total order consistent with
 * `equals` closes that, at the price of extra work on the branch where the collation comparison returns zero.
 *
 * That branch is NOT rare: a B+ tree descent that FINDS its key returns zero exactly once, on the hit. So the
 * question this benchmark answers is "what does one extra String comparison per successful lookup cost, against
 * the `log2(n)` collation-key comparisons the descent already paid".
 *
 * # Why three arms and not two
 *
 * The obvious tie-break is `compareTo`, but the overwhelmingly common zero case is two *genuinely equal*
 * strings, and `compareTo` must then scan both to their end. `String#equals` is a JDK intrinsic that compares
 * whole words at a time, so testing equality first and only falling through to `compareTo` for the true
 * collator-tie should make the common case cheaper than the general one. Whether that actually shows up is the
 * reason this arm exists rather than being assumed.
 *
 * - {@link Arm#BASELINE} — production today.
 * - {@link Arm#TIE_BREAK_COMPARE_TO} — the straightforward remedy.
 * - {@link Arm#TIE_BREAK_EQUALS_FIRST} — the same remedy with an intrinsic equality guard.
 *
 * All three run interleaved inside one JMH run as a `@Param`, rather than as separate runs of two builds: a
 * back-to-back A/B of the same code has already produced a convincing reversal on this codebase, and the only
 * defence is to let JMH interleave the arms and pool the spread across forks.
 *
 * # What is measured
 *
 * The real query shape: {@link InvertedIndex#getRecordsEqualTo} over a tree built from a localized corpus, for a
 * probe that HITS (pays the zero-comparison the tie-break extends) and one that MISSES (never compares equal, so
 * it prices the arm's overhead on a path the change should not touch — the negative control).
 *
 * **The write path is bracketed by these two, not measured separately.** A write descends the same tree with the
 * same comparator: adding a value the tree does not hold ends on a miss and pays the tie-break nothing, while
 * adding one it already holds ends on a hit and pays it once. A benchmark that mutated the tree would measure a
 * structure growing under it rather than either case, so the honest bound is `lookupMiss <= write <= lookupHit`.
 *
 * Corpus shapes are named by {@link Corpus} and generated, never sampled from a catalog: short codes where
 * `compareTo` has little to scan, and long accented names where it has the most. If the tie-break costs anything
 * anywhere it will be on {@link Corpus#LONG_ACCENTED} hits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
public class CollatorTieBreakBenchmark {

	/**
	 * The comparator variants under test. Each builds a `Comparator<String>` over the SAME
	 * {@link LocalizedStringComparator}, so the collation work — and the shared collation-key cache it reads —
	 * is identical across arms and only the zero branch differs.
	 */
	public enum Arm {
		BASELINE,
		TIE_BREAK_COMPARE_TO,
		TIE_BREAK_EQUALS_FIRST;

		@Nonnull
		Comparator<String> build(@Nonnull Locale locale) {
			final LocalizedStringComparator collating = new LocalizedStringComparator(locale);
			return switch (this) {
				case BASELINE -> collating;
				case TIE_BREAK_COMPARE_TO -> (left, right) -> {
					final int collated = collating.compare(left, right);
					return collated != 0 ? collated : left.compareTo(right);
				};
				case TIE_BREAK_EQUALS_FIRST -> (left, right) -> {
					final int collated = collating.compare(left, right);
					if (collated != 0) {
						return collated;
					}
					return left.equals(right) ? 0 : left.compareTo(right);
				};
			};
		}
	}

	/**
	 * Generated corpus shapes, named after the property they exercise rather than after any catalog. `compareTo`
	 * scans until the strings differ, so its worst case is two long strings that are equal — which is exactly
	 * what a successful lookup compares.
	 */
	public enum Corpus {
		/** Short ASCII codes — 8 chars, the least a tie-break can cost. */
		SHORT_CODE,
		/** Long accented product-name-shaped values — ~48 chars with Czech diacritics, the most it can cost. */
		LONG_ACCENTED
	}

	@Param({"BASELINE", "TIE_BREAK_COMPARE_TO", "TIE_BREAK_EQUALS_FIRST"})
	private Arm arm;

	@Param({"SHORT_CODE", "LONG_ACCENTED"})
	private Corpus corpus;

	@Param({"100000"})
	private int distinctValues;

	private InvertedIndex tree;
	/** A value present in the tree — the descent ends on a zero comparison. */
	private String hitProbe;
	/** A value absent from the tree — no comparison ever returns zero. */
	private String missProbe;
	@Setup
	public void setUp() {
		final Locale locale = Locale.forLanguageTag("cs");
		final Comparator<String> comparator = this.arm.build(locale);
		final Function<Object, Serializable> normalizer = value -> Normalizer.normalize((String) value, Normalizer.Form.NFD);

		final String[] values = generate(this.corpus, this.distinctValues, 42L);
		final List<String> sorted = new ArrayList<>(List.of(values));
		sorted.sort(comparator);

		final ValueToRecordBitmap[] buckets = new ValueToRecordBitmap[sorted.size()];
		for (int i = 0; i < sorted.size(); i++) {
			buckets[i] = new ValueToRecordBitmap(
				(Serializable) normalizer.apply(sorted.get(i)), new BaseBitmap(i + 1)
			);
		}
		//noinspection rawtypes
		this.tree = new InvertedIndex(String.class, buckets, normalizer, (Comparator) comparator, 0);

		// a FRESH String instance, so the comparator's identity fast path and the cache's identity hit are both
		// bypassed - a probe arriving from a query is never the same instance as the stored key
		this.hitProbe = new String(values[values.length / 2].toCharArray());
		this.missProbe = values[values.length / 2] + " absent";
		// self-check: the fixture must actually exercise what it claims
		if (this.tree.getRecordsEqualTo((Serializable) normalizer.apply(this.hitProbe)).isEmpty()) {
			throw new IllegalStateException("hit probe does not hit - fixture is wrong for arm " + this.arm);
		}
		if (!this.tree.getRecordsEqualTo((Serializable) normalizer.apply(this.missProbe)).isEmpty()) {
			throw new IllegalStateException("miss probe hits - fixture is wrong for arm " + this.arm);
		}
	}

	@Benchmark
	public void lookupHit(@Nonnull Blackhole blackhole) {
		blackhole.consume(this.tree.getRecordsEqualTo(Normalizer.normalize(this.hitProbe, Normalizer.Form.NFD)));
	}

	@Benchmark
	public void lookupMiss(@Nonnull Blackhole blackhole) {
		blackhole.consume(this.tree.getRecordsEqualTo(Normalizer.normalize(this.missProbe, Normalizer.Form.NFD)));
	}

	/**
	 * Builds `count` distinct values of the requested shape from a fixed seed, so every arm sees byte-identical
	 * input and a rerun reproduces the same corpus.
	 */
	@Nonnull
	private static String[] generate(@Nonnull Corpus corpus, int count, long seed) {
		final Random random = new Random(seed);
		final String[] values = new String[count];
		final char[] accented = "aábcčdďeéěfghiíjklmnňoópqrřsštťuúůvwxyýzž".toCharArray();
		final char[] ascii = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
		final StringBuilder builder = new StringBuilder(64);
		for (int i = 0; i < count; i++) {
			builder.setLength(0);
			if (corpus == Corpus.SHORT_CODE) {
				for (int c = 0; c < 8; c++) {
					builder.append(ascii[random.nextInt(ascii.length)]);
				}
			} else {
				for (int c = 0; c < 48; c++) {
					builder.append(c % 9 == 8 ? ' ' : accented[random.nextInt(accented.length)]);
				}
			}
			// suffix the ordinal so the corpus is guaranteed distinct without weakening the shared prefixes the
			// front-coded column and the collation cache both feed on
			values[i] = builder.append('-').append(i).toString();
		}
		return values;
	}

}
