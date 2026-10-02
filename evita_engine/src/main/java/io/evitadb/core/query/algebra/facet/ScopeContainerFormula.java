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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.translator.behavioral.FilterInScopeTranslator.InScopeFormulaPostProcessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * This formula has almost identical implementation as {@link AndFormula} but it accepts only set of
 * {@link Formula} as a children and allows containing even single child (on the contrary to the {@link AndFormula}).
 * The formula envelopes part with scope focused on single {@link Scope} and is used by {@link InScopeFormulaPostProcessor}
 * to create final formula tree consisting of multiple formula tree varants specific to selected scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ScopeContainerFormula extends AbstractCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractCacheableFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -5387565378948662756L;
	/**
	 * The scope that is used to filter the data.
	 */
	@Getter private final Scope scope;
	/**
	 * Array of transactional ids of the indexes that were used to build this formula, used for cache invalidation.
	 */
	private final long[] indexTransactionId;
	/**
	 * The formula of every entity of {@link #scope} the container stands for once all its inner formulas are removed
	 * by a clone, or NULL when an emptied container stands for nothing. A container built by
	 * {@link InScopeFormulaPostProcessor} restricts one scope of the query, so removing every constraint it holds -
	 * as the hierarchy statistics do with the hierarchy filter - leaves that scope unrestricted, not empty.
	 */
	@Nullable private final Formula emptyScopeFormula;
	/**
	 * Lazily initialized list of inner formulas sorted by their estimated cost in ascending order, used to
	 * short-circuit AND evaluation starting from the cheapest formula.
	 */
	private List<Formula> sortedFormulasByComplexity;

	public ScopeContainerFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Scope scope, @Nonnull Formula[] innerFormulas, @Nonnull long[] indexTransactionId) {
		this(computationCallback, scope, null, innerFormulas, indexTransactionId);
	}

	/**
	 * Creates the container with a computation callback and the formula an emptied clone stands for.
	 *
	 * @param computationCallback the callback of the computation
	 * @param scope               the scope the container restricts
	 * @param emptyScopeFormula   the formula of every entity of the scope, or NULL - see {@link #emptyScopeFormula}
	 * @param innerFormulas       the constraints of the scope
	 * @param indexTransactionId  the transactional ids of the indexes the formula was built from
	 */
	private ScopeContainerFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nonnull Scope scope,
		@Nullable Formula emptyScopeFormula,
		@Nonnull Formula[] innerFormulas,
		@Nullable long[] indexTransactionId
	) {
		super(computationCallback);
		this.scope = scope;
		this.emptyScopeFormula = emptyScopeFormula;
		this.indexTransactionId = indexTransactionId;
		this.initFields(innerFormulas);
	}

	public ScopeContainerFormula(@Nonnull Scope scope, @Nonnull Formula... innerFormulas) {
		this(null, scope, null, innerFormulas, null);
	}

	/**
	 * Creates the container of one scope of the query that stands for every entity of the scope once a clone removes
	 * all its inner formulas. A factory rather than a constructor, because a `(Scope, Formula, Formula)` constructor
	 * would capture every two-child call of {@link #ScopeContainerFormula(Scope, Formula...)}.
	 *
	 * @param scope             the scope the container restricts
	 * @param emptyScopeFormula the formula of every entity of the scope
	 * @param innerFormula      the constraint of the scope
	 * @return the container
	 */
	@Nonnull
	public static ScopeContainerFormula restrictingScope(
		@Nonnull Scope scope,
		@Nonnull Formula emptyScopeFormula,
		@Nonnull Formula innerFormula
	) {
		return new ScopeContainerFormula(null, scope, emptyScopeFormula, new Formula[]{innerFormula}, null);
	}

	@Override
	public void clearMemory() {
		super.clearMemory();
		this.sortedFormulasByComplexity = null;
	}

	@Override
	public int getEstimatedCardinality() {
		return getMinEstimatedCardinality(this.innerFormulas);
	}

	@Override
	public long getOperationCost() {
		return 9;
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new ScopeContainerFormula(
			selfOperator,
			this.scope,
			this.emptyScopeFormula,
			innerFormulas,
			this.indexTransactionId
		);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0L;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.sortedFormulasByComplexity == null) {
			this.sortedFormulasByComplexity = sortFormulasByComplexity(getInnerFormulas());
		}
		return computeSortedConjunctionCost(this.sortedFormulasByComplexity, getOperationCost());
	}

	@Override
	protected long getCostToPerformanceInternal() {
		if (this.sortedFormulasByComplexity == null) {
			this.sortedFormulasByComplexity = sortFormulasByComplexity(getInnerFormulas());
		}
		return computeSortedConjunctionCostToPerformance(this.sortedFormulasByComplexity)
			+ getCost() / Math.max(1, compute().size());
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		if (this.sortedFormulasByComplexity == null) {
			this.sortedFormulasByComplexity = sortFormulasByComplexity(getInnerFormulas());
		}
		return computeConjunctionResult(computeSortedConjunctionBitmaps(this.sortedFormulasByComplexity));
	}

	@Override
	public String toString() {
		return "SCOPE_CONTAINER(" + this.scope.name() + ")";
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return this.emptyScopeFormula == null ?
				EmptyFormula.INSTANCE :
				new ScopeContainerFormula(
					null, this.scope, this.emptyScopeFormula, new Formula[]{this.emptyScopeFormula}, null
				);
		}
		return new ScopeContainerFormula(null, this.scope, this.emptyScopeFormula, innerFormulas, null);
	}

}
