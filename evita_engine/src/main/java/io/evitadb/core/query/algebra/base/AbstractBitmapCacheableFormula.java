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
import io.evitadb.utils.Assert;
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
			// the high-cardinality fallback substitutes the index/leaf-page transactional id set for the per-bitmap
			// ids; a null OR EMPTY set would leave this cacheable formula with no staleness dependency and make it
			// impossible to ever invalidate. Fail fast instead.
			Assert.isPremiseValid(
				this.indexTransactionId != null && this.indexTransactionId.length > 0,
				"High-cardinality bitmaps require a non-empty indexTransactionId (else the cached result could never " +
					"be invalidated)!"
			);
			bitmapIdCount = this.indexTransactionId.length;
		} else {
			// every bitmap contributes a token, whether or not it owns a transactional identity - see the loop below
			bitmapIdCount = this.bitmaps.length;
		}
		int innerIdCount = 0;
		for (final Formula formula : this.innerFormulas) {
			innerIdCount += formula.gatherTransactionalIds().length;
		}
		final long[] result = new long[bitmapIdCount + innerIdCount];
		int pos = 0;
		if (this.bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
			System.arraycopy(this.indexTransactionId, 0, result, 0, this.indexTransactionId.length);
			pos = this.indexTransactionId.length;
		} else {
			for (final Bitmap bitmap : this.bitmaps) {
				// A bitmap that owns a transactional identity is keyed on that identity; one that does not is keyed
				// on its CONTENTS. Skipping the latter - which is what this loop used to do - left a cacheable
				// formula built over such bitmaps with no staleness dependency at all, so a cached answer could
				// never be invalidated by a write to them. That was a latent hole for the single-record bucket
				// view, and a live one for the sorted-array bucket tier, whose record sets are read-only views
				// created per read and therefore cannot carry an identity of their own.
				//
				// A content hash is a legitimate member of this set: the cache validates a hit by comparing the
				// HASH of the whole set (`CacheEden` compares `getTransactionalIdHash()`), never by resolving an
				// individual id back to an object. It is also strictly stronger than an object id for this purpose,
				// because it changes when the data changes even where the holder was mutated in place.
				result[pos++] = bitmapIdentityToken(bitmap, HASH_FUNCTION);
			}
		}
		for (final Formula innerFormula : this.innerFormulas) {
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
				hashes[i] = bitmapIdentityToken(this.bitmaps[i], hashFunction);
			}
			Arrays.sort(hashes);
			// NOT folded in here: `indexTransactionId`. It is a STALENESS token set, not part of the identity of the
			// computation, and the two differ - the trigram-accelerated path carries the accelerator's own id while
			// the scan over the same buckets cannot, yet both must land on one cache entry (see
			// TrigramSubstringSearchTest#shouldHashIdenticallyToTheScan). Staleness is carried by
			// `gatherBitmapIdsInternal` instead, which is what the cache validates a hit against.
			return hashFunction.hashLongs(hashes);
		}
	}

}
