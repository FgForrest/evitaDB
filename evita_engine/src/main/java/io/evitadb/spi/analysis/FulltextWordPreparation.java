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


package io.evitadb.spi.analysis;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Prepares a word of a resource the way a step compares it: runs it through the steps of the words phase before the
 * reading step, on the side being compiled, so that `wordList("Nike")` read after `lowercase` is compared as `nike`.
 * It also tells whether the word splitting of the side brings such a word to the step whole. Handed to a step in its
 * {@link FulltextAnalysisContext}.
 */
@FunctionalInterface
public interface FulltextWordPreparation {

	/**
	 * The preparation of a step that no step precedes: the word is compared as the resource holds it.
	 */
	FulltextWordPreparation NONE = List::of;

	/**
	 * Prepares one word.
	 *
	 * @param word a word of a resource
	 * @return the words the preceding steps leave of it - none when one of them removes it (a stop word), several
	 *         when one of them emits further forms of it at its position (a fold keeping the original); the synonyms
	 *         a preceding step adds are other words, not forms of this one, and are left out
	 */
	@Nonnull
	List<String> prepare(@Nonnull String word);

	/**
	 * Tells whether a word of a resource reaches the words phase as one word: whether the tokenizer of the side yields
	 * exactly one token for it. A step matching single words refuses a word for which it does not - no text brings
	 * that word to the step whole. The further splitting steps are not asked: `word-number-split` keeps the word
	 * besides its parts.
	 *
	 * The default, for a preparation that does not know the tokenizer, counts a word without whitespace as one.
	 *
	 * @param word a word of a resource, as the resource holds it
	 * @return `true` when the word reaches the words phase as a single word
	 */
	default boolean isSingleWord(@Nonnull String word) {
		return word.codePoints().noneMatch(Character::isWhitespace);
	}

}
