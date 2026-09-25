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

package io.evitadb.index.fulltext.typo;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.automaton.Transition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Proof-of-concept port of Lucene's {@code AutomatonTermsEnum} walk ("read while the automaton accepts, seek to the
 * next acceptable string on rejection") over evitaDB's own {@link TransactionalBucketBPlusTree} keyed by
 * {@link String}. Test code only: it exists to show that the guided walk needs nothing from Lucene but the
 * {@link Automaton} classes, and to measure how much of a dictionary the walk touches.
 *
 * Differences from the Lucene original, all deliberate:
 *
 * - the alphabet is Unicode **code points**, not UTF-8 bytes, because the tree keys are `String`s and the automaton
 *   is built with `alphaMax = Character.MAX_CODE_POINT` — so `ě` against `e` costs one edit, not two;
 * - the language is **finite** (a Levenshtein automaton has no loops), so the visited-state bookkeeping that guards
 *   infinite languages is omitted;
 * - the tree is assumed to be in natural `String` order, which coincides with code point order for keys inside the
 *   Basic Multilingual Plane; the caller has to guarantee that (the tests do).
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class LevenshteinDictionaryWalker {
	private final Automaton automaton;
	private final CharacterRunAutomaton[] acceptorsByDistance;
	private final Transition transition = new Transition();
	private int[] seek = new int[16];
	private int seekLength;
	private int[] savedStates = new int[17];
	private final int readAhead;
	private long reads;
	private long seeks;
	private long readAheadHits;

	/**
	 * A dictionary term accepted by the walk, together with the edit distance the cascade of narrower acceptors
	 * assigned to it.
	 *
	 * @param term     the dictionary key
	 * @param distance the exact Damerau–Levenshtein distance from the query (0..maxEdits)
	 * @param records  how many records the bucket holds
	 */
	record Hit(@Nonnull String term, int distance, int records) {
	}

	/**
	 * Builds the walker for a query word.
	 *
	 * @param query          the query word, already in the dictionary's normalization space
	 * @param maxEdits       0, 1 or 2
	 * @param nonFuzzyPrefix number of leading characters in which no edit is permitted
	 */
	LevenshteinDictionaryWalker(@Nonnull String query, int maxEdits, int nonFuzzyPrefix) {
		this(query, maxEdits, nonFuzzyPrefix, 0);
	}

	/**
	 * Builds the walker for a query word with a bounded sequential read-ahead: after a rejection, instead of seeking
	 * straight away, the cursor is advanced with `next()` while the key is still below the seek target, at most
	 * `readAhead` times. Every key skipped this way is one the automaton rejects by construction (the target is the
	 * smallest acceptable string above the rejected key), so the result is unchanged; only the cost profile moves
	 * from root descents to sequential reads. Sized to the leaf block, this approximates an intra-leaf seek without
	 * touching the tree.
	 *
	 * @param readAhead maximum sequential reads before falling back to a seek; 0 disables the read-ahead
	 */
	LevenshteinDictionaryWalker(@Nonnull String query, int maxEdits, int nonFuzzyPrefix, int readAhead) {
		this.readAhead = readAhead;
		final int prefixLength = Math.min(nonFuzzyPrefix, query.length());
		final String prefix = query.substring(0, prefixLength);
		final int[] suffix = query.substring(prefixLength).codePoints().toArray();
		final LevenshteinAutomata builder = new LevenshteinAutomata(suffix, Character.MAX_CODE_POINT, true);
		this.automaton = builder.toAutomaton(maxEdits, prefix);
		if (!this.automaton.isDeterministic()) {
			throw new IllegalStateException("Levenshtein automaton is expected to be deterministic.");
		}
		if (Operations.hasDeadStates(this.automaton)) {
			throw new IllegalStateException("Levenshtein automaton is expected to have no dead states.");
		}
		this.acceptorsByDistance = new CharacterRunAutomaton[maxEdits + 1];
		for (int distance = 0; distance <= maxEdits; distance++) {
			this.acceptorsByDistance[distance] = new CharacterRunAutomaton(builder.toAutomaton(distance, prefix));
		}
	}

	/**
	 * Walks the tree and returns every key the automaton accepts, in dictionary order.
	 *
	 * @param tree the dictionary
	 * @return accepted keys with their distance and bucket size
	 */
	@Nonnull
	List<Hit> walk(@Nonnull TransactionalBucketBPlusTree<String> tree) {
		final List<Hit> hits = new ArrayList<>(32);
		this.reads = 0;
		this.seeks = 0;
		this.readAheadHits = 0;
		// the first candidate: the smallest string the automaton could ever accept
		this.seekLength = 0;
		if (!nextString()) {
			return hits;
		}
		BucketCursor<String> cursor = tree.cursor(seekAsString());
		this.seeks++;
		final CharacterRunAutomaton widest = this.acceptorsByDistance[this.acceptorsByDistance.length - 1];
		// a key the read-ahead already pulled from the cursor but has not examined yet
		String pending = null;
		while (true) {
			final String key;
			if (pending != null) {
				key = pending;
				pending = null;
			} else if (cursor.next()) {
				this.reads++;
				key = cursor.value();
			} else {
				break;
			}
			if (widest.run(key)) {
				hits.add(new Hit(key, classify(key), cursor.isSingle() ? 1 : -1));
				continue;
			}
			// rejected: compute the next string the automaton could accept
			setSeek(key);
			if (!nextString()) {
				break;
			}
			final String target = seekAsString();
			// read-ahead: every key between the rejected one and the target is rejected by construction, so
			// skipping them sequentially is safe; it pays off when the target sits in the cursor's current leaf
			boolean reached = false;
			boolean exhausted = false;
			for (int i = 0; i < this.readAhead; i++) {
				if (!cursor.next()) {
					exhausted = true;
					break;
				}
				this.reads++;
				final String ahead = cursor.value();
				if (ahead.compareTo(target) >= 0) {
					pending = ahead;
					reached = true;
					break;
				}
			}
			if (exhausted) {
				break;
			}
			if (reached) {
				this.readAheadHits++;
				continue;
			}
			cursor = tree.cursor(target);
			this.seeks++;
		}
		return hits;
	}

	/**
	 * Reference implementation: every key of the tree tested by the widest acceptor. Linear in the dictionary; the
	 * guided walk must return exactly this set.
	 *
	 * @param tree the dictionary
	 * @return accepted keys, in dictionary order
	 */
	@Nonnull
	List<String> scan(@Nonnull TransactionalBucketBPlusTree<String> tree) {
		final List<String> hits = new ArrayList<>(32);
		final CharacterRunAutomaton widest = this.acceptorsByDistance[this.acceptorsByDistance.length - 1];
		final BucketCursor<String> cursor = tree.cursor();
		while (cursor.next()) {
			final String key = cursor.value();
			if (widest.run(key)) {
				hits.add(key);
			}
		}
		return hits;
	}

	/**
	 * Number of keys the last {@link #walk} read from the tree.
	 */
	long getReads() {
		return this.reads;
	}

	/**
	 * Number of cursor seeks the last {@link #walk} issued.
	 */
	long getSeeks() {
		return this.seeks;
	}

	/**
	 * Number of rejections the last {@link #walk} resolved by sequential read-ahead instead of a seek.
	 */
	long getReadAheadHits() {
		return this.readAheadHits;
	}

	/**
	 * Learns the exact distance of an accepted term for free by testing it against the narrower acceptors, the way
	 * {@code FuzzyTermsEnum} does.
	 */
	private int classify(@Nonnull String term) {
		int distance = this.acceptorsByDistance.length - 1;
		while (distance > 0 && this.acceptorsByDistance[distance - 1].run(term)) {
			distance--;
		}
		return distance;
	}

	private void setSeek(@Nonnull String key) {
		final int[] codePoints = key.codePoints().toArray();
		ensureCapacity(codePoints.length + 1);
		System.arraycopy(codePoints, 0, this.seek, 0, codePoints.length);
		this.seekLength = codePoints.length;
	}

	@Nonnull
	private String seekAsString() {
		final StringBuilder sb = new StringBuilder(this.seekLength);
		for (int i = 0; i < this.seekLength; i++) {
			sb.appendCodePoint(this.seek[i]);
		}
		return sb.toString();
	}

	private void ensureCapacity(int length) {
		if (this.seek.length < length) {
			this.seek = Arrays.copyOf(this.seek, Math.max(length, this.seek.length * 2));
		}
		if (this.savedStates.length < length + 1) {
			this.savedStates = Arrays.copyOf(this.savedStates, Math.max(length + 1, this.savedStates.length * 2));
		}
	}

	/**
	 * Rewrites {@link #seek} to the smallest string in code point order that is greater than the current seek and
	 * that the automaton can still extend to an accepting state. Port of {@code AutomatonTermsEnum#nextString()}.
	 *
	 * @return false when no such string exists, i.e. the walk is finished
	 */
	private boolean nextString() {
		ensureCapacity(this.seekLength + 1);
		int state;
		int pos = 0;
		this.savedStates[0] = 0;
		while (true) {
			// walk the automaton along the seek string until a code point is rejected
			for (state = this.savedStates[pos]; pos < this.seekLength; pos++) {
				final int nextState = this.automaton.step(state, this.seek[pos]);
				if (nextState == -1) {
					break;
				}
				this.savedStates[pos + 1] = nextState;
				state = nextState;
			}
			// try to append code points from the last non-reject state
			if (nextStringFrom(state, pos)) {
				return true;
			}
			// no continuation from here: backtrack and bump an earlier code point
			pos = backtrack(pos);
			if (pos < 0) {
				return false;
			}
			final int newState = this.automaton.step(this.savedStates[pos], this.seek[pos]);
			if (newState >= 0 && this.automaton.isAccept(newState)) {
				return true;
			}
		}
	}

	/**
	 * Port of {@code AutomatonTermsEnum#nextString(int, int)}: from `state`, after `position` code points of the
	 * seek string, appends the lexicographically minimal path that reaches an accepting state, requiring the next
	 * code point to be strictly greater than the one currently at `position` (if any).
	 */
	private boolean nextStringFrom(int state, int position) {
		int c = 0;
		if (position < this.seekLength) {
			c = this.seek[position];
			if (c == Character.MAX_CODE_POINT) {
				// nothing is greater than the last code point; this path is dead
				return false;
			}
			c++;
		}
		this.seekLength = position;
		final int numTransitions = this.automaton.getNumTransitions(state);
		this.automaton.initTransition(state, this.transition);
		for (int i = 0; i < numTransitions; i++) {
			this.automaton.getNextTransition(this.transition);
			if (this.transition.max >= c) {
				append(Math.max(c, this.transition.min));
				state = this.transition.dest;
				// follow minimal transitions until an accepting state; finite language, so this terminates
				while (!this.automaton.isAccept(state)) {
					this.automaton.initTransition(state, this.transition);
					this.automaton.getNextTransition(this.transition);
					state = this.transition.dest;
					append(this.transition.min);
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Port of {@code AutomatonTermsEnum#backtrack(int)}: drops the tail of the seek string and increments the last
	 * remaining code point that can be incremented.
	 *
	 * @return the position that was bumped, or -1 when every position is exhausted
	 */
	private int backtrack(int position) {
		while (position-- > 0) {
			final int next = this.seek[position];
			if (next != Character.MAX_CODE_POINT) {
				this.seek[position] = next + 1;
				this.seekLength = position + 1;
				return position;
			}
		}
		return -1;
	}

	private void append(int codePoint) {
		ensureCapacity(this.seekLength + 1);
		this.seek[this.seekLength++] = codePoint;
	}

	/**
	 * Exposes the acceptor of the widest distance, for tests that want to check a single string.
	 */
	@Nullable
	CharacterRunAutomaton widestAcceptor() {
		return this.acceptorsByDistance[this.acceptorsByDistance.length - 1];
	}
}
