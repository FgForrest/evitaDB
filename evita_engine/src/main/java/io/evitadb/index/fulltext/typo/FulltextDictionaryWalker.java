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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.Wtf8;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextTermKeys;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormCursor;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Transition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

/**
 * Finite Levenshtein-language walk over one field of the fulltext dictionary. Scalar successor seeks skip rejected
 * ranges, using saved automaton states to backtrack. The open-ended form accepts any key
 * having an accepted prefix, while retaining the finite language for seeking.
 *
 * The widest automaton is built once for the bare term and reused across fields. Field prefixes are offsets in the
 * keys, never part of the automaton. Distances come from a restricted Damerau DP, with no narrower automata.
 *
 * Successors follow natural `String` order: supplementary Unicode scalar values precede BMP values from `U+E000`
 * onward. Counting and automaton transitions still operate on code points. No UTF-8 encoding is involved.
 *
 * Every walk terminates, and no well-formed accepted term is missed. A term containing an unpaired surrogate is
 * visited when reached linearly, but may be passed over if it sorts between a well-formed rejected key and that
 * key's scalar successor. Rejected terms containing unpaired surrogates are advanced linearly, never used as seek
 * origins. Reachability of malformed terms depends on the edit budget: a wider walk can reach one by linear
 * advancement after accepting a predecessor that a narrower walk rejected and sought past. Queries containing
 * unpaired surrogates use linear advancement for every rejection. Recovered malformed hits are correctly
 * classified. When callers cap results, containment of narrower-walk candidates is considered before selection:
 * a recovered hit can displace a former winner, and does not justify overflowing the cap.
 *
 * A walker owns mutable seek buffers and DP rows and must be used sequentially; it is reusable after a visitor stops.
 */
@NotThreadSafe
public final class FulltextDictionaryWalker {
	/** Order offset of supplementary scalars, which follow `U+D7FF` directly in UTF-16 order. */
	private static final int SUPPLEMENTARY_SHIFT = Character.MIN_SURROGATE - Character.MIN_SUPPLEMENTARY_CODE_POINT;
	/** First scalar after the surrogate block; from here on, BMP scalars sort after every supplementary one. */
	private static final int BMP_TAIL_START = Character.MAX_SURROGATE + 1;
	/** Order value of `U+E000`, one above that of `U+10FFFF`. */
	private static final int BMP_TAIL_ORDER_START = Character.MAX_CODE_POINT + SUPPLEMENTARY_SHIFT + 1;
	/** Order offset of the BMP scalars from `U+E000` on. */
	private static final int BMP_TAIL_SHIFT = BMP_TAIL_ORDER_START - BMP_TAIL_START;
	/** Order value of `U+FFFF`, the last scalar in UTF-16 order; a position holding it cannot be incremented. */
	private static final int MAX_ORDER = Character.MAX_VALUE + BMP_TAIL_SHIFT;

	/** Finite widest language, shared by every field walk. */
	private final Automaton automaton;
	/** Compiled transitions of the same automaton for sequential acceptance. */
	private final CharacterRunAutomaton acceptor;
	/** Query-bound DP rows reused by every hit and every field walk. */
	private final FulltextEditDistance editDistance;
	/** Whether successor generation can represent every code point of the query as a Unicode scalar. */
	private final boolean scalarQuery;
	/** Leading query code points required verbatim in every candidate. */
	private final String frozenPrefix;
	/** Whether the automaton and DP both count adjacent swaps as one edit. */
	private final boolean transpositions;
	/** Whether an accepted term prefix makes every continuation acceptable. */
	private final boolean openEnd;
	/** Reusable transition reader for successor construction. */
	private final Transition transition = new Transition();
	/** Code points of the rejected term, rewritten in place into its successor. */
	private int[] seek = new int[16];
	/** Number of occupied code-point slots in the successor buffer. */
	private int seekLength;
	/** Automaton state after each surviving prefix of the rejected term. */
	private int[] savedStates = new int[17];

	/** Visitor of an accepted term and the read-only postings and impacts of its current bucket. */
	@FunctionalInterface
	public interface MatchVisitor {
		/**
		 * Visits one accepted term. Postings and impacts have the same view contract as a fulltext prefix scan.
		 *
		 * @param term analyzed dictionary term without its field prefix
		 * @param postings read-only posting list of the current bucket
		 * @param impacts read-only impacts in posting order
		 * @param distance exact distance, minimized over term prefixes for an open-ended walk
		 * @param documentFrequency bucket cardinality, obtained without decoding postings for the count
		 * @return true to continue, false to stop immediately
		 */
		boolean visit(
			@Nonnull String term, @Nonnull Bitmap postings, @Nonnull ImpactView impacts,
			int distance, int documentFrequency
		);
	}

	/**
	 * Builds only the widest automaton, independently of the fields it will search.
	 *
	 * @param query bare query term in the dictionary's normalization space
	 * @param maxEdits permitted distance, zero through two
	 * @param nonFuzzyPrefix initial code points that cannot be edited, clamped to the query length
	 * @param transpositions whether adjacent swaps count as one edit
	 * @param openEnd whether any continuation of an accepted term is also accepted
	 * @throws IllegalArgumentException when the edit budget or prefix length is invalid
	 */
	public FulltextDictionaryWalker(
		@Nonnull String query, int maxEdits, int nonFuzzyPrefix, boolean transpositions, boolean openEnd
	) {
		if (maxEdits < 0 || maxEdits > LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE) {
			throw new IllegalArgumentException("Fulltext edit distance must be between zero and two.");
		}
		if (nonFuzzyPrefix < 0) {
			throw new IllegalArgumentException("The frozen prefix must not be negative.");
		}
		final int[] codePoints = query.codePoints().toArray();
		this.editDistance = new FulltextEditDistance(codePoints);
		this.scalarQuery = !Wtf8.hasUnpairedSurrogate(query);
		final int prefixLength = Math.min(nonFuzzyPrefix, codePoints.length);
		this.frozenPrefix = query.substring(0, query.offsetByCodePoints(0, prefixLength));
		this.transpositions = transpositions;
		this.openEnd = openEnd;
		final int[] suffix = Arrays.copyOfRange(codePoints, prefixLength, codePoints.length);
		this.automaton = new LevenshteinAutomata(suffix, Character.MAX_CODE_POINT, transpositions)
			.toAutomaton(maxEdits, this.frozenPrefix);
		this.acceptor = new CharacterRunAutomaton(this.automaton);
	}

	/**
	 * Visits the accepted terms of one field in ascending dictionary order, reading only keys that start with the
	 * frozen prefix, until the visitor returns false or the keys run out. The dictionary remains owned by the calling
	 * fulltext index; the visitor must not write to it or invoke this walker recursively. A new call resets all seek
	 * state, so a walk its visitor stopped leaves nothing behind.
	 *
	 * @param tree fulltext dictionary, in natural `String` order
	 * @param fieldId field whose encoded key range is visited
	 * @param visitor receives the accepted terms reached by the walk with their current bucket views
	 * @throws GenericEvitaInternalError when a broken successor construction fails to advance past a rejected key
	 */
	public void walk(
		@Nonnull TransactionalBucketBPlusTree<String> tree, int fieldId, @Nonnull MatchVisitor visitor
	) {
		final String fieldPrefix = FulltextTermKeys.fieldPrefix(fieldId);
		final String keyPrefix = fieldPrefix + this.frozenPrefix;
		BucketCursor<String> cursor = seek(tree, null, keyPrefix);
		while (cursor != null) {
			final String key = cursor.value();
			if (!key.startsWith(keyPrefix)) {
				return;
			}
			if (accepts(key, fieldPrefix.length())) {
				final String term = FulltextTermKeys.termOf(key);
				final int distance = this.editDistance.distance(term, this.transpositions, this.openEnd);
				final int frequency = cursor.size();
				if (!visitor.visit(term, cursor.records(), cursor.impacts(), distance, frequency)) {
					return;
				}
			} else if (this.scalarQuery && !Wtf8.hasUnpairedSurrogate(key)) {
				// The encoded field prefix is ASCII: scanning the full key detects exactly the term's lone surrogates.
				setSeek(key, fieldPrefix.length());
				if (!nextString()) {
					return;
				}
				final String target = fieldPrefix + new String(this.seek, 0, this.seekLength);
				if (target.compareTo(key) <= 0) {
					throw new GenericEvitaInternalError(
						"A fulltext dictionary successor must advance past the rejected key."
					);
				}
				// an accepted successor always starts with the frozen prefix, so the target stays inside the key run
				cursor = seek(tree, cursor, target);
				continue;
			}
			if (!cursor.next()) {
				return;
			}
		}
	}

	/** Visitor of an accepted lane pair; its cursor views are borrowed until the next advance. */
	@FunctionalInterface
	public interface SurfaceMatchVisitor {
		/**
		 * Visits a pair matched solely by its decoded folded component.
		 * @param cursor positioned phase-bounded surface cursor
		 * @param distance distance to the folded word, minimized over its prefixes for an open-ended walk
		 * @return true to continue, false to stop
		 */
		boolean visit(@Nonnull SurfaceFormCursor cursor, int distance);
	}

	/**
	 * Walks a surface phase using the same automaton, DP rows and successors as a stem walk. Cursor bounds encode
	 * literal components, so neither suffix stems nor displays take part in acceptance or successor construction.
	 * Accepted-key reads borrow cursor storage. Rejected-key successor seeks still materialize keys and bounds.
	 *
	 * @param index dictionary owning the phase-bounded cursor
	 * @param wordPhase persistent index word-phase fingerprint
	 * @param visitor receives accepted surface pairs and their folded distance
	 */
	public void walkSurface(
		@Nonnull FulltextIndex index, long wordPhase, @Nonnull SurfaceMatchVisitor visitor
	) {
		final SurfaceFormCursor cursor = index.surfaceFormCursor(wordPhase, this.frozenPrefix);
		while (cursor.next()) {
			final CharSequence folded = cursor.foldedCharacters();
			if (accepts(folded, 0)) {
				final int distance = this.editDistance.distance(folded, this.transpositions, this.openEnd);
				if (!visitor.visit(cursor, distance)) {
					return;
				}
			} else if (this.scalarQuery && isScalar(folded)) {
				setSeek(folded, 0);
				if (!nextString()) {
					return;
				}
				final String target = new String(this.seek, 0, this.seekLength);
				cursor.seek(target);
			}
		}
	}

	/**
	 * Checks the borrowed folded characters before scalar successor construction.
	 * @param text decoded folded component
	 * @return true when every surrogate belongs to a complete pair
	 */
	private static boolean isScalar(@Nonnull CharSequence text) {
		for (int i = 0; i < text.length(); i++) {
			final char character = text.charAt(i);
			if (Character.isHighSurrogate(character)) {
				if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
					return false;
				}
			} else if (Character.isLowSurrogate(character)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Positions a cursor at a lower bound. The first call opens a cursor at the target; every later reposition moves
	 * the existing cursor forward with {@link BucketCursor#seek(Comparable)}, which searches the current leaf and
	 * descends from the root only when the target lies beyond it. Either way the cursor is left positioned at the
	 * first bucket at or above the target, just as `next()` leaves it.
	 *
	 * @param tree dictionary owning the cursor
	 * @param cursor positioned cursor of the walk whose current key is below the target, or null for the first seek
	 * @param target encoded lower bound of the next read
	 * @return cursor positioned at the first bucket at or above the target, or null when exhausted
	 */
	@Nullable
	private static BucketCursor<String> seek(
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		@Nullable BucketCursor<String> cursor, @Nonnull String target
	) {
		if (cursor != null) {
			return cursor.seek(target) ? cursor : null;
		}
		final BucketCursor<String> positioned = tree.cursor(target);
		return positioned.next() ? positioned : null;
	}

	/**
	 * Tests the key using only its term portion. In the open-ended language an accepting prefix is sufficient.
	 *
	 * @param key encoded dictionary key
	 * @param offset UTF-16 offset of the bare term
	 * @return whether the finite or open-ended language accepts the term
	 */
	private boolean accepts(@Nonnull CharSequence key, int offset) {
		int state = 0;
		if (this.openEnd && this.acceptor.isAccept(state)) {
			return true;
		}
		for (int i = offset; i < key.length();) {
			final int codePoint = Character.codePointAt(key, i);
			state = this.acceptor.step(state, codePoint);
			if (state < 0) {
				return false;
			}
			if (this.openEnd && this.acceptor.isAccept(state)) {
				return true;
			}
			i += Character.charCount(codePoint);
		}
		return this.acceptor.isAccept(state);
	}

	/**
	 * Copies a well-formed rejected term into the successor buffer, excluding the field prefix.
	 *
	 * @param key rejected encoded dictionary key
	 * @param offset UTF-16 offset of the bare term
	 */
	private void setSeek(@Nonnull CharSequence key, int offset) {
		ensureCapacity(key.length() - offset + 1);
		this.seekLength = 0;
		for (int i = offset; i < key.length();) {
			final int codePoint = Character.codePointAt(key, i);
			this.seek[this.seekLength++] = codePoint;
			i += Character.charCount(codePoint);
		}
	}

	/**
	 * Reserves successor and state slots, growing only when a longer term is encountered.
	 *
	 * @param length required code-point capacity
	 */
	private void ensureCapacity(int length) {
		if (this.seek.length < length) {
			this.seek = Arrays.copyOf(this.seek, Math.max(length, this.seek.length * 2));
		}
		if (this.savedStates.length < length + 1) {
			this.savedStates = Arrays.copyOf(this.savedStates, Math.max(length + 1, this.savedStates.length * 2));
		}
	}

	/**
	 * Replaces the well-formed rejected term in the seek buffer with the smallest greater scalar term in the finite
	 * language. For an open-ended rejection, no accepted prefix can precede the rejected key, so the same successor
	 * is safe for both languages.
	 *
	 * @return true with the successor left in `seek[0..seekLength)`, or false when no greater accepted term exists,
	 * in which case the buffer holds no meaningful term
	 */
	private boolean nextString() {
		ensureCapacity(this.seekLength + 1);
		int position = 0;
		this.savedStates[0] = 0;
		while (true) {
			int state = this.savedStates[position];
			while (position < this.seekLength) {
				final int nextState = this.automaton.step(state, this.seek[position]);
				if (nextState < 0) {
					break;
				}
				this.savedStates[++position] = nextState;
				state = nextState;
			}
			if (nextStringFrom(state, position)) {
				return true;
			}
			position = backtrack(position);
			if (position < 0) {
				return false;
			}
			final int nextState = this.automaton.step(this.savedStates[position], this.seek[position]);
			if (nextState >= 0 && this.automaton.isAccept(nextState)) {
				return true;
			}
		}
	}

	/**
	 * Truncates the seek buffer to a surviving prefix and appends the smallest accepted continuation. While the prefix
	 * is shorter than the rejected term, the continuation must start strictly above the rejected character at that
	 * position; when the whole rejected term survived, any continuation qualifies.
	 *
	 * @param state automaton state after the surviving prefix
	 * @param position length of that prefix in code points
	 * @return whether an accepted continuation exists; when it does, the buffer holds the extended term
	 * @throws IllegalStateException when a live state has no accepting continuation, which an automaton without dead
	 * states never produces
	 */
	private boolean nextStringFrom(int state, int position) {
		final int lower = position < this.seekLength ? orderOf(this.seek[position]) + 1 : 0;
		this.seekLength = position;
		int label = smallestLabel(state, lower);
		if (label < 0) {
			return false;
		}
		while (true) {
			append(label);
			state = this.automaton.step(state, label);
			if (this.automaton.isAccept(state)) {
				return true;
			}
			label = smallestLabel(state, 0);
			if (label < 0) {
				throw new IllegalStateException("A finite Levenshtein automaton must have an accepting continuation.");
			}
		}
	}

	/**
	 * Finds the smallest transition label in UTF-16 string order, rather than code-point numeric order: scalars below
	 * `U+D800` first, then the supplementary scalars, then `U+E000` to `U+FFFF`. Surrogate code units are excluded as
	 * transition labels; the walk advances past malformed rejected terms linearly instead.
	 *
	 * @param state source automaton state
	 * @param lower inclusive lower bound in scalar UTF-16 order
	 * @return code point of the smallest label meeting the bound across all transitions of the state, which may lie
	 * inside a transition's range rather than at its minimum, or -1 when no label meets the bound
	 */
	private int smallestLabel(int state, int lower) {
		int best = Integer.MAX_VALUE;
		final int count = this.automaton.initTransition(state, this.transition);
		for (int i = 0; i < count; i++) {
			this.automaton.getNextTransition(this.transition);
			final int min = this.transition.min;
			final int max = this.transition.max;
			best = Math.min(best, intervalMinimum(min, max, 0, Character.MIN_SURROGATE - 1, 0, lower));
			best = Math.min(best, intervalMinimum(
				min, max, Character.MIN_SUPPLEMENTARY_CODE_POINT, Character.MAX_CODE_POINT, SUPPLEMENTARY_SHIFT, lower
			));
			best = Math.min(best, intervalMinimum(
				min, max, BMP_TAIL_START, Character.MAX_VALUE, BMP_TAIL_SHIFT, lower
			));
		}
		if (best == Integer.MAX_VALUE) {
			return -1;
		}
		return codePointOf(best);
	}

	/**
	 * Intersects a transition with a scalar interval and the required lower bound in UTF-16 order.
	 *
	 * @param min smallest code point of the transition
	 * @param max largest code point of the transition
	 * @param start first scalar code point in the interval
	 * @param end last scalar code point in the interval
	 * @param shift offset from code-point numeric order to UTF-16 order for this interval
	 * @param lower required inclusive lower bound in UTF-16 order
	 * @return smallest permitted order value, or `Integer.MAX_VALUE` for an empty intersection
	 */
	private static int intervalMinimum(int min, int max, int start, int end, int shift, int lower) {
		final int first = Math.max(Math.max(min, start) + shift, lower);
		final int last = Math.min(max, end) + shift;
		return first <= last ? first : Integer.MAX_VALUE;
	}

	/**
	 * Maps a scalar code point to its rank under UTF-16 string order. Scalars below `U+D800` keep their value,
	 * supplementary scalars move down by `0x2800` to follow them directly, and `U+E000` to `U+FFFF` move up by
	 * `0xFF800` to follow `U+10FFFF`, because their single code unit is greater than any leading surrogate.
	 *
	 * @param codePoint Unicode scalar value
	 * @return order value, with supplementary characters between the two BMP intervals
	 */
	private static int orderOf(int codePoint) {
		if (codePoint < Character.MIN_SURROGATE) {
			return codePoint;
		}
		if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT) {
			return codePoint + SUPPLEMENTARY_SHIFT;
		}
		return codePoint + BMP_TAIL_SHIFT;
	}

	/**
	 * Maps a rank under UTF-16 string order back to its scalar code point; the inverse of {@link #orderOf(int)}.
	 *
	 * @param order order value between zero and `MAX_ORDER`
	 * @return Unicode scalar value holding that rank
	 */
	private static int codePointOf(int order) {
		if (order < Character.MIN_SURROGATE) {
			return order;
		}
		if (order < BMP_TAIL_ORDER_START) {
			return order - SUPPLEMENTARY_SHIFT;
		}
		return order - BMP_TAIL_SHIFT;
	}

	/**
	 * Drops the exhausted tail of the seek buffer and increments in place, in UTF-16 order, the rightmost remaining
	 * scalar that is not already the last one.
	 *
	 * @param position first exhausted position of the seek buffer
	 * @return index of the incremented position, after which `seekLength` is that index plus one, or -1 when no
	 * position can be incremented and the language holds no greater term
	 */
	private int backtrack(int position) {
		while (position-- > 0) {
			final int codePoint = this.seek[position];
			final int next = orderOf(codePoint) + 1;
			if (next <= MAX_ORDER) {
				this.seek[position] = codePointOf(next);
				this.seekLength = position + 1;
				return position;
			}
		}
		return -1;
	}

	/**
	 * Appends one code point to the successor buffer.
	 *
	 * @param codePoint next code point of the candidate term
	 */
	private void append(int codePoint) {
		ensureCapacity(this.seekLength + 1);
		this.seek[this.seekLength++] = codePoint;
	}
}
