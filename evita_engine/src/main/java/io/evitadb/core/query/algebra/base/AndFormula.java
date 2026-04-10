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

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * And formula will perform boolean conjunction (AND) on multiple bitmaps at once.
 * Example input:
 *
 * [1,    3, 4, 5, 8]
 * [1, 2,    4,    8]
 * [1, 2, 3, 4, 5]
 *
 * Produces output:
 *
 * [1, 4]
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AndFormula extends AbstractBitmapCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 2754438812730972016L;
	/**
	 * Lazily initialized list of inner formulas sorted by their estimated cost (cheapest first) to allow
	 * short-circuit evaluation during AND computation.
	 */
	private List<Formula> sortedFormulasByComplexity;

	AndFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Formula[] innerFormulas, long[] indexTransactionId, @Nullable Bitmap[] bitmaps) {
		super(computationCallback, indexTransactionId, bitmaps);
		Assert.isTrue(
			innerFormulas.length > 1 || Objects.requireNonNull(bitmaps).length > 1,
			"And formula has no sense with " + innerFormulas.length + " inner formulas / bitmaps!"
		);
		this.initFields(innerFormulas);
	}

	public AndFormula(@Nonnull Formula... innerFormulas) {
		super(null, null, null);
		Assert.isTrue(innerFormulas.length > 1, "And formula has no sense with " + innerFormulas.length + " inner formulas!");
		this.initFields(innerFormulas);
	}

	public AndFormula(long[] indexTransactionId, @Nonnull Bitmap... bitmaps) {
		super(null, indexTransactionId, bitmaps);
		Assert.isTrue(bitmaps.length > 1, "And formula has no sense with " + bitmaps.length + " inner bitmaps!");
		this.initFields();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0];
		} else {
			return new AndFormula(innerFormulas);
		}
	}

	@Override
	public int getEstimatedCardinality() {
		if (this.bitmaps == null) {
			return getMinEstimatedCardinality(this.innerFormulas);
		} else {
			if (this.bitmaps.length == 0) {
				return 0;
			}
			int min = this.bitmaps[0].size();
			for (int i = 1; i < this.bitmaps.length; i++) {
				final int size = this.bitmaps[i].size();
				if (size < min) {
					min = size;
				}
			}
			return min;
		}
	}

	@Override
	public long getOperationCost() {
		return 9;
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new AndFormula(
			selfOperator,
			innerFormulas,
			this.indexTransactionId,
			this.bitmaps
		);
	}

	@Override
	public void clearMemory() {
		super.clearMemory();
		this.sortedFormulasByComplexity = null;
	}

	@Override
	protected long getEstimatedBaseCost() {
		if (this.bitmaps == null) {
			return super.getEstimatedBaseCost();
		}
		long sum = 0L;
		for (final Bitmap bitmap : this.bitmaps) {
			sum += bitmap.size();
		}
		// multiply by operation cost: baseCost represents the full work on direct bitmap data
		// (scanning all elements and applying the AND operation to each). Without this multiplier,
		// the estimated cost would fall below actual cost because `getEstimatedCardinality()` returns
		// `min` (output bound), which underestimates the total per-element work of `sum * opCost`.
		return sum * getOperationCost();
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.bitmaps != null) {
			// bitmaps are iterated in array order — short-circuit on empty is a best-effort
			// optimization; callers are expected to filter empties during construction
			long cost = 0L;
			for (final Bitmap bitmap : this.bitmaps) {
				if (bitmap.isEmpty()) {
					break;
				}
				cost += bitmap.size() * getOperationCost();
			}
			return cost;
		} else {
			if (this.sortedFormulasByComplexity == null) {
				this.sortedFormulasByComplexity = sortFormulasByComplexity(getInnerFormulas());
			}
			return computeSortedConjunctionCost(this.sortedFormulasByComplexity, getOperationCost());
		}
	}

	@Override
	protected long getCostToPerformanceInternal() {
		if (this.bitmaps != null) {
			return getCost() / Math.max(1, compute().size());
		} else {
			if (this.sortedFormulasByComplexity == null) {
				this.sortedFormulasByComplexity = sortFormulasByComplexity(getInnerFormulas());
			}
			return computeSortedConjunctionCostToPerformance(this.sortedFormulasByComplexity)
				+ getCost() / Math.max(1, compute().size());
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return computeConjunctionResult(getRoaringBitmaps());
	}

	@Override
	public String toString() {
		if (ArrayUtils.isEmpty(this.bitmaps)) {
			return "AND";
		} else {
			final StringBuilder sb = new StringBuilder((this.bitmaps.length << 4) + 8);
			sb.append("AND: ");
			for (int i = 0; i < this.bitmaps.length; i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(this.bitmaps[i].toString());
			}
			return sb.toString();
		}
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private RoaringBitmap[] getRoaringBitmaps() {
		if (this.bitmaps != null) {
			final RoaringBitmap[] result = new RoaringBitmap[this.bitmaps.length];
			for (int i = 0; i < this.bitmaps.length; i++) {
				result[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(this.bitmaps[i]);
			}
			return result;
		} else {
			if (this.sortedFormulasByComplexity == null) {
				this.sortedFormulasByComplexity = sortFormulasByComplexity(getInnerFormulas());
			}
			return computeSortedConjunctionBitmaps(this.sortedFormulasByComplexity);
		}
	}

}
