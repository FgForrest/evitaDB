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
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.FILTER;

/**
 * This test verifies {@link FilteredPriceRecordsCollector} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(ENGINE)
@Tag(ORDER)
@Tag(PRICE)
@Tag(FILTER)
class FilteredPriceRecordsCollectorTest {

	@Test
	void shouldCombineNonOverlappingArrays() {
		final PriceRecordContract[] combinedRecords = combineRecords(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2)
			},
			new PriceRecord[]{
				createPriceRecord(3),
				createPriceRecord(4)
			}
		);
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
		final PriceRecordContract[] combinedRecords = combineRecords(
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
		final PriceRecordContract[] combinedRecords = combineRecords(
			new PriceRecord[0],
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(4)
			}
		);
		assertArrayEquals(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2),
				createPriceRecord(4)
			},
			combinedRecords
		);
	}

	/**
	 * Verifies that an added owner the accessors hold no price for contributes nothing.
	 */
	@Test
	@DisplayName("returns the original records when the added owner has no price")
	void shouldReturnOriginalRecordsWhenAddedOwnerHasNoPrice() {
		final PriceRecordContract[] combinedRecords = combineRecords(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2)
			},
			new PriceRecord[0],
			3
		);
		assertArrayEquals(
			new PriceRecord[]{
				createPriceRecord(1),
				createPriceRecord(2)
			},
			combinedRecords
		);
	}

	@Nonnull
	private static PriceRecord createPriceRecord(int priceId) {
		return new PriceRecord(priceId, priceId, priceId, priceId, priceId);
	}

	/**
	 * Collects the prices for the original owners, then supplements them with the prices for the added owners.
	 *
	 * @param originalRecords         the original price records
	 * @param addedRecords            the supplementary price records
	 * @param addedOwnersWithoutPrice added owners the accessor holds no price for
	 * @return the ordered union from the collector
	 */
	@Nonnull
	private static PriceRecordContract[] combineRecords(
		@Nonnull PriceRecord[] originalRecords, @Nonnull PriceRecord[] addedRecords, int... addedOwnersWithoutPrice
	) {
		final PersistentRoaringBitmap originalOwners = new PersistentRoaringBitmap();
		final PersistentRoaringBitmap addedOwners = new PersistentRoaringBitmap();
		for (final int owner : addedOwnersWithoutPrice) {
			addedOwners.add(owner);
		}
		final Map<Integer, PriceRecord> availablePrices = new TreeMap<>();
		for (final PriceRecord record : originalRecords) {
			originalOwners.add(record.entityPrimaryKey());
			availablePrices.put(record.entityPrimaryKey(), record);
		}
		for (final PriceRecord record : addedRecords) {
			addedOwners.add(record.entityPrimaryKey());
			availablePrices.putIfAbsent(record.entityPrimaryKey(), record);
		}
		final ResolvedFilteredPriceRecords prices = new ResolvedFilteredPriceRecords(
			availablePrices.values().toArray(PriceRecordContract[]::new), SortingForm.ENTITY_PK
		);
		// the accessor ignores the context, so the collector only passes the mock through
		final FilteredPriceRecordsCollector collector = new FilteredPriceRecordsCollector(
			originalOwners, List.of(context -> prices), mock(QueryExecutionContext.class)
		);
		return collector.combineResultWithAndReturnPriceRecords(addedOwners);
	}
}
