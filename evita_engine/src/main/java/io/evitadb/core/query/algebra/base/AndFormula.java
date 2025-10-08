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
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static java.util.Optional.ofNullable;

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
public class AndFormula extends AbstractCacheableFormula {
	private static final long CLASS_ID = 2754438812730972016L;
	private static final Bitmap[] EMPTY_BITMAP_ARRAY = new Bitmap[0];
	private final Bitmap[] bitmaps;
	private final long[] indexTransactionId;
	private List<Formula> sortedFormulasByComplexity;

	AndFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Formula[] innerFormulas, long[] indexTransactionId, @Nullable Bitmap[] bitmaps) {
		super(computationCallback);
		Assert.isTrue(
			innerFormulas.length > 1 || Objects.requireNonNull(bitmaps).length > 1,
			"And formula has no sense with " + innerFormulas.length + " inner formulas / bitmaps!"
		);
		this.bitmaps = bitmaps;
		this.indexTransactionId = indexTransactionId;
		this.initFields(innerFormulas);
	}

	public AndFormula(@Nonnull Formula... innerFormulas) {
		super(null);
		Assert.isTrue(innerFormulas.length > 1, "And formula has no sense with " + innerFormulas.length + " inner formulas!");
		this.bitmaps = null;
		this.indexTransactionId = null;
		this.initFields(innerFormulas);
	}

	public AndFormula(long[] indexTransactionId, @Nonnull Bitmap... bitmaps) {
		super(null);
		Assert.isTrue(bitmaps.length > 1, "And formula has no sense with " + bitmaps.length + " inner bitmaps!");
		this.bitmaps = bitmaps;
		this.indexTransactionId = indexTransactionId;
		this.initFields();
	}

	@Nonnull
	public Bitmap[] getBitmaps() {
		return this.bitmaps == null ? EMPTY_BITMAP_ARRAY : this.bitmaps;
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
			return Arrays.stream(this.innerFormulas).mapToInt(Formula::getEstimatedCardinality).min().orElse(0);
		} else {
			return Arrays.stream(this.bitmaps).mapToInt(Bitmap::size).min().orElse(0);
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
	public long[] gatherBitmapIdsInternal() {
		if (this.bitmaps == null) {
			return Arrays.stream(this.innerFormulas)
				.flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
				.distinct()
				.toArray();
		} else {
			return LongStream.concat(
					this.bitmaps.length > EXCESSIVE_HIGH_CARDINALITY ?
						LongStream.of(this.indexTransactionId) :
						Arrays.stream(this.bitmaps)
							.filter(TransactionalLayerProducer.class::isInstance)
							.mapToLong(it -> ((TransactionalLayerProducer<?, ?>) it).getId()),
					Arrays.stream(this.innerFormulas).flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
				)
				.toArray();
		}
	}

	@Override
	protected long getEstimatedBaseCost() {
		return ofNullable(this.bitmaps)
			.map(it -> Arrays.stream(it).mapToLong(Bitmap::size).min().orElse(0L) * getOperationCost())
			.orElseGet(super::getEstimatedBaseCost);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		if (this.bitmaps == null) {
			return 0L;
		} else {
			return hashFunction.hashLongs(
				Arrays.stream(this.bitmaps).mapToLong(it -> {
						if (it instanceof TransactionalLayerProducer) {
							return ((TransactionalLayerProducer<?, ?>) it).getId();
						} else {
							// this shouldn't happen for long arrays - these are expected to be always linked to transactional
							// bitmaps located in indexes and represented by "transactional id"
							return hashFunction.hashInts(it.getArray());
						}
					})
					.sorted()
					.toArray()
			);
		}
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		return ofNullable(this.bitmaps)
			.map(it -> {
				long cost = 0L;
				for (Bitmap bitmap : this.bitmaps) {
					if (bitmap == EmptyBitmap.INSTANCE) {
						break;
					}
					cost += bitmap.size() * getOperationCost();
				}
				return cost;
			})
			.orElseGet(() -> {
				long cost = 0L;
				for (Formula innerFormula : this.sortedFormulasByComplexity) {
					final Bitmap innerResult = innerFormula.compute();
					cost += innerFormula.getCost() + innerResult.size() * getOperationCost();
					if (innerResult == EmptyBitmap.INSTANCE) {
						break;
					}
				}
				return cost;
			});
	}

	@Override
	protected long getCostToPerformanceInternal() {
		return ofNullable(this.bitmaps)
			.map(it -> getCost() / Math.max(1, compute().size()))
			.orElseGet(() -> {
				long costToPerformance = 0L;
				for (Formula innerFormula : this.sortedFormulasByComplexity) {
					final Bitmap innerResult = innerFormula.compute();
					if (innerResult == EmptyBitmap.INSTANCE) {
						break;
					}
					costToPerformance += innerFormula.getCostToPerformanceRatio();
				}
				return costToPerformance + getCost() / Math.max(1, compute().size());
			});
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
	public String toString() {
		if (ArrayUtils.isEmpty(this.bitmaps)) {
			return "AND";
		} else {
			return "AND: " + Arrays.stream(this.bitmaps).map(Bitmap::toString).collect(Collectors.joining(", "));
		}
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private RoaringBitmap[] getRoaringBitmaps() {
		return ofNullable(this.bitmaps)
			.map(it -> Arrays
				.stream(it)
				.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
				.toArray(RoaringBitmap[]::new)
			)
			.orElseGet(
				() -> {
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
			);
	}

}
