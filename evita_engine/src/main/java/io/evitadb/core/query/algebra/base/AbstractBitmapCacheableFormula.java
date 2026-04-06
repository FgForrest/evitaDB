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
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.Bitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Abstract ancestor for cacheable formulas that can operate on raw {@link Bitmap} operands in addition to composed
 * inner formulas. Consolidates the shared bitmap-related fields ({@link #bitmaps}, {@link #indexTransactionId})
 * and common methods ({@link #getBitmaps()}, {@link #includeAdditionalHash(LongHashFunction)},
 * {@link #gatherBitmapIdsInternal()}) used by {@link AndFormula} and {@link OrFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class AbstractBitmapCacheableFormula extends AbstractCacheableFormula {
	/**
	 * Shared empty array returned by {@link #getBitmaps()} when no bitmap operands are present.
	 */
	private static final Bitmap[] EMPTY_BITMAP_ARRAY = new Bitmap[0];

	/**
	 * Raw bitmap operands this formula operates on. May be `null` when the formula operates solely on inner formulas.
	 */
	protected final Bitmap[] bitmaps;
	/**
	 * Pre-computed transactional IDs for the {@link #bitmaps} array, used as a fast-path substitute when the number
	 * of bitmaps exceeds {@link #EXCESSIVE_HIGH_CARDINALITY} to avoid iterating all bitmaps one-by-one.
	 */
	protected final long[] indexTransactionId;

	protected AbstractBitmapCacheableFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nullable long[] indexTransactionId,
		@Nullable Bitmap[] bitmaps
	) {
		super(computationCallback);
		this.bitmaps = bitmaps;
		this.indexTransactionId = indexTransactionId;
	}

	/**
	 * Returns the raw bitmap operands of this formula, or an empty array if this formula operates solely on inner
	 * formulas.
	 */
	@Nonnull
	public Bitmap[] getBitmaps() {
		return this.bitmaps == null ? EMPTY_BITMAP_ARRAY : this.bitmaps;
	}

	@Nonnull
	@Override
	protected long[] gatherBitmapIdsInternal() {
		if (this.bitmaps == null) {
			return super.gatherBitmapIdsInternal();
		}
		// estimate capacity: bitmap IDs + inner formula IDs
		int bitmapIdCount;
		if (this.bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
			bitmapIdCount = this.indexTransactionId == null ? 0 : this.indexTransactionId.length;
		} else {
			bitmapIdCount = 0;
			for (final Bitmap bitmap : this.bitmaps) {
				if (bitmap instanceof TransactionalLayerProducer) {
					bitmapIdCount++;
				}
			}
		}
		int innerIdCount = 0;
		for (final io.evitadb.core.query.algebra.Formula formula : this.innerFormulas) {
			innerIdCount += formula.gatherTransactionalIds().length;
		}
		final long[] result = new long[bitmapIdCount + innerIdCount];
		int pos = 0;
		if (this.indexTransactionId != null && this.bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
			System.arraycopy(this.indexTransactionId, 0, result, 0, this.indexTransactionId.length);
			pos = this.indexTransactionId.length;
		} else {
			for (final Bitmap bitmap : this.bitmaps) {
				if (bitmap instanceof TransactionalLayerProducer) {
					result[pos++] = ((TransactionalLayerProducer<?, ?>) bitmap).getId();
				}
			}
		}
		for (final io.evitadb.core.query.algebra.Formula innerFormula : this.innerFormulas) {
			final long[] ids = innerFormula.gatherTransactionalIds();
			System.arraycopy(ids, 0, result, pos, ids.length);
			pos += ids.length;
		}
		return pos == result.length ? result : Arrays.copyOf(result, pos);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		if (this.bitmaps == null) {
			return 0L;
		} else {
			final long[] hashes = new long[this.bitmaps.length];
			for (int i = 0; i < this.bitmaps.length; i++) {
				if (this.bitmaps[i] instanceof TransactionalLayerProducer) {
					hashes[i] = ((TransactionalLayerProducer<?, ?>) this.bitmaps[i]).getId();
				} else {
					// this shouldn't happen for long arrays - these are expected to be always linked to transactional
					// bitmaps located in indexes and represented by "transactional id"
					hashes[i] = hashFunction.hashInts(this.bitmaps[i].getArray());
				}
			}
			Arrays.sort(hashes);
			return hashFunction.hashLongs(hashes);
		}
	}

}
