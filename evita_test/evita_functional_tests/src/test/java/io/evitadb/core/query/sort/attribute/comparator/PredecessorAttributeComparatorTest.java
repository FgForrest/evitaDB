/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.sort.attribute.comparator;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PredecessorAttributeComparator} - the K-way predecessor comparator that resolves each entity's
 * sorted position through a chain of {@link SortedRecordsProvider}s, parks entities no provider can place into a
 * lazily-created collector, and memoizes resolved positions in a per-provider cache.
 *
 * The comparator only reads {@link EntityContract#getPrimaryKeyOrThrowException()} off the entities, so the tests
 * follow the lightweight idiom of the sibling {@code AttributeComparatorTest}: mock just that one accessor and drive
 * the position resolution through real {@link SortedRecordsProvider} instances (the shared {@link SortedRecordsProvider#EMPTY}
 * and small array-backed {@link SortedRecordsSupplier}s) rather than a full query-execution harness.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(ORDER)
@Tag(ATTRIBUTE)
@Tag(COMPARATOR)
@DisplayName("Predecessor attribute comparator")
class PredecessorAttributeComparatorTest {

	/**
	 * Builds an {@link EntityContract} mock whose only stubbed behaviour is the primary-key accessor the comparator reads.
	 *
	 * @param primaryKey the primary key the mock returns
	 * @return a lightweight entity mock addressable by its primary key
	 */
	@Nonnull
	private static EntityContract entityWithPrimaryKey(int primaryKey) {
		final EntityContract entity = Mockito.mock(EntityContract.class);
		Mockito.when(entity.getPrimaryKeyOrThrowException()).thenReturn(primaryKey);
		return entity;
	}

	/**
	 * Builds a single-record array-backed {@link SortedRecordsProvider} (the legacy materialized flavour) that places
	 * `recordId` at sorted position `0` and reports every other record id as absent.
	 *
	 * @param recordId the sole record id the provider knows, seated at position 0
	 * @return an array-backed provider containing exactly one record
	 */
	@Nonnull
	private static SortedRecordsProvider singleRecordProvider(int recordId) {
		return new SortedRecordsSupplier(
			1L,
			new int[]{recordId},
			new int[]{0},
			new BaseBitmap(recordId),
			SortedComparableForwardSeeker.EMPTY
		);
	}

	/**
	 * Drains the comparator's not-found hand-off into an ordered list so the accumulation order can be asserted.
	 *
	 * @param entities the iterable returned by {@link PredecessorAttributeComparator#getNonSortedEntities()}
	 * @return the entities in iteration order
	 */
	@Nonnull
	private static List<EntityContract> collect(@Nonnull Iterable<EntityContract> entities) {
		final List<EntityContract> collected = new ArrayList<>();
		for (final EntityContract entity : entities) {
			collected.add(entity);
		}
		return collected;
	}

	@Nested
	@DisplayName("Unsorted-entity collection")
	class UnsortedCollectionTest {

		@Test
		@DisplayName("accumulates every unsortable entity across successive compare calls without resetting")
		void shouldRetainAllUnsortableEntitiesAcrossMultipleCompareCalls() {
			// a provider that contains nothing forces every compared entity into the not-found collector
			final PredecessorAttributeComparator comparator = new PredecessorAttributeComparator(
				() -> new SortedRecordsProvider[]{SortedRecordsProvider.EMPTY}
			);
			comparator.prepareFor(100);

			final EntityContract e1 = entityWithPrimaryKey(1);
			final EntityContract e2 = entityWithPrimaryKey(2);
			final EntityContract e3 = entityWithPrimaryKey(3);
			final EntityContract e4 = entityWithPrimaryKey(4);
			final EntityContract e5 = entityWithPrimaryKey(5);
			final EntityContract e6 = entityWithPrimaryKey(6);

			// two mutually-absent entities compare equal (neither is found in any provider, so no position or
			// provider-precedence tie-break applies) while both are handed to the not-found collector
			assertEquals(0, comparator.compare(e1, e2), "two unsortable entities compare equal");
			assertEquals(0, comparator.compare(e3, e4), "two unsortable entities compare equal");
			assertEquals(0, comparator.compare(e5, e6), "two unsortable entities compare equal");

			// the collector is created once and appended to on every compare - it must hold all six entities, in the
			// order they were handed over (o1 before o2 within each compare call)
			final List<EntityContract> nonSorted = collect(comparator.getNonSortedEntities());
			assertEquals(6, nonSorted.size(), "every entity from every compare call must be retained");
			assertEquals(List.of(e1, e2, e3, e4, e5, e6), nonSorted, "accumulation order must be preserved");
		}

		@Test
		@DisplayName("clears the accumulated unsortable entities on prepareFor")
		void shouldResetNonSortedEntitiesOnPrepareFor() {
			final PredecessorAttributeComparator comparator = new PredecessorAttributeComparator(
				() -> new SortedRecordsProvider[]{SortedRecordsProvider.EMPTY}
			);
			comparator.prepareFor(100);

			assertEquals(
				0, comparator.compare(entityWithPrimaryKey(1), entityWithPrimaryKey(2)),
				"two unsortable entities compare equal"
			);
			assertTrue(
				comparator.getNonSortedEntities().iterator().hasNext(),
				"the first comparison must have parked both unsortable entities"
			);

			// prepareFor starts a fresh sort round and must drop the previously collected entities
			comparator.prepareFor(50);
			assertFalse(
				comparator.getNonSortedEntities().iterator().hasNext(),
				"prepareFor must clear the not-found collector"
			);
		}
	}

	@Nested
	@DisplayName("Provider position ordering")
	class ProviderPositionOrderingTest {

		@Test
		@DisplayName("orders both entities by their sorted position and parks nothing when both resolve")
		void shouldReturnEmptyNonSortedEntitiesWhenBothEntitiesResolve() {
			// sorted order places record 20 at position 0 and record 10 at position 1 (allRecords is ascending by id)
			final SortedRecordsProvider provider = new SortedRecordsSupplier(
				1L,
				new int[]{20, 10},
				new int[]{1, 0},
				new BaseBitmap(10, 20),
				SortedComparableForwardSeeker.EMPTY
			);
			final PredecessorAttributeComparator comparator = new PredecessorAttributeComparator(
				() -> new SortedRecordsProvider[]{provider}
			);
			comparator.prepareFor(100);

			final EntityContract entity10 = entityWithPrimaryKey(10);
			final EntityContract entity20 = entityWithPrimaryKey(20);

			// record 20 (position 0) sorts before record 10 (position 1)
			assertEquals(-1, comparator.compare(entity20, entity10), "lower position must sort first");
			assertEquals(1, comparator.compare(entity10, entity20), "comparison must be antisymmetric");
			assertEquals(0, comparator.compare(entity10, entity10), "an entity compared with itself is equal");

			// both entities were placed, so nothing is parked as unsortable
			assertFalse(
				comparator.getNonSortedEntities().iterator().hasNext(),
				"no entity should be collected when every entity resolves"
			);
		}

		@Test
		@DisplayName("ranks a found entity relative to a missing one and honours provider precedence")
		void shouldRankFoundEntityBeforeNotFoundAndEarlierProviderFirst() {
			// single provider holding record 20 only: comparing it against the absent record 99 exercises the
			// only-one-found epilogue branch (the resolved entity gets the provider-index rank, the missing one -1)
			final PredecessorAttributeComparator singleProviderComparator = new PredecessorAttributeComparator(
				() -> new SortedRecordsProvider[]{singleRecordProvider(20)}
			);
			singleProviderComparator.prepareFor(100);
			final EntityContract found = entityWithPrimaryKey(20);
			final EntityContract missing = entityWithPrimaryKey(99);
			assertEquals(1, singleProviderComparator.compare(found, missing), "provider index 0 ranks after the -1 miss");
			assertEquals(-1, singleProviderComparator.compare(missing, found), "the mirrored comparison flips the sign");
			// the missing entity is handed to the collector on each comparison (found twice)
			assertEquals(2, collect(singleProviderComparator.getNonSortedEntities()).size(), "only the missing entity is parked");

			// two-provider chain: record 100 lives only in the earlier provider, record 200 only in the later one -
			// the earlier (lower-index) provider must win, exercising the found-in-different-providers epilogue branch
			final PredecessorAttributeComparator twoProviderComparator = new PredecessorAttributeComparator(
				() -> new SortedRecordsProvider[]{singleRecordProvider(100), singleRecordProvider(200)}
			);
			twoProviderComparator.prepareFor(100);
			final EntityContract earlyProviderEntity = entityWithPrimaryKey(100);
			final EntityContract lateProviderEntity = entityWithPrimaryKey(200);
			assertEquals(
				-1, twoProviderComparator.compare(earlyProviderEntity, lateProviderEntity),
				"the record from the earlier provider must sort first"
			);
			assertEquals(
				1, twoProviderComparator.compare(lateProviderEntity, earlyProviderEntity),
				"provider precedence is antisymmetric"
			);
			// both records were placed by some provider, so none is parked as unsortable
			assertFalse(
				twoProviderComparator.getNonSortedEntities().iterator().hasNext(),
				"records found in any provider must not be collected"
			);
		}
	}

	@Nested
	@DisplayName("Position cache (computeIfAbsent)")
	class PositionCacheTest {

		@Test
		@DisplayName("caches a resolved position of zero via the reserved remap and never re-invokes the locator")
		void shouldRoundTripPositionZeroThroughComputeIfAbsentCache() {
			// position 0 collides with the map's "absent" default, so it is stored as the reserved Integer.MIN_VALUE remap
			final IntIntMap cache = new IntIntHashMap(16);
			final CountingLocator locator = new CountingLocator(0);
			final int primaryKey = 42;

			final int first = PredecessorAttributeComparator.computeIfAbsent(cache, primaryKey, locator);
			assertEquals(0, first, "the resolved position must be reported verbatim as 0");
			assertEquals(1, locator.invocations(), "the first lookup must invoke the locator exactly once");
			assertEquals(Integer.MIN_VALUE, cache.get(primaryKey), "position 0 must be stored as the reserved remap");

			final int second = PredecessorAttributeComparator.computeIfAbsent(cache, primaryKey, locator);
			assertEquals(0, second, "the cached remap must decode back to position 0");
			assertEquals(1, locator.invocations(), "a cached position must not re-invoke the locator");
		}

		@Test
		@DisplayName("caches the not-found sentinel and a positive position, each resolved once")
		void shouldCacheAndReturnPositionNotFoundSentinelViaComputeIfAbsent() {
			final IntIntMap cache = new IntIntHashMap(16);

			// the not-found sentinel (-1) is stored verbatim (it is neither 0 nor the reserved remap) and memoized
			final CountingLocator absentLocator = new CountingLocator(SortedRecordsProvider.POSITION_NOT_FOUND);
			final int absentKey = 7;
			assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, PredecessorAttributeComparator.computeIfAbsent(cache, absentKey, absentLocator));
			assertEquals(1, absentLocator.invocations(), "the first lookup resolves the sentinel");
			assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, PredecessorAttributeComparator.computeIfAbsent(cache, absentKey, absentLocator));
			assertEquals(1, absentLocator.invocations(), "the cached sentinel must not re-invoke the locator");
			assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, cache.get(absentKey), "the sentinel is stored verbatim");

			// a positive position round-trips unchanged and is likewise resolved only once
			final CountingLocator positiveLocator = new CountingLocator(5);
			final int presentKey = 9;
			assertEquals(5, PredecessorAttributeComparator.computeIfAbsent(cache, presentKey, positiveLocator));
			assertEquals(1, positiveLocator.invocations(), "the first lookup resolves the position");
			assertEquals(5, PredecessorAttributeComparator.computeIfAbsent(cache, presentKey, positiveLocator));
			assertEquals(1, positiveLocator.invocations(), "the cached position must not re-invoke the locator");
			assertEquals(5, cache.get(presentKey), "a positive position is stored verbatim");
		}
	}

	/**
	 * Counting {@link IntUnaryOperator} that returns a fixed position and records how many times it was invoked, so the
	 * memoization of {@link PredecessorAttributeComparator#computeIfAbsent} can be asserted.
	 */
	private static final class CountingLocator implements IntUnaryOperator {
		private final int result;
		private int invocations;

		CountingLocator(int result) {
			this.result = result;
		}

		@Override
		public int applyAsInt(int operand) {
			this.invocations++;
			return this.result;
		}

		int invocations() {
			return this.invocations;
		}
	}

}
