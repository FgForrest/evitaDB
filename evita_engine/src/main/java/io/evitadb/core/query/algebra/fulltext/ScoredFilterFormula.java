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

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor.SourceIdentity;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retains a segment predicate's membership and all its score sources together across computeOnlyOnce hits.
 * Additional owner-space sources are otherwise absent from the formula tree. The wrapper is never flattened
 * into a cache bitmap, and weights are supplied by each segment's sorter only when it requests scores.
 */
public final class ScoredFilterFormula extends AbstractFormula implements NonCacheableFormula {
	/** Generated structural formula identity. */
	private static final long CLASS_ID = -4912435561419412826L;
	/** Sources absent from the membership tree, retained for cloning. */
	@Nonnull private final Map<?, FulltextScoreAccessor> additionalSources;
	/** Structurally distinct formula-tree sources in the predicate's owner space. */
	@Nonnull private final Map<SourceIdentity, FulltextScoreAccessor> accessors;

	/**
	 * Captures score sources before the predicate visitor is discarded, without evaluating any score.
	 *
	 * @param membership the translated predicate, whose formula-tree sources are found and grouped here
	 * @param additionalSources reference sources the predicate's visitor registered outside the formula tree, keyed
	 *                          by their opaque structural place keys
	 */
	public ScoredFilterFormula(
		@Nonnull Formula membership, @Nonnull Map<?, FulltextScoreAccessor> additionalSources
	) {
		this.additionalSources = Collections.unmodifiableMap(new LinkedHashMap<>(additionalSources));
		this.accessors = Collections.unmodifiableMap(FulltextScoreCombiner.findSources(membership));
		initFields(membership);
	}

	/**
	 * Returns the independent sources to combine beside the main filter's sources, without nested combination.
	 *
	 * @return unmodifiable formula-tree sources of the predicate, one per structural search identity
	 */
	@Nonnull
	public Map<SourceIdentity, FulltextScoreAccessor> getFulltextScoreSources() {
		return this.accessors;
	}

	/**
	 * Returns additional owner-space sources with their opaque structural identities for cross-filter collapse.
	 *
	 * @return unmodifiable reference sources keyed like those of the main filter, so equal places collapse
	 */
	@Nonnull
	public Map<?, FulltextScoreAccessor> getAdditionalFulltextScoreSources() {
		return this.additionalSources;
	}

	/** Preserves additional score sources when an optimizer clones the membership subtree. */
	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "A scored filter needs one membership formula!");
		return new ScoredFilterFormula(innerFormulas[0], this.additionalSources);
	}

	/** Delegates the membership estimate without evaluating scores. */
	@Override
	public int getEstimatedCardinality() {
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	/** Membership delegation costs no additional bitmap operation. */
	@Override
	public long getOperationCost() {
		return 0;
	}

	/** Membership identity comes from the predicate subtree; ordering weights never affect it. */
	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0L;
	}

	/** Returns the generated formula identity. */
	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	/** Computes membership only; score vectors are never part of this memo. */
	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return this.innerFormulas[0].compute();
	}
}
