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

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * The word-like corpus both arms of the substring A/B are built from - generated once, deterministically, and shared
 * by every benchmark in this package.
 *
 * # Why word-like rather than random
 *
 * A corpus of random characters gives almost every trigram a posting of one, which makes *every* pattern look rare and
 * flatters the trigram path beyond recognition. Real attribute values are made of a bounded vocabulary, so their
 * trigrams are shared by thousands of values and the intersection has real work to do. Every value here is therefore
 * three tokens drawn from {@link #VOCABULARY}, optionally one or more marker tokens, and a numeric suffix:
 *
 * ```text
 * meadow jasper cedar nimbus #0004217
 * ```
 *
 * # Distinctness
 *
 * The trailing `#0004217` is the value's own index, so the corpus holds exactly `entityCount` **distinct** values and
 * every entity owns one of them. That matters because both constants of `TrigramSubstringSearch` are expressed in
 * distinct values, not in entities - a corpus with repeated values would move the thresholds without saying so.
 * `SubstringCatalogFixture` re-asserts this against the built index's bucket count rather than trusting it.
 *
 * # Marker planting
 *
 * Each {@link SubstringPatternClass} owns one marker token and is planted into exactly
 * {@link SubstringPatternClass#plantingCount(int)} values, spread evenly over the corpus by
 * `position = j * entityCount / plantingCount` rather than clustered at the front - value ids ascend with insertion
 * order, and a clustered marker would make the candidate set contiguous in a way no real pattern is. A value that
 * happens to be chosen by two classes carries **both** markers, which is what keeps each class's count exact.
 *
 * **Every** class is planted, whether or not the run measures it - the corpus cannot depend on which `-p
 * patternClass` values were requested, or two runs would silently disagree about what the catalog contains while
 * `SubstringCatalogFixture` handed them the same cached fixture. The consequence is that adding a class to
 * {@link SubstringPatternClass} lengthens the values of the whole corpus a little, and therefore moves the absolute
 * scores of every class by a few percent even though no existing class changed. Ratios measured *within* one run are
 * unaffected, since both arms read the same corpus; splicing scores from runs taken across such a change is what is
 * not safe.
 *
 * # Determinism
 *
 * The vocabulary draw is a {@link Random} seeded with the constant {@link #VOCABULARY_SEED}, and nothing else in the
 * generation consults a random source. Two JVMs building a corpus of the same size therefore build the same corpus,
 * which is what lets a fork measuring the `TRIGRAM` arm be compared against a different fork measuring the `SCAN` arm.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SubstringCorpus {

	/**
	 * Name of the single benchmarked entity collection.
	 */
	public static final String PRODUCT = "Product";

	/**
	 * Name of the `String` attribute every measured query filters on.
	 */
	public static final String ATTRIBUTE_TITLE = "title";

	/**
	 * The token pool every value draws its three filler words from. Deliberately small, so its trigrams are shared by
	 * a large share of the corpus and the scan the trigram path is compared against has realistic work to do.
	 *
	 * None of these words shares a trigram with any {@link SubstringPatternClass} marker; `SubstringCatalogFixture`
	 * proves it on the built index instead of taking it on trust.
	 */
	private static final String[] VOCABULARY = {
		"alpha", "bravo", "cedar", "delta", "ember", "falcon", "garnet", "harbor",
		"indigo", "jasper", "kelvin", "lantern", "meadow", "nectar", "opal", "poplar"
	};

	/**
	 * How many filler words each value carries.
	 */
	private static final int WORDS_PER_VALUE = 3;

	/**
	 * Seed of the vocabulary draw. Any constant would do; what matters is that it is a constant, so the corpus is a
	 * pure function of its size.
	 */
	private static final long VOCABULARY_SEED = 0x5EED_7819_3AC0_1DEFL;

	/**
	 * Estimated characters per generated value, used to size the assembling buffer.
	 *
	 * Sized for the **worst** case rather than the mean, because a value that overflows it pays a copy: three
	 * seven-character words plus their separators (23), every marker token of every pattern class at once (a value
	 * can be chosen by all of them, ~55), and the nine-character index suffix. The mean is around 32.
	 */
	private static final int ESTIMATED_VALUE_LENGTH = 96;

	/**
	 * Number of entities, which is also the number of distinct values.
	 */
	private final int entityCount;

	/**
	 * The generated values, indexed by `primaryKey - 1`.
	 */
	private final String[] values;

	/**
	 * Generates the corpus.
	 *
	 * @param entityCount how many entities - and therefore how many distinct values - to generate
	 */
	public SubstringCorpus(int entityCount) {
		if (entityCount < 1) {
			throw new GenericEvitaInternalError(
				"A corpus of " + entityCount + " entities cannot be generated!",
				"The requested corpus size is not positive!"
			);
		}
		this.entityCount = entityCount;
		this.values = new String[entityCount];

		// which values carry which marker - computed first, so value assembly is a single pass
		final SubstringPatternClass[] patternClasses = SubstringPatternClass.values();
		final boolean[][] planted = new boolean[patternClasses.length][];
		for (int c = 0; c < patternClasses.length; c++) {
			planted[c] = plantingPositions(patternClasses[c], entityCount);
		}

		final Random random = new Random(VOCABULARY_SEED);
		final StringBuilder buffer = new StringBuilder(ESTIMATED_VALUE_LENGTH);
		for (int i = 0; i < entityCount; i++) {
			buffer.setLength(0);
			for (int w = 0; w < WORDS_PER_VALUE; w++) {
				if (w > 0) {
					buffer.append(' ');
				}
				buffer.append(VOCABULARY[random.nextInt(VOCABULARY.length)]);
			}
			for (int c = 0; c < patternClasses.length; c++) {
				if (planted[c][i]) {
					buffer.append(' ').append(patternClasses[c].getPattern());
				}
			}
			// the value's own index, which is what makes the corpus all-distinct
			buffer.append(" #").append(String.format("%07d", i));
			this.values[i] = buffer.toString();
		}
	}

	/**
	 * @return how many entities - and distinct values - the corpus holds
	 */
	public int getEntityCount() {
		return this.entityCount;
	}

	/**
	 * @param index zero-based position of the value, i.e. `primaryKey - 1`
	 * @return the attribute value of that entity
	 */
	@Nonnull
	public String getValue(int index) {
		return this.values[index];
	}

	/**
	 * The answer the query must return, computed from the generated values rather than from either execution path.
	 *
	 * An oracle derived from the corpus is a stronger check than comparing the two arms against each other: two paths
	 * can agree and both be wrong, whereas this one knows what the corpus contains. `String#contains` is the very
	 * predicate `AttributeContainsTranslator` applies, and the corpus is pure ASCII, so Unicode normalization cannot
	 * move the answer between here and the index.
	 *
	 * @param patternClass the class whose marker is searched for
	 * @return the primary keys whose value contains the marker, ascending
	 */
	@Nonnull
	public int[] expectedPrimaryKeysOf(@Nonnull SubstringPatternClass patternClass) {
		final String pattern = patternClass.getPattern();
		final int[] collected = new int[patternClass.plantingCount(this.entityCount)];
		int found = 0;
		for (int i = 0; i < this.entityCount; i++) {
			if (this.values[i].contains(pattern)) {
				if (found == collected.length) {
					throw new GenericEvitaInternalError(
						"`" + patternClass.name() + "` occurs in more than the " + collected.length
							+ " values it was planted into - a vocabulary word grew into the marker token!",
						"A pattern class occurs more often than it was planted!"
					);
				}
				collected[found++] = i + 1;
			}
		}
		if (found != collected.length) {
			throw new GenericEvitaInternalError(
				"`" + patternClass.name() + "` was planted into " + collected.length + " values but occurs in "
					+ found + " of them!",
				"A pattern class occurs less often than it was planted!"
			);
		}
		return collected;
	}

	/**
	 * Decides which values a class's marker is planted into: `plantingCount` positions spread evenly over the corpus.
	 *
	 * The positions are `j * entityCount / plantingCount` for ascending `j`, which are pairwise distinct because the
	 * step is at least one whenever the planting count does not exceed the corpus size - so the count is exact rather
	 * than approximate, and the assertions built on it can be equalities.
	 *
	 * @param patternClass the class being planted
	 * @param entityCount  the corpus size
	 * @return a flag per value, `true` where the marker goes
	 */
	@Nonnull
	private static boolean[] plantingPositions(@Nonnull SubstringPatternClass patternClass, int entityCount) {
		final boolean[] positions = new boolean[entityCount];
		final int plantingCount = patternClass.plantingCount(entityCount);
		if (plantingCount > entityCount) {
			throw new GenericEvitaInternalError(
				"`" + patternClass.name() + "` asks to be planted into " + plantingCount + " of " + entityCount
					+ " values!",
				"A pattern class asks for more plantings than the corpus has values!"
			);
		}
		for (int j = 0; j < plantingCount; j++) {
			positions[(int) ((long) j * entityCount / plantingCount)] = true;
		}
		return positions;
	}

}
