/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.dataType.trie;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link Trie} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class TrieTest {

	void shouldReturnEmptyWhenTrieIsNew() {
		Trie<Integer> trie = new Trie<>(Integer.class);

		assertTrue(trie.isEmpty());
	}

	void shouldReturnNotEmptyWhenTrieHasBeenUpdated() {
		Trie<Integer> trie = createExampleTrie();

		assertFalse(trie.isEmpty());
	}

	void shouldTryPrefixAndSuffixAddingToTheTree() {
		Trie<Integer> trie = new Trie<>(Integer.class);

		trie.insert("er", 1);
		trie.insert("fall", 2);
		trie.insert("water", 3);
		trie.insert("waterfall", 4);
		trie.insert("er", 5);
		trie.insert("erf", 6);

		assertArrayEquals(new Integer[] {1, 5}, trie.getValuesForWord("er"));
		assertArrayEquals(new Integer[] {6}, trie.getValuesForWord("erf"));
		assertArrayEquals(new Integer[] {2}, trie.getValuesForWord("fall"));
		assertArrayEquals(new Integer[] {3}, trie.getValuesForWord("water"));
		assertArrayEquals(new Integer[] {4}, trie.getValuesForWord("waterfall"));
	}

	void shouldReturnNodesThatHasBeenAddedToATrie() {
		Trie<Integer> trie = createExampleTrie();

		assertFalse(trie.containsWord("3"));
		assertFalse(trie.containsWord("vida"));

		assertTrue(trie.containsWord("Programming"));
		assertEquals(1, trie.getValueForWord("Programming"));
		assertTrue(trie.containsWord("is"));
		assertEquals(2, trie.getValueForWord("is"));
		assertTrue(trie.containsWord("a"));
		assertEquals(3, trie.getValueForWord("a"));
		assertTrue(trie.containsWord("way"));
		assertEquals(4, trie.getValueForWord("way"));
		assertTrue(trie.containsWord("of"));
		assertEquals(5, trie.getValueForWord("of"));
		assertTrue(trie.containsWord("life"));
		assertEquals(6, trie.getValueForWord("life"));
	}

	void shouldNotReturnNodeThatHasNotBeenAddedToATrie() {
		Trie<Integer> trie = createExampleTrie();

		assertFalse(trie.containsWord("99"));
	}

	void shouldNotReturnNodesThatHasBeenAddedButRemovedFromATrie() {
		Trie<Integer> trie = createExampleTrie();

		assertTrue(trie.containsWord("Programming"));
		trie.remove("Programming");
		assertFalse(trie.containsWord("Programming"));
	}

	void shouldReturnAllWords() {
		Trie<Integer> trie = createExampleTrie();

		final Set<String> words = trie.getWords();
		assertEquals(6, words.size());
		assertTrue(words.contains("Programming"));
		assertTrue(words.contains("is"));
		assertTrue(words.contains("a"));
		assertTrue(words.contains("way"));
		assertTrue(words.contains("of"));
		assertTrue(words.contains("life"));
	}

	void shouldReturnAllWordsWithValues() {
		Trie<Integer> trie = createExampleTrie();

		final Set<Entry<String, Integer>> words = trie.wordToValueEntrySet();
		assertEquals(6, words.size());

		final Map<String, Integer> index = new HashMap<>();
		for (Entry<String, Integer> wordEntry : words) {
			index.put(wordEntry.getKey(), wordEntry.getValue());
		}

		assertEquals(1, index.get("Programming"));
		assertEquals(2, index.get("is"));
		assertEquals(3, index.get("a"));
		assertEquals(4, index.get("way"));
		assertEquals(5, index.get("of"));
		assertEquals(6, index.get("life"));
	}

	void shouldNotDeleteSubElementWhenOverlappingElementHasBeenRemoved() {
		Trie<Integer> trie1 = new Trie<>(Integer.class);

		trie1.insert("pie", 1);
		trie1.insert("pies", 2);

		trie1.remove("pies");

		assertTrue(trie1.containsWord("pie"));
	}

	@Test
	void shouldFindWordsBetween() throws IOException {
		final Trie<String> trie = new Trie<>(String.class);
		final String file = IOUtils.toString(Objects.requireNonNull(this.getClass().getClassLoader().getResourceAsStream("testData/TrieTest_words.txt")), StandardCharsets.UTF_8);
		for (String line : file.split("\n")) {
			final String[] keyValue = line.split("=");
			trie.insert(keyValue[0], keyValue[1]);
		}

		final List<String> wordsBetween = trie.getWordsBetween("innerContainer.index[", ']');
		assertEquals(6, wordsBetween.size());
		assertTrue(wordsBetween.contains("AAA"));
		assertTrue(wordsBetween.contains("ZBBE"));
		assertTrue(wordsBetween.contains("BBB"));
		assertTrue(wordsBetween.contains("ZBZ"));
		assertTrue(wordsBetween.contains("ZBB"));
		assertTrue(wordsBetween.contains("ZZZ"));
	}

	@Test
	void shouldFindWordsBetweenOrStartingWith() throws IOException {
		final Trie<String> trie = new Trie<>(String.class);
		final String file = IOUtils.toString(Objects.requireNonNull(this.getClass().getClassLoader().getResourceAsStream("testData/TrieTest_words.txt")), StandardCharsets.UTF_8);
		for (String line : file.split("\n")) {
			final String[] keyValue = line.split("=");
			trie.insert(keyValue[0], keyValue[1]);
		}

		final List<String> wordsBetween = trie.getWordsBetweenOrStartingWith("", '.');
		assertEquals(22, wordsBetween.size());
		assertTrue(wordsBetween.contains("fString"));
		assertTrue(wordsBetween.contains("fByte"));
		assertTrue(wordsBetween.contains("fByteP"));
		assertTrue(wordsBetween.contains("fShort"));
		assertTrue(wordsBetween.contains("fShortP"));
		assertTrue(wordsBetween.contains("fInteger"));
		assertTrue(wordsBetween.contains("fIntegerP"));
		assertTrue(wordsBetween.contains("fLong"));
		assertTrue(wordsBetween.contains("fLongP"));
		assertTrue(wordsBetween.contains("fBoolean"));
		assertTrue(wordsBetween.contains("fBooleanP"));
		assertTrue(wordsBetween.contains("fChar"));
		assertTrue(wordsBetween.contains("fCharP"));
		assertTrue(wordsBetween.contains("fBigDecimal"));
		assertTrue(wordsBetween.contains("fOffsetDateTime"));
		assertTrue(wordsBetween.contains("fLocalDateTime"));
		assertTrue(wordsBetween.contains("fLocalDate"));
		assertTrue(wordsBetween.contains("fLocalTime"));
		assertTrue(wordsBetween.contains("fDateTimeRange"));
		assertTrue(wordsBetween.contains("fNumberRange"));
		assertTrue(wordsBetween.contains("fLocale"));
		assertTrue(wordsBetween.contains("innerContainer"));
	}

	@Test
	void shouldTestComplexFile() throws IOException {
		final Trie<String> trie = new Trie<>(String.class);
		final Map<String, String> verificationMap = new HashMap<>();
		final String file = IOUtils.toString(Objects.requireNonNull(this.getClass().getClassLoader().getResourceAsStream("testData/TrieTest_words.txt")), StandardCharsets.UTF_8);
		for (String line : file.split("\n")) {
			final String[] keyValue = line.split("=");
			trie.insert(keyValue[0], keyValue[1]);
			verificationMap.put(keyValue[0], keyValue[1]);
		}
		assertEquals(verificationMap.size(), trie.getWords().size());
		for (Entry<String, String> entry : trie.wordToValueEntrySet()) {
			assertEquals(verificationMap.get(entry.getKey()), entry.getValue());
		}
	}

	private static Trie<Integer> createExampleTrie() {
		final Trie<Integer> trie = new Trie<>(Integer.class);

		trie.insert("Programming", 1);
		trie.insert("is", 2);
		trie.insert("a", 3);
		trie.insert("way", 4);
		trie.insert("of", 5);
		trie.insert("life", 6);

		return trie;
	}

}
