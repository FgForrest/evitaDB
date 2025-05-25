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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.function.BiFunction;

/**
 * Histogram subset is a slice of the original histogram that references all key data. Slices can be combined together,
 * provide useful statistical information such as min/max or can output all record ids in the entire subset.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class InvertedIndexSubSet {
	private final long indexTransactionId;
	@Getter private final ValueToRecordBitmap[] histogramBuckets;
	private final BiFunction<Long, ValueToRecordBitmap[], Formula> aggregationLambda;
	private Formula memoizedResult;

	/**
	 * Returns record ids of all buckets in this histogram subset as single bitmap (ordered distinct array).
	 * For aggregation of record ids of different buckets {@link #aggregationLambda} is used. Result of this call
	 * is memoized so that additional calls are cheap and returns already computed result.
	 */
	public Bitmap getRecordIds() {
		return getFormula().compute();
	}

	/**
	 * Returns formula for computing record ids of all buckets in this histogram subset as single bitmap (ordered
	 * distinct array). For aggregation of record ids of different buckets {@link #aggregationLambda} is used.
	 * Result of this call is memoized so that additional calls are cheap and returns already computed result.
	 */
	public Formula getFormula() {
		if (this.memoizedResult == null) {
			this.memoizedResult = this.histogramBuckets.length == 0 ?
				EmptyFormula.INSTANCE : this.aggregationLambda.apply(this.indexTransactionId, this.histogramBuckets);
		}
		return this.memoizedResult;
	}

	/**
	 * Returns true if this histogram subset contains no buckets / no record ids.
	 */
	public boolean isEmpty() {
		return ArrayUtils.isEmpty(this.histogramBuckets);
	}

	/**
	 * Returns minimal {@link ValueToRecordBitmap#getValue()} of buckets in this histogram subset.
	 */
	public Serializable getMinimalValue() {
		return isEmpty() ? null : this.histogramBuckets[0].getValue();
	}

	/**
	 * Returns maximal {@link ValueToRecordBitmap#getValue()} of buckets in this histogram subset.
	 */
	public Serializable getMaximalValue() {
		return isEmpty() ? null : this.histogramBuckets[this.histogramBuckets.length - 1].getValue();
	}
}
