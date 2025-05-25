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
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * And formula will perform boolean negation (NOT) on two bitmaps: superset and subtracted one
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
	private static final long CLASS_ID = -588386855739382284L;
	private final Bitmap subtractedBitmap;
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
			return this.innerFormulas[1].getEstimatedCardinality();
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
			return "NOT: " + Stream.of(this.subtractedBitmap, this.supersetBitmap).map(Bitmap::toString).collect(Collectors.joining(", "));
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
		return LongStream.concat(
				Stream.of(this.subtractedBitmap, this.supersetBitmap)
					.filter(TransactionalLayerProducer.class::isInstance)
					.mapToLong(it -> ((TransactionalLayerProducer<?, ?>) it).getId()),
				Arrays.stream(this.innerFormulas).flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
			)
			.toArray();
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
			return Stream.of(this.supersetBitmap, this.subtractedBitmap).mapToLong(Bitmap::size).sum();
		} else {
			return super.getEstimatedBaseCost();
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Stream.of(this.subtractedBitmap, this.supersetBitmap)
				.filter(Objects::nonNull)
				.mapToLong(it -> {
					if (it instanceof TransactionalLayerProducer) {
						return ((TransactionalLayerProducer<?, ?>) it).getId();
					} else {
						// this shouldn't happen for long arrays - these are expected to be always linked to transactional
						// bitmaps located in indexes and represented by "transactional id"
						return hashFunction.hashInts(it.getArray());
					}
				})
				.toArray()
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.supersetBitmap != null && this.subtractedBitmap != null) {
			return Stream.of(this.supersetBitmap, this.subtractedBitmap).mapToLong(Bitmap::size).sum();
		} else {
			final Bitmap supersetBitmap = this.innerFormulas[1].compute();
			if (supersetBitmap.isEmpty()) {
				return this.innerFormulas[1].getCost();
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
			final Bitmap supersetBitmap = this.innerFormulas[1].compute();
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
			final Bitmap supersetBitmap = this.innerFormulas[1].compute();
			if (supersetBitmap.isEmpty()) {
				theResult = EmptyBitmap.INSTANCE;
			} else {
				theResult = new BaseBitmap(
					RoaringBitmap.andNot(
						RoaringBitmapBackedBitmap.getRoaringBitmap(supersetBitmap),
						RoaringBitmapBackedBitmap.getRoaringBitmap(this.innerFormulas[0].compute())
					)
				);
			}
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

}
