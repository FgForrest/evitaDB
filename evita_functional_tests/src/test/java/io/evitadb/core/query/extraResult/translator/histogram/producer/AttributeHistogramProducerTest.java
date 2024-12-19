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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import org.junit.jupiter.api.Test;

import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies {@link AttributeHistogramProducer} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
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

}
