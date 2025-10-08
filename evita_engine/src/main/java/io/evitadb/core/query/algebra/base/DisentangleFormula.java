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
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Disentangle formula accepts two bitmaps of numbers and produces bitmap of numbers, that are present in first array
 * but are not duplicated on the same indexes in the second array.
 *
 * Example input:
 *
 * ```
 * [   3, 3,    6,     9,     12],
 * [2, 3,    4, 6,  8,    10, 12]
 * ```
 *
 * Produces output:
 *
 * ```
 * [3, 9]
 * ```
 *
 * Algorithm picks number from both bitmaps and skips it when both numbers are equal. Then it picks another one and compares
 * it again. Second array pointer is advancing only when it is lesser or equal than number from the main bitmap.
 *
 * Its complexity is **O(M+N)**.
 *
 * ** Measured performance: **
 *
 * - 1m unique random numbers
 * - 2 arrays with minimum of 600k and maximum 1m numbers
 * - average DISTINCT performance is **3-8ms per computation** with result array size close to 25k
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DisentangleFormula extends AbstractCacheableFormula implements CacheablePriceFormula {
	private static final long CLASS_ID = -3805332683683704679L;
	private static final int END_OF_STREAM = -1;
	private final Bitmap mainBitmap;
	private final Bitmap controlBitmap;

	public DisentangleFormula(@Nonnull Formula mainBitmap, @Nonnull Formula controlBitmap) {
		super(null);
		this.mainBitmap = null;
		this.controlBitmap = null;
		this.initFields(mainBitmap, controlBitmap);
	}

	public DisentangleFormula(@Nonnull Bitmap mainBitmap, @Nonnull Bitmap controlBitmap) {
		super(null);
		this.mainBitmap = mainBitmap;
		this.controlBitmap = controlBitmap;
		this.initFields();
	}
	DisentangleFormula(
		@Nullable Consumer<CacheableFormula> computationCallback,
		@Nullable Bitmap mainBitmap,
		@Nullable Bitmap controlBitmap,
		@Nullable Formula... formulas
	) {
		super(computationCallback);
		this.mainBitmap = mainBitmap;
		this.controlBitmap = controlBitmap;
		this.initFields(formulas == null ? EMPTY_FORMULA_ARRAY : formulas);
		Assert.isTrue(
			(ArrayUtils.isEmpty(this.innerFormulas) && (mainBitmap != null && controlBitmap != null)) ||
			(this.innerFormulas.length == 2 && (mainBitmap == null && controlBitmap == null)),
			"Disentangle supports either two formulas or two bitmaps but not both!"
		);
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		return LongStream.concat(
				Stream.of(this.mainBitmap, this.controlBitmap)
					.filter(TransactionalLayerProducer.class::isInstance)
					.mapToLong(it -> ((TransactionalLayerProducer<?, ?>) it).getId()),
				Arrays.stream(this.innerFormulas).flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
			)
			.toArray();
	}

	@Override
	public long getEstimatedCostInternal() {
		if (this.mainBitmap != null && this.controlBitmap != null) {
			try {
				long costs = this.mainBitmap.size();
				costs = Math.addExact(costs, this.controlBitmap.size());
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
		if (this.mainBitmap != null && this.controlBitmap != null) {
			return Stream.of(this.mainBitmap, this.controlBitmap).mapToLong(Bitmap::size).sum();
		} else {
			return super.getEstimatedBaseCost();
		}
	}

	@Override
	public int getEstimatedCardinality() {
		return this.mainBitmap == null ? this.innerFormulas[0].getEstimatedCardinality() : this.mainBitmap.size();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Stream.of(this.mainBitmap, this.controlBitmap)
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
	protected boolean isFormulaOrderSignificant() {
		return true;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		if (this.mainBitmap != null && this.controlBitmap != null) {
			return Stream.of(this.mainBitmap, this.controlBitmap).mapToLong(Bitmap::size).sum();
		} else {
			return super.getCostInternal();
		}
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new DisentangleFormula(innerFormulas[0], innerFormulas[1]);
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		return new DisentangleFormula(
			selfOperator,
			this.mainBitmap, this.controlBitmap,
			innerFormulas
		);
	}

	@Override
	public long getOperationCost() {
		return 2130;
	}

	@Override
	public String toString() {
		if (this.mainBitmap != null && this.controlBitmap != null) {
			return "DISENTANGLE: main " + this.mainBitmap.size() + ", control: " + this.controlBitmap.size() + " primary keys";
		} else {
			return "DISENTANGLE";
		}
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		if (this.mainBitmap != null && this.controlBitmap != null) {
			return "DISENTANGLE: " + Stream.of(this.mainBitmap, this.controlBitmap).map(Bitmap::toString).collect(Collectors.joining(", "));
		} else {
			return "DISENTANGLE";
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final OfInt controlIt = ofNullable(this.controlBitmap).map(Bitmap::iterator).orElseGet(() -> this.innerFormulas[1].compute().iterator());
		if (!controlIt.hasNext()) {
			final OfInt mainIt = ofNullable(this.mainBitmap).map(Bitmap::iterator).orElseGet(() -> this.innerFormulas[0].compute().iterator());
			while (mainIt.hasNext()) {
				writer.add(mainIt.next());
			}
		} else {
			final OfInt mainIt = ofNullable(this.mainBitmap).map(Bitmap::iterator).orElseGet(() -> this.innerFormulas[0].compute().iterator());
			int number;
			final AtomicInteger controlNumberRef = new AtomicInteger(END_OF_STREAM);
			while ((number = computeNextInt(mainIt, controlIt, controlNumberRef)) != END_OF_STREAM) {
				writer.add(number);
			}
		}
		return new BaseBitmap(writer.get());
	}

	/*
		PRIVATE METHODS
	 */

	private static int computeNextInt(OfInt mainIt, OfInt controlIt, AtomicInteger controlNumberRef) {
		if (mainIt.hasNext()) {
			do {
				final int nextNumberAdept = mainIt.next();
				if (!controlIt.hasNext() && controlNumberRef.get() == END_OF_STREAM) {
					return nextNumberAdept;
				}
				while (controlIt.hasNext() && (controlNumberRef.get() == END_OF_STREAM || controlNumberRef.get() < nextNumberAdept)) {
					controlNumberRef.set(controlIt.next());
				}

				if (nextNumberAdept == controlNumberRef.get()) {
					// swallow in control list and repeat
					if (mainIt.hasNext()) {
						if (controlIt.hasNext()) {
							controlNumberRef.set(controlIt.next());
						} else {
							controlNumberRef.set(END_OF_STREAM);
						}
					} else {
						return END_OF_STREAM;
					}
				} else {
					return nextNumberAdept;
				}
			} while (true);
		}

		return END_OF_STREAM;
	}

}
