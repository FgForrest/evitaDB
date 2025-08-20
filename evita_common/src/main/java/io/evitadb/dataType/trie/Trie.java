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

package io.evitadb.dataType.trie;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static java.util.Optional.ofNullable;

/**
 * Trie implementation. See [Wiki description](https://en.wikipedia.org/wiki/Trie).
 * Currently it's super trivial implementation without word stacking.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class Trie<T extends Serializable> implements Serializable {
	@Serial private static final long serialVersionUID = 7649813495448701492L;
	@Getter private final Class<T> type;
	@Getter private final TrieNode<T> root;
	private final IntFunction<T[]> arrayFactory;
	private int size;

	public Trie(@Nonnull Class<T> type) {
		this.type = type;
		//noinspection unchecked
		this.arrayFactory = length -> (T[]) Array.newInstance(type, length);
		this.root = new TrieNode<>(this.arrayFactory);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static <S extends Serializable> Trie<S> _internalBuild(@Nonnull Class<S> type, @Nonnull TrieNode<S> rootNode) {
		return new Trie<>(type, rootNode);
	}

	private Trie(@Nonnull Class<T> type, @Nonnull TrieNode<T> rootNode) {
		this.type = type;
		//noinspection unchecked
		this.arrayFactory = length -> (T[]) Array.newInstance(type, length);
		this.root = rootNode;
	}

	/**
	 * Returns count of values stored in the trie.
	 * Note: count of values might be greater than count of words if there are multiple values connected to single word.
	 *
	 * @return count of values
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Adds new word to the trie.
	 */
	public void insert(@Nonnull String word, @Nonnull T value) {
		this.size++;
		TrieNode<T> current = this.root;
		int i = 0;

		while (i < word.length() && current.hasEdgeLabel(word.charAt(i))) {
			final char examinedChar = word.charAt(i);
			int j = 0;
			final String label = current.getEdgeLabel(examinedChar);

			while (j < label.length() && i < word.length() && label.charAt(j) == word.charAt(i)) {
				++i;
				++j;
			}

			if (j == label.length()) {
				current = current.getChildren(examinedChar);
			} else {
				if (i == word.length()) {
					// inserting a prefix of existing word
					final String remainingLabel = strCopy(label, j);

					final TrieNode<T> newChild = new TrieNode<>(this.arrayFactory, value); // new node for "ele"
					final TrieNode<T> existingChild = current.swapChildren(examinedChar, newChild);

					current.setEdgeLabelLength(examinedChar, j);     // making "element" as "ele"
					newChild.setChildrenAndEdgeLabel(remainingLabel.charAt(0), existingChild, remainingLabel);
				} else {
					// inserting word which has a partial match with existing word
					final String remainingLabel = strCopy(label, j);
					final String remainingWord = strCopy(word, i);

					final TrieNode<T> newChild = new TrieNode<>(this.arrayFactory);
					final TrieNode<T> existingChild = current.swapChildren(examinedChar, newChild);

					current.setEdgeLabelLength(examinedChar, j);
					newChild.setChildrenAndEdgeLabel(remainingLabel.charAt(0), existingChild, remainingLabel);
					newChild.setChildrenAndEdgeLabel(remainingWord.charAt(0), new TrieNode<>(this.arrayFactory, value), remainingWord);
				}

				return;
			}
		}

		if (i < word.length()) {
			// inserting new node for new word
			current.setChildrenAndEdgeLabel(word.charAt(i), new TrieNode<>(this.arrayFactory, value), strCopy(word, i));
		} else {
			// inserting "there" when "therein" and "thereafter" are existing
			current.addValue(this.arrayFactory, value);
		}
	}

	/**
	 * Removes word from a trie.
	 */
	public T remove(@Nonnull String word) {
		/* TOBEDONE JNO IMPLEMENT */
		final TrieNode<T> removedNode = null;
		if (removedNode != null) {
			this.size += removedNode.getValues().length;
		}
		return null;
	}

	/**
	 * Returns values assigned to the specified word.
	 */
	@Nonnull
	public T[] getValuesForWord(@Nonnull String word) {
		return ofNullable(getWordTrieNode(word))
			.map(TrieNode::getValues)
			.orElseGet(() -> this.arrayFactory.apply(0));
	}

	/**
	 * Returns value assigned to the specified word but only if there is single one, otherwise exception is thrown.
	 *
	 * @return null if there is no result otherwise the value
	 * @throws IllegalArgumentException if there is more than one result
	 */
	@Nullable
	public T getValueForWord(@Nonnull String word) throws IllegalArgumentException {
		return ofNullable(getWordTrieNode(word))
			.map(TrieNode::getValues)
			.map(it -> getSingleValue(word, it))
			.orElse(null);
	}

	/**
	 * Returns true if trie contains specified word.
	 */
	public boolean containsWord(@Nonnull String word) {
		return getWordTrieNode(word) != null;
	}

	/**
	 * Returns true if there is no single word in trie.
	 */
	public boolean isEmpty() {
		return this.root.hasNoChildren();
	}

	/**
	 * Returns all words in this trie.
	 */
	public Set<String> getWords() {
		final HashSet<String> result = new HashSet<>();
		walkTree(
			this.root,
			new StringBuilder(),
			(word, trieNode) -> {
				if (trieNode.isEndOfWord()) {
					result.add(word.toString());
					return trieNode.hasNoChildren();
				} else {
					return false;
				}
			}
		);
		return result;
	}

	/**
	 * Returns true if trie contains any word starting with the prefix.
	 */
	public boolean containsWordStartingWith(@Nonnull String prefix) {
		int i = 0;
		TrieNode<T> current = this.root;

		while (i < prefix.length() && current.hasEdgeLabel(prefix.charAt(i))) {
			final char examinedChar = prefix.charAt(i);
			final String label = current.getEdgeLabel(examinedChar);
			int j = 0;

			while (i < prefix.length() && j < label.length()) {
				if (prefix.charAt(i) != label.charAt(j)) {
					return false;   // character mismatch
				}

				++i;
				++j;
			}

			if (j == label.length() && i <= prefix.length()) {
				current = current.getChildren(examinedChar);    // traverse further
			} else {
				// edge label is larger than target word, which is fine
				return true;
			}
		}

		return i == prefix.length();
	}

	/**
	 * Returns list of all words that start with passed prefix.
	 */
	public Set<String> getWordsStartingWith(@Nonnull final String prefix) {
		final StringBuilder pathBuilder = new StringBuilder();
		final TrieNode<T> prefixRootNode = findPrefixRoot(prefix, pathBuilder);
		if (prefixRootNode == null) {
			return Collections.emptySet();
		}

		// gather word, that was completed by traversing from root to prefixRootNode node
		final HashSet<String> result = new HashSet<>();
		final String currentNodeParentWord = pathBuilder.toString();
		walkTree(
			prefixRootNode,
			new StringBuilder(),
			(word, trieNode) -> {
				final String completedPrefix = currentNodeParentWord + word;
				if (completedPrefix.startsWith(prefix)) {
					if (trieNode.isEndOfWord()) {
						result.add(completedPrefix.substring(prefix.length()));
					}
					// we need to visit additional children to find breaking character
					return trieNode.hasNoChildren();
				} else {
					// end the walk to this sub tree - word doesn't start with prefix
					return true;
				}
			}
		);
		return result;
	}

	/**
	 * Returns all words in this trie that starts with passed word, contains any substring and finish with breaking
	 * character.
	 *
	 * For example from words:
	 *
	 * a.b[c]
	 * a[c]
	 * a.b[def]
	 * a.b.c[de]
	 *
	 * When looking up for words starting with `a.b[` and breaking character `]` these are found:
	 *
	 * a.b[c]
	 * a.b[def]
	 *
	 * @param prefix            mandatory prefix word (in example `a.b[`)
	 * @param breakingCharacter breaking character that would finish our search deepwise (in example `]`)
	 */
	public List<String> getWordsBetween(@Nonnull final String prefix, char... breakingCharacter) {
		return getWordsBetween(prefix, breakingCharacter, false);
	}

	/**
	 * Returns all words in this trie that starts with passed word, contains any substring and OPTIONALLY finish with
	 * breaking character.
	 *
	 * For example from words:
	 *
	 * a.b[c]
	 * a[c]
	 * a.b[def]
	 * a.b.c[de]
	 * a.b[whatever
	 *
	 * When looking up for words starting with `a.b[` and breaking character `]` these are found:
	 *
	 * a.b[c]
	 * a.b[def]
	 * a.b[whatever
	 *
	 * @param prefix            mandatory prefix word (in example `a.b[`)
	 * @param breakingCharacter breaking character that would finish our search deepwise (in example `]`)
	 */
	public List<String> getWordsBetweenOrStartingWith(@Nonnull final String prefix, char... breakingCharacter) {
		return getWordsBetween(prefix, breakingCharacter, true);
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize(@Nonnull ToIntFunction<Serializable> valueSizeEstimator) {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// type
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// array factory
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// root node
			MemoryMeasuringConstants.REFERENCE_SIZE + this.root.estimateSize(valueSizeEstimator) +
			// size
			MemoryMeasuringConstants.INT_SIZE;
	}

	/**
	 * Returns all words in this trie that starts with passed word, contains any substring and finish with breaking
	 * character.
	 *
	 * For example from words:
	 *
	 * a.b[c]
	 * a[c]
	 * a.b[def]
	 * a.b.c[de]
	 *
	 * When looking up for words starting with `a.b[` and breaking character `]` these are found:
	 *
	 * a.b[c]
	 * a.b[def]
	 *
	 * @param prefix                    mandatory prefix word (in example `a.b[`)
	 * @param breakingCharacter         breaking character that would finish our search deepwise (in example `]`)
	 * @param optionalBreakingCharacter true if words that doesn't end with breaking character should be returned as well
	 */
	private List<String> getWordsBetween(@Nonnull final String prefix, char[] breakingCharacter, boolean optionalBreakingCharacter) {
		final StringBuilder pathBuilder = new StringBuilder();
		final TrieNode<T> prefixRootNode = findPrefixRoot(prefix, pathBuilder);
		if (prefixRootNode == null) {
			return Collections.emptyList();
		}

		// gather word, that was completed by traversing from root to prefixRootNode node
		final LinkedList<String> result = new LinkedList<>();
		final String currentNodeParentWord = pathBuilder.toString();
		walkTree(
			prefixRootNode,
			new StringBuilder(),
			(word, trieNode) -> {
				if (word.length() == 0) {
					// don't end the walk - we've just entered the sub tree
					return false;
				} else {
					final String completedPrefix = currentNodeParentWord + word;
					if (completedPrefix.startsWith(prefix)) {
						// yay we're on the right path - find breaking char
						final int breakingCharIndex = findBreakingCharacter(prefix, breakingCharacter, completedPrefix);
						if (breakingCharIndex > -1) {
							// if breaking character is found register between word and end walk to inner nodes
							result.add(completedPrefix.substring(prefix.length(), breakingCharIndex));
							return true;
						} else {
							if (optionalBreakingCharacter && trieNode.isEndOfWord()) {
								result.add(completedPrefix.substring(prefix.length()));
							}
							// we need to visit additional children to find breaking character
							return trieNode.hasNoChildren();
						}
					} else {
						// end the walk to this sub tree - word doesn't start with prefix
						return true;
					}
				}
			}
		);
		return result;
	}

	/**
	 * Returns entry set of all words and values connected with that word. It flattens the values to single value and
	 * fails if there is more than one value. Use this method only in situations when you know for sure there will be
	 * exactly one value for each word.
	 */
	public Set<Entry<String, T>> wordToValueEntrySet() {
		final Set<Entry<String, T>> result = new HashSet<>();
		walkTree(
			this.root,
			new StringBuilder(),
			(word, trieNode) -> {
				if (trieNode.isEndOfWord()) {
					final String wordString = word.toString();
					result.add(new SimpleEntry<>(wordString, getSingleValue(wordString, trieNode.getValues())));
					return trieNode.hasNoChildren();
				} else {
					return false;
				}
			}
		);
		return result;
	}

	/**
	 * Returns entry set of all words and values connected with that word.
	 */
	public List<Entry<String, T[]>> wordToValuesEntrySet() {
		final List<Entry<String, T[]>> result = new LinkedList<>();
		walkTree(
			this.root,
			new StringBuilder(),
			(word, trieNode) -> {
				if (trieNode.isEndOfWord()) {
					result.add(new SimpleEntry<>(word.toString(), trieNode.getValues()));
					return trieNode.hasNoChildren();
				} else {
					return false;
				}
			}
		);
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		Trie<?> trie = (Trie<?>) o;

		if (!this.type.equals(trie.type)) return false;
		return this.root.equals(trie.root);
	}

	@Override
	public int hashCode() {
		int result = this.type.hashCode();
		result = 31 * result + this.root.hashCode();
		return result;
	}

	/**
	 * Creates a new String from an existing String starting from the given index.
	 */
	private String strCopy(CharSequence str, int index) {
		final StringBuilder result = new StringBuilder(128);

		while (index != str.length()) {
			result.append(str.charAt(index++));
		}

		return result.toString();
	}

	/**
	 * Finds trie node but only if it represents entire word.
	 */
	private TrieNode<T> getWordTrieNode(String word) {
		int i = 0;
		TrieNode<T> current = this.root;

		while (i < word.length() && current.hasEdgeLabel(word.charAt(i))) {
			final char examinedCharacter = word.charAt(i);
			final String label = current.getEdgeLabel(examinedCharacter);

			int j = 0;
			while (i < word.length() && j < label.length()) {
				if (word.charAt(i) != label.charAt(j)) {
					return null;   // character mismatch
				}

				++i;
				++j;
			}

			if (j == label.length() && i <= word.length()) {
				current = current.getChildren(examinedCharacter);    // traverse further
			} else {
				// edge label is larger than target word
				// searching for "ent" when tree has "entity"
				return null;
			}
		}

		// target word fully traversed and current node is a word ending
		return i == word.length() && current.isEndOfWord() ? current : null;
	}

	/**
	 * Goes through entire tree and executes lambda at the leaf nodes (i.e. those with values).
	 *
	 * @param terminator limits the tree traversal and probably procesess the word or the node values
	 */
	private void walkTree(@Nonnull TrieNode<T> node, @Nonnull StringBuilder builder, @Nonnull BiPredicate<StringBuilder, TrieNode<T>> terminator) {
		if (terminator.test(builder, node)) {
			return;
		}

		final String[] edgeLabels = node.getEdgeLabels();
		@SuppressWarnings("rawtypes") final TrieNode[] childrens = node.getChildren();
		for (int i = 0; i < node.getEdgeLabels().length; ++i) {
			int length = builder.length();

			builder.append(edgeLabels[i]);
			//noinspection unchecked
			walkTree(childrens[i], builder, terminator);
			builder.delete(length, builder.length());
		}
	}

	/**
	 * Returns single value for the word if there is exactly one.
	 */
	@Nullable
	private T getSingleValue(String word, T[] it) {
		if (it.length == 1) {
			return it[0];
		} else {
			throw new GenericEvitaInternalError("There are " + it.length + " values connected with word: " + word);
		}
	}

	/**
	 * Finds a trie node that contains words starting with the prefix.
	 */
	@Nullable
	private TrieNode<T> findPrefixRoot(String prefix, StringBuilder pathBuilder) {
		int i = 0;
		TrieNode<T> current = this.root;

		while (i < prefix.length() && current.hasEdgeLabel(prefix.charAt(i))) {
			final char examinedChar = prefix.charAt(i);
			final String label = current.getEdgeLabel(examinedChar);

			int j = 0;

			while (i < prefix.length() && j < label.length()) {
				if (prefix.charAt(i) != label.charAt(j)) {
					return null;   // character mismatch
				}

				++i;
				++j;
			}

			if (j == label.length() && i <= prefix.length()) {
				pathBuilder.append(label);
				current = current.getChildren(examinedChar);    // traverse further
			} else {
				// edge label is larger than target word, which is fine
				break;
			}
		}

		return current;
	}

	/**
	 * Returns least index of all breaking characters.
	 */
	private int findBreakingCharacter(@Nonnull String prefix, char[] breakingCharacter, String completedPrefix) {
		int resultIndex = Integer.MAX_VALUE;
		for (char bc : breakingCharacter) {
			final int index = completedPrefix.indexOf(bc, prefix.length());
			if (index > -1) {
				resultIndex = Math.min(index, resultIndex);
			}
		}
		return resultIndex == Integer.MAX_VALUE ? -1 : resultIndex;
	}

}
