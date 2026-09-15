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

package io.evitadb.index.fulltext.analysis;

import javax.annotation.Nonnull;

/**
 * A query-side stemmer that produces **every stem a word could have had before diacritics were folded away**,
 * not just one. One implementation per language, each shaped by that language's own stemmer rules; what they
 * share is the contract named here, which is what lets a single {@link VariantStemFilter} drive any of them.
 *
 * The problem it solves: the index side stems the **accented** text and folds afterwards, so `černá` is indexed
 * as `fold(čern)` = `cern`. A query typed without accents arrives as `cerna`, and a stemmer reading that folded
 * text cannot tell which of its rules the original accents would have triggered. Instead of guessing one answer,
 * a variant stemmer takes **both branches wherever a fold-ambiguous rule matches**, and emits the whole resulting
 * set. The contract that makes the two sides meet is:
 *
 * **The folded image of the index-side stem is always among the emitted variants.** Every language's
 * implementation is verified against its full Hunspell lexicon for exactly this property, so a bare-typed query
 * is guaranteed to produce the term the accented index lane stored.
 *
 * Input must already be **lowercased and diacritics-folded** — a variant stemmer reads the folded alphabet only,
 * and feeding it accented text produces meaningless output rather than an error.
 *
 * Implementations are stateful scratch — the results of {@link #stem(char[], int)} live in internal reusable
 * arrays until the next call — and are therefore **not thread-safe**: one instance per token stream, exactly like
 * a Lucene stemmer.
 *
 * See `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/` for the measurements and the rejected
 * alternatives behind this design.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
interface VariantStemmer {

	/**
	 * Computes every distinct stem variant of the given diacritics-folded, lowercased word. The buffer is only
	 * read, never mutated; the results stay valid until the next call.
	 *
	 * @param word   input buffer, read only
	 * @param length length of the word in the buffer
	 * @return number of distinct variants, always at least one
	 */
	int stem(@Nonnull char[] word, int length);

	/**
	 * Length of the given variant of the last {@link #stem(char[], int)} call.
	 *
	 * @param variantIndex index of the variant, `0` to `count - 1`
	 * @return length of the variant
	 */
	int length(int variantIndex);

	/**
	 * Writes the given variant into the destination buffer, which must be at least {@link #length(int)}
	 * characters long.
	 *
	 * @param variantIndex index of the variant, `0` to `count - 1`
	 * @param originalWord the exact buffer the last {@link #stem(char[], int)} call read
	 * @param destination  buffer to write into
	 * @return length of the variant written
	 */
	int materialize(int variantIndex, @Nonnull char[] originalWord, @Nonnull char[] destination);

}
