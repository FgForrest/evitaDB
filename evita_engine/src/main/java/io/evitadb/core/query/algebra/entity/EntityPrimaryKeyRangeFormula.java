/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.algebra.entity;

import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Formula that filters a superset bitmap to retain only primary keys within the inclusive range
 * `[from, to]`. The superset formula is passed as a single inner formula. Iteration leverages the
 * sorted nature of the superset bitmap for early termination when the upper bound is exceeded.
 *
 * Use `Integer.MIN_VALUE` for an unbounded lower bound and `Integer.MAX_VALUE` for an unbounded
 * upper bound.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EntityPrimaryKeyRangeFormula extends AbstractCacheableFormula {
	/**
	 * Unique identifier of this formula used for hash computation.
	 */
	private static final long CLASS_ID = -4829176503281746519L;
	/**
	 * Exclusive upper bound of the entire unsigned 32-bit value space (2^32).
	 * {@link RoaringBitmap} stores ints as unsigned 32-bit values in the range `[0, 2^32)`.
	 */
	private static final long UNSIGNED_INT_LIMIT = 0x100000000L;
	/**
	 * Inclusive lower bound of the primary key range.
	 */
	private final int from;
	/**
	 * Inclusive upper bound of the primary key range.
	 */
	private final int to;

	/**
	 * Creates a new range formula wrapping the given superset formula.
	 *
	 * @param from             inclusive lower bound (`Integer.MIN_VALUE` for unbounded)
	 * @param to               inclusive upper bound (`Integer.MAX_VALUE` for unbounded)
	 * @param superSetFormula  the formula providing the full set of candidate primary keys
	 */
	public EntityPrimaryKeyRangeFormula(
		int from,
		int to,
		@Nonnull Formula superSetFormula
	) {
		super(null);
		this.from = from;
		this.to = to;
		this.initFields(superSetFormula);
	}

	/**
	 * Internal constructor accepting a computation callback for cache integration.
	 *
	 * @param computationCallback callback invoked after first computation (may be null)
	 * @param from                inclusive lower bound
	 * @param to                  inclusive upper bound
	 * @param superSetFormula     the formula providing the full set of candidate primary keys
	 */
	private EntityPrimaryKeyRangeFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		int from,
		int to,
		@Nonnull Formula superSetFormula
	) {
		super(computationCallback);
		this.from = from;
		this.to = to;
		this.initFields(superSetFormula);
	}

	@Override
	public int getEstimatedCardinality() {
		// worst case: nothing filtered out, delegate to child
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	@Override
	public long getOperationCost() {
		// benchmarked via FormulaCostMeasurement at ~6,487 ops/s on a 100K-element bitmap
		return 154;
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(
		@Nonnull Consumer<CacheableFormula> selfOperator,
		@Nonnull Formula... innerFormulas
	) {
		Assert.isTrue(
			innerFormulas.length == 1,
			"Expected exactly one inner formula (superset)!"
		);
		return new EntityPrimaryKeyRangeFormula(
			selfOperator,
			this.from,
			this.to,
			innerFormulas[0]
		);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(
			innerFormulas.length == 1,
			"Expected exactly one inner formula (superset)!"
		);
		return new EntityPrimaryKeyRangeFormula(this.from, this.to, innerFormulas[0]);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		// manual combination avoids the long[] allocation of hashFunction.hashLongs;
		// the nested `31L + hashCode(from)` breaks symmetry so that e.g. (0, 31) and (1, 0) do not collide
		return 31L * (31L + Integer.hashCode(this.from)) + Integer.hashCode(this.to);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap superSet = this.innerFormulas[0].compute();
		if (superSet.isEmpty() || this.from > this.to) {
			return EmptyBitmap.INSTANCE;
		}

		final RoaringBitmap roaring = RoaringBitmapBackedBitmap.getRoaringBitmap(superSet);
		final RoaringBitmap result = selectRangeSigned(roaring, this.from, this.to);
		if (result.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		return new BaseBitmap(result);
	}

	/**
	 * Selects values from a {@link RoaringBitmap} that fall within the inclusive signed integer
	 * range `[signedFrom, signedTo]`.
	 *
	 * {@link RoaringBitmap} stores ints as unsigned 32-bit values ordered by
	 * {@link Integer#compareUnsigned}, so a signed range like `[-10, 5]` wraps around in unsigned
	 * space (`-10` = `0xFFFFFFF6`, far above `5`). This method detects the wrap-around by comparing
	 * the unsigned-converted bounds. When `unsignedStart > unsignedEnd`, it splits the query into
	 * two contiguous unsigned sub-ranges `[0, unsignedEnd)` and `[unsignedStart, 2^32)`, each
	 * handled by a single {@link RoaringBitmap#selectRange} call, then combines them with
	 * {@link RoaringBitmap#or}. Since the sub-ranges never share container keys, the `or` is just
	 * an interleave with no per-container merging overhead.
	 *
	 * @param bitmap     the source bitmap to filter
	 * @param signedFrom inclusive lower bound (signed)
	 * @param signedTo   inclusive upper bound (signed)
	 * @return new bitmap containing only the values within the signed range
	 */
	@Nonnull
	private static RoaringBitmap selectRangeSigned(
		@Nonnull RoaringBitmap bitmap,
		int signedFrom,
		int signedTo
	) {
		final long unsignedStart = Integer.toUnsignedLong(signedFrom);
		// exclusive upper bound — safe for MAX_VALUE since toUnsignedLong(MAX_VALUE) + 1 < 2^32
		final long unsignedEnd = Integer.toUnsignedLong(signedTo) + 1;
		if (unsignedStart < unsignedEnd) {
			// range does not wrap around the unsigned boundary
			return bitmap.selectRange(unsignedStart, unsignedEnd);
		}
		// range wraps around — split into [0, unsignedEnd) ∪ [unsignedStart, 2^32)
		return RoaringBitmap.or(
			bitmap.selectRange(0L, unsignedEnd),
			bitmap.selectRange(unsignedStart, UNSIGNED_INT_LIMIT)
		);
	}

	@Override
	public String toString() {
		return "PK_RANGE[" + this.from + "," + this.to + "]";
	}
}
