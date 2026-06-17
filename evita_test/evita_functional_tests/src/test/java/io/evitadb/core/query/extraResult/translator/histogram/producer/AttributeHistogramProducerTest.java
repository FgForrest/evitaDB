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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies {@link AttributeHistogramProducer} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(ATTRIBUTE)
class AttributeHistogramProducerTest {

	@Test
	void shouldReturnSimpleBuckets() {
		final ValueToRecordBitmap[] input = {
			new ValueToRecordBitmap(1, 1),
			new ValueToRecordBitmap(2, 2),
			new ValueToRecordBitmap(3, 3)
		};
		final ValueToRecordBitmap[] output = AttributeHistogramProducer.getCombinedAndFilteredBucketArray(
			new ConstantFormula(new BaseBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)),
			new ValueToRecordBitmap[][]{
				input
			},
			Comparator.naturalOrder()
		);
		assertArrayEquals(input, output);
	}

	@Test
	void shouldReturnFilteredSimpleBuckets() {
		final ValueToRecordBitmap[] output = AttributeHistogramProducer.getCombinedAndFilteredBucketArray(
			new ConstantFormula(new BaseBitmap(2, 4, 6, 8, 10)),
			new ValueToRecordBitmap[][]{
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 1, 2, 3),
					new ValueToRecordBitmap(2, 4, 5, 6),
					new ValueToRecordBitmap(3, 7, 8, 9)
				}
			},
			Comparator.naturalOrder()
		);
		assertArrayEquals(
			new ValueToRecordBitmap[]{
				new ValueToRecordBitmap(1, 2),
				new ValueToRecordBitmap(2, 4, 6),
				new ValueToRecordBitmap(3, 8)
			},
			output
		);
	}

	@Test
	void shouldReturnCombinedBuckets() {
		final ValueToRecordBitmap[] output = AttributeHistogramProducer.getCombinedAndFilteredBucketArray(
			new ConstantFormula(new BaseBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)),
			new ValueToRecordBitmap[][]{
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 1, 3),
					new ValueToRecordBitmap(3, 8, 9)
				},
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 1),
					new ValueToRecordBitmap(2, 6)
				},
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 2),
					new ValueToRecordBitmap(2, 6),
					new ValueToRecordBitmap(3, 7)
				},
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(2, 4, 5)
				}
			},
			Comparator.naturalOrder()
		);
		assertArrayEquals(
			new ValueToRecordBitmap[]{
				new ValueToRecordBitmap(1, 1, 2, 3),
				new ValueToRecordBitmap(2, 4, 5, 6),
				new ValueToRecordBitmap(3, 7, 8, 9)
			},
			output
		);
	}

	@Test
	void shouldReturnFilteredAndCombinedBuckets() {
		final ValueToRecordBitmap[] output = AttributeHistogramProducer.getCombinedAndFilteredBucketArray(
			new ConstantFormula(new BaseBitmap(2, 4, 6, 8, 10)),
			new ValueToRecordBitmap[][]{
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 1, 3),
					new ValueToRecordBitmap(2, 6)
				},
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 1),
					new ValueToRecordBitmap(3, 8, 9)
				},
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 2),
					new ValueToRecordBitmap(2, 6),
					new ValueToRecordBitmap(3, 7)
				},
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(2, 4, 5)
				}
			},
			Comparator.naturalOrder()
		);
		assertArrayEquals(
			new ValueToRecordBitmap[]{
				new ValueToRecordBitmap(1, 2),
				new ValueToRecordBitmap(2, 4, 6),
				new ValueToRecordBitmap(3, 8)
			},
			output
		);
	}

	/**
	 * Verifies the integer-domain histogram math for a `BigDecimal` attribute whose filter index now stores
	 * already-scaled `int` keys (a value with `indexDecimalPlaces = 2` is held as `value * 100`). The cruncher
	 * is wired exactly like {@link AttributeHistogramComputer} does for the `STANDARD` behaviour: an identity
	 * threshold retriever (the bucket value is already the integer-domain key) and a
	 * `BigDecimal.valueOf(scaledInt, places)` reconstruction. The user-facing thresholds / max must restore the
	 * original magnitudes 1.50 / 2.00 / 2.50.
	 */
	@Test
	void shouldReconstructBigDecimalBoundariesFromScaledIntegerBuckets() {
		final int places = 2;
		// scaled integer keys as stored by the filter index for BigDecimal values 1.50, 2.00, 2.50
		final ValueToRecordBitmap[] buckets = {
			new ValueToRecordBitmap(150, 1),
			new ValueToRecordBitmap(200, 2),
			new ValueToRecordBitmap(250, 3)
		};
		// identity converter: the bucket value is an already-scaled Integer in the integer domain
		final ToIntFunction<ValueToRecordBitmap> thresholdRetriever = bucket -> (Integer) bucket.getValue();

		final HistogramDataCruncher<ValueToRecordBitmap> cruncher = new HistogramDataCruncher<>(
			"test histogram",
			3,
			places,
			buckets,
			thresholdRetriever,
			bucket -> bucket.getRecordIds().size(),
			value -> BigDecimal.valueOf(value, places),
			value -> value.stripTrailingZeros().scaleByPowerOfTen(places).intValueExact()
		);

		final CacheableBucket[] histogram = cruncher.getHistogram();
		// the left bound of the first bucket is the restored minimum 1.50
		assertEquals(0, new BigDecimal("1.50").compareTo(histogram[0].threshold()));
		// the right bound of the last bucket is the restored maximum 2.50
		assertEquals(0, new BigDecimal("2.50").compareTo(cruncher.getMaxValue()));
	}

}
