/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Data;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.IntIterator;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This formula produces bitmap with possible duplicated record ids but still maintaining ascending order.
 * Formula accepts bitmaps like these:
 *
 * [1, 2, 3, 4, 5]
 * [   2,    4   ]
 * [1,          5]
 *
 * And produces following result:
 *
 * [1, 1, 2, 2, 3, 4, 4, 5, 5]
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class JoinFormula extends AbstractFormula {
	private static final long CLASS_ID = 1167849768781680098L;
	private static final int END_OF_STREAM = -1;
	private final long[] indexTransactionId;
	private final Bitmap[] bitmaps;

	/**
	 * Computes next integer to be included in result map.
	 */
	private static int computeNextInt(PriorityQueue<IntIteratorPointer> priorityQueue) {
		// finish when priority queue is empty
		if (!priorityQueue.isEmpty()) {
			// poll pointer with the lowest number from the queue
			final IntIteratorPointer pointer = priorityQueue.poll();
			// get the number
			final int value = pointer.fetchNext();
			// if pointer is not exhausted (and has another number) put it into the queue again
			if (pointer.hasNextValue()) {
				priorityQueue.offer(pointer);
			}

			return value;
		}
		return END_OF_STREAM;
	}

	/**
	 * Initializes {@link PriorityQueue} with iterators that get wrapped into {@link IntIteratorPointer} with first
	 * value initialized in them.
	 */
	private static PriorityQueue<IntIteratorPointer> initIntPriorityQueue(IntIterator[] iterators) {
		final PriorityQueue<IntIteratorPointer> priorityQueue = new PriorityQueue<>(iterators.length);
		for (IntIterator it : iterators) {
			if (it.hasNext()) {
				priorityQueue.offer(new IntIteratorPointer(it));
			}
		}
		return priorityQueue;
	}

	public JoinFormula(long indexTransactionId, @Nonnull Bitmap... bitmaps) {
		super();
		Assert.isTrue(bitmaps.length > 0, "Join formula has to have at least one bitmap - otherwise use EmptyFormula.INSTANCE.");
		this.bitmaps = bitmaps;
		this.indexTransactionId = new long[]{indexTransactionId};
	}

	@Override
	public String toString() {
		return "JOIN: " + Arrays.stream(bitmaps).map(Bitmap::toString).collect(Collectors.joining(", "));
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		if (bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
			return indexTransactionId;
		} else {
			return Stream.of(bitmaps)
				.filter(TransactionalLayerProducer.class::isInstance)
				.mapToLong(it -> ((TransactionalLayerProducer<?, ?>) it).getId())
				.toArray();
		}
	}

	@Override
	public long getEstimatedCostInternal() {
		try {
			long costs = 0L;
			for (Bitmap bitmap : bitmaps) {
				costs = Math.addExact(costs, bitmap.size());
			}
			return Math.multiplyExact(costs, getOperationCost());
		} catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	@Override
	protected long getEstimatedBaseCost() {
		return Arrays.stream(this.bitmaps).mapToLong(Bitmap::size).min().orElse(0L);
	}

	@Override
	public int getEstimatedCardinality() {
		return Arrays.stream(this.bitmaps).mapToInt(Bitmap::size).sum();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		if (bitmaps.length > EXCESSIVE_HIGH_CARDINALITY) {
			return hashFunction.hashLongs(indexTransactionId);
		} else {
			return hashFunction.hashLongs(
				Stream.of(bitmaps)
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
		return ofNullable(this.bitmaps)
			.map(it -> Arrays.stream(it).mapToLong(Bitmap::size).sum())
			.orElseGet(super::getCostInternal);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Join formula doesn't support inner formulas, just bitmaps.");
	}

	@Override
	public long getOperationCost() {
		return 2560;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// init priority queue that will produce numbers from all bitmaps from lowest to highest keeping duplicates
		final IntIterator[] iterators = getImmutableRoaringBitmapIterators();
		if (ArrayUtils.isEmpty(iterators)) {
			return EmptyBitmap.INSTANCE;
		}
		final PriorityQueue<IntIteratorPointer> priorityQueue = initIntPriorityQueue(iterators);
		// init array that can extend itself
		final CompositeIntArray intArray = new CompositeIntArray();
		// iterate number by number until priority queue is exhausted.
		int number;
		while ((number = computeNextInt(priorityQueue)) != END_OF_STREAM) {
			intArray.add(number);
		}
		// now just wrap array into a bitmap
		return new ArrayBitmap(intArray);
	}

	/*
		PRIVATE METHODS
	 */

	private IntIterator[] getImmutableRoaringBitmapIterators() {
		return Arrays.stream(bitmaps)
				.filter(it -> !(it instanceof EmptyBitmap))
				.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
				.map(it -> it.getBatchIterator().asIntIterator(new int[256]))
				.toArray(IntIterator[]::new);
	}

	/**
	 * Class that envelopes iterator that remembers last provided value and allows to compare multiple instances
	 * of this class by this last returned value. This class is purely intended to be used in {@link PriorityQueue}
	 */
	@Data
	private static class IntIteratorPointer implements Comparable<IntIteratorPointer> {
		private final IntIterator iterator;
		private int nextValue;

		private IntIteratorPointer(IntIterator iterator) {
			this.iterator = iterator;
			this.nextValue = iterator.next();
		}

		@Override
		public int compareTo(@Nonnull IntIteratorPointer o) {
			// comparator compare next number to return with other pointer numbers
			return Integer.compare(nextValue, o.getNextValue());
		}

		private boolean hasNextValue() {
			return this.nextValue != END_OF_STREAM;
		}

		private int fetchNext() {
			// return current value
			final int value = this.nextValue;
			// and if there is another number available, prepare it for another fetch
			if (this.iterator.hasNext()) {
				this.nextValue = this.iterator.next();
			} else {
				this.nextValue = END_OF_STREAM;
			}
			return value;
		}
	}

}
