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

import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This test verifies contract of {@link InvertedIndexSubSet}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HistogramSubSetTest {
	private final InvertedIndexSubSet tested = new InvertedIndexSubSet(
		1L,
		new ValueToRecordBitmap[]{
			new ValueToRecordBitmap(1, 1, 2),
			new ValueToRecordBitmap(5, 8, 9),
		},
		(indexTransactionId, histogramBuckets) -> new OrFormula(
			new long[]{indexTransactionId}, Arrays.stream(histogramBuckets).map(ValueToRecordBitmap::getRecordIds).toArray(RoaringBitmapBackedBitmap[]::new)
		)
	);

	@Test
	void shouldNotBeEmpty() {
		assertFalse(this.tested.isEmpty());
	}

	@Test
	void shouldReturnMin() {
		assertEquals(1, this.tested.getMinimalValue());
	}

	@Test
	void shouldReturnMax() {
		assertEquals(5, this.tested.getMaximalValue());
	}

	@Test
	void shouldReturnAggregatedRecordIds() {
		assertArrayEquals(new int[]{1, 2, 8, 9}, this.tested.getRecordIds().getArray());
	}

}
