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

package io.evitadb.performance.substring.state;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.trigram.TrigramSubstringSearch;

import javax.annotation.Nonnull;

/**
 * The posting widths the substring matrix is measured at, each carried by one **marker token** that
 * {@link SubstringCorpus} plants into an exactly known number of distinct values.
 *
 * A pattern class is nothing but a posting width: the trigram path's whole decision is
 * {@link TrigramSubstringSearch#isWorthAccelerating(int, int)} over the pattern's *cheapest* trigram cardinality, so
 * a class that does not land on the width it claims silently measures a different cell of the matrix than the one it
 * is labelled with. That is why every class knows how many values it is planted into, why the marker tokens are
 * chosen to share no trigram with the corpus vocabulary or with each other, and why
 * {@link #verifyPostingWidth(int, int, int)} insists the width actually observed in the built index is the width the
 * class promised.
 *
 * # Where the widths sit relative to the two constants
 *
 * {@link TrigramSubstringSearch#REQUIRED_NARROWING_FACTOR} admits a pattern whose cheapest posting covers at most
 * `1/D` of the distinct values, where `D` is that constant - 12 today, and 4 when this matrix was first measured:
 *
 * - {@link #COMMON} - 15%, which sat comfortably inside the gate at `D = 4` and is **declined** at any `D` above
 *   ~6.7, hence at today's 12. Its label therefore means something different either side of that retune: before it,
 *   this class measured an acceleration that lost; after it, it measures the gate correctly refusing. A cell-by-cell
 *   comparison of a pre-retune matrix against a post-retune one reads backwards for this row, and for
 *   {@link #THRESHOLD}, which follows the constant by definition;
 * - {@link #THRESHOLD} - exactly 25%, the widest candidate set the gate admits **at all**. This class exists only to
 *   price the factor: if the trigram path is still faster than the scan here, the factor is too cautious, and if it
 *   is slower, the factor is too permissive. Neither question can be asked from inside the comfortable region;
 * - {@link #MEDIUM} - 1%, the mid-selectivity case the spike's crossover measurements were taken on;
 * - {@link #RARE} - at most four values, where the intersection is nearly free;
 * - {@link #NONEXISTENT} - zero, which `TrigramSubstringSearch#match` answers from the cardinality probes alone,
 *   bypassing the gate entirely.
 *
 * The other constant, {@link TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT}, is bracketed by the
 * `entityCount` axis rather than by this one - see `SubstringQueryBenchmark`.
 *
 * # The width bisect
 *
 * The first sweep of the five classes above found the accelerator **losing** across 15-25% and **winning** by
 * 2.8-12.7x at 1%, with nothing measured in between - so the crossover was known only to lie inside a fifteen-fold
 * range, which is not a number anyone can set a constant from. {@link #WIDTH_02_PCT}, {@link #WIDTH_04_PCT},
 * {@link #WIDTH_08_PCT} and {@link #WIDTH_12_PCT} fill that gap.
 *
 * They are named after their width rather than after an adjective on purpose. `COMMON` and `MEDIUM` are already
 * carrying more meaning than a word can: nothing in either name says one is fifteen times the other, and a reader
 * six months from now has to open this file to find out. A name that states the width is not prettier, it is the
 * only kind that survives being read out of context - in a result table, in a `-p` argument, in a commit message.
 * The two-digit zero-padded form sorts the group correctly in every one of those places.
 *
 * ## What the first sweep already predicts, so the second one can falsify it
 *
 * The trigram path visits `share x n` candidates where the scan visits `n` values, and both then run the same exact
 * predicate - so to a first approximation the speedup scales as `1 / share` and each measured cell is an estimate of
 * the **break-even share** `f* = share x speedup`. Reading the first sweep back through that identity:
 *
 * ```text
 * n = 10 000   COMMON 0.15/1.14 -> 0.132   THRESHOLD 0.25/1.79 -> 0.140   MEDIUM 0.01*12.65 -> 0.127
 * n = 100 000  COMMON 0.15/1.52 -> 0.099   THRESHOLD 0.25/2.10 -> 0.119   MEDIUM 0.01*9.35  -> 0.094
 * ```
 *
 * Three classes spanning a twenty-five-fold range of widths agree to within a few percent at each size, which is
 * what makes the identity worth trusting - and they put the crossover at roughly **13% at 10k and 10% at 100k**.
 * That is above every member of this group except {@link #WIDTH_12_PCT}, which is therefore predicted to win
 * narrowly at 10k and lose at 100k. If it does, the crossover demonstrably moves with corpus size and no single
 * scalar factor can express it; if it wins or loses at both, the identity is wrong somewhere and that is worth
 * more than a confirmation.
 *
 * Unlike {@link #THRESHOLD}, none of the four asserts that the selectivity gate admits it. They sit well inside a
 * `1/4` gate today, but the whole point of the bisect is to *move* that constant, and a class that refused to run
 * once the gate tightened around it would block the re-measurement that proves the change worked. A cell whose path
 * declined is still a legitimate measurement here - `SubstringCatalogFixture.PatternProfile#accelerated()` records
 * which it was, per cell, per trial.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum SubstringPatternClass {

	/**
	 * A pattern whose cheapest trigram posts against 15% of the distinct values - inside the selectivity gate at every
	 * corpus size the matrix uses, so the accelerated path is genuinely taken and genuinely has a lot of candidates to
	 * verify.
	 */
	COMMON("nimbus") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return Math.max(1, (int) Math.round(distinctValueCount * COMMON_SHARE));
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.10d, 0.30d);
		}
	},

	/**
	 * A pattern planted into exactly one quarter of the distinct values - the widest candidate set
	 * {@link TrigramSubstringSearch#REQUIRED_NARROWING_FACTOR} still admits. The A/B at this cell is the direct
	 * evidence for or against that constant's value.
	 */
	THRESHOLD("kumquat") {
		@Override
		public int plantingCount(int distinctValueCount) {
			// exactly the gate: `candidateUpperBound <= distinctValueCount / REQUIRED_NARROWING_FACTOR`
			return Math.max(1, distinctValueCount / TrigramSubstringSearch.REQUIRED_NARROWING_FACTOR);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			if (distinctValueCount >= TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT
				&& !TrigramSubstringSearch.isWorthAccelerating(measuredMinimum, distinctValueCount)) {
				throw new GenericEvitaInternalError(
					"`THRESHOLD` is planted at the selectivity gate itself and must be ADMITTED by it, but a width of "
						+ measuredMinimum + " over " + distinctValueCount + " distinct values was declined - the class "
						+ "would then measure the scan on both arms and prove nothing about the factor!",
					"The threshold pattern class fell outside the selectivity gate!"
				);
			}
		}
	},

	/**
	 * A pattern whose cheapest trigram posts against 1% of the distinct values - the mid-selectivity case whose
	 * crossover the spike measured at 58-353 entities, and therefore the class the corpus-size axis is calibrated
	 * against.
	 */
	MEDIUM("zephyr") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return Math.max(1, (int) Math.round(distinctValueCount * MEDIUM_SHARE));
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			// the lower bound is one value rather than a share: below a hundred distinct values `~1%` cannot be
			// expressed at all, and the two smallest corpora exist to bracket the distinct-value floor, not this axis
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.0d, 0.02d);
		}
	},

	/**
	 * A pattern planted into a handful of values - at most four, and one when the corpus is too small to hold four
	 * without becoming a `MEDIUM` in disguise.
	 */
	RARE("quokka") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return Math.max(1, Math.min(RARE_CEILING, distinctValueCount / RARE_DIVISOR));
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			if (measuredMinimum < 1 || measuredMinimum > RARE_CEILING) {
				throw new GenericEvitaInternalError(
					"`RARE` must post against 1.." + RARE_CEILING + " distinct values, but posts against "
						+ measuredMinimum + " of " + distinctValueCount + "!",
					"The rare pattern class left its band!"
				);
			}
		}
	},

	/**
	 * A pattern no value contains. Its cheapest trigram posts against nothing, which the accelerated path answers from
	 * the cardinality probes alone - the cheapest outcome either path can produce, and the one cell where the trigram
	 * index wins without touching a single value.
	 */
	NONEXISTENT("xylitol") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return 0;
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			if (measuredMinimum != 0) {
				throw new GenericEvitaInternalError(
					"`NONEXISTENT` must have a trigram nobody posts against, but its cheapest trigram posts against "
						+ measuredMinimum + " of " + distinctValueCount + " distinct values - the corpus vocabulary "
						+ "has grown into the marker token!",
					"The nonexistent pattern class is present in the corpus!"
				);
			}
		}
	},

	/**
	 * 2% of the distinct values - the narrow end of the bisect band.
	 *
	 * See the class comment's *width bisect* section for why this group exists and why its members are named after
	 * their width rather than after an adjective.
	 */
	WIDTH_02_PCT("gizmo") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.02d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.015d, 0.025d);
		}
	},

	/**
	 * 4% of the distinct values.
	 */
	WIDTH_04_PCT("fjord") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.04d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.03d, 0.05d);
		}
	},

	/**
	 * 8% of the distinct values.
	 */
	WIDTH_08_PCT("syrup") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.08d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.06d, 0.10d);
		}
	},

	/**
	 * 12% of the distinct values - the widest member of the bisect band, and the only one the arithmetic in the class
	 * comment predicts will *change sign* across the corpus-size axis.
	 */
	WIDTH_12_PCT("waltz") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.12d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.10d, 0.14d);
		}
	},

	/**
	 * 20% of the distinct values.
	 *
	 * This and the three below exist to OBSERVE the crossover rather than extrapolate to it. The band up to 15% was
	 * enough while the accelerated path lost somewhere around 9-10%; it no longer is, because the per-candidate cost
	 * has since fallen far enough that no planted width in the old range loses at all, and a curve that never changes
	 * sign cannot locate the point where it would. A production corpus measured over the same code puts the crossover
	 * past 34% of distinct values without reaching it either, so these widths are chosen to bracket that region and
	 * to keep going until the scan actually wins.
	 *
	 * None of the four is expected to be admitted by the gate, and none asserts that it is: they are measured with
	 * both arms forced, and the whole purpose is to say where the gate SHOULD sit.
	 *
	 * Two things about measuring with them. **Adding them changed every generated corpus**, because every class in
	 * this enum is planted into the one shared corpus whether or not the run selects it - so a score taken with these
	 * present may not be spliced against one taken without them, and a crossover measured here cannot be compared
	 * against an older curve to attribute a movement to anything. **And the gate must never be forced by lowering**
	 * {@link TrigramSubstringSearch#REQUIRED_NARROWING_FACTOR}: {@link #THRESHOLD} plants into
	 * `n / REQUIRED_NARROWING_FACTOR` values, so lowering it widens that class across the shared corpus and lengthens
	 * every value, changing the very cost under measurement. Force the arm at the call site instead.
	 */
	WIDTH_20_PCT("sphinx") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.20d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.18d, 0.22d);
		}
	},

	/**
	 * 30% of the distinct values - just under the widest pattern a production corpus was observed to produce.
	 */
	WIDTH_30_PCT("jackdaw") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.30d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.28d, 0.32d);
		}
	},

	/**
	 * 40% of the distinct values - past anything a production corpus was observed to produce, and therefore past the
	 * point where the measurement stops being an extrapolation.
	 */
	WIDTH_40_PCT("obelisk") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.40d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.38d, 0.42d);
		}
	},

	/**
	 * 55% of the distinct values. Deliberately past half the corpus: if the scan does not win here it does not win
	 * anywhere this gate can reach, and that is itself the answer.
	 */
	WIDTH_55_PCT("vortex") {
		@Override
		public int plantingCount(int distinctValueCount) {
			return plantByShare(0.55d, distinctValueCount);
		}

		@Override
		public void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount) {
			assertExactWidth(this, measuredMinimum, measuredMaximum, distinctValueCount);
			assertShareWithin(this, measuredMinimum, distinctValueCount, 0.53d, 0.57d);
		}
	};

	/**
	 * Share of the distinct values {@link #COMMON} is planted into.
	 */
	private static final double COMMON_SHARE = 0.15d;

	/**
	 * Share of the distinct values {@link #MEDIUM} is planted into.
	 */
	private static final double MEDIUM_SHARE = 0.01d;

	/**
	 * The most values {@link #RARE} is ever planted into.
	 */
	private static final int RARE_CEILING = 4;

	/**
	 * Corpus size per {@link #RARE} planting while the corpus is too small for {@link #RARE_CEILING} of them - chosen
	 * so a 256-value corpus already carries the full four.
	 */
	private static final int RARE_DIVISOR = 64;

	/**
	 * The token planted into the corpus, and the term the query searches for. Every one of these is at least five code
	 * points long, so all of its trigrams lie strictly inside the token and none of them can be produced by the space
	 * that separates it from its neighbours.
	 */
	private final String pattern;

	/**
	 * @param pattern the marker token this class plants and searches for
	 */
	SubstringPatternClass(@Nonnull String pattern) {
		this.pattern = pattern;
	}

	/**
	 * @return the marker token, which is also the raw search term handed to `attributeContains`
	 */
	@Nonnull
	public String getPattern() {
		return this.pattern;
	}

	/**
	 * How many distinct values of a corpus of this size carry the marker. This is simultaneously the intended posting
	 * width of every trigram of {@link #getPattern()} and the expected size of the query's result, because each value
	 * belongs to exactly one entity.
	 *
	 * @param distinctValueCount the corpus size
	 * @return the number of values the marker is planted into
	 */
	public abstract int plantingCount(int distinctValueCount);

	/**
	 * Refuses a corpus in which this class does not have the posting width it claims.
	 *
	 * The check is exact rather than a band, and it is two-sided: the *cheapest* trigram of the pattern decides
	 * whether the accelerated path is taken and how many candidates it verifies, while the *dearest* one exceeding the
	 * planting count would mean a trigram of the marker also occurs somewhere else in the corpus - a `MEDIUM` pattern
	 * that is secretly `COMMON`, which would silently destroy the cell it is measured in.
	 *
	 * @param measuredMinimum    the smallest posting cardinality among the pattern's trigrams, as built
	 * @param measuredMaximum    the largest posting cardinality among the pattern's trigrams, as built
	 * @param distinctValueCount the corpus size
	 * @throws GenericEvitaInternalError when the class does not land where it claims
	 */
	public abstract void verifyPostingWidth(int measuredMinimum, int measuredMaximum, int distinctValueCount);

	/**
	 * Turns a target share into an exact planting count.
	 *
	 * Rounding is what makes the count an integer, and the count - never the share - is what the fixture asserts
	 * against the built index, because a share is not expressible at every corpus size (2% of 100 values is two
	 * values; 2% of 30 would be none). The floor of one keeps a class from vanishing on a corpus too small to
	 * express it, which would turn its cell into a silent `NONEXISTENT`.
	 *
	 * @param share              the intended fraction of the distinct values
	 * @param distinctValueCount the corpus size
	 * @return the exact number of values the marker is planted into
	 */
	private static int plantByShare(double share, int distinctValueCount) {
		return Math.max(1, (int) Math.round(distinctValueCount * share));
	}

	/**
	 * Insists both extremes of the pattern's posting cardinalities equal the planting count - i.e. every trigram of
	 * the marker occurs in exactly the values the marker was planted into, and nowhere else.
	 *
	 * @param patternClass       the class being verified
	 * @param measuredMinimum    the smallest posting cardinality among the pattern's trigrams
	 * @param measuredMaximum    the largest posting cardinality among the pattern's trigrams
	 * @param distinctValueCount the corpus size
	 */
	private static void assertExactWidth(
		@Nonnull SubstringPatternClass patternClass,
		int measuredMinimum,
		int measuredMaximum,
		int distinctValueCount
	) {
		final int planted = patternClass.plantingCount(distinctValueCount);
		if (measuredMinimum != planted || measuredMaximum != planted) {
			throw new GenericEvitaInternalError(
				"`" + patternClass.name() + "` was planted into " + planted + " of " + distinctValueCount
					+ " distinct values, but its trigrams post against " + measuredMinimum + ".." + measuredMaximum
					+ " of them - the marker token `" + patternClass.getPattern() + "` shares a trigram with the "
					+ "corpus vocabulary, so this class does not measure the width it is labelled with!",
				"A pattern class does not have the posting width it claims!"
			);
		}
	}

	/**
	 * Insists the planted width falls inside the share band the class is documented to occupy.
	 *
	 * @param patternClass       the class being verified
	 * @param measuredMinimum    the smallest posting cardinality among the pattern's trigrams
	 * @param distinctValueCount the corpus size
	 * @param minimumShare       the lowest admissible share of the distinct values, inclusive
	 * @param maximumShare       the highest admissible share of the distinct values, inclusive
	 */
	private static void assertShareWithin(
		@Nonnull SubstringPatternClass patternClass,
		int measuredMinimum,
		int distinctValueCount,
		double minimumShare,
		double maximumShare
	) {
		final double share = (double) measuredMinimum / (double) distinctValueCount;
		// a single value is admissible however small the corpus - a share band cannot be honoured below 1/share values
		if (measuredMinimum > 1 && (share < minimumShare || share > maximumShare)) {
			throw new GenericEvitaInternalError(
				"`" + patternClass.name() + "` must post against " + minimumShare + ".." + maximumShare
					+ " of the distinct values, but posts against " + share + " (" + measuredMinimum + " of "
					+ distinctValueCount + ")!",
				"A pattern class left its share band!"
			);
		}
	}

}
