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

import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Not formula will perform boolean negation (NOT) on two bitmaps: superset and subtracted one
 * Example input:
 *
 * superset:   [   2, 3, 4, 5, 8]
 * subtracted: [1, 2,    4,    8]
 *
 * Produces output:
 *
 * [3, 5]
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class NotFormula extends AbstractCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractCacheableFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -588386855739382284L;
	/**
	 * Bitmap of entity primary keys to subtract from the superset (used when formula is constructed from raw bitmaps).
	 */
	private final Bitmap subtractedBitmap;
	/**
	 * Bitmap of entity primary keys representing the superset from which subtracted keys are removed.
	 */
	private final Bitmap supersetBitmap;

	protected NotFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Formula subtractedFormula, @Nonnull Formula supersetFormula) {
		super(computationCallback);
		this.subtractedBitmap = null;
		this.supersetBitmap = null;
		this.initFields(subtractedFormula, supersetFormula);
		Assert.isTrue(this.innerFormulas.length > 1, "And formula has no sense with " + this.innerFormulas.length + " inner formulas!");
	}

	public NotFormula(@Nonnull Formula subtractedFormula, @Nonnull Formula supersetFormula) {
		super(null);
		this.subtractedBitmap = null;
		this.supersetBitmap = null;
		this.initFields(subtractedFormula, supersetFormula);
	}

	public NotFormula(@Nonnull Bitmap subtractedBitmap, @Nonnull Bitmap supersetBitmap) {
		super(null);
		this.subtractedBitmap = subtractedBitmap;
		this.supersetBitmap = supersetBitmap;
		this.initFields();
	}

	/**
	 * Returns the subtracted formula (the set of items to remove from the superset).
	 * This is the first inner formula (index 0).
	 */
	@Nonnull
	public Formula getSubtractedFormula() {
		return this.innerFormulas[0];
	}

	/**
	 * Returns the superset formula (the universe from which items are subtracted).
	 * This is the second inner formula (index 1).
	 */
	@Nonnull
	public Formula getSupersetFormula() {
		return this.innerFormulas[1];
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new NotFormula(innerFormulas[0], innerFormulas[1]);
	}

	@Override
	public int getEstimatedCardinality() {
		if (this.supersetBitmap != null && this.subtractedBitmap != null) {
			return this.supersetBitmap.size();
		} else {
			return getSupersetFormula().getEstimatedCardinality();
		}
	}

	@Override
	public long getOperationCost() {
		return 9;
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new NotFormula(
			selfOperator,
			innerFormulas[0], innerFormulas[1]
		);
	}

	@Override
	public String toString() {
		if (this.subtractedBitmap != null && this.supersetBitmap != null) {
			return "NOT: " + this.subtractedBitmap + ", " + this.supersetBitmap;
		} else {
			return "NOT";
		}
	}

	@Override
	protected boolean isFormulaOrderSignificant() {
		return true;
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		if (this.subtractedBitmap != null && this.supersetBitmap != null) {
			int idx = 0;
			final long[] ids = new long[2];
			if (this.subtractedBitmap instanceof TransactionalLayerProducer<?, ?> tlp) {
				ids[idx++] = tlp.getId();
			}
			if (this.supersetBitmap instanceof TransactionalLayerProducer<?, ?> tlp) {
				ids[idx++] = tlp.getId();
			}
			return idx == ids.length ? ids : Arrays.copyOf(ids, idx);
		} else {
			return super.gatherBitmapIdsInternal();
		}
	}

	@Override
	public long getEstimatedCostInternal() {
		if (this.subtractedBitmap != null && this.supersetBitmap != null) {
			try {
				long costs = this.subtractedBitmap.size();
				costs = Math.addExact(costs, this.supersetBitmap.size());
				return Math.multiplyExact(costs, getOperationCost());
			} catch (ArithmeticException ex) {
				return Long.MAX_VALUE;
			}
		} else {
			return super.getEstimatedCostInternal();
		}
	}

	@Override
	protected long getEstimatedBaseCost() {
		if (this.supersetBitmap != null && this.subtractedBitmap != null) {
			return (long) this.supersetBitmap.size() + (long) this.subtractedBitmap.size();
		} else {
			return super.getEstimatedBaseCost();
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		int idx = 0;
		final long[] hashes = new long[2];
		if (this.subtractedBitmap != null) {
			hashes[idx++] = this.subtractedBitmap instanceof TransactionalLayerProducer<?, ?> tlp
				? tlp.getId()
				// this shouldn't happen for long arrays - these are expected to be always linked to transactional
				// bitmaps located in indexes and represented by "transactional id"
				: hashFunction.hashInts(this.subtractedBitmap.getArray());
		}
		if (this.supersetBitmap != null) {
			hashes[idx++] = this.supersetBitmap instanceof TransactionalLayerProducer<?, ?> tlp
				? tlp.getId()
				// this shouldn't happen for long arrays - these are expected to be always linked to transactional
				// bitmaps located in indexes and represented by "transactional id"
				: hashFunction.hashInts(this.supersetBitmap.getArray());
		}
		return hashFunction.hashLongs(idx == hashes.length ? hashes : Arrays.copyOf(hashes, idx));
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.supersetBitmap != null && this.subtractedBitmap != null) {
			return (long) this.supersetBitmap.size() + (long) this.subtractedBitmap.size();
		} else {
			final Bitmap supersetBitmap = getSupersetFormula().compute();
			if (supersetBitmap.isEmpty()) {
				return getSupersetFormula().getCost();
			} else {
				return super.getCostInternal();
			}
		}
	}

	@Override
	protected long getCostToPerformanceInternal() {
		if (this.supersetBitmap != null && this.subtractedBitmap != null) {
			return getCost() / Math.max(1, compute().size());
		} else {
			final Bitmap supersetBitmap = getSupersetFormula().compute();
			if (supersetBitmap.isEmpty()) {
				return getCost() / Math.max(1, compute().size());
			} else {
				return super.getCostToPerformanceInternal();
			}
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap theResult;
		if (this.subtractedBitmap != null && this.supersetBitmap != null) {
			if (this.supersetBitmap.isEmpty()) {
				theResult = EmptyBitmap.INSTANCE;
			} else {
				theResult = new BaseBitmap(
					RoaringBitmap.andNot(
						RoaringBitmapBackedBitmap.getRoaringBitmap(this.supersetBitmap),
						RoaringBitmapBackedBitmap.getRoaringBitmap(this.subtractedBitmap)
					)
				);
			}
		} else {
			final Bitmap supersetBitmap = getSupersetFormula().compute();
			if (supersetBitmap.isEmpty()) {
				theResult = EmptyBitmap.INSTANCE;
			} else {
				theResult = new BaseBitmap(
					RoaringBitmap.andNot(
						RoaringBitmapBackedBitmap.getRoaringBitmap(supersetBitmap),
						RoaringBitmapBackedBitmap.getRoaringBitmap(getSubtractedFormula().compute())
					)
				);
			}
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

}
