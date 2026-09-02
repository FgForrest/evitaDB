/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.Scope;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;

import static io.evitadb.index.IndexHeapSizeAssertions.AUTOBOX_CACHE_CEILING;
import static io.evitadb.index.IndexHeapSizeAssertions.assertDivergenceDoesNotGrowWithTheData;
import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.measuredHeapOf;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures every price index's `getHeapSizeInBytes` against what JOL actually finds on the heap.
 *
 * # The one thing this suite exists to pin
 *
 * A {@link PriceListAndCurrencyPriceRefIndex} holds **the very same** {@link PriceRecordContract} instances as the
 * {@link PriceListAndCurrencyPriceSuperIndex} of its combination — its tree is populated by copying references out of
 * the super index, never by allocating records. Only the super index may charge those bodies. If a reduced index
 * charged them too, the reported footprint of a catalog would grow with the number of *views* of the price data
 * rather than with the data, and the more scopes and reduced indexes a collection had the further out it would be.
 *
 * Every reduced-index test therefore hands the super index's records to the walker as shared roots: the walk then
 * measures exactly what a spine-only figure claims, and any body that crept into the arithmetic shows up immediately.
 *
 * See `documentation/developer/heap-size-testing.md` for the ownership rules and the traps behind these assertions.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("Price index heap size")
class PriceIndexHeapSizeTest {

	/**
	 * The price list and currency every fixture indexes under.
	 */
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE
	);

	/**
	 * Everything a per-combination index reaches but does not charge: the key the enclosing map owns and files it
	 * under, the flush bookkeeping, and (for a reduced index) the scope enum constant the JVM owns — plus the
	 * scaffolding of the two sub-structures it owns outright. A {@link io.evitadb.index.range.RangeIndex} excludes its
	 * own page bookkeeping and transactional wrapper, and an element-keyed tree its key extractor, so an index holding
	 * them reaches those through it and must name them by the same nested paths its own test does.
	 */
	private static final String[] SUPER_EXCLUSIONS = {
		"priceIndexKey", "pageStreamRegistry",
		"validityIndex.pageStreamRegistry", "validityIndex.ranges.transactionalLayerWrapper",
		"priceRecords.keyExtractor"
	};
	private static final String[] REF_EXCLUSIONS = {
		"priceIndexKey", "scope",
		"validityIndex.pageStreamRegistry", "validityIndex.ranges.transactionalLayerWrapper",
		"priceRecords.keyExtractor"
	};

	/**
	 * Builds a super index holding `records` prices, every id clearing {@link #AUTOBOX_CACHE_CEILING} so no boxed
	 * value is the JVM's rather than the index's.
	 *
	 * Every third price is inner-record specific, so both stored record shapes are exercised — they differ by one
	 * `int` component, which a sizer that priced only one of them would get wrong.
	 *
	 * @param records how many price records to seed
	 * @return the seeded index
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceSuperIndex superIndex(int records) {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
		for (int i = 0; i < records; i++) {
			final int id = AUTOBOX_CACHE_CEILING + i + 1;
			final PriceRecordContract priceRecord = i % 3 == 0 ?
				new PriceRecordInnerRecordSpecific(id, id, AUTOBOX_CACHE_CEILING + i + 1, id, 100 + i, 90 + i) :
				new PriceRecord(id, id, AUTOBOX_CACHE_CEILING + i + 1, 100 + i, 90 + i);
			index.addPrice(priceRecord, null);
		}
		return index;
	}

	/**
	 * Builds a reduced index referencing every record the passed super index holds.
	 *
	 * @param superIndex the index owning the records
	 * @return the reduced index sharing them
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceRefIndex refIndex(
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex
	) {
		final PriceListAndCurrencyPriceRefIndex index = new PriceListAndCurrencyPriceRefIndex(
			Scope.LIVE, PRICE_INDEX_KEY
		);
		for (final PriceRecordContract priceRecord : superIndex.getPriceRecords()) {
			index.addPrice(priceRecord.internalPriceId(), null, superIndex);
		}
		return index;
	}

	@Nested
	@DisplayName("per-combination super index")
	class SuperIndexes {

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final PriceListAndCurrencyPriceSuperIndex index = superIndex(0);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, SUPER_EXCLUSIONS);
		}

		@Test
		void shouldMeasureASeededIndexExactly() {
			final PriceListAndCurrencyPriceSuperIndex index = superIndex(200);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, SUPER_EXCLUSIONS);
		}

		@Test
		void shouldNotLetTheDivergenceGrowWithTheRecordCount() {
			final PriceListAndCurrencyPriceSuperIndex small = superIndex(50);
			final PriceListAndCurrencyPriceSuperIndex large = superIndex(800);
			assertDivergenceDoesNotGrowWithTheData(
				small.getHeapSizeInBytes(), small,
				large.getHeapSizeInBytes(), large,
				SUPER_EXCLUSIONS
			);
		}

		@Test
		void shouldChargeTheEntityPricesMapOnTopOfTheRecordTree() {
			// the map is the same records re-indexed by entity id, so it adds its spine and wrappers but no bodies -
			// which means it must cost something, and materially less than the records themselves
			final PriceListAndCurrencyPriceSuperIndex index = superIndex(200);
			final long whole = index.getHeapSizeInBytes();
			final String[] alsoWithoutEntityPrices = new String[SUPER_EXCLUSIONS.length + 1];
			System.arraycopy(SUPER_EXCLUSIONS, 0, alsoWithoutEntityPrices, 0, SUPER_EXCLUSIONS.length);
			alsoWithoutEntityPrices[SUPER_EXCLUSIONS.length] = "entityPrices";
			final long withoutEntityPrices = measuredHeapOf(index, alsoWithoutEntityPrices);
			assertTrue(
				whole > withoutEntityPrices,
				"the entity-prices map must show up as occupancy - " + whole + " vs " + withoutEntityPrices
			);
		}

	}

	@Nested
	@DisplayName("per-combination reduced index")
	class RefIndexes {

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final PriceListAndCurrencyPriceRefIndex index = new PriceListAndCurrencyPriceRefIndex(
				Scope.LIVE, PRICE_INDEX_KEY
			);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, REF_EXCLUSIONS);
		}

		@Test
		void shouldChargeItsSpineButNoneOfTheRecordBodiesItShares() {
			final PriceListAndCurrencyPriceSuperIndex superIndex = superIndex(200);
			final PriceListAndCurrencyPriceRefIndex index = refIndex(superIndex);
			// hand the walker the bodies as borrowed roots: what remains is precisely the spine this index owns, and
			// the arithmetic claiming a spine-only figure has to match it to the byte
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, superIndex.getPriceRecords(), REF_EXCLUSIONS
			);
		}

		@Test
		void shouldStayFarBelowTheSuperIndexItReferences() {
			final PriceListAndCurrencyPriceSuperIndex superIndex = superIndex(500);
			final PriceListAndCurrencyPriceRefIndex index = refIndex(superIndex);
			assertTrue(
				index.getHeapSizeInBytes() < superIndex.getHeapSizeInBytes(),
				"a reduced index that borrows every record must weigh less than the index owning them - " +
					index.getHeapSizeInBytes() + " vs " + superIndex.getHeapSizeInBytes()
			);
		}

		@Test
		void shouldNotChargeMoreBecauseASecondReducedIndexSharesTheSameRecords() {
			// two reduced indexes over one super index: the second must cost the same as the first, because what it
			// adds is another spine and NOT another copy of the payload
			final PriceListAndCurrencyPriceSuperIndex superIndex = superIndex(200);
			final PriceListAndCurrencyPriceRefIndex first = refIndex(superIndex);
			final PriceListAndCurrencyPriceRefIndex second = refIndex(superIndex);
			assertEquals(
				first.getHeapSizeInBytes(), second.getHeapSizeInBytes(),
				"a reduced index's figure must depend on what it references, not on how many others reference it"
			);
		}

	}

	@Nested
	@DisplayName("price index containers")
	class Containers {

		@Test
		void shouldMeasureAnEmptySuperContainerExactly() {
			final PriceSuperIndex index = new PriceSuperIndex();
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "priceIndexes.transactionalLayerWrapper"
			);
		}

		@Test
		void shouldMeasureAnEmptyRefContainerExactly() {
			final PriceRefIndex index = new PriceRefIndex(Scope.LIVE);
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, "scope", "priceIndexes.transactionalLayerWrapper"
			);
		}

	}

}
