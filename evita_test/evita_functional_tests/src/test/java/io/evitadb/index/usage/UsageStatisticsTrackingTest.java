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

package io.evitadb.index.usage;

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.OffsetDateTime;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what `server.usageStatisticsTracking: false` actually buys and what it must never cost.
 *
 * The switch exists because the counters are not free at rest: an {@link io.evitadb.index.IndexActivity} holder is five
 * longs, and a large catalog holds hundreds of thousands of indexes. Switching it off has to remove the *object*, not
 * merely stop incrementing it - a shared no-op holder would reclaim nothing per index and would hand every caller an
 * object that lies about having been observed.
 *
 * The other half is the reporting contract, and it is the half that can do real damage. A capability row whose count
 * reads `0` against a live observation window says *"nothing uses this flag, drop it"* - which is precisely the action
 * this whole surface exists to inform. On a server that never counted, that statement is false and the schema mutation
 * it invites is destructive. Every carrier therefore states whether its numbers were taken at all, and the record
 * constructors refuse the contradictory combination outright rather than letting it reach an operator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
@DisplayName("Usage statistics tracking switch")
class UsageStatisticsTrackingTest {

	private static final OffsetDateTime SOME_INSTANT = OffsetDateTime.now();

	@Nested
	@DisplayName("configuration")
	class Configuration {

		@Test
		@DisplayName("track by default, because a diagnostic nobody switched on answers nothing")
		void shouldDefaultToTracking() {
			assertTrue(ServerOptions.DEFAULT_USAGE_STATISTICS_TRACKING);
			assertTrue(new ServerOptions().usageStatisticsTracking());
			assertTrue(ServerOptions.builder().build().usageStatisticsTracking());
		}

		@Test
		@DisplayName("carry the switch through the builder and its copy constructor")
		void shouldRoundTripThroughBuilder() {
			final ServerOptions off = ServerOptions.builder()
				.usageStatisticsTracking(false)
				.build();
			assertFalse(off.usageStatisticsTracking());
			// the copy builder is what a caller overriding one unrelated option goes through - dropping the flag there
			// would silently re-enable tracking on a server that asked for it off
			assertFalse(ServerOptions.builder(off).build().usageStatisticsTracking());
			assertTrue(ServerOptions.builder(off).usageStatisticsTracking(true).build().usageStatisticsTracking());
		}
	}

	@Nested
	@DisplayName("index allocation")
	class IndexAllocation {

		@Test
		@DisplayName("allocate no activity holder at all, rather than a holder that counts nothing")
		void shouldAllocateNoHolderWhenOff() {
			final GlobalEntityIndex tracked = new GlobalEntityIndex(
				1, "product", new EntityIndexKey(EntityIndexType.GLOBAL), true
			);
			final GlobalEntityIndex untracked = new GlobalEntityIndex(
				2, "product", new EntityIndexKey(EntityIndexType.GLOBAL), false
			);

			assertNotNull(tracked.getActivity());
			assertNull(
				untracked.getActivity(),
				"An untracked index must hold no activity object - the five longs are what the switch reclaims"
			);
		}

		@Test
		@DisplayName("charge nothing for a holder that does not exist")
		void shouldNotChargeHeapForAbsentHolder() {
			final GlobalEntityIndex tracked = new GlobalEntityIndex(
				1, "product", new EntityIndexKey(EntityIndexType.GLOBAL), true
			);
			final GlobalEntityIndex untracked = new GlobalEntityIndex(
				2, "product", new EntityIndexKey(EntityIndexType.GLOBAL), false
			);

			// the two indexes are identical but for the holder, so the whole difference is the holder's own weight -
			// asserted as a strict inequality rather than an exact byte count, which `EntityIndexHeapSizeTest` owns
			assertTrue(
				untracked.getHeapSizeInBytes() < tracked.getHeapSizeInBytes(),
				"An index with no activity holder must not be charged for one"
			);
		}

		@Test
		@DisplayName("keep the reduced index and the catalog index on the same rule")
		void shouldApplyToEveryIndexKind() {
			final EntityIndexKey reducedKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey("categories", 42), new Serializable[0])
			);
			assertNull(new ReducedEntityIndex(3, "product", reducedKey, false).getActivity());
			assertNotNull(new ReducedEntityIndex(4, "product", reducedKey, true).getActivity());

			assertNull(new CatalogIndex(Scope.LIVE, false).getActivity());
			assertNotNull(new CatalogIndex(Scope.LIVE, true).getActivity());
		}
	}

	@Nested
	@DisplayName("capability rows")
	class CapabilityRows {

		@Test
		@DisplayName("still list every declared capability, so `not measured` never looks like `not declared`")
		void shouldStillListDeclaredCapabilities() {
			final SchemaCapabilityUsageRegistry registry = new SchemaCapabilityUsageRegistry();
			registry.resolve(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, null, "ean", Capability.FILTERABLE, Scope.LIVE)
			);

			final var rows = SchemaCapabilityUsageProjection.project("product", registry, false);

			assertEquals(1, rows.size(), "Seeding runs whether or not anything counts - the row must still be there");
			final SchemaCapabilityUsageStatistics row = rows.get(0);
			assertFalse(row.measured());
			assertEquals(0L, row.requestedCount());
			assertEquals(0L, row.updatedCount());
			assertNull(row.lastRequestedAt());
			assertNull(row.lastUpdatedAt());
			// the window stays populated: the capability genuinely has existed since then, and only the counts are
			// unknown - which is exactly what `measured` says and what `observedSince` must not be made to say
			assertNotNull(row.observedSince());
		}

		@Test
		@DisplayName("mark the same registry as measured when the server does count")
		void shouldMarkRowsMeasuredWhenOn() {
			final SchemaCapabilityUsageRegistry registry = new SchemaCapabilityUsageRegistry();
			registry.resolve(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, null, "ean", Capability.FILTERABLE, Scope.LIVE)
			).recordRequested(System.currentTimeMillis());

			final var rows = SchemaCapabilityUsageProjection.project("product", registry, true);

			assertEquals(1, rows.size());
			assertTrue(rows.get(0).measured());
			assertEquals(1L, rows.get(0).requestedCount());
		}
	}

	@Nested
	@DisplayName("carrier contracts")
	class CarrierContracts {

		@Test
		@DisplayName("refuse a capability row that reports counts while claiming to be unmeasured")
		void shouldRejectContradictoryCapabilityRow() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new SchemaCapabilityUsageStatistics(
					"product", ElementKind.ATTRIBUTE, null, "ean", Capability.FILTERABLE, Scope.LIVE,
					7L, 0L, null, null, SOME_INSTANT, false
				),
				"A row that was never counted cannot have a count"
			);
		}

		@Test
		@DisplayName("refuse a browsed index that reports activity while claiming to be unmeasured")
		void shouldRejectContradictoryBrowsedIndex() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new BrowsedIndex(
					"product", 1, EntityIndexType.GLOBAL, Scope.LIVE, null, null, null, 10,
					0L, 0L, null, null, SOME_INSTANT, false
				),
				"An unmeasured index cannot carry an observation window either - nothing was observed"
			);
		}

		@Test
		@DisplayName("refuse an index detail that reports activity while claiming to be unmeasured")
		void shouldRejectContradictoryIndexDetail() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new IndexDetail(
					"product", 1, 4_096L,
					new IndexCardinality(
						EntityIndexType.GLOBAL, Scope.LIVE, null, 10, 1, new AttributeCardinality[0]
					),
					3L, 0L, null, null, null, false
				),
				"An unmeasured detail cannot carry a query count"
			);
		}

		@Test
		@DisplayName("accept the shape an untracked server actually produces")
		void shouldAcceptFullyUnmeasuredRows() {
			final BrowsedIndex row = new BrowsedIndex(
				"product", 1, EntityIndexType.GLOBAL, Scope.LIVE, null, null, null, 10,
				0L, 0L, null, null, null, false
			);
			assertFalse(row.measured());
			assertNull(row.observedSince());
		}
	}
}
