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
import java.io.Serializable;
import java.util.List;

/**
 * One group of words a query treats as equal: a query word of the group also finds the other words of the group.
 * Created by {@link AnalysisSteps#synonymGroup(String...)} and listed in a {@link SynonymList}. Only single words are
 * supported; a group needs at least two of them.
 *
 * @param words the words of the group in declaration order, at least two, none of them blank
 */
public record SynonymGroup(@Nonnull List<String> words) implements Serializable {

	/**
	 * Validates the words and keeps an immutable copy of them.
	 *
	 * @param words the words of the group in declaration order, at least two, none of them blank
	 */
	public SynonymGroup {
		Assert.notNull(words, "Synonym group words must not be null.");
		Assert.isTrue(words.size() >= 2, "A synonym group needs at least two words, got: " + words);
		for (final String word : words) {
			Assert.notNull(word, "A word of the synonym group must not be null.");
			Assert.isTrue(!word.isBlank(), "A word of the synonym group must not be blank.");
		}
		words = List.copyOf(words);
	}

	@Nonnull
	@Override
	public String toString() {
		return "synonymGroup" + this.words;
	}

}
