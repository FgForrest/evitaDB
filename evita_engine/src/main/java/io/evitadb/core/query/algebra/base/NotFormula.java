/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

	protected NotFormula(@Nonnull Consumer<CacheableFormula> computationCallback, @Nonnull Formula subtractedBitmap, @Nonnull Formula supersetBitmap) {
		super(computationCallback, subtractedBitmap, supersetBitmap);
		Assert.isTrue(innerFormulas.length > 1, "And formula has no sense with " + innerFormulas.length + " inner formulas!");
		this.subtractedBitmap = null;
		this.supersetBitmap = null;
	}

	public NotFormula(@Nonnull Formula subtractedBitmap, @Nonnull Formula supersetBitmap) {
		super(null, subtractedBitmap, supersetBitmap);
		this.subtractedBitmap = null;
		this.supersetBitmap = null;
	}

	public NotFormula(@Nonnull Bitmap subtractedBitmap, @Nonnull Bitmap supersetBitmap) {
		super(null);
		this.subtractedBitmap = subtractedBitmap;
		this.supersetBitmap = supersetBitmap;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new NotFormula(innerFormulas[0], innerFormulas[1]);
	}

	@Override
	public int getEstimatedCardinality() {
		if (supersetBitmap != null && subtractedBitmap != null) {
			return supersetBitmap.size();
		} else {
			return innerFormulas[1].getEstimatedCardinality();
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
		if (subtractedBitmap != null && supersetBitmap != null) {
			return "NOT: " + Stream.of(subtractedBitmap, supersetBitmap).map(Bitmap::toString).collect(Collectors.joining(", "));
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
				Stream.of(subtractedBitmap, supersetBitmap)
					.filter(TransactionalLayerProducer.class::isInstance)
					.mapToLong(it -> ((TransactionalLayerProducer<?, ?>) it).getId()),
				Arrays.stream(innerFormulas).flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
			)
			.toArray();
	}

	@Override
	public long getEstimatedCostInternal() {
		if (subtractedBitmap != null && supersetBitmap != null) {
			try {
				long costs = subtractedBitmap.size();
				costs = Math.addExact(costs, supersetBitmap.size());
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
		if (supersetBitmap != null && subtractedBitmap != null) {
			return Stream.of(supersetBitmap, subtractedBitmap).mapToLong(Bitmap::size).sum();
		} else {
			return super.getEstimatedBaseCost();
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Stream.of(subtractedBitmap, supersetBitmap)
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
		if (supersetBitmap != null && subtractedBitmap != null) {
			return Stream.of(supersetBitmap, subtractedBitmap).mapToLong(Bitmap::size).sum();
		} else {
			final Bitmap supersetBitmap = innerFormulas[1].compute();
			if (supersetBitmap.isEmpty()) {
				return innerFormulas[1].getCost();
			} else {
				return super.getCostInternal();
			}
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap theResult;
		if (subtractedBitmap != null && supersetBitmap != null) {
			if (supersetBitmap.isEmpty()) {
				theResult = EmptyBitmap.INSTANCE;
			} else {
				theResult = new BaseBitmap(
					RoaringBitmap.andNot(
						RoaringBitmapBackedBitmap.getRoaringBitmap(supersetBitmap),
						RoaringBitmapBackedBitmap.getRoaringBitmap(subtractedBitmap)
					)
				);
			}
		} else {
			final Bitmap supersetBitmap = innerFormulas[1].compute();
			if (supersetBitmap.isEmpty()) {
				theResult = EmptyBitmap.INSTANCE;
			} else {
				theResult = new BaseBitmap(
					RoaringBitmap.andNot(
						RoaringBitmapBackedBitmap.getRoaringBitmap(supersetBitmap),
						RoaringBitmapBackedBitmap.getRoaringBitmap(innerFormulas[0].compute())
					)
				);
			}
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

}
