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
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Or formula will perform boolean disjunction (OR) on multiple bitmaps at once.
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
		for (final Bitmap bitmap : this.bitmaps) {
			sum += bitmap.size();
		}
		return sum;
	}

	@Override
	public int getEstimatedCardinality() {
		int sum = 0;
		if (this.bitmaps == null) {
			for (final Formula innerFormula : this.innerFormulas) {
				sum += innerFormula.getEstimatedCardinality();
			}
		} else {
			for (final Bitmap bitmap : this.bitmaps) {
				sum += bitmap.size();
			}
		}
		return sum;
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
		for (final Bitmap bitmap : this.bitmaps) {
			sum += bitmap.size();
		}
		return sum * getOperationCost();
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
		final PersistentRoaringBitmap[] theBitmaps = getRoaringBitmaps();
		if (theBitmaps.length == 0) {
			theResult = EmptyBitmap.INSTANCE;
		} else if (theBitmaps.length == 1) {
			theResult = new BaseBitmap(theBitmaps[0]);
		} else {
			theResult = new BaseBitmap(PersistentRoaringBitmap.or(theBitmaps));
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private PersistentRoaringBitmap[] getRoaringBitmaps() {
		if (this.bitmaps != null) {
			return toRoaringBitmapsFoldingSingles(this.bitmaps);
		} else {
			final Formula[] formulas = getInnerFormulas();
			final PersistentRoaringBitmap[] result = new PersistentRoaringBitmap[formulas.length];
			for (int i = 0; i < formulas.length; i++) {
				result[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(formulas[i].compute());
			}
			return result;
		}
	}

	/**
	 * Converts the operand bitmaps to Roaring form, folding every {@link SingleRecordBitmap} operand into ONE bitmap
	 * on the way.
	 *
	 * A single-record operand is not Roaring-backed, so the plain conversion builds it a one-element array, a bitmap,
	 * a container and that container's backing array - four allocations to carry one `int`, and then the union has one
	 * more operand to merge. An inverted index whose values are near-unique produces a fold that is almost entirely
	 * such operands: a substring match over an identifier-like attribute can nominate five figures of them in one
	 * query, so the difference is the bulk of what computing this formula costs.
	 *
	 * **This changes only how the operands are combined, never which operands there are.** The formula's own
	 * {@link #bitmaps} array is untouched, so its hash, its cached identity and the transactional ids it gathers are
	 * exactly what they were - the fold happens after all of that, on the way into the union.
	 *
	 * Left alone when fewer than two operands are single-record: one such operand costs the same either way, and
	 * folding it would only move the allocation around.
	 *
	 * @param bitmaps the operand bitmaps
	 * @return the operands in Roaring form, with the single-record ones merged into the first entry
	 */
	@Nonnull
	private static PersistentRoaringBitmap[] toRoaringBitmapsFoldingSingles(@Nonnull Bitmap[] bitmaps) {
		int singleCount = 0;
		for (final Bitmap bitmap : bitmaps) {
			if (bitmap instanceof SingleRecordBitmap) {
				singleCount++;
			}
		}
		if (singleCount < 2) {
			final PersistentRoaringBitmap[] result = new PersistentRoaringBitmap[bitmaps.length];
			for (int i = 0; i < bitmaps.length; i++) {
				result[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(bitmaps[i]);
			}
			return result;
		}
		final int[] singleRecordIds = new int[singleCount];
		final PersistentRoaringBitmap[] result = new PersistentRoaringBitmap[bitmaps.length - singleCount + 1];
		int singlePos = 0;
		int resultPos = 1;
		for (final Bitmap bitmap : bitmaps) {
			if (bitmap instanceof final SingleRecordBitmap single) {
				singleRecordIds[singlePos++] = single.getRecordId();
			} else {
				result[resultPos++] = RoaringBitmapBackedBitmap.getRoaringBitmap(bitmap);
			}
		}
		// sorted purely for the build: `bitmapOf` accepts any order and tolerates the duplicates an array-valued
		// attribute produces when one record sits in several matched buckets, but ascending input lets it append to
		// the container it is filling instead of binary-searching an insertion point per id.
		//
		// Ascending in ROARING's order, which is unsigned - a record id is an `int` and nothing on the way in rules
		// out a negative one. Signed-sorted, the negatives would arrive as the LOWEST ids and be filled into the
		// highest containers, so every subsequent container would be inserted at the front of the container array and
		// shift the whole of it. How the unsigned order is reached without boxing is `ArrayUtils#sortUnsigned`.
		// (`PersistentRoaringBitmap#bitmapOfUnordered` skips the sort but buffers 1024 words per call - a loss here.)
		ArrayUtils.sortUnsigned(singleRecordIds);
		result[0] = PersistentRoaringBitmap.bitmapOf(singleRecordIds);
		return result;
	}

}
