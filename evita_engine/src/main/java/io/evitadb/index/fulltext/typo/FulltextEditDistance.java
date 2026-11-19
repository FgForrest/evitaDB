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

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Reusable three-row optimal string alignment workspace, independent of the accepting automaton. The query and
 * rows are retained once; every distance call resets its initial row and overwrites the other rows before use.
 * Results depend only on the query and current arguments, never on earlier calls. Calls must be sequential.
 */
@NotThreadSafe
final class FulltextEditDistance {
	/** Read-only code points of the query this workspace serves. */
	private final int[] query;
	/** Scratch row for a transposition's two-position lookback. */
	private final int[] twoBack;
	/** Scratch row initialized to the empty-key distances at the start of every call. */
	private final int[] previous;
	/** Scratch row overwritten while consuming the next key code point. */
	private final int[] current;

	/**
	 * Allocates the three rows once for a query. The caller must keep the supplied query array unchanged.
	 *
	 * @param query read-only code points of the bare query term
	 */
	FulltextEditDistance(@Nonnull int[] query) {
		this.query = query;
		this.twoBack = new int[query.length + 1];
		this.previous = new int[query.length + 1];
		this.current = new int[query.length + 1];
	}

	/**
	 * Computes restricted Damerau distance over Unicode code points without allocating rows. The open end minimizes
	 * over all key prefixes, including the empty one; acceptance and the frozen prefix are the walker's responsibility.
	 * Open-ended evaluation stops when zero is reached or when the consumed key length exceeds the query length by at
	 * least the best distance so far: that excess is a lower bound on the distance of every longer prefix, so none of
	 * them can improve. The rows rotate through local references only, so the fields always keep three distinct rows
	 * and an early return needs no restoring.
	 *
	 * @param key dictionary term without its field prefix
	 * @param transpositions whether adjacent swaps cost one edit
	 * @param openEnd whether any suffix of the key is free
	 * @return exact distance to the whole key, or minimum distance to one of its prefixes
	 */
	int distance(@Nonnull CharSequence key, boolean transpositions, boolean openEnd) {
		final int[] query = this.query;
		final int queryLength = query.length;
		int best = queryLength;
		if (openEnd && best == 0) {
			return 0;
		}
		int[] twoBack = this.twoBack;
		int[] previous = this.previous;
		int[] current = this.current;
		for (int i = 0; i <= queryLength; i++) {
			previous[i] = i;
		}
		int previousCodePoint = -1;
		int keyLength = 0;
		for (int offset = 0; offset < key.length();) {
			final int codePoint = Character.codePointAt(key, offset);
			current[0] = ++keyLength;
			for (int i = 1; i <= queryLength; i++) {
				final int substitution = previous[i - 1] + (query[i - 1] == codePoint ? 0 : 1);
				int edits = Math.min(substitution, Math.min(previous[i] + 1, current[i - 1] + 1));
				if (transpositions && i > 1 && keyLength > 1 && query[i - 1] == previousCodePoint
					&& query[i - 2] == codePoint) {
					edits = Math.min(edits, twoBack[i - 2] + 1);
				}
				current[i] = edits;
			}
			best = Math.min(best, current[queryLength]);
			if (openEnd && (best == 0 || keyLength - queryLength >= best)) {
				return best;
			}
			final int[] recycled = twoBack;
			twoBack = previous;
			previous = current;
			current = recycled;
			previousCodePoint = codePoint;
			offset += Character.charCount(codePoint);
		}
		return openEnd ? best : previous[queryLength];
	}
}
