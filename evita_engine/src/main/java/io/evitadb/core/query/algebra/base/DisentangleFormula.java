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
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Consumer;

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
	/**
	 * Unique identifier of this formula used in {@link AbstractCacheableFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = -3805332683683704679L;
	/**
	 * Sentinel value indicating that a bitmap iterator has been fully exhausted.
	 */
	private static final int END_OF_STREAM = -1;
	/**
	 * Primary bitmap whose elements are iterated and conditionally included in the result.
	 */
	private final Bitmap mainBitmap;
	/**
	 * Control bitmap whose elements are used to exclude matching entries from the main bitmap.
	 */
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
	protected long[] gatherBitmapIdsInternal() {
		int bitmapIdCount = 0;
		if (this.mainBitmap instanceof TransactionalLayerProducer) {
			bitmapIdCount++;
		}
		if (this.controlBitmap instanceof TransactionalLayerProducer) {
			bitmapIdCount++;
		}
		int innerIdCount = 0;
		for (int i = 0; i < this.innerFormulas.length; i++) {
			innerIdCount += this.innerFormulas[i].gatherTransactionalIds().length;
		}
		final long[] result = new long[bitmapIdCount + innerIdCount];
		int pos = 0;
		if (this.mainBitmap instanceof TransactionalLayerProducer) {
			result[pos++] = ((TransactionalLayerProducer<?, ?>) this.mainBitmap).getId();
		}
		if (this.controlBitmap instanceof TransactionalLayerProducer) {
			result[pos++] = ((TransactionalLayerProducer<?, ?>) this.controlBitmap).getId();
		}
		for (int i = 0; i < this.innerFormulas.length; i++) {
			final long[] ids = this.innerFormulas[i].gatherTransactionalIds();
			System.arraycopy(ids, 0, result, pos, ids.length);
			pos += ids.length;
		}
		return pos == result.length ? result : Arrays.copyOf(result, pos);
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
			return (long) this.mainBitmap.size() + (long) this.controlBitmap.size();
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
		int count = 0;
		if (this.mainBitmap != null) {
			count++;
		}
		if (this.controlBitmap != null) {
			count++;
		}
		final long[] hashes = new long[count];
		int pos = 0;
		if (this.mainBitmap != null) {
			if (this.mainBitmap instanceof TransactionalLayerProducer) {
				hashes[pos++] = ((TransactionalLayerProducer<?, ?>) this.mainBitmap).getId();
			} else {
				// this shouldn't happen for long arrays - these are expected to be always linked to transactional
				// bitmaps located in indexes and represented by "transactional id"
				hashes[pos++] = hashFunction.hashInts(this.mainBitmap.getArray());
			}
		}
		if (this.controlBitmap != null) {
			if (this.controlBitmap instanceof TransactionalLayerProducer) {
				hashes[pos++] = ((TransactionalLayerProducer<?, ?>) this.controlBitmap).getId();
			} else {
				// this shouldn't happen for long arrays - these are expected to be always linked to transactional
				// bitmaps located in indexes and represented by "transactional id"
				hashes[pos++] = hashFunction.hashInts(this.controlBitmap.getArray());
			}
		}
		return hashFunction.hashLongs(hashes);
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
			return (long) this.mainBitmap.size() + (long) this.controlBitmap.size();
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
			return "DISENTANGLE: " + this.mainBitmap.toString() + ", " + this.controlBitmap.toString();
		} else {
			return "DISENTANGLE";
		}
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final OfInt controlIt = this.controlBitmap != null
			? this.controlBitmap.iterator()
			: this.innerFormulas[1].compute().iterator();
		if (!controlIt.hasNext()) {
			final OfInt mainIt = this.mainBitmap != null
				? this.mainBitmap.iterator()
				: this.innerFormulas[0].compute().iterator();
			while (mainIt.hasNext()) {
				writer.add(mainIt.next());
			}
		} else {
			final OfInt mainIt = this.mainBitmap != null
				? this.mainBitmap.iterator()
				: this.innerFormulas[0].compute().iterator();
			int number;
			final int[] controlNumberRef = {END_OF_STREAM};
			while ((number = computeNextInt(mainIt, controlIt, controlNumberRef)) != END_OF_STREAM) {
				writer.add(number);
			}
		}
		return new BaseBitmap(writer.get());
	}

	/*
		PRIVATE METHODS
	 */

	private static int computeNextInt(OfInt mainIt, OfInt controlIt, int[] controlNumberRef) {
		if (mainIt.hasNext()) {
			do {
				final int nextNumberAdept = mainIt.next();
				if (!controlIt.hasNext() && controlNumberRef[0] == END_OF_STREAM) {
					return nextNumberAdept;
				}
				while (controlIt.hasNext() && (controlNumberRef[0] == END_OF_STREAM || controlNumberRef[0] < nextNumberAdept)) {
					controlNumberRef[0] = controlIt.next();
				}

				if (nextNumberAdept == controlNumberRef[0]) {
					// swallow in control list and repeat
					if (mainIt.hasNext()) {
						if (controlIt.hasNext()) {
							controlNumberRef[0] = controlIt.next();
						} else {
							controlNumberRef[0] = END_OF_STREAM;
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
