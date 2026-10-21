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

package io.evitadb.test.fulltext;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A realistic **term dictionary of folded stems** for any language with a hunspell fixture — the language-neutral
 * sibling of {@link CzechStemDictionary}, which stays as it is for its existing callers. Every inflected form of the
 * fixture ({@link HunspellWordForms}) is run through the **production index analyzer** of the language, as
 * {@link FulltextAnalyzerRegistry#getIndexAnalyzer(String, Locale)} resolves it, and the distinct terms are loaded
 * into evitaDB's own {@link TransactionalBucketBPlusTree}.
 *
 * Unlike {@link CzechStemDictionary}, no stemmer shortcut is taken: the index term is whatever the production chain
 * emits (stop words dropped, the language's stemmer on the accented form, its normalizations, diacritics folded),
 * so it needs no separate check against the analyzer. A form the chain drops (a stop word) gets no term; a form the
 * tokenizer splits into more than one token is counted ({@link #getSplitFormCount()}) and left out.
 *
 * Alongside the dictionary a **deterministic sample** of forms is kept — one distinct form in a given modulus,
 * chosen by its hash, the same rule as the Czech dictionary (whose modulus is
 * {@link CzechStemDictionary#SAMPLE_MODULUS}) — together with each sampled form's index term, as the source of
 * synthetic queries. A fixture with fewer forms takes a smaller modulus to yield a sample of the same size.
 *
 * Building takes from half a minute to a few minutes per language and is not shared: the caller builds one
 * dictionary at a time and drops it when done, so that several languages measured in one JVM never hold more than
 * one dictionary in memory.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class LanguageStemDictionary {
	/**
	 * Entity type the analyzers are resolved for; the built-in table does not distinguish entity types.
	 */
	private static final String ENTITY_TYPE = "PRODUCT";
	/**
	 * Leaf block size of the tree; odd on purpose, the tree derives its minimum block size as half of it.
	 */
	private static final int LEAF_BLOCK_SIZE = 63;

	/**
	 * The language the dictionary was built for.
	 */
	@Nonnull private final Locale locale;
	/**
	 * The dictionary of folded stems.
	 */
	@Nonnull private final TransactionalBucketBPlusTree<String> tree;
	/**
	 * Number of distinct keys in {@link #tree}.
	 */
	private final int size;
	/**
	 * Number of forms the generator produced (with duplicates).
	 */
	private final long formCount;
	/**
	 * Number of forms the index analyzer dropped (stop words).
	 */
	private final long droppedFormCount;
	/**
	 * Number of forms the index analyzer split into more than one term, left out of the dictionary.
	 */
	private final long splitFormCount;
	/**
	 * The sampled forms, accented and lowercased, in sorted order, each mapped to its index term.
	 */
	@Nonnull private final Map<String, String> sampledForms;

	/**
	 * @param locale           the language
	 * @param tree             the dictionary of folded stems
	 * @param size             number of distinct keys
	 * @param formCount        number of generated forms
	 * @param droppedFormCount number of forms the analyzer dropped
	 * @param splitFormCount   number of forms the analyzer split
	 * @param sampledForms     sampled forms with their index terms
	 */
	private LanguageStemDictionary(
		@Nonnull Locale locale,
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		int size,
		long formCount,
		long droppedFormCount,
		long splitFormCount,
		@Nonnull Map<String, String> sampledForms
	) {
		this.locale = locale;
		this.tree = tree;
		this.size = size;
		this.formCount = formCount;
		this.droppedFormCount = droppedFormCount;
		this.splitFormCount = splitFormCount;
		this.sampledForms = sampledForms;
	}

	/**
	 * Builds the dictionary of one language from every form of its hunspell fixture, sampling one form in
	 * {@link CzechStemDictionary#SAMPLE_MODULUS} as the Czech dictionary does.
	 *
	 * @param locale a locale with language and country, naming both the fixture (`sk_SK`) and the analyzer (`sk`)
	 * @return the dictionary
	 */
	@Nonnull
	public static LanguageStemDictionary build(@Nonnull Locale locale) {
		return build(locale, CzechStemDictionary.SAMPLE_MODULUS);
	}

	/**
	 * Builds the dictionary of one language from every form of its hunspell fixture.
	 *
	 * @param locale        a locale with language and country, naming both the fixture (`sk_SK`) and the analyzer
	 *                      (`sk`)
	 * @param sampleModulus one distinct form in this many, chosen by its hash, is kept as a query sample
	 * @return the dictionary
	 */
	@Nonnull
	public static LanguageStemDictionary build(@Nonnull Locale locale, int sampleModulus) {
		if (sampleModulus < 1) {
			throw new IllegalArgumentException("The sample modulus must be positive, got " + sampleModulus + ".");
		}
		final HunspellWordForms forms = HunspellWordForms.load(HunspellWordForms.fixtureOf(locale));
		final TreeSet<String> stems = new TreeSet<>();
		final TreeMap<String, String> sample = new TreeMap<>();
		final long[] counts = new long[3];
		try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry()) {
			final FulltextAnalyzer indexAnalyzer = registry.getIndexAnalyzer(ENTITY_TYPE, locale);
			forms.forEachForm(form -> {
				counts[0]++;
				final List<AnalyzedTerm> terms = indexAnalyzer.getTerms(form);
				if (terms.isEmpty()) {
					counts[1]++;
					return;
				}
				if (terms.size() > 1) {
					counts[2]++;
					return;
				}
				final String term = terms.get(0).term();
				stems.add(term);
				if (Math.floorMod(form.hashCode(), sampleModulus) == 0) {
					sample.put(form, term);
				}
			});
		}
		final TransactionalBucketBPlusTree<String> tree =
			new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
		int pk = 1;
		for (final String stem : stems) {
			tree.addRecord(stem, pk++);
		}
		return new LanguageStemDictionary(
			locale, tree, stems.size(), counts[0], counts[1], counts[2], Collections.unmodifiableMap(sample)
		);
	}

	/**
	 * @return the language the dictionary was built for
	 */
	@Nonnull
	public Locale getLocale() {
		return this.locale;
	}

	/**
	 * @return the dictionary of folded stems
	 */
	@Nonnull
	public TransactionalBucketBPlusTree<String> getTree() {
		return this.tree;
	}

	/**
	 * @return number of distinct keys in the dictionary
	 */
	public int getSize() {
		return this.size;
	}

	/**
	 * @return number of forms the generator produced, duplicates included
	 */
	public long getFormCount() {
		return this.formCount;
	}

	/**
	 * @return number of forms the index analyzer dropped, duplicates included
	 */
	public long getDroppedFormCount() {
		return this.droppedFormCount;
	}

	/**
	 * @return number of forms the index analyzer split into more than one term, duplicates included
	 */
	public long getSplitFormCount() {
		return this.splitFormCount;
	}

	/**
	 * @return the deterministic sample of forms, accented and lowercased, sorted, each mapped to its index term
	 */
	@Nonnull
	public Map<String, String> getSampledForms() {
		return this.sampledForms;
	}
}
