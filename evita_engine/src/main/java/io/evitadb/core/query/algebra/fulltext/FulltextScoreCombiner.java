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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor.SourceIdentity;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.FulltextScoringPolicy.Place;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;

/** Combines complete, candidate-aligned source vectors before any page selection. */
public final class FulltextScoreCombiner {
	/** Utility class. */
	private FulltextScoreCombiner() { }

	/**
	 * Finds owner-space sources without crossing a key-space translating accessor, then groups copies by their
	 * structural search identity. A wrapper retains the SHALLOW delegation contract used during scoring.
	 *
	 * @param formula the filter formula whose scoring sources are looked up
	 * @return the sources in discovery order, copies of one search collapsed under its identity
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a source that provides scores has no identity
	 */
	@Nonnull
	public static Map<SourceIdentity, FulltextScoreAccessor> findSources(@Nonnull Formula formula) {
		final Map<SourceIdentity, FulltextScoreAccessor> sources = new LinkedHashMap<>();
		for (final FulltextScoreAccessor accessor : FormulaFinder.find(
			formula, FulltextScoreAccessor.class, LookUp.SHALLOW
		)) {
			if (accessor.providesFulltextScores()) {
				final SourceIdentity identity = accessor.getFulltextSourceIdentity();
				Assert.isPremiseValid(identity != null,
					"A formula-tree text source needs its structural query identity!");
				sources.merge(identity, accessor, FulltextScoreCombiner::collapsePair);
			}
		}
		return sources;
	}

	/**
	 * Merges structurally keyed sources without changing the masks or preparation of either input.
	 *
	 * @param sources the sources collected so far, modified in place: a key already present collapses with its copy
	 * @param copies the sources to add, only read
	 */
	public static void mergeSources(
		@Nonnull Map<Object, FulltextScoreAccessor> sources, @Nonnull Map<?, FulltextScoreAccessor> copies
	) {
		for (final Map.Entry<?, FulltextScoreAccessor> copy : copies.entrySet()) {
			sources.merge(copy.getKey(), copy.getValue(), FulltextScoreCombiner::collapsePair);
		}
	}

	/**
	 * Combines sources by best quality and best strength + t × other strengths. A source contributes strength
	 * regardless of its quality. Only one source vector is retained at a time, with no primary-key map.
	 * @param candidates sorted distinct primary keys in the collection being ranked
	 * @param accessors independent score sources in that primary-key space
	 * @param policy the same effective tie breaker used inside each source
	 * @param weights overrides of the relevance ordering being scored
	 * @return every candidate and its combined composite, aligned with the input
	 */
	@Nonnull
	public static Result combine(
		@Nonnull int[] candidates, @Nonnull FulltextScoreAccessor[] accessors, @Nonnull FulltextScoringPolicy policy,
		@Nonnull Map<Place, Float> weights
	) {
		if (accessors.length == 1) {
			// The rule is the identity for one source; retain its aligned vector without more candidate arrays.
			return accessors[0].getFulltextScores(candidates, weights);
		}
		final long[] composites = new long[candidates.length];
		final char[] best = new char[candidates.length];
		final long[] sums = new long[candidates.length];
		long postingsWalked = 0;
		for (final FulltextScoreAccessor accessor : accessors) {
			final Result scores = accessor.getFulltextScores(candidates, weights);
			Assert.isPremiseValid(scores.composites().length == candidates.length,
				"Fulltext source scores must align with every candidate!");
			postingsWalked += scores.postingsWalked();
			for (int i = 0; i < candidates.length; i++) {
				final long composite = scores.composites()[i];
				final long quality = composite & FulltextScoringPolicy.QUALITY_MASK;
				if (Long.compareUnsigned(quality, composites[i]) > 0) {
					composites[i] = quality;
				}
				final int strength = FulltextScoringPolicy.strength(composite);
				best[i] = (char) Math.max(best[i], strength);
				sums[i] += strength;
			}
		}
		int matched = 0;
		for (int i = 0; i < candidates.length; i++) {
			if (composites[i] != 0) {
				matched++;
				composites[i] |= (long) policy.combine(best[i], sums[i]) << FulltextScoringPolicy.STRENGTH_SHIFT;
			}
		}
		return new Result(candidates, composites, matched, postingsWalked);
	}

	/**
	 * Collapses copies of one structurally identified search into best quality and best strength, never their sum.
	 * Formula-tree callers group by query origin, collection, locale and reference context; mapped-reference callers
	 * retain their existing reference, mapping-kind and origin keys. Each copy applies its own place weights once.
	 * @param accessors copies of one searched source, never empty
	 * @return the only accessor itself, or an accessor returning best quality and best strength across the copies
	 */
	@Nonnull
	public static FulltextScoreAccessor collapseSources(@Nonnull FulltextScoreAccessor[] accessors) {
		Assert.isPremiseValid(accessors.length > 0, "A searched source needs at least one copy!");
		return accessors.length == 1 ? accessors[0] : new CollapsedSources(accessors.clone());
	}

	/**
	 * Collapses two sources registered under one structural key. The same accessor reached twice - a memoized formula
	 * shared by the main filter and a segment predicate - stays itself: collapsing a source with itself yields its own
	 * composites, so scoring it twice would only repeat the work.
	 *
	 * @param first the source already registered under the key
	 * @param second the source arriving under the same key
	 * @return `first` when both are the same accessor, otherwise their best-quality, best-strength collapse
	 */
	@Nonnull
	private static FulltextScoreAccessor collapsePair(
		@Nonnull FulltextScoreAccessor first, @Nonnull FulltextScoreAccessor second
	) {
		return first == second ? first : collapseSources(new FulltextScoreAccessor[]{first, second});
	}

	/**
	 * Merges the composites of two copies of one place into the better quality and the larger strength, each taken on
	 * its own. The merge is symmetric and never adds strengths.
	 * @param first composite of one copy, `0` when it does not match
	 * @param second composite of another copy, `0` when it does not match
	 * @return the better quality lanes with the larger strength
	 */
	static long mergeBest(long first, long second) {
		final long firstQuality = first & FulltextScoringPolicy.QUALITY_MASK;
		final long secondQuality = second & FulltextScoringPolicy.QUALITY_MASK;
		final long quality = Long.compareUnsigned(firstQuality, secondQuality) > 0 ? firstQuality : secondQuality;
		final int strength = Math.max(FulltextScoringPolicy.strength(first), FulltextScoringPolicy.strength(second));
		return quality | ((long) strength << FulltextScoringPolicy.STRENGTH_SHIFT);
	}

	/**
	 * Candidate-aligned best-only view of one searched source.
	 * @param accessors copies grouped by their structural source identity
	 */
	private record CollapsedSources(@Nonnull FulltextScoreAccessor[] accessors) implements FulltextScoreAccessor {
		@Override
		public boolean providesFulltextScores() {
			return true;
		}

		/** Collapses copies using the requesting ordering's weights without adding their strengths. */
		@Nonnull
		@Override
		public Result getFulltextScores(
			@Nonnull int[] candidates, @Nonnull Map<Place, Float> weights
		) {
			final long[] composites = new long[candidates.length];
			long walked = 0;
			for (final FulltextScoreAccessor accessor : this.accessors) {
				final Result scores = accessor.getFulltextScores(candidates, weights);
				walked += scores.postingsWalked();
				for (int i = 0; i < candidates.length; i++) {
					composites[i] = mergeBest(scores.composites()[i], composites[i]);
				}
			}
			int matched = 0;
			for (final long composite : composites) {
				matched += composite == 0 ? 0 : 1;
			}
			return new Result(candidates, composites, matched, walked);
		}
	}

}
