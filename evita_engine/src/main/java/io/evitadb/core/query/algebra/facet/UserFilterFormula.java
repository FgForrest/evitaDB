/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.NonCacheableFormulaScope;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * This formula has almost identical implementation as {@link AndFormula} but it accepts only set of
 * {@link Formula} as a children and allows containing even single child (on the contrary to the {@link AndFormula}).
 * The formula envelopes "user-defined" part of the formula tree that might be omitted for some computations and that's
 * the main purpose of this formula definition. Particularly the formula and its internals are omitted during initial
 * phase of the {@link FacetSummary} computation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UserFilterFormula extends AbstractFormula implements NonCacheableFormula, NonCacheableFormulaScope {
	private static final long CLASS_ID = 6890499931556487481L;
	private List<Formula> sortedFormulasByComplexity;

	public UserFilterFormula(Formula... innerFormulas) {
		super(innerFormulas);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new UserFilterFormula(innerFormulas);
	}

	@Override
	public long getOperationCost() {
		return 15;
	}

	@Override
	protected long getCostInternal() {
		long cost = 0L;
		if (this.sortedFormulasByComplexity == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
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
		if (this.sortedFormulasByComplexity == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
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
	public int getEstimatedCardinality() {
		return Arrays.stream(this.innerFormulas).mapToInt(Formula::getEstimatedCardinality).min().orElse(0);
	}

	@Override
	public String toString() {
		return "USER FILTER";
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
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
