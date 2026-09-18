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
import io.evitadb.core.query.algebra.price.CacheablePriceFormula;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Computes the *signed multiplicity* of two families of bitmaps in a single pass:
 *
 * ```
 * result = { v : count(plus, v) - count(minus, v) > 0 },   count(F, v) = |{ i : v in F[i] }|
 * ```
 *
 * This replaces the `JoinFormula` -> `DisentangleFormula` pair that {@link io.evitadb.index.range.RangeIndex} used
 * to build. That pair reached the same answer by materializing a duplicate-carrying array in a k-way merge and then
 * cancelling the duplicates in a second merge; the duplicates were never an output, only an encoding of the counts,
 * so this formula accumulates the counts directly. See {@link RangeCountKernel} for the kernel and its invariants.
 *
 * ## What the operands mean to the range index
 *
 * For `getRecordsTo` the plus family is the `starts` of every threshold point up to the threshold and the minus
 * family is their `ends` - a record is valid when it started more often than it ended. `getRecordsFrom` is the
 * mirror image over the suffix, with the families swapped. The order of the two families is therefore significant
 * and is part of this formula's identity.
 *
 * `getRecordsEnvelopingInclusive` and `getRecordsWithRangesOverlapping` build this same formula through
 * {@link io.evitadb.index.range.RangeIndex}'s private `createPrefixCountFormula`, whose two bounds are chosen
 * independently rather than mirrored around one threshold - see that method for how each of the four callers maps
 * onto a `(startsBound, startsInclusive, endsBound, endsInclusive)` tuple.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RangeCountFormula extends AbstractCacheableFormula implements CacheablePriceFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractCacheableFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 8_142_337_905_512_664_411L;
	/**
	 * Separator folded into the operand hash between the two families, so that swapping `plus` and `minus` - which
	 * computes a different result - cannot produce the same identity.
	 */
	private static final long FAMILY_SEPARATOR = 0x5241_4E47_4553_4550L;
	/**
	 * Transactional id of the index the operands were read from, used as the staleness token when the operand count
	 * makes per-bitmap tokens uneconomical.
	 */
	private final long indexTransactionId;
	/**
	 * Operands each membership of which contributes `+1`.
	 */
	private final Bitmap[] plus;
	/**
	 * Operands each membership of which contributes `-1`.
	 */
	private final Bitmap[] minus;

	public RangeCountFormula(long indexTransactionId, @Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		this(null, indexTransactionId, plus, minus);
	}

	RangeCountFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		long indexTransactionId,
		@Nonnull Bitmap[] plus,
		@Nonnull Bitmap[] minus
	) {
		super(computationCallback);
		Assert.isTrue(
			plus.length > 0,
			"Range count formula with an empty plus family computes nothing - use EmptyFormula.INSTANCE instead!"
		);
		this.indexTransactionId = indexTransactionId;
		this.plus = plus;
		this.minus = minus;
		this.initFields();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Range count formula doesn't support inner formulas, just bitmaps.");
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(
		@Nonnull Consumer<CacheableFormula> selfOperator,
		@Nonnull Formula... innerFormulas
	) {
		Assert.isPremiseValid(innerFormulas.length == 0, "Range count formula doesn't support inner formulas!");
		return new RangeCountFormula(selfOperator, this.indexTransactionId, this.plus, this.minus);
	}

	@Override
	public long getOperationCost() {
		// Derived from the A/B, not guessed - this constant drives the planner's cost model and the cache
		// admission threshold, so a made-up value silently mis-ranks plans and mis-admits cache entries.
		//
		// On the measured production shape (29,159 endpoints each side) the pair this replaces estimates
		// 2560 * (Np + Nm) + 2130 * Np = 211,402,750 units and takes 7,840.5 us; this formula takes 3,161.9 us.
		// Holding the model's scale fixed, the same work should estimate
		// 211,402,750 * (3161.9 / 7840.5) = 85,254,047 units over 58,318 elements = 1,462 per element.
		return 1462;
	}

	@Override
	public int getEstimatedCardinality() {
		// the result can never exceed the union of the plus family, and the union can never exceed its total size
		int sum = 0;
		for (final Bitmap bitmap : this.plus) {
			sum += bitmap.size();
		}
		return sum;
	}

	@Override
	protected boolean isFormulaOrderSignificant() {
		return true;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return RangeCountKernel.compute(this.plus, this.minus);
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		if (this.plus.length + this.minus.length > TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY) {
			// mirrors JoinFormula: past this width the per-bitmap tokens cost more than they buy, and the index's own
			// id is a sound substitute because a mutated index becomes a fresh instance with a fresh id. A null or
			// empty set would leave this cacheable formula with no staleness dependency at all - fail fast instead.
			Assert.isPremiseValid(
				this.indexTransactionId != 0L,
				"High-cardinality operands require a non-zero indexTransactionId (else the cached result could never " +
					"be invalidated)!"
			);
			return new long[]{this.indexTransactionId};
		}
		int count = 0;
		for (final Bitmap bitmap : this.plus) {
			if (bitmap instanceof TransactionalLayerProducer) {
				count++;
			}
		}
		for (final Bitmap bitmap : this.minus) {
			if (bitmap instanceof TransactionalLayerProducer) {
				count++;
			}
		}
		final long[] ids = new long[count];
		int index = 0;
		for (final Bitmap bitmap : this.plus) {
			if (bitmap instanceof final TransactionalLayerProducer<?, ?> producer) {
				ids[index++] = producer.getId();
			}
		}
		for (final Bitmap bitmap : this.minus) {
			if (bitmap instanceof final TransactionalLayerProducer<?, ?> producer) {
				ids[index++] = producer.getId();
			}
		}
		return ids;
	}

	@Override
	public long getEstimatedCostInternal() {
		try {
			long costs = 0L;
			for (final Bitmap bitmap : this.plus) {
				costs = Math.addExact(costs, bitmap.size());
			}
			for (final Bitmap bitmap : this.minus) {
				costs = Math.addExact(costs, bitmap.size());
			}
			return Math.multiplyExact(costs, getOperationCost());
		} catch (final ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Actual cost of this formula, priced the way {@link #getEstimatedCostInternal()} prices the same work: every
	 * endpoint this formula scatters, scaled by {@link #getOperationCost()}.
	 *
	 * The multiplier is not decoration. `AbstractFormula#getCostToPerformanceInternal()` divides this by the result
	 * size and that ratio is what the formula cache admits and ranks entries by, so dropping the factor would price a
	 * range result three orders of magnitude below every other formula type and effectively bar it from the cache.
	 * The sibling bitmap-carrying formula - `OrFormula#getCostInternal()` - applies it for the same reason. Overflow
	 * is capped rather than thrown, matching {@link #getEstimatedCostInternal()}.
	 *
	 * @return the actual cost, or {@link Long#MAX_VALUE} when the product overflows
	 */
	@Override
	protected long getCostInternal() {
		try {
			return Math.multiplyExact(totalOperandSize(), getOperationCost());
		} catch (final ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		final long[] hashes = new long[this.plus.length + this.minus.length + 1];
		int index = 0;
		for (final Bitmap bitmap : this.plus) {
			hashes[index++] = bitmapIdentityToken(bitmap, hashFunction);
		}
		// without this the families would be indistinguishable by their concatenation alone, and swapping them -
		// which is exactly what getRecordsFrom does relative to getRecordsTo - would collide
		hashes[index++] = FAMILY_SEPARATOR;
		for (final Bitmap bitmap : this.minus) {
			hashes[index++] = bitmapIdentityToken(bitmap, hashFunction);
		}
		return hashFunction.hashLongs(hashes);
	}

	@Override
	public String toString() {
		return "RANGE COUNT: +" + this.plus.length + " operands, -" + this.minus.length + " operands";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		final StringBuilder sb = new StringBuilder(128);
		sb.append("RANGE COUNT: +[");
		for (int i = 0; i < this.plus.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(this.plus[i]);
		}
		sb.append("] -[");
		for (int i = 0; i < this.minus.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(this.minus[i]);
		}
		return sb.append(']').toString();
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Total number of endpoints this formula has to scatter - the real driver of its cost.
	 *
	 * @return summed size of every operand in both families
	 */
	private long totalOperandSize() {
		long sum = 0L;
		for (final Bitmap bitmap : this.plus) {
			sum += bitmap.size();
		}
		for (final Bitmap bitmap : this.minus) {
			sum += bitmap.size();
		}
		return sum;
	}

}
