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
	 * Lazily initialized list of inner formulas sorted by their estimated cost in ascending order, used to
	 * short-circuit AND evaluation starting from the cheapest formula.
	 */
	private List<Formula> sortedFormulasByComplexity;

	public ScopeContainerFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Scope scope, @Nonnull Formula[] innerFormulas, @Nonnull long[] indexTransactionId) {
		super(computationCallback);
		this.scope = scope;
		this.indexTransactionId = indexTransactionId;
		this.initFields(innerFormulas);
	}

	public ScopeContainerFormula(@Nonnull Scope scope, @Nonnull Formula... innerFormulas) {
		super(null);
		this.scope = scope;
		this.indexTransactionId = null;
		this.initFields(innerFormulas);
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
			innerFormulas,
			this.indexTransactionId
		);
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		return sortAndDeduplicateLongArray(super.gatherBitmapIdsInternal());
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
			return EmptyFormula.INSTANCE;
		}
		return new ScopeContainerFormula(this.scope, innerFormulas);
	}

}
