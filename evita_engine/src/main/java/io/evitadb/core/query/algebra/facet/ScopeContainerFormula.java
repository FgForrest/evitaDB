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
import io.evitadb.core.query.filter.translator.behavioral.FilterInScopeTranslator.InScopeFormulaPostProcessor;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;

/**
 * This formula has almost identical implementation as {@link AndFormula} but it accepts only set of
 * {@link Formula} as a children and allows containing even single child (on the contrary to the {@link AndFormula}).
 * The formula envelopes part with scope focused on single {@link Scope} and is used by {@link InScopeFormulaPostProcessor}
 * to create final formula tree consisting of multiple formula tree varants specific to selected scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ScopeContainerFormula extends AbstractCacheableFormula {
	private static final long CLASS_ID = -5387565378948662756L;
	/**
	 * The scope that is used to filter the data.
	 */
	@Getter private final Scope scope;
	private final long[] indexTransactionId;
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
	public int getEstimatedCardinality() {
		return Arrays.stream(this.innerFormulas).mapToInt(Formula::getEstimatedCardinality).min().orElse(0);
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
		return Arrays.stream(this.innerFormulas)
			.flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
			.distinct()
			.toArray();
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
		long cost = 0L;
		for (Formula innerFormula : this.sortedFormulasByComplexity) {
			final Bitmap innerResult = innerFormula.compute();
			cost += innerFormula.getCost() + innerResult.size() * getOperationCost();
			if (innerResult == EmptyBitmap.INSTANCE) {
				break;
			}
		}
		return cost;
	}

	@Override
	protected long getCostToPerformanceInternal() {
		long costToPerformance = 0L;
		for (Formula innerFormula : this.sortedFormulasByComplexity) {
			final Bitmap innerResult = innerFormula.compute();
			if (innerResult == EmptyBitmap.INSTANCE) {
				break;
			}
			costToPerformance += innerFormula.getCostToPerformanceRatio();
		}
		return costToPerformance + getCost() / Math.max(1, compute().size());
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap theResult;
		final RoaringBitmap[] theBitmaps = getRoaringBitmaps();
		if (theBitmaps.length == 0 || Arrays.stream(theBitmaps).anyMatch(RoaringBitmap::isEmpty)) {
			theResult = EmptyBitmap.INSTANCE;
		} else if (theBitmaps.length == 1) {
			theResult = new BaseBitmap(theBitmaps[0]);
		} else {
			theResult = RoaringBitmapBackedBitmap.and(theBitmaps);
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

	@Override
	public String toString() {
		return "SCOPE_CONTAINER(" + this.scope.name() + ")";
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new ScopeContainerFormula(this.scope, innerFormulas);
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private RoaringBitmap[] getRoaringBitmaps() {
		if (this.sortedFormulasByComplexity == null) {
			this.sortedFormulasByComplexity = Arrays.stream(getInnerFormulas())
				.sorted(Comparator.comparingLong(TransactionalDataRelatedStructure::getEstimatedCost))
				.toList();
		}
		final RoaringBitmap[] theBitmaps = new RoaringBitmap[this.sortedFormulasByComplexity.size()];
		// go from the cheapest formula to the more expensive and compute one by one
		for (int i = 0; i < this.sortedFormulasByComplexity.size(); i++) {
			final Formula formula = this.sortedFormulasByComplexity.get(i);
			final Bitmap computedBitmap = formula.compute();
			// if you encounter formula that returns nothing immediately return nothing - hence AND
			if (computedBitmap.isEmpty()) {
				return new RoaringBitmap[0];
			} else {
				theBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(computedBitmap);
			}
		}
		return theBitmaps;
	}

}
