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
package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A flat list of words stored as a named fulltext resource - the content of a stop list or of a list of protected
 * words. Created by {@link AnalysisSteps#wordList(String...)}.
 *
 * The words are kept as given; each step reading them prepares them the way it compares them (a stop list, for
 * example, lower-cases and folds them as its chain does).
 *
 * @param words the words of the list in declaration order, none of them blank
 */
public record WordList(@Nonnull List<String> words) implements FulltextResourceContent {

	/**
	 * Validates the words and keeps an immutable copy of them.
	 *
	 * @param words the words of the list in declaration order, none of them blank
	 */
	public WordList {
		Assert.notNull(words, "Words must not be null.");
		for (final String word : words) {
			Assert.notNull(word, "A word of the list must not be null.");
			Assert.isTrue(!word.isBlank(), "A word of the list must not be blank.");
		}
		words = List.copyOf(words);
	}

	@Nonnull
	@Override
	public FulltextResourceKind kind() {
		return FulltextResourceKind.WORD_LIST;
	}

	@Nonnull
	@Override
	public String toString() {
		return "wordList" + this.words;
	}

}
