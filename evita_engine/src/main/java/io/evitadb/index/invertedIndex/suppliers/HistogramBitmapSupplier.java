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

package io.evitadb.index.invertedIndex.suppliers;

import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Implementation of {@link BitmapSupplier} that provides access to the data stored in {@link InvertedIndex}
 * in a lazy fashion. The expensive computations happen in {@link #get()} method. This class is meant to be used in
 * combination with {@link DeferredFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HistogramBitmapSupplier<T extends Comparable<T>> implements BitmapSupplier {
	private static final long CLASS_ID = 516692463222738021L;
	private final ValueToRecordBitmap<T>[] histogramBuckets;

	@Override
	public long getEstimatedCost() {
		return getEstimatedCardinality() * getOperationCost();
	}

	@Override
	public long getCost() {
		return getEstimatedCost();
	}

	@Override
	public long getOperationCost() {
		return 242;
	}

	@Override
	public int getEstimatedCardinality() {
		return Arrays.stream(histogramBuckets)
			.mapToInt(it -> it.getRecordIds().size())
			.sum();
	}

	@Override
	public long getCostToPerformanceRatio() {
		return getCost() / (get().size() * getOperationCost());
	}

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Stream.of(
					LongStream.of(CLASS_ID),
					Arrays.stream(histogramBuckets).mapToLong(it -> it.getRecordIds().getId()).sorted()
				)
				.flatMapToLong(it -> it)
				.toArray()
		);
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray()
		);
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return Arrays.stream(histogramBuckets)
			.mapToLong(it -> it.getRecordIds().getId())
			.toArray();
	}

	@Override
	public Bitmap get() {
		final CompositeIntArray result = new CompositeIntArray();
		Arrays.stream(histogramBuckets)
			.map(ValueToRecordBitmap::getRecordIds)
			.map(Bitmap::getArray)
			.forEach(it -> result.addAll(it, 0, it.length));
		return new ArrayBitmap(result);
	}
}
