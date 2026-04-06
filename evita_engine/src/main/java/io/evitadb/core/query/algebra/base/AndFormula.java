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
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
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
			if (this.innerFormulas.length == 0) {
				return 0;
			}
			int min = this.innerFormulas[0].getEstimatedCardinality();
			for (int i = 1; i < this.innerFormulas.length; i++) {
				final int cardinality = this.innerFormulas[i].getEstimatedCardinality();
				if (cardinality < min) {
					min = cardinality;
				}
			}
			return min;
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

	@Nonnull
	@Override
	protected long[] gatherBitmapIdsInternal() {
		if (this.bitmaps == null) {
			// collect all transactional IDs from inner formulas, then deduplicate
			final long[] collected = super.gatherBitmapIdsInternal();
			Arrays.sort(collected);
			int unique = 0;
			for (int i = 0; i < collected.length; i++) {
				if (i == 0 || collected[i] != collected[i - 1]) {
					collected[unique++] = collected[i];
				}
			}
			return unique == collected.length ? collected : Arrays.copyOf(collected, unique);
		}
		return super.gatherBitmapIdsInternal();
	}

	@Override
	protected long getEstimatedBaseCost() {
		if (this.bitmaps == null) {
			return super.getEstimatedBaseCost();
		}
		if (this.bitmaps.length == 0) {
			return 0L;
		}
		long min = this.bitmaps[0].size();
		for (int i = 1; i < this.bitmaps.length; i++) {
			final long size = this.bitmaps[i].size();
			if (size < min) {
				min = size;
			}
		}
		return min * getOperationCost();
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.bitmaps != null) {
			long cost = 0L;
			for (final Bitmap bitmap : this.bitmaps) {
				if (bitmap == EmptyBitmap.INSTANCE) {
					break;
				}
				cost += bitmap.size() * getOperationCost();
			}
			return cost;
		} else {
			long cost = 0L;
			for (final Formula innerFormula : this.sortedFormulasByComplexity) {
				final Bitmap innerResult = innerFormula.compute();
				cost += innerFormula.getCost() + innerResult.size() * getOperationCost();
				if (innerResult == EmptyBitmap.INSTANCE) {
					break;
				}
			}
			return cost;
		}
	}

	@Override
	protected long getCostToPerformanceInternal() {
		if (this.bitmaps != null) {
			return getCost() / Math.max(1, compute().size());
		} else {
			long costToPerformance = 0L;
			for (final Formula innerFormula : this.sortedFormulasByComplexity) {
				final Bitmap innerResult = innerFormula.compute();
				if (innerResult == EmptyBitmap.INSTANCE) {
					break;
				}
				costToPerformance += innerFormula.getCostToPerformanceRatio();
			}
			return costToPerformance + getCost() / Math.max(1, compute().size());
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap theResult;
		final RoaringBitmap[] theBitmaps = getRoaringBitmaps();
		boolean hasEmpty = theBitmaps.length == 0;
		if (!hasEmpty) {
			for (final RoaringBitmap theBitmap : theBitmaps) {
				if (theBitmap.isEmpty()) {
					hasEmpty = true;
					break;
				}
			}
		}
		if (hasEmpty) {
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
				final Formula[] formulas = getInnerFormulas();
				final Formula[] sorted = new Formula[formulas.length];
				System.arraycopy(formulas, 0, sorted, 0, formulas.length);
				Arrays.sort(sorted, Comparator.comparingLong(TransactionalDataRelatedStructure::getEstimatedCost));
				this.sortedFormulasByComplexity = List.of(sorted);
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

}
