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
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.cz.CzechStemmer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * A realistic **term dictionary of folded stems**: every inflected form of the hunspell `cs_CZ` fixture
 * ({@link CzechWordForms}) run through the index side of the Czech chain — stop words dropped, `CzechStemmer` on the
 * accented lowercase form, diacritics folded afterwards — and the distinct results loaded into evitaDB's own
 * {@link TransactionalBucketBPlusTree}. About 865,000 keys from 4.8 million forms, which is the order of magnitude
 * of a production catalog's dictionary (400–500 thousand terms) rather than of a lemma list.
 *
 * The index-side function is applied directly (stemmer plus fold) instead of through the `czech` analyzer, because
 * running the analyzer per word over 4.8 million forms costs minutes; tests that use this class check the shortcut
 * against the production analyzer on the sampled forms.
 *
 * Building takes about half a minute, so the instance is built once per JVM and shared ({@link #get()}). Alongside
 * the dictionary a **deterministic sample** of forms is kept — one form in {@link #SAMPLE_MODULUS}, chosen by its
 * hash — as the source of synthetic queries.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CzechStemDictionary {
	/**
	 * One distinct form in this many is kept as a query sample.
	 */
	public static final int SAMPLE_MODULUS = 500;
	/**
	 * Leaf block size of the tree; odd on purpose, the tree derives its minimum block size as half of it.
	 */
	private static final int LEAF_BLOCK_SIZE = 63;
	/**
	 * The shared instance, built on first use.
	 */
	@Nullable private static volatile CzechStemDictionary instance;

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
	 * The sampled forms, accented and lowercased, in sorted order.
	 */
	@Nonnull private final List<String> sampledForms;

	/**
	 * @param tree         the dictionary of folded stems
	 * @param size         number of distinct keys
	 * @param formCount    number of generated forms
	 * @param sampledForms sampled forms
	 */
	private CzechStemDictionary(
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		int size,
		long formCount,
		@Nonnull List<String> sampledForms
	) {
		this.tree = tree;
		this.size = size;
		this.formCount = formCount;
		this.sampledForms = sampledForms;
	}

	/**
	 * Returns the shared instance, building it on first use.
	 *
	 * @return the dictionary
	 */
	@Nonnull
	public static CzechStemDictionary get() {
		CzechStemDictionary result = instance;
		if (result == null) {
			synchronized (CzechStemDictionary.class) {
				result = instance;
				if (result == null) {
					result = build();
					instance = result;
				}
			}
		}
		return result;
	}

	/**
	 * Computes the index-side term of one lowercase accented word: `CzechStemmer`, then diacritics folding — the
	 * per-word core of the `czech` analyzer.
	 *
	 * @param word    lowercase word with diacritics
	 * @param stemmer the stemmer to use (stateless between calls, but not thread-safe)
	 * @return the folded stem, or null for a Czech stop word, which the index chain drops
	 */
	@Nullable
	public static String indexTerm(@Nonnull String word, @Nonnull CzechStemmer stemmer) {
		final CharArraySet stopWords = CzechAnalyzer.getDefaultStopSet();
		if (stopWords.contains(word)) {
			return null;
		}
		final char[] buffer = word.toCharArray();
		final int length = stemmer.stem(buffer, buffer.length);
		return CzechLexicon.fold(new String(buffer, 0, length));
	}

	/**
	 * Builds the dictionary from every generated form.
	 *
	 * @return the dictionary
	 */
	@Nonnull
	private static CzechStemDictionary build() {
		final CzechWordForms forms = CzechWordForms.load();
		final CzechStemmer stemmer = new CzechStemmer();
		final TreeSet<String> stems = new TreeSet<>();
		final TreeSet<String> sample = new TreeSet<>();
		final long[] formCount = {0L};
		forms.forEachForm(form -> {
			formCount[0]++;
			final String term = indexTerm(form, stemmer);
			if (term == null) {
				return;
			}
			stems.add(term);
			if (Math.floorMod(form.hashCode(), SAMPLE_MODULUS) == 0) {
				sample.add(form);
			}
		});
		final TransactionalBucketBPlusTree<String> tree =
			new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
		int pk = 1;
		for (final String stem : stems) {
			tree.addRecord(stem, pk++);
		}
		return new CzechStemDictionary(
			tree, stems.size(), formCount[0], Collections.unmodifiableList(new ArrayList<>(sample))
		);
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
	 * @return the deterministic sample of forms, accented and lowercased, sorted
	 */
	@Nonnull
	public List<String> getSampledForms() {
		return this.sampledForms;
	}
}
