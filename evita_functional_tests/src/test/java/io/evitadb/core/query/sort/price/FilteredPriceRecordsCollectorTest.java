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

package io.evitadb.core.query.sort.price;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies {@link FilteredPriceRecordsCollector} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class FilteredPriceRecordsCollectorTest {

	@Test
	void shouldCombineNonOverlappingArrays() {
		final FilteredPriceRecordsCollector tested = new MockedPriceRecordsCollector(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2)
			},
			new PriceRecord[]{
				createPriceRecord(3),
				createPriceRecord(4)
			}
		);
		final PriceRecordContract[] combinedRecords = tested.combineResultWithAndReturnPriceRecords(new RoaringBitmap());
		assertArrayEquals(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(3),
				createPriceRecord(4)
			},
			combinedRecords
		);
	}

	@Test
	void shouldCombineOverlappingArrays() {
		final FilteredPriceRecordsCollector tested = new MockedPriceRecordsCollector(
			new PriceRecord[]{
				createPriceRecord(2),
				createPriceRecord(3)
			},
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(4)
			}
		);
		final PriceRecordContract[] combinedRecords = tested.combineResultWithAndReturnPriceRecords(new RoaringBitmap());
		assertArrayEquals(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(3),
				createPriceRecord(4)
			},
			combinedRecords
		);
	}

	@Test
	void shouldCombineEmptyWithFullArray() {
		final FilteredPriceRecordsCollector tested = new MockedPriceRecordsCollector(
			new PriceRecord[0],
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(4)
			}
		);
		final PriceRecordContract[] combinedRecords = tested.combineResultWithAndReturnPriceRecords(new RoaringBitmap());
		assertArrayEquals(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(4)
			},
			combinedRecords
		);
	}

	@Nonnull
	private static PriceRecord createPriceRecord(int priceId) {
		return new PriceRecord(priceId, priceId, priceId, priceId, priceId);
	}

	private static class MockedPriceRecordsCollector extends FilteredPriceRecordsCollector {
		private final PriceRecord[] addedRecords;

		public MockedPriceRecordsCollector(@Nonnull PriceRecord[] originalRecords, @Nonnull PriceRecord[] addedRecords) {
			super(new FilteredPriceRecordsLookupResult(originalRecords), Collections.emptyList(), null);
			this.addedRecords = addedRecords;
		}

		@Nonnull
		@Override
		protected FilteredPriceRecordsLookupResult computeResult(
			@Nonnull RoaringBitmap filteredResults,
			@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors,
			@Nonnull QueryExecutionContext context
		) {
			return new FilteredPriceRecordsLookupResult(this.addedRecords);
		}

	}

}
