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

package io.evitadb.performance.fulltext.state;

import io.evitadb.core.Evita;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * A small generated catalog with two searchable attributes, for checking `TextMatchesQueryBenchmark` on any machine.
 *
 * **Its numbers are not a measurement of anything.** The words are pseudo-words built from syllables, so the Czech
 * analyzer stems them by accident rather than by grammar, and the corpus is two orders of magnitude smaller than the
 * production catalogs the phase-1 budget is about. What it does provide is every frequency class at every query length
 * the benchmark asks for, which is what a harness check needs: the vocabulary is drawn from a Zipf distribution, so a
 * few words occur in most products and most words in a handful.
 *
 * Generation is seeded with a constant, so two JVMs build the same catalog and sample the same queries from it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SyntheticFulltextCorpus {

	/**
	 * Name of the generated catalog.
	 */
	public static final String CATALOG_NAME = "fulltextBenchmark";
	/**
	 * Name of the single generated entity collection.
	 */
	public static final String ENTITY_TYPE = "product";
	/**
	 * The locale every searchable value is written in. Czech, so the search side expands variant stems and a query
	 * token can carry several terms, as it does on the production catalogs.
	 */
	public static final Locale LOCALE = Locale.forLanguageTag("cs");
	/**
	 * A short searchable attribute, three to eight words.
	 */
	public static final String ATTRIBUTE_NAME = "name";
	/**
	 * A long searchable attribute, twenty to sixty words.
	 */
	public static final String ATTRIBUTE_DESCRIPTION = "description";
	/**
	 * How many products the catalog holds.
	 */
	public static final int ENTITY_COUNT = 20_000;
	/**
	 * How many distinct words the vocabulary holds.
	 */
	private static final int VOCABULARY_SIZE = 30_000;
	/**
	 * Exponent of the Zipf distribution the words are drawn from; `1.0` is the classic natural-language shape.
	 */
	private static final double ZIPF_EXPONENT = 1.0;
	/**
	 * Seed of the only random source the generation consults.
	 */
	private static final long SEED = 42L;
	/**
	 * Consonants the syllables start with.
	 */
	private static final String CONSONANTS = "bcdfghjklmnprstvz";
	/**
	 * Vowels the syllables end with.
	 */
	private static final String VOWELS = "aeiouy";

	/**
	 * The generated words, the most frequent first.
	 */
	private final String[] vocabulary;
	/**
	 * Cumulative probability of drawing each word of {@link #vocabulary}, ascending to `1.0`.
	 */
	private final double[] cumulativeProbabilities;
	/**
	 * The random source of the whole generation.
	 */
	private final Random random = new Random(SEED);

	/**
	 * Generates the vocabulary and its distribution.
	 */
	public SyntheticFulltextCorpus() {
		this.vocabulary = generateVocabulary(this.random);
		this.cumulativeProbabilities = computeCumulativeProbabilities();
	}

	/**
	 * Defines the catalog in the given instance, fills it and switches it live.
	 *
	 * @param evita an instance that does not hold {@link #CATALOG_NAME} yet
	 */
	public void populate(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG_NAME);
		evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(ENTITY_TYPE)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().searchable())
					.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().searchable())
					.updateVia(session);
				for (int i = 1; i <= ENTITY_COUNT; i++) {
					session.createNewEntity(ENTITY_TYPE, i)
						.setAttribute(ATTRIBUTE_NAME, LOCALE, generateText(3, 8))
						.setAttribute(ATTRIBUTE_DESCRIPTION, LOCALE, generateText(20, 60))
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Generates a text of words drawn from the vocabulary.
	 *
	 * @param minimumWords the fewest words the text holds
	 * @param maximumWords the most words the text holds
	 * @return the words separated by single spaces
	 */
	@Nonnull
	private String generateText(int minimumWords, int maximumWords) {
		final int wordCount = minimumWords + this.random.nextInt(maximumWords - minimumWords + 1);
		final StringBuilder text = new StringBuilder(wordCount * 8);
		for (int i = 0; i < wordCount; i++) {
			if (i > 0) {
				text.append(' ');
			}
			text.append(drawWord());
		}
		return text.toString();
	}

	/**
	 * Draws one word with its Zipf probability.
	 *
	 * @return the word
	 */
	@Nonnull
	private String drawWord() {
		final int found = Arrays.binarySearch(this.cumulativeProbabilities, this.random.nextDouble());
		// a miss returns `-(insertion point) - 1`, and the insertion point is the first word whose cumulative
		// probability exceeds the drawn number - the word the draw lands in
		final int index = found >= 0 ? found : -found - 1;
		return this.vocabulary[Math.min(index, this.vocabulary.length - 1)];
	}

	/**
	 * Computes the cumulative probability of drawing each word, the word at rank `r` (from one) weighing
	 * `1 / r^ZIPF_EXPONENT`.
	 *
	 * @return the cumulative probabilities, ascending, the last one `1.0`
	 */
	@Nonnull
	private static double[] computeCumulativeProbabilities() {
		final double[] cumulative = new double[VOCABULARY_SIZE];
		double sum = 0.0;
		for (int rank = 0; rank < VOCABULARY_SIZE; rank++) {
			sum += 1.0 / Math.pow(rank + 1, ZIPF_EXPONENT);
			cumulative[rank] = sum;
		}
		for (int rank = 0; rank < VOCABULARY_SIZE; rank++) {
			cumulative[rank] /= sum;
		}
		return cumulative;
	}

	/**
	 * Generates the distinct pseudo-words of the vocabulary, two to four consonant-vowel syllables each.
	 *
	 * @param random the random source of the generation
	 * @return the words in the order they were generated, which is also their frequency rank
	 */
	@Nonnull
	private static String[] generateVocabulary(@Nonnull Random random) {
		final Set<String> words = CollectionUtils.createLinkedHashSet(VOCABULARY_SIZE);
		final StringBuilder word = new StringBuilder(8);
		while (words.size() < VOCABULARY_SIZE) {
			word.setLength(0);
			final int syllableCount = 2 + random.nextInt(3);
			for (int i = 0; i < syllableCount; i++) {
				word.append(CONSONANTS.charAt(random.nextInt(CONSONANTS.length())));
				word.append(VOWELS.charAt(random.nextInt(VOWELS.length())));
			}
			words.add(word.toString());
		}
		return words.toArray(String[]::new);
	}

}
