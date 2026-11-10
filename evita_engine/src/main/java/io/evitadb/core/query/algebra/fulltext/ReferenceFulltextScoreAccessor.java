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

package io.evitadb.core.query.algebra.fulltext;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;

import javax.annotation.Nonnull;

/**
 * Scores a direct reference text place with reference-attribute union impacts, masked to owners matched by
 * positive occurrences. Odd negation depth contributes no owners; double negation contributes normally.
 * Several visited partitions accumulate one mask. Reference attribute weights are already in the shared union
 * preparation; this source applies no referenced-entity weight and never truncates the candidate array.
 */
public final class ReferenceFulltextScoreAccessor implements FulltextScoreAccessor {
	/** Shared rank-time preparation, hidden from the formula tree. */
	@Nonnull private final FulltextFormula union;
	/** Positive text matches in the actual rows evaluated by this visitor. */
	@Nonnull private final BaseBitmap matches = new BaseBitmap();

	/**
	 * Creates one scope copy of a direct reference text place.
	 * @param membership scope's memoized reference membership and union preparation
	 */
	public ReferenceFulltextScoreAccessor(@Nonnull ReferenceFulltextFormula membership) {
		this.union = membership.getUnion();
	}

	/**
	 * Accumulates positive matches from one actual materialized or virtual row subset.
	 * @param owners owners matched by a positive occurrence; callers exclude occurrences under odd negation depth
	 */
	public void include(@Nonnull Bitmap owners) {
		this.matches.addAll(owners.getArray());
	}

	/**
	 * Accumulates one virtual owner's positive match without a temporary bitmap.
	 * @param owner positive matched owner
	 */
	public void include(int owner) {
		this.matches.add(owner);
	}

	/**
	 * Merges another registration of this same place and scope without amplifying its strength.
	 * @param other another visit to rows of the same place
	 */
	public void merge(@Nonnull ReferenceFulltextScoreAccessor other) {
		include(other.matches);
	}

	@Override
	public boolean providesFulltextScores() {
		return true;
	}

	@Nonnull
	@Override
	public Result getFulltextScores(@Nonnull int[] candidates) {
		final Result scores = this.union.getFulltextScores(candidates);
		final long[] masked = scores.composites().clone();
		int matched = 0;
		for (int i = 0; i < candidates.length; i++) {
			if (!this.matches.contains(candidates[i])) {
				masked[i] = 0L;
			}
			if (masked[i] != 0L) {
				matched++;
			}
		}
		return new Result(candidates, masked, matched, scores.postingsWalked());
	}
}
