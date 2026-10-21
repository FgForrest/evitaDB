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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.index.attribute.LegacyOwnerSortIndexWriter;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.catalog.OfflineCatalogImages;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that temporal sort values are ordered by their millisecond `Instant` form in every sort index, whatever
 * shape the index has - a sort-only attribute, a sortable attribute that is filterable too, an element of a sortable
 * attribute compound - and whether the catalog was just written or loaded from disk.
 *
 * Two `OffsetDateTime` values denoting the same moment at different offsets are one value to a sort index: they tie,
 * and the tie is broken by the primary key in the ordering direction. The ordering on prefetched entities, which
 * compares the values the entities carry instead of reading a sort index, follows the same rule.
 *
 * Every scenario runs once in a catalog that stays in warm-up and once in a catalog that went live, because the
 * two states write and flush the indexes along different paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Temporal sort values are ordered by their millisecond instant in every sort index")
@Tag(ATTRIBUTE)
@Tag(ORDER)
@Tag(REFERENCE)
@Tag(STORAGE)
class TemporalSortValueFunctionalTest implements EvitaTestSupport {
	private static final String TARGET = "target";
	private static final String OWNER = "owner";
	private static final String TARGETS = "targets";
	/** Filterable and sortable: its sort index is a view over the filter index once the catalog is loaded. */
	private static final String MOMENT = "moment";
	/** Sortable only: its sort index always owns its values. */
	private static final String SORT_ONLY_MOMENT = "sortOnlyMoment";
	private static final String LOCAL_MOMENT = "localMoment";
	private static final String LOCAL_TIME = "localTime";
	private static final String PRIORITY = "priority";
	/** Compound of the sort-only moment ascending and the priority descending. */
	private static final String MOMENT_THEN_PRIORITY = "momentThenPriority";
	/** Compound of the sort-only moment descending and the priority ascending. */
	private static final String MOMENT_DESC_THEN_PRIORITY = "momentDescThenPriority";
	/** 09:00Z written at offset +02:00. */
	private static final OffsetDateTime NINE_AT_PLUS_TWO = OffsetDateTime.parse("2024-01-01T11:00:00+02:00");
	/** 09:00Z written at offset +01:00 - the same moment as {@link #NINE_AT_PLUS_TWO}, but an earlier local time. */
	private static final OffsetDateTime NINE_AT_PLUS_ONE = OffsetDateTime.parse("2024-01-01T10:00:00+01:00");
	/** 08:00Z, earlier than both. */
	private static final OffsetDateTime EIGHT_AT_UTC = OffsetDateTime.parse("2024-01-01T08:00:00Z");
	/** 08:30Z, between the two moments above. */
	private static final OffsetDateTime HALF_PAST_EIGHT_AT_UTC = OffsetDateTime.parse("2024-01-01T08:30:00Z");

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("TemporalSortValue");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Defines the schema and stores three owners whose moments are: owner 1 at 09:00Z written at +02:00, owner 2 at
	 * 09:00Z written at +01:00 and owner 3 at 08:00Z - every owner carrying them in all its temporal attributes and
	 * in its reference to target 1. The catalog then goes live when the scenario asks for it.
	 *
	 * @param state the state the catalog is left in
	 */
	private void defineAndPopulate(@Nonnull CatalogState state) {
		defineAndPopulate(
			state,
			session -> {
				upsertOwner(session, 1, NINE_AT_PLUS_TWO, 5, 1);
				upsertOwner(session, 2, NINE_AT_PLUS_ONE, 1, 1);
				upsertOwner(session, 3, EIGHT_AT_UTC, 3, 1);
			}
		);
	}

	/**
	 * Defines the schema, stores four targets and the owners the passed populator stores, and lets the catalog go
	 * live when the scenario asks for it.
	 *
	 * @param state     the state the catalog is left in
	 * @param populator stores the owners
	 */
	private void defineAndPopulate(@Nonnull CatalogState state, @Nonnull Consumer<EvitaSessionContract> populator) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(TARGET).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(OWNER)
					.withoutGeneratedPrimaryKey()
					.withAttribute(MOMENT, OffsetDateTime.class, thatIs -> thatIs.filterable().sortable())
					.withAttribute(SORT_ONLY_MOMENT, OffsetDateTime.class, AttributeSchemaEditor::sortable)
					.withAttribute(LOCAL_MOMENT, LocalDateTime.class, AttributeSchemaEditor::sortable)
					.withAttribute(LOCAL_TIME, LocalTime.class, AttributeSchemaEditor::sortable)
					.withAttribute(PRIORITY, Integer.class)
					.withSortableAttributeCompound(
						MOMENT_THEN_PRIORITY,
						AttributeElement.attributeElement(SORT_ONLY_MOMENT, OrderDirection.ASC),
						AttributeElement.attributeElement(PRIORITY, OrderDirection.DESC)
					)
					.withSortableAttributeCompound(
						MOMENT_DESC_THEN_PRIORITY,
						AttributeElement.attributeElement(SORT_ONLY_MOMENT, OrderDirection.DESC),
						AttributeElement.attributeElement(PRIORITY, OrderDirection.ASC)
					)
					.withReferenceToEntity(
						TARGETS, TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering()
							.withAttribute(MOMENT, OffsetDateTime.class, thatIs -> thatIs.filterable().sortable())
							.withAttribute(SORT_ONLY_MOMENT, OffsetDateTime.class, AttributeSchemaEditor::sortable)
					)
					.updateVia(session);
				for (int target = 1; target <= 4; target++) {
					session.createNewEntity(TARGET, target).upsertVia(session);
				}
				populator.accept(session);
			}
		);
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
	}

	/**
	 * Stores an owner carrying the passed moment in all its temporal attributes and in its reference to the passed
	 * target.
	 *
	 * @param session    the session
	 * @param primaryKey the owner primary key
	 * @param moment     the moment
	 * @param priority   the priority
	 * @param target     the referenced target
	 */
	private static void upsertOwner(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nonnull OffsetDateTime moment,
		int priority,
		int target
	) {
		session.createNewEntity(OWNER, primaryKey)
			.setAttribute(MOMENT, moment)
			.setAttribute(SORT_ONLY_MOMENT, moment)
			.setAttribute(LOCAL_MOMENT, moment.toLocalDateTime())
			.setAttribute(LOCAL_TIME, moment.toLocalTime())
			.setAttribute(PRIORITY, priority)
			.setReference(
				TARGETS, target,
				whichIs -> whichIs
					.setAttribute(MOMENT, moment)
					.setAttribute(SORT_ONLY_MOMENT, moment)
			)
			.upsertVia(session);
	}

	/**
	 * Closes the engine and opens it again over the same directory, waiting until the catalog is loaded.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Lists all owners in the order of the passed ordering, forcing the requested route.
	 *
	 * @param route    {@link DebugMode#PREFER_INDEX_SCAN} or {@link DebugMode#PREFER_PREFETCHING}
	 * @param ordering the ordering
	 * @return the owner primary keys in order
	 */
	@Nonnull
	private List<Integer> ordered(@Nonnull DebugMode route, @Nonnull OrderConstraint ordering) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys = session -> session
			.queryListOfEntityReferences(
				query(collection(OWNER), orderBy(ordering), require(page(1, 10_000), debug(route)))
			)
			.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.toList();
		return this.evita.queryCatalog(TEST_CATALOG, primaryKeys);
	}

	/**
	 * Asserts that both routes order the owners as expected.
	 *
	 * @param expected the expected owner primary keys
	 * @param ordering the ordering
	 * @param label    describes the moment of the assertion
	 */
	private void assertOrder(
		@Nonnull List<Integer> expected, @Nonnull OrderConstraint ordering, @Nonnull String label
	) {
		assertEquals(expected, ordered(DebugMode.PREFER_INDEX_SCAN, ordering), label + " (sort index)");
		assertEquals(expected, ordered(DebugMode.PREFER_PREFETCHING, ordering), label + " (prefetched entities)");
	}

	/**
	 * Asserts the order before a restart and again after it.
	 *
	 * @param expected the expected owner primary keys
	 * @param ordering the ordering
	 */
	private void assertOrderSurvivesRestart(@Nonnull List<Integer> expected, @Nonnull OrderConstraint ordering) {
		assertOrder(expected, ordering, "before the restart");
		restart();
		assertOrder(expected, ordering, "after the restart");
	}

	/**
	 * Returns the values a sort index of the global owner index holds, in its order.
	 *
	 * @param attributeName the sorted attribute
	 * @return the distinct sort values
	 */
	@Nonnull
	private Serializable[] globalSortValues(@Nonnull String attributeName) {
		final SortIndex sortIndex = globalIndex().getSortIndex(new AttributeIndexKey(null, attributeName, null));
		assertNotNull(sortIndex, "the sort index of `" + attributeName + "` must exist");
		return sortIndex.getSortedRecordValues();
	}

	/**
	 * Returns the catalog instance of the running engine.
	 *
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
	}

	/**
	 * Returns the owner collection of the running engine.
	 *
	 * @return the owner collection
	 */
	@Nonnull
	private EntityCollection ownerCollection() {
		return (EntityCollection) catalog().getCollectionForEntity(OWNER).orElseThrow();
	}

	/**
	 * Returns the global index of the owner collection.
	 *
	 * @return the global index
	 */
	@Nonnull
	private EntityIndex globalIndex() {
		return Objects.requireNonNull(
			ownerCollection().getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE))
		);
	}

	@Nested
	@DisplayName("Attribute filterable and sortable at once")
	class FilterableAndSortable {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("ties one moment written at two offsets the same way before and after a restart")
		void shouldTieOneMomentAtTwoOffsetsBeforeAndAfterRestart(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			// 08:00Z first, then the 09:00Z tie broken by the ascending primary key
			assertOrderSurvivesRestart(List.of(3, 1, 2), attributeNatural(MOMENT, OrderDirection.ASC));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("ties one moment written at two offsets of a reference attribute before and after a restart")
		void shouldTieOneReferenceMomentAtTwoOffsetsBeforeAndAfterRestart(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			assertOrderSurvivesRestart(
				List.of(3, 1, 2), referenceProperty(TARGETS, attributeNatural(MOMENT, OrderDirection.ASC))
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("breaks the tie by the descending primary key when the order is descending")
		void shouldBreakTieByDescendingPrimaryKeyWhenDescending(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			assertOrderSurvivesRestart(
				List.of(2, 1, 3), referenceProperty(TARGETS, attributeNatural(MOMENT, OrderDirection.DESC))
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("merges a loaded reduced index with one created after the restart when picking the first row")
		void shouldMergeLoadedAndNewlyCreatedReducedIndexesWhenPickingFirst(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			restart();
			// owner 4 references target 2 only, whose reduced index is created now - after the reduced index of
			// target 1 was loaded from disk - so the pick-first merge compares the heads of two runs, one from each
			TemporalSortValueFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					upsertOwner(session, 4, HALF_PAST_EIGHT_AT_UTC, 2, 2);
				}
			);
			final OrderConstraint ordering = referenceProperty(TARGETS, attributeNatural(MOMENT, OrderDirection.ASC));
			assertOrder(List.of(3, 4, 1, 2), ordering, "after the new reduced index was created");
			restart();
			assertOrder(List.of(3, 4, 1, 2), ordering, "after the second restart");
		}
	}

	@Nested
	@DisplayName("Attribute sortable only")
	class SortableOnly {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("ties one moment written at two offsets of an entity attribute")
		void shouldTieOneMomentAtTwoOffsets(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			assertOrderSurvivesRestart(List.of(3, 1, 2), attributeNatural(SORT_ONLY_MOMENT, OrderDirection.ASC));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("ties one moment written at two offsets of a reference attribute")
		void shouldTieOneReferenceMomentAtTwoOffsets(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			assertOrderSurvivesRestart(
				List.of(3, 1, 2), referenceProperty(TARGETS, attributeNatural(SORT_ONLY_MOMENT, OrderDirection.ASC))
			);
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("keeps a local date time as an instant and a local time as a millisecond local time")
		void shouldKeepLocalTemporalValuesInTheirIndexForm(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			for (int round = 0; round < 2; round++) {
				final String label = round == 0 ? " before the restart" : " after the restart";
				assertEquals(
					List.of(3, 2, 1), ordered(DebugMode.PREFER_INDEX_SCAN, attributeNatural(LOCAL_MOMENT)),
					"local date times" + label
				);
				assertEquals(
					List.of(3, 2, 1), ordered(DebugMode.PREFER_INDEX_SCAN, attributeNatural(LOCAL_TIME)),
					"local times" + label
				);
				for (Serializable value : globalSortValues(LOCAL_MOMENT)) {
					assertInstanceOf(Instant.class, value, "a local date time is sorted by its UTC instant" + label);
				}
				for (Serializable value : globalSortValues(LOCAL_TIME)) {
					assertInstanceOf(LocalTime.class, value, "a local time is sorted as a local time" + label);
				}
				restart();
			}
		}
	}

	@Nested
	@DisplayName("Sortable attribute compound with a temporal element")
	class Compound {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("lets the next element decide one moment written at two offsets when ascending")
		void shouldLetNextElementDecideTieWhenAscending(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			// 08:00Z first, then the 09:00Z tie decided by the descending priority: owner 1 (5), owner 2 (1)
			assertOrderSurvivesRestart(List.of(3, 1, 2), attributeNatural(MOMENT_THEN_PRIORITY));
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("lets the next element decide one moment written at two offsets when descending")
		void shouldLetNextElementDecideTieWhenDescending(@Nonnull CatalogState state) {
			defineAndPopulate(state);
			// the 09:00Z tie first, decided by the ascending priority: owner 2 (1), owner 1 (5), then 08:00Z
			assertOrderSurvivesRestart(List.of(2, 1, 3), attributeNatural(MOMENT_DESC_THEN_PRIORITY));
		}
	}


	@Nested
	@DisplayName("Catalog written before temporal sort values were normalized")
	class LegacyCatalog {
		/** Enough owners to spread every legacy owner sort index over several leaf pages. */
		private static final int OWNER_COUNT = 600;
		/** The owner added after the legacy catalog was upgraded. */
		private static final int LATE_OWNER = 1_000;
		/** The storage protocol of 2026.2, which the storage migration to protocol 7 starts from. */
		private static final int RELEASED_PROTOCOL = 6;

		/**
		 * The moment of a legacy owner: owners `2k + 1` and `2k + 2` share one moment, the odd one written at +02:00
		 * and the even one at +01:00 - so the raw values order the even owner first, while the normalized ones tie
		 * and order the two by primary key.
		 *
		 * @param owner the owner primary key
		 * @return the moment the owner carries
		 */
		@Nonnull
		private static OffsetDateTime legacyMoment(int owner) {
			return NINE_AT_PLUS_TWO.toInstant()
				.plusSeconds((owner - 1) / 2)
				.atOffset(owner % 2 == 1 ? ZoneOffset.ofHours(2) : ZoneOffset.ofHours(1));
		}

		/**
		 * The priority of a legacy owner - descending with the primary key, so the compound ordered by the moment and
		 * then by the descending priority orders a tie by the primary key, unlike the raw values.
		 *
		 * @param owner the owner primary key
		 * @return the priority the owner carries
		 */
		private static int legacyPriority(int owner) {
			return OWNER_COUNT + 1 - owner;
		}

		/**
		 * A catalog written by a release storing raw temporal sort values is upgraded by the storage migration to
		 * protocol 7, which rewrites every such sort index - the paged ones of a sort-only attribute and of a compound
		 * in the global index, and the paged one of a reference attribute in a partition with many owners - in
		 * normalized form. The load path corrects nothing, so the order every later load serves is the one the
		 * rewritten parts hold, and no legacy leaf page may be left behind.
		 *
		 * @param state the state the catalog is in when it is stamped
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("the storage migration rewrites raw sort values normalized, and every later load reads them so")
		void shouldRewriteRawSortValuesWhenTheCatalogIsUpgraded(@Nonnull CatalogState state) {
			defineAndPopulate(
				CatalogState.WARMING_UP,
				session -> {
					for (int owner = 1; owner <= OWNER_COUNT; owner++) {
						upsertOwner(session, owner, legacyMoment(owner), legacyPriority(owner), 1);
					}
				}
			);
			// replace the sort indexes the current engine wrote with the ones a version storing raw values wrote; the
			// next flush of the catalog - closing it in warm-up, or going live - publishes them
			restart();
			final List<LegacyPages> legacyPages = writeLegacySortIndexes();
			if (state == CatalogState.ALIVE) {
				TemporalSortValueFunctionalTest.this.evita.updateCatalog(
					TEST_CATALOG, EvitaSessionContract::goLiveAndClose
				);
			}
			restart();
			assertSortValueTypes(OffsetDateTime.class, OffsetDateTime.class, "the legacy catalog");
			TemporalSortValueFunctionalTest.this.evita.close();
			// the catalog is stamped as the release that wrote such indexes left it, so its next start upgrades it
			OfflineCatalogImages.stamp(
				newTestEvitaConfigurationBuilder(TemporalSortValueFunctionalTest.this.paths).build(), TEST_CATALOG,
				RELEASED_PROTOCOL, RELEASED_PROTOCOL
			);

			final List<Integer> byPrimaryKey = IntStream.rangeClosed(1, OWNER_COUNT).boxed().toList();
			for (String moment : new String[]{"the upgraded catalog", "the upgraded catalog reloaded"}) {
				restart();
				assertSortValueTypes(Instant.class, Instant.class, moment);
				assertLegacyPagesRemoved(legacyPages, moment);
				assertOrder(byPrimaryKey, attributeNatural(SORT_ONLY_MOMENT), moment);
				assertOrder(byPrimaryKey, attributeNatural(MOMENT_THEN_PRIORITY), moment);
				assertOrder(byPrimaryKey, referenceProperty(TARGETS, attributeNatural(SORT_ONLY_MOMENT)), moment);
			}

			// the late owner writes into every rewritten sort index of the global index - a write the raw keys of a
			// legacy index would refuse - and its single reference to target 2 becomes a row merged with the
			// partition of target 1 when picking the first row
			TemporalSortValueFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					upsertOwner(session, LATE_OWNER, EIGHT_AT_UTC, 0, 2);
				}
			);
			final List<Integer> withLateOwner = IntStream.concat(
				IntStream.of(LATE_OWNER), IntStream.rangeClosed(1, OWNER_COUNT)
			).boxed().toList();
			for (String moment : new String[]{"after a late owner", "after a late owner reloaded"}) {
				assertOrder(withLateOwner, attributeNatural(SORT_ONLY_MOMENT), moment);
				assertOrder(withLateOwner, attributeNatural(MOMENT_THEN_PRIORITY), moment);
				assertOrder(
					withLateOwner, referenceProperty(TARGETS, attributeNatural(SORT_ONLY_MOMENT)), moment
				);
				restart();
			}
		}

		/**
		 * Overwrites the persisted sort indexes of the sort-only moment and of the compound in the global index, and
		 * of the sort-only moment in the reduced index of target 1, with the leaf pages a version storing raw values
		 * wrote for the same owners. The pages are written straight into the storage of the owner collection; the
		 * catalog publishes them with its next flush.
		 *
		 * @return the leaf pages written, for every sort index
		 */
		@Nonnull
		private List<LegacyPages> writeLegacySortIndexes() {
			final EntityCollection collection = ownerCollection();
			final ReferenceSchemaContract referenceSchema =
				collection.getSchema().getReferenceOrThrowException(TARGETS);
			final List<LegacyPages> written = new ArrayList<>(3);
			written.add(
				writeLegacySortIndex(
					collection, globalIndex(), attributeKey(globalIndex(), SORT_ONLY_MOMENT),
					LegacyCatalog::legacyMoment
				)
			);
			written.add(
				writeLegacySortIndex(
					collection, globalIndex(), attributeKey(globalIndex(), MOMENT_THEN_PRIORITY),
					owner -> new ComparableArray(new Serializable[]{legacyMoment(owner), legacyPriority(owner)})
				)
			);
			final EntityIndex reducedIndex = reducedIndexOfFirstTarget();
			written.add(
				writeLegacySortIndex(
					collection, reducedIndex,
					Objects.requireNonNull(
						reducedIndex.getSortIndex(
							referenceSchema, referenceSchema.getAttribute(SORT_ONLY_MOMENT).orElseThrow(), null
						)
					).getAttributeIndexKey(),
					LegacyCatalog::legacyMoment
				)
			);
			return written;
		}

		/**
		 * Overwrites one persisted sort index with the leaf pages a version storing raw values wrote for the owners.
		 *
		 * @param collection      the owner collection
		 * @param entityIndex     the entity index the sort index belongs to
		 * @param attributeKey    the attribute or compound of the sort index
		 * @param rawValueOfOwner the raw value the old version stored for an owner
		 * @return the leaf pages written
		 */
		@Nonnull
		private LegacyPages writeLegacySortIndex(
			@Nonnull EntityCollection collection,
			@Nonnull EntityIndex entityIndex,
			@Nonnull AttributeIndexKey attributeKey,
			@Nonnull IntFunction<Serializable> rawValueOfOwner
		) {
			final long catalogVersion = catalog().getVersion();
			final SortIndexStoragePart currentRoot = onDiskRoot(entityIndex, attributeKey);
			assertTrue(currentRoot.isPaged(), "the sort index of `" + attributeKey + "` must span several leaf pages");
			final LegacyOwnerSortIndexWriter legacy = new LegacyOwnerSortIndexWriter(
				entityIndex.getPrimaryKey(), attributeKey, currentRoot.getComparatorBase()
			);
			for (int owner = 1; owner <= OWNER_COUNT; owner++) {
				legacy.add(rawValueOfOwner.apply(owner), owner);
			}
			SortIndexStoragePart legacyRoot = null;
			for (StoragePart part : legacy.toPagedParts(currentRoot.getHighWaterPageSequence())) {
				collection.getStoragePartPersistenceService().putStoragePart(catalogVersion, part);
				if (part instanceof SortIndexStoragePart root) {
					legacyRoot = root;
				}
			}
			return new LegacyPages(
				entityIndex.getIndexKey(), attributeKey, Objects.requireNonNull(legacyRoot).getHighWaterPageSequence(),
				legacyRoot.getLeafPageSequences()
			);
		}

		/**
		 * Asserts that no leaf page a legacy sort index listed is stored any more, and that every leaf page the
		 * rewritten sort index lists has an identity of its own, above the legacy high-water - read from the storage
		 * of the owner collection, page by page, rather than counted.
		 *
		 * @param legacyPages the leaf pages written for every legacy sort index
		 * @param label       describes the moment of the assertion
		 */
		private void assertLegacyPagesRemoved(@Nonnull List<LegacyPages> legacyPages, @Nonnull String label) {
			final EntityCollection collection = ownerCollection();
			for (LegacyPages legacy : legacyPages) {
				final EntityIndex entityIndex = Objects.requireNonNull(
					collection.getIndexByKeyIfExists(legacy.entityIndexKey())
				);
				final int streamId = collection.getStoragePartPersistenceService().getReadOnlyKeyCompressor().getId(
					new LeafStreamKey(
						entityIndex.getPrimaryKey(),
						new AttributeKeyWithIndexType(legacy.attributeKey(), AttributeIndexType.SORT)
					)
				);
				for (int pageSequence : legacy.pageSequences()) {
					assertNull(
						collection.getDataStoreReader().fetch(
							catalog().getVersion(), AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
							SortIndexLeafPagePart.class
						),
						"legacy leaf page " + pageSequence + " of `" + legacy.attributeKey() + "` is still stored in " +
							label
					);
				}
				final Set<Integer> legacySequences = new HashSet<>();
				for (int pageSequence : legacy.pageSequences()) {
					legacySequences.add(pageSequence);
				}
				for (int pageSequence : onDiskRoot(entityIndex, legacy.attributeKey()).getLeafPageSequences()) {
					assertFalse(
						legacySequences.contains(pageSequence),
						"leaf page " + pageSequence + " of `" + legacy.attributeKey() + "` in " + label +
							" reuses the identity of a legacy page"
					);
					assertTrue(
						pageSequence > legacy.highWaterPageSequence(),
						"leaf page " + pageSequence + " of `" + legacy.attributeKey() + "` in " + label +
							" must lie above the legacy high-water " + legacy.highWaterPageSequence()
					);
				}
			}
		}

		/**
		 * Asserts the classes of the moments the persisted sort indexes hold: the sort-only moment and the compound
		 * of the global index, and the sort-only moment of the reduced index of target 1.
		 *
		 * @param globalType  the expected class of the moments of the global index
		 * @param reducedType the expected class of the moments of the reduced index
		 * @param label       describes the moment of the assertion
		 */
		private void assertSortValueTypes(
			@Nonnull Class<?> globalType, @Nonnull Class<?> reducedType, @Nonnull String label
		) {
			final EntityIndex globalIndex = globalIndex();
			for (Serializable value : onDiskValues(globalIndex, attributeKey(globalIndex, SORT_ONLY_MOMENT))) {
				assertInstanceOf(globalType, value, "the sort-only moment of " + label);
			}
			for (Serializable value : onDiskValues(globalIndex, attributeKey(globalIndex, MOMENT_THEN_PRIORITY))) {
				assertInstanceOf(
					globalType, ((ComparableArray) value).array()[0], "the moment in the compound of " + label
				);
			}
			final EntityIndex reducedIndex = reducedIndexOfFirstTarget();
			final ReferenceSchemaContract referenceSchema =
				ownerCollection().getSchema().getReferenceOrThrowException(TARGETS);
			final AttributeIndexKey reducedKey = Objects.requireNonNull(
				reducedIndex.getSortIndex(
					referenceSchema, referenceSchema.getAttribute(SORT_ONLY_MOMENT).orElseThrow(), null
				)
			).getAttributeIndexKey();
			for (Serializable value : onDiskValues(reducedIndex, reducedKey)) {
				assertInstanceOf(reducedType, value, "the reference moment of " + label);
			}
		}

		/**
		 * Returns every value the persisted sort index holds, read from the storage of the owner collection.
		 *
		 * @param entityIndex  the entity index the sort index belongs to
		 * @param attributeKey the attribute or compound of the sort index
		 * @return the persisted values, in their persisted order
		 */
		@Nonnull
		private List<Serializable> onDiskValues(
			@Nonnull EntityIndex entityIndex, @Nonnull AttributeIndexKey attributeKey
		) {
			final SortIndexStoragePart root = onDiskRoot(entityIndex, attributeKey);
			if (!root.isPaged()) {
				return List.of(root.getSortedRecordsValues());
			}
			final EntityCollection collection = ownerCollection();
			final int streamId = collection.getStoragePartPersistenceService().getReadOnlyKeyCompressor().getId(
				new LeafStreamKey(
					entityIndex.getPrimaryKey(), new AttributeKeyWithIndexType(attributeKey, AttributeIndexType.SORT)
				)
			);
			final List<Serializable> values = new ArrayList<>(OWNER_COUNT + 1);
			for (int pageSequence : root.getLeafPageSequences()) {
				final SortIndexLeafPagePart page = Objects.requireNonNull(
					collection.getDataStoreReader().fetch(
						catalog().getVersion(), AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
						SortIndexLeafPagePart.class
					)
				);
				for (ValueToRecord bucket : page.getBuckets()) {
					values.add(bucket.getValue());
				}
			}
			assertTrue(values.size() >= OWNER_COUNT / 2, "the persisted pages must hold the owners' values");
			return values;
		}

		/**
		 * Reads the persisted root part of a sort index from the storage of the owner collection.
		 *
		 * @param entityIndex  the entity index the sort index belongs to
		 * @param attributeKey the attribute or compound of the sort index
		 * @return the root part
		 */
		@Nonnull
		private SortIndexStoragePart onDiskRoot(
			@Nonnull EntityIndex entityIndex, @Nonnull AttributeIndexKey attributeKey
		) {
			final EntityCollection collection = ownerCollection();
			final long rootPrimaryKey = AttributeIndexStoragePart.computeUniquePartId(
				entityIndex.getPrimaryKey(), AttributeIndexType.SORT, attributeKey,
				collection.getStoragePartPersistenceService().getReadOnlyKeyCompressor()
			);
			return Objects.requireNonNull(
				collection.getDataStoreReader().fetch(
					catalog().getVersion(), rootPrimaryKey, SortIndexStoragePart.class
				),
				"the sort index of `" + attributeKey + "` must be persisted"
			);
		}

		/**
		 * Returns the key the global index keeps the sort index of the passed attribute or compound under.
		 *
		 * @param globalIndex   the global index
		 * @param attributeName the attribute or compound
		 * @return the attribute index key
		 */
		@Nonnull
		private static AttributeIndexKey attributeKey(@Nonnull EntityIndex globalIndex, @Nonnull String attributeName) {
			final AttributeIndexKey key = new AttributeIndexKey(null, attributeName, null);
			assertNotNull(globalIndex.getSortIndex(key), "the sort index of `" + attributeName + "` must exist");
			return key;
		}

		/**
		 * Returns the reduced index of the owners referencing target 1.
		 *
		 * @return the reduced index
		 */
		@Nonnull
		private EntityIndex reducedIndexOfFirstTarget() {
			return Objects.requireNonNull(
				ownerCollection().getIndexByKeyIfExists(
					new EntityIndexKey(
						EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
						new RepresentativeReferenceKey(new ReferenceKey(TARGETS, 1))
					)
				)
			);
		}

		/**
		 * The leaf pages written for one legacy sort index.
		 *
		 * @param entityIndexKey        the key of the entity index the sort index belongs to
		 * @param attributeKey          the attribute or compound of the sort index
		 * @param highWaterPageSequence the high-water of the legacy page stream
		 * @param pageSequences         the leaf pages the legacy root lists
		 */
		private record LegacyPages(
			@Nonnull EntityIndexKey entityIndexKey,
			@Nonnull AttributeIndexKey attributeKey,
			int highWaterPageSequence,
			@Nonnull int[] pageSequences
		) {
		}
	}
}
