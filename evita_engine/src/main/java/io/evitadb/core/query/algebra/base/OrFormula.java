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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * And formula will perform boolean disjunction (OR) on multiple bitmaps at once.
 * Example input:
 *
 * [1,    3, 4, 5, 8]
 * [1, 2,    4,    8]
 * [1, 2, 3, 4, 5]
 *
 * Produces output:
 *
 * [1, 2, 3, 4, 5, 8]
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class OrFormula extends AbstractBitmapCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -7493244674442362190L;

	OrFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Formula[] innerFormulas, long[] indexTransactionId, @Nullable Bitmap[] bitmaps) {
		super(computationCallback, indexTransactionId, bitmaps);
		Assert.isTrue(
			innerFormulas.length > 1 || Objects.requireNonNull(bitmaps).length > 1,
			"Or formula has no sense with " + innerFormulas.length + " inner formulas / bitmaps!"
		);
		this.initFields(innerFormulas);
	}

	public OrFormula(@Nonnull Formula... innerFormulas) {
		super(null, null, null);
		Assert.isTrue(innerFormulas.length > 1, "Or formula has no sense with " + innerFormulas.length + " inner formulas!");
		this.initFields(innerFormulas);
	}

	public OrFormula(long[] indexTransactionId, @Nonnull Bitmap... bitmaps) {
		super(null, indexTransactionId, bitmaps);
		Assert.isTrue(bitmaps.length > 1, "Or formula has no sense with " + bitmaps.length + " inner bitmaps!");
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
			return new OrFormula(innerFormulas);
		}
	}

	@Override
	public long getOperationCost() {
		return 13;
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new OrFormula(
			selfOperator,
			innerFormulas,
			this.indexTransactionId,
			this.bitmaps
		);
	}

	@Override
	protected long getEstimatedBaseCost() {
		if (this.bitmaps == null) {
			return super.getEstimatedBaseCost();
		}
		long sum = 0L;
		for (int i = 0; i < this.bitmaps.length; i++) {
			sum += this.bitmaps[i].size();
		}
		return sum;
	}

	@Override
	public int getEstimatedCardinality() {
		if (this.bitmaps == null) {
			int sum = 0;
			for (int i = 0; i < this.innerFormulas.length; i++) {
				sum += this.innerFormulas[i].getEstimatedCardinality();
			}
			return sum;
		} else {
			int sum = 0;
			for (int i = 0; i < this.bitmaps.length; i++) {
				sum += this.bitmaps[i].size();
			}
			return sum;
		}
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.bitmaps == null) {
			return super.getCostInternal();
		}
		long sum = 0L;
		for (int i = 0; i < this.bitmaps.length; i++) {
			sum += this.bitmaps[i].size();
		}
		return sum;
	}

	@Override
	public String toString() {
		if (ArrayUtils.isEmpty(this.bitmaps)) {
			return "OR";
		} else {
			final StringBuilder sb = new StringBuilder(this.bitmaps.length * 16 + 8);
			sb.append("OR: ");
			for (int i = 0; i < this.bitmaps.length; i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(this.bitmaps[i].toString());
			}
			return sb.toString();
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap theResult;
		final RoaringBitmap[] theBitmaps = getRoaringBitmaps();
		if (theBitmaps.length == 0) {
			theResult = EmptyBitmap.INSTANCE;
		} else if (theBitmaps.length == 1) {
			theResult = new BaseBitmap(theBitmaps[0]);
		} else {
			theResult = new BaseBitmap(RoaringBitmap.or(theBitmaps));
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
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
			final Formula[] formulas = getInnerFormulas();
			final RoaringBitmap[] result = new RoaringBitmap[formulas.length];
			for (int i = 0; i < formulas.length; i++) {
				result[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(formulas[i].compute());
			}
			return result;
		}
	}

}
