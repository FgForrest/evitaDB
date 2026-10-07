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

import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;

import javax.annotation.Nonnull;

/**
 * Marks a formula that can rank entities by fulltext relevance — the formula a fulltext condition translated into,
 * and every wrapper that can sit above one.
 *
 * It follows the two-phase pattern of {@link FilteredPriceRecordAccessor}: the planner is to find the accessors in
 * the filter tree through {@link FormulaFinder#find} in {@link LookUp#SHALLOW} mode, and the sorter to pull the
 * scores out only when it orders the final result. Neither consumer exists yet — the query side of fulltext is still
 * to come — so this is the contract they will be written against. The score is keyed by primary key, not read from
 * the entity body, so the same accessor serves the index path and the prefetch path alike.
 *
 * **Every wrapper that can sit above a fulltext formula must implement this interface too.** A `SHALLOW` lookup
 * does not descend into a node it has matched, and with prefetch on the planner wraps translated formulas in
 * {@link SelectionFormula} and {@link EntityFilteringFormula} — both implement this interface, so the lookup stops
 * at them, as the price lookup stops at them because they implement {@link FilteredPriceRecordAccessor}. A wrapper
 * that did not delegate this interface inwards would hide the fulltext formula, and relevance would silently degrade
 * to no ordering exactly when the planner chose prefetch. A wrapper implements it unconditionally and answers
 * {@link #providesFulltextScores()} by what it actually wraps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface FulltextScoreAccessor {

	/**
	 * Returns whether this accessor has fulltext scores to give. A fulltext formula always has; a wrapper has only
	 * when it wraps one, so a sorter skips the wrappers that answer `false` rather than treating them as matching
	 * nothing.
	 *
	 * @return true when {@link #getFulltextScores(int[], int)} may be called
	 */
	boolean providesFulltextScores();

	/**
	 * Scores the candidates against the fulltext query this accessor represents and returns the top N, best first.
	 * A candidate is selected when it contains at least one query token — it need not be in the strict match set of
	 * the fulltext formula, and ranks below the candidates matching more tokens. Candidates matching no token are
	 * never selected.
	 *
	 * @param candidates primary keys of the entities to rank — typically the final result of the whole filter —
	 *                   ascending and distinct
	 * @param topN       how many entities to return, at least one
	 * @return the selected entities and their composite scores, as {@link FulltextPhaseOneScorer} defines them
	 * @throws io.evitadb.exception.GenericEvitaInternalError when {@link #providesFulltextScores()} is false
	 */
	@Nonnull
	Result getFulltextScores(@Nonnull int[] candidates, int topN);

}
