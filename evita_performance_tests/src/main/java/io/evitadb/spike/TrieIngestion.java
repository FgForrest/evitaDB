/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.spike;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.trie.Trie;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.utils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * This performance test attempts to load entire `evita_performance_tests/src/main/resources/META-INF/trieIngestion/frankenstein.txt`
 * into the trie implementation, tests word lookup and also starts with lookup used in autocompletions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TrieIngestion {
	public static final String BOOK_LOCATION = "/META-INF/trieIngestion/frankenstein.txt";
	private final Trie<Integer> trie = new Trie<>(Integer.class);
	private final Random rnd = new Random();

	/**
	 * This class holds original contents of the book - list of all words by order of occurence in the book.
	 */
	public static class TextInputState {
		private final List<String> words;
		private final StringBuilder wordBuilder = new StringBuilder();

		public TextInputState() {
			this.words = new ArrayList<>(64000);
			try (final InputStreamReader reader = new InputStreamReader(TrieIngestion.class.getResourceAsStream(BOOK_LOCATION), StandardCharsets.UTF_8)) {
				do {
					final int character = reader.read();
					if (character == -1) {
						endWord();
						System.out.println("\nLoaded " + this.words.size() + " words\n");
						return;
					} else if (Character.isWhitespace(character)) {
						endWord();
						if (this.words.size() % 3000 == 0) {
							System.out.print("#");
						}
					} else if (Character.isLetterOrDigit(character)) {
						appendToWord((char) character);
					} else {
						// do nothing and continue
					}
				} while (true);
			} catch (IOException e) {
				// this should not happen
				throw new IllegalStateException(e);
			}
		}

		/**
		 * Returns word on the specified index in the book.
		 * @param index
		 * @return
		 */
		public String getWord(int index) {
			return this.words.get(index);
		}

		/**
		 * Appends character to the currently parsed word.
		 * @param character
		 */
		private void appendToWord(char character) {
			this.wordBuilder.append(character);
		}

		/**
		 * Finishes the word - there is some whitespace that delimits the words.
		 */
		private void endWord() {
			if (this.wordBuilder.length() > 0) {
				this.words.add(this.wordBuilder.toString());
				this.wordBuilder.setLength(0);
			}
		}

	}

	/**
	 * Main testing logic.
	 */
	private void run() {
		final TextInputState state = new TextInputState();
		indexBook(state);
		testAutocompletion(state);
		testSerialization();
	}

	/**
	 * This method tries to serialize entire trie into the binary form using Kryo and measures its size.
	 */
	private void testSerialization() {
		final Kryo baseKryo = KryoFactory.createKryo();
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (final Output output = new Output(baos)) {
			baseKryo.writeObject(output, this.trie);
		}

		System.out.println("Size after serialization is: " + baos.toByteArray().length + "B");
	}

	/**
	 * This method tests autocompletion. It randomly selects any word from the book and tries to locate all it's occurences
	 * or occurrences of the words that has the word as prefix.
	 *
	 * @param state
	 */
	private void testAutocompletion(TextInputState state) {
		long duration = 0;
		int autocompleteResults = 0;
		final int lookupCount = 50000;
		a: for (int i = 0; i < lookupCount; i++) {
			final int randomIndex = this.rnd.nextInt(state.words.size());
			final String randomWord = state.words.get(randomIndex);
			long start = System.nanoTime();
			final Set<String> autocompletedWords = this.trie.getWordsStartingWith(randomWord);
			duration += System.nanoTime() - start;
			if (autocompletedWords.isEmpty()) {
				throw new GenericEvitaInternalError("Indexes doesn't match!");
			} else {
				autocompleteResults += autocompletedWords.size();
			}
		}

		System.out.println(
			"Total of " + lookupCount + " was done in " + StringUtils.formatNano(duration) +
				", average lookup took " + StringUtils.formatNano(duration / lookupCount) +
				", autocomplete returned in average " + ((float) autocompleteResults / (float) lookupCount)
		);
	}

	/**
	 * This method indexes loaded book into the trie.
	 * @param state
	 */
	private void indexBook(TextInputState state) {
		long duration = 0;
		for (int i = 0; i < state.words.size(); i++) {
			final String word = state.getWord(i);
			long start = System.nanoTime();
			this.trie.insert(word, i);
			duration += System.nanoTime() - start;
		}

		System.out.println("Total of " + state.words.size() + " words was indexed in " + StringUtils.formatNano(duration));
	}

	public static void main(String[] args) {
		new TrieIngestion().run();
	}

}
