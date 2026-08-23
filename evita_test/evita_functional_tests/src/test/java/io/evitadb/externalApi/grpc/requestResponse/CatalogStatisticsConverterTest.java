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
import com.google.protobuf.StringValue;
import io.evitadb.api.CatalogState;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexTypeCount;
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
import io.evitadb.api.statistics.DurabilityStatistics;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.FragmentationStatistics;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.IndexSummaryStatistics;
import io.evitadb.api.statistics.RecordCounts;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageCompositionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.api.statistics.CatalogIndexCardinality;
import io.evitadb.api.statistics.CatalogIndexCardinality.GlobalUniqueIndexCardinality;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.generated.GrpcBrowsedIndex;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogStatisticsComponent;
import io.evitadb.externalApi.grpc.generated.GrpcEntityScope;
import io.evitadb.externalApi.grpc.generated.GrpcIndexBrowseOrdering;
import io.evitadb.externalApi.grpc.generated.GrpcIndexBrowseRequest;
import io.evitadb.externalApi.grpc.generated.GrpcIndexBrowseResponse;
import io.evitadb.externalApi.grpc.generated.GrpcIndexDetail;
import io.evitadb.externalApi.grpc.generated.GrpcOrderDirection;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogStatisticsSnapshot;
import io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionStatisticsSnapshot;
import io.evitadb.externalApi.grpc.generated.GrpcSchemaCapability;
import io.evitadb.externalApi.grpc.generated.GrpcSchemaCapabilityUsage;
import io.evitadb.externalApi.grpc.generated.GrpcSchemaCapabilityUsageResponse;
import io.evitadb.externalApi.grpc.generated.GrpcSchemaElementKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
	// when the last checkpoint completed; distinct from every timestamp above - including COUNTING_SINCE, its own
	// neighbour in the durability message - so an arm that carried one field into the other's slot would fail rather
	// than round-trip a matching pair
	private static final OffsetDateTime LAST_CHECKPOINT_AT =
		OffsetDateTime.of(2026, 12, 1, 2, 3, 4, 500_000_000, ZoneOffset.UTC);
	/**
	 * Stamp of the last query that chose an index, distinct from every other constant here so a converter crossing two
	 * fields is visible.
	 */
	private static final OffsetDateTime LAST_QUERIED_AT =
		OffsetDateTime.of(2026, 4, 5, 6, 7, 8, 900_000_000, ZoneOffset.UTC);
	/**
	 * Stamp of the last entity mutation that acquired an index - see {@link #LAST_QUERIED_AT}.
	 */
	private static final OffsetDateTime LAST_UPDATED_AT =
		OffsetDateTime.of(2026, 6, 7, 8, 9, 10, 110_000_000, ZoneOffset.UTC);
	/**
	 * When observation of an index began - distinct from both stamps above, so a converter carrying one of them into
	 * this slot fails rather than round-trips a matching pair. Unlike the two stamps this reading is never absent, and
	 * it is never the epoch either: a converter substituting a placeholder would report a zero-length observation
	 * window and make every rate a client computes from it infinite.
	 */
	private static final OffsetDateTime OBSERVED_SINCE =
		OffsetDateTime.of(2026, 8, 9, 10, 11, 12, 130_000_000, ZoneOffset.UTC);

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
			null, null, null, null, null, null, null, null, null, null, null, null,
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

	/**
	 * The second class of refusal, and a live one: a catalog that checkpoints inline has no deferred-durability fence
	 * to describe, so `DURABILITY` is declined rather than answered with zeroes that would read as "durability is
	 * instant and free" - the inverse of the truth. Built through the real builder, so what is asserted is the
	 * encoding of a snapshot the engine actually produces rather than one assembled for the test.
	 *
	 * This is the only round-trip coverage of `FEATURE_DISABLED`, which is otherwise reachable at no other point in
	 * this suite.
	 */
	@Test
	@DisplayName("carry a refusal caused by a disabled feature, and no value with it")
	void shouldCarryAFeatureDisabledRefusalWithoutItsValue() throws InvalidProtocolBufferException {
		final String reason = "Catalog checkpoints at the end of every round, so there is no deferred-durability " +
			"fence to describe - either no checkpoint interval is configured, or writes are not synced to the " +
			"physical device.";
		final CatalogStatistics original = CatalogStatistics.builder(identity())
			.withUnavailable(CatalogStatisticsComponent.DURABILITY, ComponentAvailability.FEATURE_DISABLED, reason)
			.build();

		final CatalogStatistics roundTripped = roundTrip(original);

		assertEquals(original, roundTripped);
		assertNull(
			roundTripped.durability(),
			"A declined component must arrive without a value - an all-zero durability message would read as a " +
				"catalog that syncs instantly"
		);
		assertFalse(roundTripped.isDelivered(CatalogStatisticsComponent.DURABILITY));
		final ComponentStatus durabilityStatus =
			roundTripped.statusOf(CatalogStatisticsComponent.DURABILITY).orElseThrow();
		assertEquals(ComponentAvailability.FEATURE_DISABLED, durabilityStatus.availability());
		assertEquals(reason, durabilityStatus.reason());
	}

	/**
	 * The engine of this version cannot produce the middle state at the collection level - there is no way to record
	 * a refusal on `EntityCollectionStatistics.Builder`, so a collection response either delivers a component or was
	 * never asked for it. The *wire* still distinguishes all three, and that is what this asserts: the day a
	 * collection-level component is introduced that can decline, the encoding it needs is already there and already
	 * proven lossless, rather than being designed under the pressure of needing it.
	 *
	 * The pairing below is therefore deliberately one the engine will not emit. `FEATURE_DISABLED` has catalog-level
	 * producers but no collection-level one, so pairing it with a collection component exercises the encoding without
	 * relying on any availability that nothing produces.
	 */
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
			CatalogStatisticsComponent.INDEX_CARDINALITY,
			ComponentStatus.unavailable(
				CatalogStatisticsComponent.INDEX_CARDINALITY,
				ComponentAvailability.FEATURE_DISABLED,
				"Statistics component `INDEX_CARDINALITY` is not computed by this version yet."
			)
		);
		final EntityCollectionStatistics original = new EntityCollectionStatistics(
			identity(),
			"product",
			null,
			new CollectionRecordCounts(0, 0, 0),
			null, null, null, null, null, null,
			Map.copyOf(statuses)
		);

		final EntityCollectionStatistics roundTripped = roundTrip(original);

		assertEquals("product", roundTripped.entityType());
		assertEquals(new CollectionRecordCounts(0, 0, 0), roundTripped.recordCounts());
		assertTrue(roundTripped.isDelivered(CatalogStatisticsComponent.RECORD_COUNTS));

		// requested, refused, and carrying no value - the reading that must not decode into an all-zero component
		final ComponentStatus declinedStatus =
			roundTripped.statusOf(CatalogStatisticsComponent.INDEX_CARDINALITY).orElseThrow();
		assertEquals(ComponentAvailability.FEATURE_DISABLED, declinedStatus.availability());
		assertEquals(
			"Statistics component `INDEX_CARDINALITY` is not computed by this version yet.", declinedStatus.reason()
		);
		assertNull(roundTripped.indexCardinality());
		assertFalse(roundTripped.isDelivered(CatalogStatisticsComponent.INDEX_CARDINALITY));

		assertNull(roundTripped.indexSummary());
		assertTrue(roundTripped.statusOf(CatalogStatisticsComponent.INDEX_SUMMARY).isEmpty());
	}

	@Test
	@DisplayName("decode a collection with no recorded last-modified time back to absent")
	void shouldDecodeAnAbsentCollectionLastModifiedAsAbsent() throws InvalidProtocolBufferException {
		final Map<CatalogStatisticsComponent, ComponentStatus> statuses =
			new EnumMap<>(CatalogStatisticsComponent.class);
		statuses.put(
			CatalogStatisticsComponent.IDENTITY,
			ComponentStatus.delivered(CatalogStatisticsComponent.IDENTITY)
		);
		statuses.put(
			CatalogStatisticsComponent.COLLECTIONS,
			ComponentStatus.delivered(CatalogStatisticsComponent.COLLECTIONS)
		);
		// a catalog carried over from a release before the header timestamp existed reports this for every one of its
		// collections until each is next flushed, so it is the ordinary case rather than an edge one
		final EntityCollectionStatistics original = new EntityCollectionStatistics(
			identity(),
			"product",
			new CollectionHeaderInfo(11, 12L, 13, 14, 15, 16L, 17L, null),
			null, null, null, null, null, null, null,
			Map.copyOf(statuses)
		);

		final EntityCollectionStatistics roundTripped = roundTrip(original);

		// the component itself is delivered - it is one field inside it that is absent, and an unconditional read of
		// the sub-message would decode that absence into an epoch-zero instant a client would render as 1970
		assertTrue(roundTripped.isDelivered(CatalogStatisticsComponent.COLLECTIONS));
		assertEquals(17L, roundTripped.header().maxRecordSizeBytes());
		assertNull(roundTripped.header().lastModified());
		assertTrue(roundTripped.header().lastModifiedIfKnown().isEmpty());
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
				original, null, null, null, null, null, null, null, null, null, null, null, null, null,
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
			CatalogStatisticsComponent.VOLATILE_STATE
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

	@Test
	@DisplayName("carry a page of browsed indexes back unchanged, absent discriminator parts included")
	void shouldRoundTripBrowsedIndexes() throws InvalidProtocolBufferException {
		// the three shapes an index discriminator takes: none at all, a reference name alone, and a reference name
		// paired with the primary key of one target entity. Unset protobuf wrappers decode to an empty string and a
		// zero when read without a presence check, so all three have to be asserted rather than just the populated one
		final BrowsedIndex[] indexes = {
			new BrowsedIndex(
				"product", 1, EntityIndexType.GLOBAL, Scope.LIVE, null, null, null, 1_000,
				9_000_000_000L, 4_000_000_000L, LAST_QUERIED_AT, LAST_UPDATED_AT, OBSERVED_SINCE, true
			),
			new BrowsedIndex(
				"product", 2, EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE,
				"categories", "categories", null, 400,
				0L, 12L, null, LAST_UPDATED_AT, OBSERVED_SINCE, true
			),
			// the case the two projections cannot express: same reference, same target, told apart only by the
			// representative values the discriminator carries
			new BrowsedIndex(
				"product", 3, EntityIndexType.REFERENCED_ENTITY, Scope.ARCHIVED,
				"categories/42/[red]", "categories", 42, 7,
				3L, 5L, LAST_QUERIED_AT, LAST_UPDATED_AT, OBSERVED_SINCE, true
			),
			new BrowsedIndex(
				"product", 4, EntityIndexType.REFERENCED_ENTITY, Scope.ARCHIVED,
				"categories/42/[blue]", "categories", 42, 7,
				3L, 5L, LAST_QUERIED_AT, LAST_UPDATED_AT, OBSERVED_SINCE, true
			),
			// a catalog index that has never been touched: no owning collection, and with it no kind and no entity
			// count, plus two never-recorded stamps. Every one of those five travels as an unset wrapper or message,
			// and every one decodes to a non-null default when read without a presence check - `""`,
			// `INDEX_TYPE_UNSPECIFIED`, `0` and the epoch respectively - so this row is the one that catches a
			// converter reading any of them straight
			new BrowsedIndex(
				null, 0, null, Scope.LIVE, null, null, null, null, 0L, 0L, null, null, OBSERVED_SINCE, true
			)
		};
		final GrpcIndexBrowseResponse.Builder builder =
			GrpcIndexBrowseResponse.newBuilder()
				.setCatalogVersion(17L)
				.setPageNumber(2)
				.setPageSize(3)
				.setTotalRecordCount(9);
		for (final BrowsedIndex index : indexes) {
			builder.addIndexes(CatalogStatisticsConverter.toGrpcBrowsedIndex(index));
		}

		final IndexBrowseResult roundTripped = CatalogStatisticsConverter.toIndexBrowseResult(
			GrpcIndexBrowseResponse.parseFrom(builder.build().toByteArray())
		);

		assertEquals(17L, roundTripped.catalogVersion());
		assertEquals(2, roundTripped.pageNumber());
		assertEquals(3, roundTripped.pageSize());
		assertEquals(9, roundTripped.totalRecordCount());
		assertArrayEquals(indexes, roundTripped.indexes());
		assertNull(roundTripped.indexes()[0].referenceName());
		assertNull(roundTripped.indexes()[0].discriminatorPrimaryKey());
		assertNull(roundTripped.indexes()[0].discriminator(), "A global index has no siblings to be told apart from");
		assertNull(roundTripped.indexes()[1].discriminatorPrimaryKey());
		// the identity guarantee, which is the index primary key and nothing else: it is what a client compares and
		// what it hands back to drill into one index, so a zero here - the value an unset int32 decodes to - would
		// silently address the wrong index rather than fail
		for (int i = 0; i < indexes.length; i++) {
			assertEquals(indexes[i].indexPrimaryKey(), roundTripped.indexes()[i].indexPrimaryKey());
		}
		// the last two agree on kind, scope, reference name and target primary key, so only the discriminator keeps
		// them apart on screen. If it were dropped on the wire an operator would face two rows differing in nothing
		// but an opaque integer, with no way to see which is which
		assertEquals(roundTripped.indexes()[2].referenceName(), roundTripped.indexes()[3].referenceName());
		assertEquals(
			roundTripped.indexes()[2].discriminatorPrimaryKey(), roundTripped.indexes()[3].discriminatorPrimaryKey()
		);
		assertNotEquals(roundTripped.indexes()[2].discriminator(), roundTripped.indexes()[3].discriminator());
		assertNotEquals(roundTripped.indexes()[2], roundTripped.indexes()[3]);
		// the activity readings, and specifically the two ways a stamp can be absent: an index that has been updated
		// but never queried, and one that has been neither. A stamp read without a presence check decodes to the epoch,
		// which a client renders as a date in 1970 rather than as "not since the catalog was loaded"
		assertEquals(9_000_000_000L, roundTripped.indexes()[0].queryCount(), "A count past int range must not wrap");
		assertEquals(4_000_000_000L, roundTripped.indexes()[0].updateCount(), "A count past int range must not wrap");
		assertEquals(LAST_QUERIED_AT, roundTripped.indexes()[0].lastQueriedAt());
		assertEquals(LAST_UPDATED_AT, roundTripped.indexes()[0].lastUpdatedAt());
		assertNull(roundTripped.indexes()[1].lastQueriedAt(), "A never-queried index must not decode to the epoch");
		assertTrue(roundTripped.indexes()[1].lastQueriedAtIfKnown().isEmpty());
		assertEquals(LAST_UPDATED_AT, roundTripped.indexes()[1].lastUpdatedAt());
		assertNull(roundTripped.indexes()[4].lastQueriedAt());
		assertNull(roundTripped.indexes()[4].lastUpdatedAt());
		assertEquals(0L, roundTripped.indexes()[4].queryCount());
		assertEquals(0L, roundTripped.indexes()[4].updateCount());
		// the observation window every row carries, including the one that has never been queried or updated - it is
		// what makes a zero count readable as "not once in this long" rather than as an unqualified zero, so it has to
		// arrive as sent rather than as a stand-in the decoder made up
		for (int i = 0; i < indexes.length; i++) {
			assertEquals(OBSERVED_SINCE, roundTripped.indexes()[i].observedSince());
		}
	}

	@Test
	@DisplayName("carry the full description of one index back unchanged, including a heap estimate beyond int range")
	void shouldRoundTripAnIndexDetail() throws InvalidProtocolBufferException {
		// the heap figure is the field a narrower wire type would silently corrupt: a real index can exceed two
		// gigabytes - the largest one measured on a production catalog held 1.03 GB and nothing caps it - so a value
		// past Integer.MAX_VALUE has to survive rather than wrap
		final long heapSizeInBytes = 3_000_000_000L;
		final IndexDetail detail = new IndexDetail(
			"product",
			42,
			heapSizeInBytes,
			new IndexCardinality(
				EntityIndexType.REFERENCED_ENTITY,
				Scope.ARCHIVED,
				"categories/42/[red]",
				7,
				3,
				new AttributeCardinality[]{
					new AttributeCardinality("code", null, null, AttributeIndexType.FILTER, 5, 7),
					new AttributeCardinality("name", "categories", Locale.ENGLISH, AttributeIndexType.SORT, 7, 7)
				}
			),
			9_000_000_000L,
			4_000_000_000L,
			LAST_QUERIED_AT,
			LAST_UPDATED_AT,
			OBSERVED_SINCE, true
		);

		final IndexDetail roundTripped = CatalogStatisticsConverter.toIndexDetail(
			GrpcIndexDetail.parseFrom(
				CatalogStatisticsConverter.toGrpcIndexDetail(detail).toByteArray()
			)
		);

		assertEquals(42, roundTripped.indexPrimaryKey());
		assertEquals(heapSizeInBytes, roundTripped.heapSizeInBytes(), "The heap estimate must not be narrowed");
		assertEquals(detail.cardinality(), roundTripped.cardinality());
		// the discriminator of a per-referenced-entity index is the one this surface newly carries - the collection
		// level component never describes such an index, so nothing else would notice it being dropped
		assertEquals("categories/42/[red]", roundTripped.cardinality().discriminator());
		// the two counters are int64 for the same reason the heap figure is: a busy index passes two billion queries
		// long before anybody restarts the server
		assertEquals(9_000_000_000L, roundTripped.queryCount(), "A count past int range must not wrap");
		assertEquals(4_000_000_000L, roundTripped.updateCount(), "A count past int range must not wrap");
		assertEquals(LAST_QUERIED_AT, roundTripped.lastQueriedAt());
		assertEquals(LAST_UPDATED_AT, roundTripped.lastUpdatedAt());
		assertEquals(OBSERVED_SINCE, roundTripped.observedSince(), "The observation window must arrive as sent");
		assertEquals(detail, roundTripped);
	}

	@Test
	@DisplayName("carry a catalog index's description back with its three absences intact")
	void shouldRoundTripACatalogIndexDetail() throws InvalidProtocolBufferException {
		// `GrpcIndexCardinality` carries its own presence checks, so the populated round trip above proves nothing
		// about this shape. Every one of the three fields a catalog index leaves unset has a non-null default that a
		// converter reading it without a presence check would accept without complaint - `""`,
		// `INDEX_TYPE_UNSPECIFIED` and `0` - so the failure would be a plausible number rather than an exception,
		// which is the whole reason those fields travel as wrappers instead of as sentinels
		final IndexDetail detail = new IndexDetail(
			null,
			0,
			4_096L,
			new IndexCardinality(
				null,
				Scope.LIVE,
				null,
				null,
				null,
				new AttributeCardinality[]{
					// a catalog index's attribute entries are its global unique indexes: entity-level by definition, so
					// never bound to a reference, and one per locale for an attribute unique within a locale
					new AttributeCardinality("url", null, Locale.ENGLISH, AttributeIndexType.UNIQUE, 3, 3)
				}
			),
			// never queried and never updated since the catalog was loaded - the fourth and fifth absence this shape
			// has to carry, and the two that would decode to the epoch rather than to null if read straight
			0L,
			0L,
			null,
			null,
			OBSERVED_SINCE, true
		);

		final IndexDetail roundTripped = CatalogStatisticsConverter.toIndexDetail(
			GrpcIndexDetail.parseFrom(
				CatalogStatisticsConverter.toGrpcIndexDetail(detail).toByteArray()
			)
		);

		assertNull(roundTripped.entityType(), "An unset entity type must not decode to the empty string");
		assertNull(roundTripped.cardinality().indexType(), "An unset kind must not decode to INDEX_TYPE_UNSPECIFIED");
		assertNull(roundTripped.cardinality().entityCount(), "An unset entity count must not decode to zero");
		assertTrue(roundTripped.cardinality().entityCountIfKnown().isEmpty());
		assertEquals(0, roundTripped.indexPrimaryKey(), "The live catalog index's handle is zero, and must survive");
		assertNull(roundTripped.lastQueriedAt(), "A never-queried index must not decode to the epoch");
		assertNull(roundTripped.lastUpdatedAt(), "A never-updated index must not decode to the epoch");
		assertTrue(roundTripped.lastQueriedAtIfKnown().isEmpty());
		assertTrue(roundTripped.lastUpdatedAtIfKnown().isEmpty());
		// the reading that keeps the two absences above readable: an index untouched since observation began still
		// says how long that has been, which is the difference between "never" and "never, in the last four minutes"
		assertEquals(OBSERVED_SINCE, roundTripped.observedSince());
		assertEquals(detail, roundTripped);
	}

	@Test
	@DisplayName("decode a server that predates the observation window as not knowing it, not as any instant")
	void shouldDecodeAnAbsentObservedSinceAsUnknown() throws InvalidProtocolBufferException {
		// a server built before the field existed sends messages without it - a newer client must not crash there,
		// and no instant may stand in for the missing window either: the epoch would fabricate a decades-long window
		// that turns "never queried in the last week" falsely true, "now" a zero-length one that turns every rate
		// infinite. The absence is the truth, and it is what travels
		final BrowsedIndex row = new BrowsedIndex(
			"product", 1, EntityIndexType.GLOBAL, Scope.LIVE, null, null, null, 1_000,
			3L, 5L, LAST_QUERIED_AT, LAST_UPDATED_AT, OBSERVED_SINCE, true
		);
		final IndexDetail detail = new IndexDetail(
			"product", 1, 4_096L,
			new IndexCardinality(EntityIndexType.GLOBAL, Scope.LIVE, null, 1_000, 2, new AttributeCardinality[0]),
			3L, 5L, LAST_QUERIED_AT, LAST_UPDATED_AT, OBSERVED_SINCE, true
		);

		final BrowsedIndex rowFromOldServer = CatalogStatisticsConverter.toBrowsedIndex(
			GrpcBrowsedIndex.parseFrom(
				CatalogStatisticsConverter.toGrpcBrowsedIndex(row).toBuilder().clearObservedSince().build()
					.toByteArray()
			)
		);
		final IndexDetail detailFromOldServer = CatalogStatisticsConverter.toIndexDetail(
			GrpcIndexDetail.parseFrom(
				CatalogStatisticsConverter.toGrpcIndexDetail(detail).toBuilder().clearObservedSince().build()
					.toByteArray()
			)
		);

		assertNull(rowFromOldServer.observedSince(), "An old server's silence must decode to an unknown window");
		assertNull(detailFromOldServer.observedSince(), "An old server's silence must decode to an unknown window");
		assertTrue(rowFromOldServer.observedSinceIfKnown().isEmpty());
		assertTrue(detailFromOldServer.observedSinceIfKnown().isEmpty());
		// everything else the old server did send still arrives intact
		assertEquals(LAST_QUERIED_AT, rowFromOldServer.lastQueriedAt());
		assertEquals(LAST_UPDATED_AT, detailFromOldServer.lastUpdatedAt());
	}

	@ParameterizedTest
	@MethodSource("browseOrderings")
	@DisplayName("carry index browse criteria in both directions")
	void shouldRoundTripIndexBrowseCriteria(
		@Nonnull IndexBrowseOrdering ordering,
		@Nonnull OrderDirection direction
	) throws InvalidProtocolBufferException {
		// driven off the enums rather than a written-out list, so an ordering key added later has to be given a wire
		// value the day it is declared - an unmapped one would otherwise travel as the request's default and silently
		// re-ask the server a different question. The direction is half of the order and travels as its own field, so
		// every accepted pairing is round-tripped rather than only the keys
		final IndexBrowseCriteria criteria = new IndexBrowseCriteria(
			3, 25, ordering, direction,
			EnumSet.of(EntityIndexType.REFERENCED_ENTITY, EntityIndexType.GLOBAL),
			EnumSet.of(Scope.ARCHIVED),
			Set.of("categories", "brands")
		);

		final IndexBrowseCriteria roundTripped = CatalogStatisticsConverter.toIndexBrowseCriteria(
			GrpcIndexBrowseRequest.parseFrom(
				CatalogStatisticsConverter
					.toGrpcIndexBrowseRequest("catalog", "product", criteria)
					.toByteArray()
			)
		);

		assertEquals(criteria, roundTripped);
	}

	/**
	 * Every key paired with every direction it accepts - which is both of them, except for the key that ranks nothing
	 * and therefore has no ranking to reverse.
	 *
	 * @return the pairings a request may carry
	 */
	@Nonnull
	static Stream<Arguments> browseOrderings() {
		final List<Arguments> pairings = new ArrayList<>(
			IndexBrowseOrdering.values().length * OrderDirection.values().length
		);
		for (final IndexBrowseOrdering ordering : IndexBrowseOrdering.values()) {
			for (final OrderDirection direction : OrderDirection.values()) {
				if (ordering != IndexBrowseOrdering.MAP_ORDER || direction == OrderDirection.ASC) {
					pairings.add(Arguments.of(ordering, direction));
				}
			}
		}
		return pairings.stream();
	}

	@Test
	@DisplayName("read an unset browse ordering as the map-order walk")
	void shouldReadAnUnsetBrowseOrderingAsTheMapOrderWalk() {
		// both halves of the order hold the zero slot of their enum, so a request that sets neither asks for the walk
		// of the whole set in the map's own order - the cheapest answer, and the only one that carries no ranking a
		// client could mistake for one it asked for
		final IndexBrowseCriteria criteria = assertDoesNotThrow(
			() -> CatalogStatisticsConverter.toIndexBrowseCriteria(
				GrpcIndexBrowseRequest.newBuilder()
					.setCatalogName("catalog")
					.setEntityType(StringValue.of("product"))
					.setPageNumber(1)
					.setPageSize(10)
					.build()
			)
		);

		assertEquals(IndexBrowseOrdering.MAP_ORDER, criteria.ordering());
		assertEquals(OrderDirection.ASC, criteria.direction());
	}

	@Test
	@DisplayName("reject a map-order request read descending instead of ignoring the direction")
	void shouldRejectMapOrderReadDescendingOffTheWire() {
		// the one pairing that does not exist. Rejected at the same place an embedded caller is rejected - the
		// criteria's own constructor - so the wire cannot reach a combination the engine has no answer for, and a
		// direction the server could not honour is never silently dropped on the way in
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> CatalogStatisticsConverter.toIndexBrowseCriteria(
				GrpcIndexBrowseRequest.newBuilder()
					.setCatalogName("catalog")
					.setEntityType(StringValue.of("product"))
					.setPageNumber(1)
					.setPageSize(10)
					.setOrdering(GrpcIndexBrowseOrdering.INDEX_BROWSE_ORDERING_MAP_ORDER)
					.setDirection(GrpcOrderDirection.DESC)
					.build()
			)
		);
	}

	@Test
	@DisplayName("carry a schema-capability usage listing back unchanged, catalog-owned rows included")
	void shouldRoundTripSchemaCapabilityUsage() throws InvalidProtocolBufferException {
		// the four owner shapes a row takes, chosen so that every nullable field is absent on some row and present on
		// another - an unset `StringValue` decodes to the empty string when read without a presence check, and an
		// unset `GrpcOffsetDateTime` to the epoch, so an absence asserted only where it happens to be populated
		// proves nothing
		final List<SchemaCapabilityUsageStatistics> usages = List.of(
			// an entity attribute, requested and maintained: nothing absent, which is the baseline the rest deviate
			// from
			new SchemaCapabilityUsageStatistics(
				"product", ElementKind.ATTRIBUTE, null, "ean", Capability.FILTERABLE, Scope.LIVE,
				9_000_000_000L, 4_000_000_000L, LAST_QUERIED_AT, LAST_UPDATED_AT, OBSERVED_SINCE, true
			),
			// a reference attribute - the container is the only thing telling it apart from an entity attribute of the
			// same name, so losing it would silently pool two different elements' traffic into one row
			new SchemaCapabilityUsageStatistics(
				"product", ElementKind.ATTRIBUTE, "categories", "priority", Capability.SORTABLE, Scope.ARCHIVED,
				3L, 0L, LAST_QUERIED_AT, null, OBSERVED_SINCE, true
			),
			// a sortable compound carrying the *same* container and name as the row above: only the kind separates
			// them, which is the one collision no other field can resolve
			new SchemaCapabilityUsageStatistics(
				"product", ElementKind.SORTABLE_COMPOUND, "categories", "priority", Capability.SORTABLE, Scope.ARCHIVED,
				0L, 12L, null, LAST_UPDATED_AT, OBSERVED_SINCE, true
			),
			// a capability the catalog owns, never requested and never maintained: no owning collection, no container
			// and neither stamp, so this row is the one that catches a converter reading any of the four straight
			new SchemaCapabilityUsageStatistics(
				null, ElementKind.ATTRIBUTE, null, "code", Capability.UNIQUE, Scope.LIVE,
				0L, 0L, null, null, OBSERVED_SINCE, true
			)
		);
		final GrpcSchemaCapabilityUsageResponse.Builder builder = GrpcSchemaCapabilityUsageResponse.newBuilder();
		for (final SchemaCapabilityUsageStatistics usage : usages) {
			builder.addCapabilities(CatalogStatisticsConverter.toGrpcSchemaCapabilityUsage(usage));
		}

		final List<SchemaCapabilityUsageStatistics> roundTripped = CatalogStatisticsConverter.toSchemaCapabilityUsages(
			GrpcSchemaCapabilityUsageResponse.parseFrom(builder.build().toByteArray())
		);

		assertEquals(usages, roundTripped);
		// the server's order is the client's order: the rows of one element belong together, and a listing that
		// arrived reshuffled would make two polls of an unchanged catalog look like a moving table
		for (int i = 0; i < usages.size(); i++) {
			assertEquals(usages.get(i).elementName(), roundTripped.get(i).elementName());
		}
		// the absences, stated one by one rather than left to record equality - each of them decodes to a plausible
		// non-null value when its presence is not checked, so each is a mistake that produces an answer rather than a
		// failure
		assertNull(roundTripped.get(0).containerName(), "An entity attribute is declared on no reference");
		assertNull(roundTripped.get(3).entityType(), "A capability the catalog owns belongs to no collection");
		assertNull(roundTripped.get(3).containerName(), "A catalog schema declares no references");
		assertNull(roundTripped.get(1).lastUpdatedAt(), "A never-maintained capability must not decode to the epoch");
		assertNull(roundTripped.get(2).lastRequestedAt(), "A never-requested capability must not decode to the epoch");
		assertTrue(roundTripped.get(3).lastRequestedAtIfKnown().isEmpty());
		assertTrue(roundTripped.get(3).lastUpdatedAtIfKnown().isEmpty());
		// the two counts are plain int64s, so a dropped one arrives as a perfectly plausible "this flag is cold"
		assertEquals(9_000_000_000L, roundTripped.get(0).requestedCount(), "A count past int range must not wrap");
		assertEquals(4_000_000_000L, roundTripped.get(0).updatedCount(), "A count past int range must not wrap");
		// the kind is what keeps rows 1 and 2 apart - they agree on owner, container, name, capability and scope
		assertEquals(roundTripped.get(1).elementName(), roundTripped.get(2).elementName());
		assertEquals(roundTripped.get(1).containerName(), roundTripped.get(2).containerName());
		assertNotEquals(roundTripped.get(1).elementKind(), roundTripped.get(2).elementKind());
		assertNotEquals(roundTripped.get(1), roundTripped.get(2));
		// the observation window every row carries, including the one that has never been requested or maintained: it
		// is what makes a zero count readable as "not once in this long" rather than as an unqualified zero
		for (final SchemaCapabilityUsageStatistics usage : roundTripped) {
			assertEquals(OBSERVED_SINCE, usage.observedSince());
		}
	}

	@Test
	@DisplayName("every capability and element kind survives the wire, none collapsing onto another")
	void shouldRoundTripEverySchemaCapabilityAndElementKind() {
		// The switches converting these are exhaustive, so a *missing* value fails to compile. What the compiler
		// cannot catch is a value mapped onto the wrong gRPC constant - two capabilities sharing one wire value
		// still compiles, and silently pools two flags' traffic into one row on the client. Hence value-by-value.
		for (final Capability capability : Capability.values()) {
			assertEquals(
				capability,
				EvitaEnumConverter.toSchemaCapability(EvitaEnumConverter.toGrpcSchemaCapability(capability)),
				"Capability " + capability + " did not survive the round trip intact"
			);
		}
		assertEquals(
			Capability.values().length,
			Arrays.stream(Capability.values()).map(EvitaEnumConverter::toGrpcSchemaCapability).distinct().count(),
			"Two capabilities share one gRPC constant, which pools their traffic into a single reported row"
		);

		for (final ElementKind elementKind : ElementKind.values()) {
			assertEquals(
				elementKind,
				EvitaEnumConverter.toSchemaElementKind(EvitaEnumConverter.toGrpcSchemaElementKind(elementKind)),
				"Element kind " + elementKind + " did not survive the round trip intact"
			);
		}
		assertEquals(
			ElementKind.values().length,
			Arrays.stream(ElementKind.values()).map(EvitaEnumConverter::toGrpcSchemaElementKind).distinct().count(),
			"Two element kinds share one gRPC constant, and the kind is what keeps same-named elements apart"
		);
	}

	@Test
	@DisplayName("reject a usage row that arrived without its observation window")
	void shouldRejectSchemaCapabilityUsageWithoutItsObservationWindow() {
		// unlike a browsed index's window - a field added to a message older servers already spoke - this whole
		// procedure is new, so any server able to answer it sets the window. A missing one is a broken sender, and no
		// instant may be substituted for it: the epoch would fabricate a decades-long window that turns "never
		// requested in the last week" falsely true, and "now" a zero-length one that turns every rate infinite
		assertThrows(
			GenericEvitaInternalError.class,
			() -> CatalogStatisticsConverter.toSchemaCapabilityUsage(
				GrpcSchemaCapabilityUsage.newBuilder()
					.setElementKind(GrpcSchemaElementKind.SCHEMA_ELEMENT_KIND_ATTRIBUTE)
					.setElementName("ean")
					.setCapability(GrpcSchemaCapability.SCHEMA_CAPABILITY_FILTERABLE)
					.setScope(GrpcEntityScope.SCOPE_LIVE)
					.build()
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
	 * A catalog snapshot with every component populated, each field carrying a distinct value so that a field written
	 * into the wrong slot changes the result. Every component defined today has a Java type and a sub-message, so
	 * "fully populated" and "every component" are now the same set.
	 *
	 * The refused-component readings are deliberately *not* folded in here - they live in
	 * `shouldKeepCatalogComponentTriStateDistinguishable` and
	 * `shouldCarryAFeatureDisabledRefusalWithoutItsValue`, because nulling a field to make room for a status would
	 * give up exactly the distinct-value-per-slot property this fixture exists for.
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
			CatalogStatisticsComponent.INDEX_CARDINALITY,
			CatalogStatisticsComponent.VOLATILE_STATE,
			CatalogStatisticsComponent.DURABILITY
		)) {
			statuses.put(component, ComponentStatus.delivered(component));
		}
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
			new DurabilityStatistics(221L, 222L, 223L, 224, 225L, 226L, LAST_CHECKPOINT_AT, COUNTING_SINCE),
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
			// both halves of the nullable locale exercised exactly once: a non-localized globally-unique attribute
			// and one that is unique globally only within a locale, so an arm that dropped either would be caught
			new CatalogIndexCardinality(
				new GlobalUniqueIndexCardinality[]{
					new GlobalUniqueIndexCardinality("code", null, Scope.LIVE, 711),
					new GlobalUniqueIndexCardinality("url", Locale.GERMAN, Scope.ARCHIVED, 712)
				}
			),
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
			CatalogStatisticsComponent.INDEX_CARDINALITY,
			CatalogStatisticsComponent.VOLATILE_STATE
		)) {
			statuses.put(component, ComponentStatus.delivered(component));
		}
		return new EntityCollectionStatistics(
			identity(),
			"product",
			// a populated `lastModified` here; the absent case is covered by
			// shouldDecodeAnAbsentCollectionLastModifiedAsAbsent below, which is the one a naive
			// `hasLastModified()`-less converter arm would fail
			new CollectionHeaderInfo(11, 12L, 13, 14, 15, 16L, 17L, COUNTING_SINCE),
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
				new IndexTypeCount[]{
					new IndexTypeCount(EntityIndexType.GLOBAL, Scope.LIVE, 62),
					new IndexTypeCount(EntityIndexType.REFERENCED_ENTITY, Scope.ARCHIVED, 63)
				}
			),
			// both halves of every nullable field appear exactly once: the global index has no discriminator and no
			// reference cardinality, the reference index has both; the first attribute is entity-level and
			// non-localized, the second is defined on a reference and localized. An arm that dropped a `has...()`
			// guard decodes the absent half into `""` / `0` and fails on the first pair
			new CollectionIndexCardinality(
				new IndexCardinality[]{
					new IndexCardinality(
						EntityIndexType.GLOBAL, Scope.LIVE, null, 81, null,
						new AttributeCardinality[]{
							new AttributeCardinality("code", null, null, AttributeIndexType.FILTER, 82, 83)
						}
					),
					new IndexCardinality(
						EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.ARCHIVED, "categories", 84, 85,
						new AttributeCardinality[]{
							new AttributeCardinality(
								"label", "categories", Locale.GERMAN, AttributeIndexType.SORT, 86, 87
							)
						}
					)
				},
				88
			),
			new DataStoreVolatileState(71L, 72, 73L, OLDEST_RECORD_KEPT),
			Map.copyOf(statuses)
		);
	}

}
