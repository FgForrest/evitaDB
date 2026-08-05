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

package io.evitadb.externalApi.grpc.requestResponse;

import com.google.protobuf.InvalidProtocolBufferException;
import io.evitadb.api.CatalogState;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexKindCount;
import io.evitadb.api.statistics.CollectionRecordCounts;
import io.evitadb.api.statistics.CollectionStorageComposition;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.api.statistics.CollectionsInfo;
import io.evitadb.api.statistics.CollectionsInfo.CollectionInfo;
import io.evitadb.api.statistics.ActivityStatistics;
import io.evitadb.api.statistics.CommitPipelineStatistics;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.ComponentStatus;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.EntityIndexKind;
import io.evitadb.api.statistics.FragmentationStatistics;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.IndexSummaryStatistics;
import io.evitadb.api.statistics.RecordCounts;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageCompositionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogStatisticsComponent;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogStatisticsSnapshot;
import io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionStatisticsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link CatalogStatisticsConverter} carries the component-selected statistics model across the wire
 * without losing what makes it useful.
 *
 * Two properties are checked, and the second is the one worth the test. The first is plain fidelity: a snapshot with
 * every component populated with a distinct value must come back byte-identical, which catches a dropped or
 * transposed field. The second is the **tri-state** - a component that was never requested, a component that was
 * delivered, and a component that was requested but could not be computed are three different answers, and the first
 * and last are both an absent sub-message on the wire. If a converter ever collapses them, a corrupted catalog starts
 * rendering as an empty one, which is the exact failure the component model exists to prevent.
 *
 * Every conversion is driven through real serialized bytes rather than straight from builder to reader, so the
 * assertions are about the wire format and not about an in-process object graph that never left the JVM.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog statistics survive the wire, including what is absent")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
class CatalogStatisticsConverterTest {
	private static final OffsetDateTime OLDEST_AVAILABLE =
		OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 600_000_000, ZoneOffset.UTC);
	private static final OffsetDateTime NEWEST =
		OffsetDateTime.of(2026, 7, 8, 9, 10, 11, 120_000_000, ZoneOffset.UTC);
	private static final OffsetDateTime OLDEST_RECORD_KEPT =
		OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 890_000_000, ZoneOffset.UTC);
	private static final OffsetDateTime ESTIMATED_COMPACTION_AT =
		OffsetDateTime.of(2026, 9, 10, 11, 12, 13, 140_000_000, ZoneOffset.UTC);
	// deliberately a different instant from the catalog-wide one, so that a converter arm reading the enclosing
	// message's timestamp into the nested one cannot pass
	private static final OffsetDateTime CATALOG_STORE_ESTIMATED_COMPACTION_AT =
		OffsetDateTime.of(2026, 11, 12, 13, 14, 15, 160_000_000, ZoneOffset.UTC);
	// likewise distinct from the catalog-wide retained-history timestamp
	private static final OffsetDateTime CATALOG_STORE_OLDEST_RECORD_KEPT =
		OffsetDateTime.of(2026, 5, 6, 7, 8, 9, 100_000_000, ZoneOffset.UTC);
	// the epoch the activity counters are read against; distinct from every timestamp above for the same reason
	private static final OffsetDateTime COUNTING_SINCE =
		OffsetDateTime.of(2026, 2, 3, 4, 5, 6, 700_000_000, ZoneOffset.UTC);

	@Test
	@DisplayName("carry every catalog-level component back unchanged")
	void shouldRoundTripFullyPopulatedCatalogStatistics() throws InvalidProtocolBufferException {
		final CatalogStatistics original = fullyPopulatedCatalogStatistics();
		assertEquals(original, roundTrip(original));
	}

	@Test
	@DisplayName("carry every collection-level component back unchanged")
	void shouldRoundTripFullyPopulatedEntityCollectionStatistics() throws InvalidProtocolBufferException {
		final EntityCollectionStatistics original = fullyPopulatedEntityCollectionStatistics();
		assertEquals(original, roundTrip(original));
	}

	@Test
	@DisplayName("tell an unrequested catalog component from one that could not be computed")
	void shouldKeepCatalogComponentTriStateDistinguishable() throws InvalidProtocolBufferException {
		final Map<CatalogStatisticsComponent, ComponentStatus> statuses =
			new EnumMap<>(CatalogStatisticsComponent.class);
		statuses.put(
			CatalogStatisticsComponent.IDENTITY,
			ComponentStatus.delivered(CatalogStatisticsComponent.IDENTITY)
		);
		statuses.put(
			CatalogStatisticsComponent.RECORD_COUNTS,
			ComponentStatus.delivered(CatalogStatisticsComponent.RECORD_COUNTS)
		);
		statuses.put(
			CatalogStatisticsComponent.SESSIONS,
			ComponentStatus.unavailable(
				CatalogStatisticsComponent.SESSIONS,
				ComponentAvailability.CATALOG_UNUSABLE,
				"Catalog `testCatalog` could not be loaded."
			)
		);
		final CatalogStatistics original = new CatalogStatistics(
			identity(),
			// deliberately all zeroes - an empty catalog reports a real measurement of zero, and that must not be
			// mistaken for "no value was produced"
			new RecordCounts(0L, 0L, 0L),
			null, null, null, null, null, null, null, null, null, null,
			Map.copyOf(statuses)
		);

		final CatalogStatistics roundTripped = roundTrip(original);

		// delivered, all-zero: the value is present and the status says so
		assertEquals(new RecordCounts(0L, 0L, 0L), roundTripped.recordCounts());
		assertTrue(roundTripped.isDelivered(CatalogStatisticsComponent.RECORD_COUNTS));

		// requested but unavailable: no value, but a status explaining why
		assertNull(roundTripped.sessions());
		assertFalse(roundTripped.isDelivered(CatalogStatisticsComponent.SESSIONS));
		final ComponentStatus sessionStatus = roundTripped.statusOf(CatalogStatisticsComponent.SESSIONS).orElseThrow();
		assertEquals(ComponentAvailability.CATALOG_UNUSABLE, sessionStatus.availability());
		assertEquals("Catalog `testCatalog` could not be loaded.", sessionStatus.reason());

		// never requested: no value and, crucially, no status entry at all
		assertNull(roundTripped.history());
		assertTrue(
			roundTripped.statusOf(CatalogStatisticsComponent.HISTORY).isEmpty(),
			"A component that was never requested must not acquire a status on the way through the wire"
		);
	}

	@Test
	@DisplayName("tell an unrequested collection component from one that could not be computed")
	void shouldKeepCollectionComponentTriStateDistinguishable() throws InvalidProtocolBufferException {
		final Map<CatalogStatisticsComponent, ComponentStatus> statuses =
			new EnumMap<>(CatalogStatisticsComponent.class);
		statuses.put(
			CatalogStatisticsComponent.IDENTITY,
			ComponentStatus.delivered(CatalogStatisticsComponent.IDENTITY)
		);
		statuses.put(
			CatalogStatisticsComponent.RECORD_COUNTS,
			ComponentStatus.delivered(CatalogStatisticsComponent.RECORD_COUNTS)
		);
		statuses.put(
			CatalogStatisticsComponent.MEMORY_FOOTPRINT,
			ComponentStatus.unavailable(
				CatalogStatisticsComponent.MEMORY_FOOTPRINT,
				ComponentAvailability.NOT_SUPPORTED,
				"Statistics component `MEMORY_FOOTPRINT` is not computed by this version yet."
			)
		);
		final EntityCollectionStatistics original = new EntityCollectionStatistics(
			identity(),
			"product",
			null,
			new CollectionRecordCounts(0, 0, 0),
			null, null, null, null, null,
			Map.copyOf(statuses)
		);

		final EntityCollectionStatistics roundTripped = roundTrip(original);

		assertEquals("product", roundTripped.entityType());
		assertEquals(new CollectionRecordCounts(0, 0, 0), roundTripped.recordCounts());
		assertTrue(roundTripped.isDelivered(CatalogStatisticsComponent.RECORD_COUNTS));

		final ComponentStatus memoryStatus =
			roundTripped.statusOf(CatalogStatisticsComponent.MEMORY_FOOTPRINT).orElseThrow();
		assertEquals(ComponentAvailability.NOT_SUPPORTED, memoryStatus.availability());
		assertEquals(
			"Statistics component `MEMORY_FOOTPRINT` is not computed by this version yet.", memoryStatus.reason()
		);

		assertNull(roundTripped.indexSummary());
		assertTrue(roundTripped.statusOf(CatalogStatisticsComponent.INDEX_SUMMARY).isEmpty());
	}

	@Test
	@DisplayName("keep the unknown id and unknown state of a corrupted catalog unknown")
	void shouldRoundTripUnusableCatalogIdentity() throws InvalidProtocolBufferException {
		// this is what UnusableCatalog reports: the name and the read-only flag are known without loading anything,
		// everything else is not. `null` must not come back as a fabricated value.
		final CatalogIdentity original = new CatalogIdentity(
			null, "corruptedCatalog", null, -1L, false, true, false, false, -1
		);
		final CatalogStatistics roundTripped = roundTrip(
			new CatalogStatistics(
				original, null, null, null, null, null, null, null, null, null, null, null,
				Map.of(
					CatalogStatisticsComponent.IDENTITY,
					ComponentStatus.delivered(CatalogStatisticsComponent.IDENTITY)
				)
			)
		);

		assertEquals(original, roundTripped.identity());
		assertNull(roundTripped.identity().catalogId());
		assertNull(roundTripped.identity().catalogState());
		assertTrue(roundTripped.identity().unusable());
	}

	@Test
	@DisplayName("carry a component selection in both directions")
	void shouldRoundTripComponentSelection() {
		final Set<CatalogStatisticsComponent> components = EnumSet.of(
			CatalogStatisticsComponent.IDENTITY,
			CatalogStatisticsComponent.RECORD_COUNTS,
			CatalogStatisticsComponent.STORAGE_SIZE,
			CatalogStatisticsComponent.MEMORY_FOOTPRINT
		);
		assertEquals(
			components,
			CatalogStatisticsConverter.toComponents(CatalogStatisticsConverter.toGrpcComponents(components))
		);
	}

	@Test
	@DisplayName("reject an empty selection instead of answering a different question")
	void shouldRejectEmptyComponentSelection() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> CatalogStatisticsConverter.toComponents(List.of())
		);
	}

	@Test
	@DisplayName("reject an unspecified component instead of dropping it")
	void shouldRejectUnspecifiedComponent() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> CatalogStatisticsConverter.toComponents(
				List.of(GrpcCatalogStatisticsComponent.COMPONENT_UNSPECIFIED)
			)
		);
	}

	/**
	 * Converts a catalog snapshot to its gRPC form, serializes it, parses it back and converts it to Java again -
	 * so what is asserted has genuinely been through the wire format.
	 *
	 * @param statistics the snapshot to send
	 * @return the snapshot as it arrives on the other side
	 */
	@Nonnull
	private static CatalogStatistics roundTrip(@Nonnull CatalogStatistics statistics)
		throws InvalidProtocolBufferException {
		return CatalogStatisticsConverter.toCatalogStatistics(
			GrpcCatalogStatisticsSnapshot.parseFrom(
				CatalogStatisticsConverter.toGrpcCatalogStatisticsSnapshot(statistics).toByteArray()
			)
		);
	}

	/**
	 * Converts a collection snapshot to its gRPC form, serializes it, parses it back and converts it to Java again.
	 *
	 * @param statistics the snapshot to send
	 * @return the snapshot as it arrives on the other side
	 */
	@Nonnull
	private static EntityCollectionStatistics roundTrip(@Nonnull EntityCollectionStatistics statistics)
		throws InvalidProtocolBufferException {
		return CatalogStatisticsConverter.toEntityCollectionStatistics(
			GrpcEntityCollectionStatisticsSnapshot.parseFrom(
				CatalogStatisticsConverter.toGrpcEntityCollectionStatisticsSnapshot(statistics).toByteArray()
			)
		);
	}

	/**
	 * A healthy catalog's identity. The booleans alternate so that transposing any adjacent pair changes the value.
	 *
	 * @return the identity component used by every fixture here
	 */
	@Nonnull
	private static CatalogIdentity identity() {
		return new CatalogIdentity(
			UUID.fromString("f8b3a2c1-0d4e-4f5a-8b6c-7d8e9f0a1b2c"),
			"testCatalog",
			CatalogState.ALIVE,
			42L,
			true,
			false,
			false,
			true,
			7
		);
	}

	/**
	 * A catalog snapshot with every component that has a Java type populated, each field carrying a distinct value so
	 * that a field written into the wrong slot changes the result.
	 *
	 * `DURABILITY` is present as a status without a value on purpose - it is a component with no sub-message yet, and
	 * its status must still survive the trip.
	 *
	 * @return the fixture
	 */
	@Nonnull
	private static CatalogStatistics fullyPopulatedCatalogStatistics() {
		final Map<CatalogStatisticsComponent, ComponentStatus> statuses =
			new EnumMap<>(CatalogStatisticsComponent.class);
		for (final CatalogStatisticsComponent component : EnumSet.of(
			CatalogStatisticsComponent.IDENTITY,
			CatalogStatisticsComponent.RECORD_COUNTS,
			CatalogStatisticsComponent.COLLECTIONS,
			CatalogStatisticsComponent.SESSIONS,
			CatalogStatisticsComponent.COMMIT_PIPELINE,
			CatalogStatisticsComponent.ACTIVITY,
			CatalogStatisticsComponent.STORAGE_SIZE,
			CatalogStatisticsComponent.STORAGE_COMPOSITION,
			CatalogStatisticsComponent.FRAGMENTATION,
			CatalogStatisticsComponent.HISTORY,
			CatalogStatisticsComponent.INDEX_SUMMARY,
			CatalogStatisticsComponent.VOLATILE_STATE
		)) {
			statuses.put(component, ComponentStatus.delivered(component));
		}
		statuses.put(
			CatalogStatisticsComponent.DURABILITY,
			ComponentStatus.unavailable(
				CatalogStatisticsComponent.DURABILITY,
				ComponentAvailability.NOT_SUPPORTED,
				"Statistics component `DURABILITY` is not computed by this version yet."
			)
		);
		return new CatalogStatistics(
			identity(),
			new RecordCounts(101L, 102L, 103L),
			new CollectionsInfo(
				new CollectionInfo[]{new CollectionInfo("product", 1), new CollectionInfo("category", 2)}
			),
			new SessionStatistics(11, 12, 13),
			new CommitPipelineStatistics(201L, 202L, 203L, 204L),
			// `pipelineDepth` deliberately does NOT equal `201 - 204` of the watermarks above: on the wire the two
			// components are independent fields, and an arm that recomputed the depth from the pipeline message
			// instead of carrying the one it was sent would still pass a fixture where the two agreed
			new ActivityStatistics(
				211L, 212L, 213L, 214L, 215L, 216L, 217.5d, 218.5d, 219.5d, COUNTING_SINCE
			),
			new StorageSizeStatistics(301L, 302L, 303L, 310L, 311L, 304L, 305L, 306L, 307L, 308L, 309L),
			new StorageCompositionStatistics(
				new StoragePartUsage[]{
					new StoragePartUsage("EntityBodyStoragePart", 401, 402L),
					new StoragePartUsage("AttributesStoragePart", 403, 404L)
				}
			),
			// a projected time *and* an eligible store: the two are independent at the catalog level, where one data
			// store can be due now while the next one is still only heading there
			new FragmentationStatistics(
				0.75d, 501L, 502L, true, 505L, 506.5d, ESTIMATED_COMPACTION_AT,
				// the catalog's own data store carries its own values throughout - a round trip that dropped the
				// nested message, or filled it from the enclosing one, would still match on every field otherwise
				new DataStoreFragmentation(
					0.6d, 511L, 512L, false, 513L, 514.5d, CATALOG_STORE_ESTIMATED_COMPACTION_AT
				),
				503L, 0.5d, 0.25d, 504L
			),
			new HistoryStatistics(
				true, 601L, OLDEST_AVAILABLE, 602L, NEWEST, 603, 604L, 605L, 606, 607L, 608L, 609L
			),
			new IndexSummaryStatistics(701L),
			new VolatileStateStatistics(
				801L, 802, 803L, OLDEST_RECORD_KEPT,
				// distinct values throughout: a nested slice copied from its enclosing record would still match on
				// every scalar field otherwise
				new DataStoreVolatileState(811L, 812, 813L, CATALOG_STORE_OLDEST_RECORD_KEPT)
			),
			Map.copyOf(statuses)
		);
	}

	/**
	 * A collection snapshot with every component populated, each field carrying a distinct value.
	 *
	 * @return the fixture
	 */
	@Nonnull
	private static EntityCollectionStatistics fullyPopulatedEntityCollectionStatistics() {
		final Map<CatalogStatisticsComponent, ComponentStatus> statuses =
			new EnumMap<>(CatalogStatisticsComponent.class);
		for (final CatalogStatisticsComponent component : EnumSet.of(
			CatalogStatisticsComponent.IDENTITY,
			CatalogStatisticsComponent.COLLECTIONS,
			CatalogStatisticsComponent.RECORD_COUNTS,
			CatalogStatisticsComponent.STORAGE_SIZE,
			CatalogStatisticsComponent.STORAGE_COMPOSITION,
			CatalogStatisticsComponent.FRAGMENTATION,
			CatalogStatisticsComponent.INDEX_SUMMARY,
			CatalogStatisticsComponent.VOLATILE_STATE
		)) {
			statuses.put(component, ComponentStatus.delivered(component));
		}
		return new EntityCollectionStatistics(
			identity(),
			"product",
			new CollectionHeaderInfo(11, 12L, 13, 14, 15, 16L, 17L),
			new CollectionRecordCounts(21, 22, 23),
			new CollectionStorageSize(31L, 32L, 33L, 34L, 35L),
			new CollectionStorageComposition(
				new StoragePartUsage[]{new StoragePartUsage("EntityBodyStoragePart", 41, 42L)}
			),
			// the other half of the same nullable field - an eligible store carries no projection, and an absent one
			// must decode back to absent rather than to an epoch-zero instant
			new DataStoreFragmentation(0.6d, 51L, 52L, true, 53L, 54.5d, null),
			new CollectionIndexSummary(
				61,
				new IndexKindCount[]{
					new IndexKindCount(EntityIndexKind.GLOBAL, Scope.LIVE, 62),
					new IndexKindCount(EntityIndexKind.REFERENCED_ENTITY, Scope.ARCHIVED, 63)
				}
			),
			new DataStoreVolatileState(71L, 72, 73L, OLDEST_RECORD_KEPT),
			Map.copyOf(statuses)
		);
	}

}
