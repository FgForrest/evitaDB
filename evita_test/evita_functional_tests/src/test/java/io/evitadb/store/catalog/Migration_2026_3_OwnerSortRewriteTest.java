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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.LegacyOwnerSortImages.LegacySortIndex;
import io.evitadb.store.catalog.OfflineCatalogImages.StoredFile;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.assertRewritten;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.assertStillAsWritten;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.pageValues;
import static io.evitadb.store.catalog.LegacyOwnerSortImages.writeLegacySortIndexes;
import static io.evitadb.store.catalog.OfflineCatalogImages.assertOnlyAppended;
import static io.evitadb.store.catalog.OfflineCatalogImages.catalogFolder;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.migrateOffline;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.storedFiles;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the rewrite of the owner sort indexes release 2026.2 wrote that the storage migration to protocol 8
 * ({@link Migration_2026_3#upgradeFromStorageProtocolVersion_6_to_8}) performs, over catalogs whose only change in the
 * migration is that rewrite: no reference and no unique attribute, so nothing is re-keyed and nothing converted.
 * Without its own term in the publication decision such a collection would keep its old collection header, and
 * protocol 8 would be published over the legacy parts.
 *
 * The catalog is written by the engine, its sort index replaced offline by the parts release 2026.2 wrote
 * ({@link LegacyOwnerSortImages}), and stamped with storage protocol 6 ({@link OfflineCatalogImages#stamp}); its next
 * start runs the real migration.
 *
 * A collection of events with a sort-only `OffsetDateTime` attribute covers the raw temporal values: every pair of
 * events `2k + 1` and `2k + 2` carries one moment written at +02:00 and +01:00 - raw, the earlier local time orders the
 * even event first; normalized, the two tie and are ordered by primary key. The order the sort index serves therefore
 * tells the two forms apart.
 *
 * Localized compounds cover the order of the bare collator the release compared a localized text by: the order of the
 * legacy pages is checked by the comparator of the release that wrote them, while the index is rebuilt by the current
 * one, and a compound with no temporal element at all is re-sorted too - its pages would not load as they were
 * written. Compounds with a `Locale` element cover the language tag the release compared a locale by alone, whether
 * the compound is localized or not. Periods - `DateTimeRange` values, alone or in a compound - cover the whole seconds
 * and the open bounds derived from a zone offset the release compared a range by. The class also characterizes the
 * one input the migration does not reach: a catalog of protocol 8 holding raw temporal sort values, which only a
 * development build of 2026.3 can have written.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — owner sort indexes of 2026.2 are rewritten in the current form and order (v6→v8)")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
@Tag(ORDER)
@SuppressWarnings("removal") // the migration is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_OwnerSortRewriteTest implements EvitaTestSupport {
	/**
	 * The catalog of every case.
	 */
	private static final String CATALOG = "ownerSortCatalog";
	/**
	 * The collection of events.
	 */
	private static final String ENTITY_EVENT = "event";
	/**
	 * The sort-only moment of an event - its sort index owns its values.
	 */
	private static final String ATTRIBUTE_MOMENT = "moment";
	/**
	 * The collection of entries with a localized label, sorted by a compound of the label and a moment.
	 */
	private static final String ENTITY_ENTRY = "entry";
	/**
	 * The localized label of an entry - an element of {@link #COMPOUND_LABEL_MOMENT}.
	 */
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * The compound of the label ascending and the moment ascending - a localized owner sort index.
	 */
	private static final String COMPOUND_LABEL_MOMENT = "labelThenMoment";
	/**
	 * The integer rank of an entry - an element of {@link #COMPOUND_LABEL_RANK}.
	 */
	private static final String ATTRIBUTE_RANK = "rank";
	/**
	 * The compound of the label ascending and the rank ascending - a localized owner sort index with no temporal
	 * element.
	 */
	private static final String COMPOUND_LABEL_RANK = "labelThenRank";
	/**
	 * The language of an entry - a `Locale` attribute, not localized - an element of
	 * {@link #COMPOUND_LANGUAGE_RANK} and {@link #COMPOUND_LABEL_LANGUAGE_RANK}.
	 */
	private static final String ATTRIBUTE_LANGUAGE = "language";
	/**
	 * The compound of the language ascending and the rank ascending - an owner sort index that is not localized.
	 */
	private static final String COMPOUND_LANGUAGE_RANK = "languageThenRank";
	/**
	 * The compound of the label, the language and the rank, all ascending - a localized owner sort index with a
	 * `Locale` element.
	 */
	private static final String COMPOUND_LABEL_LANGUAGE_RANK = "labelThenLanguageThenRank";
	/**
	 * The validity period of an entry - a `DateTimeRange` attribute, sort-only on its own or an element of
	 * {@link #COMPOUND_PERIOD_RANK} and {@link #COMPOUND_PERIOD_MOMENT}.
	 */
	private static final String ATTRIBUTE_PERIOD = "period";
	/**
	 * The compound of the period ascending and the rank ascending - an owner sort index whose only element the release
	 * ordered differently is the period.
	 */
	private static final String COMPOUND_PERIOD_RANK = "periodThenRank";
	/**
	 * The compound of the period ascending and the moment ascending - an owner sort index holding raw temporal values.
	 */
	private static final String COMPOUND_PERIOD_MOMENT = "periodThenMoment";
	/**
	 * How many entries the localized compound case writes - enough to page its sort index.
	 */
	private static final int ENTRY_COUNT = 600;
	/**
	 * How many entries a case of an inline sort index writes.
	 */
	private static final int INLINE_ENTRY_COUNT = 6;
	/**
	 * The entry whose label and that of the next entry are the only two spellings the collator equates in the case of
	 * a pair straddling a page boundary - placed so that the legacy index ends its first leaf page with it.
	 */
	private static final int STRADDLING_ENTRY = 128;
	/**
	 * The storage protocol of 2026.2 - the one the migration starts from.
	 */
	private static final int RELEASED_PROTOCOL = 6;
	/**
	 * 09:00Z of the first day the events use.
	 */
	private static final OffsetDateTime NINE_AT_UTC = OffsetDateTime.parse("2024-01-01T09:00:00Z");

	/**
	 * The directories the test allocated, removed after it.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);
	/**
	 * The engine the test runs, closed after it.
	 */
	@Nullable private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		this.evita = null;
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * The shapes a sort index is stored in.
	 */
	enum Shape {
		/**
		 * One inline part.
		 */
		INLINE(6),
		/**
		 * Leaf pages and a paged root.
		 */
		PAGED(600);

		/**
		 * How many events the catalog holds - enough for the shape.
		 */
		private final int eventCount;

		Shape(int eventCount) {
			this.eventCount = eventCount;
		}
	}

	/**
	 * Cases of a collection whose only change in the migration is the rewrite of its sort index.
	 */
	@Nested
	@DisplayName("A collection with nothing but a sort index to rewrite")
	class TemporalRewriteOnly {

		/**
		 * The first attempt fails at its publication and must leave the published raw state exactly as it was; the
		 * rerun publishes the rewritten sort index, which every later load reads in instant order.
		 *
		 * @param shape the shape the legacy sort index is stored in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Shape.class)
		@DisplayName("publishes the rewritten sort index, also after a failed first attempt, and keeps it on reload")
		void shouldPublishTheRewriteOfACollectionWithNothingElseToUpgrade(@Nonnull Shape shape) {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("rewriteOnly_" + shape.name()));
			final List<LegacySortIndex> legacy = writeLegacyImage(configuration, shape);
			final Path folder = catalogFolder(configuration, CATALOG);
			final SortedMap<String, StoredFile> before = storedFiles(folder);
			final CatalogBootstrap publishedBefore = lastBootstrap(configuration, CATALOG);

			// the first attempt fails at its publication: the rewrite only appended, nothing was published
			assertThrows(
				InjectedFailure.class,
				() -> migrateOffline(
					configuration, CATALOG,
					header -> {
						throw new InjectedFailure();
					},
					new HashMap<>()
				)
			);
			assertOnlyAppended("after the failed migration", before, storedFiles(folder), true);
			assertEquals(publishedBefore, lastBootstrap(configuration, CATALOG), "nothing may be published");
			assertStillAsWritten("after the failed migration", configuration, CATALOG, ENTITY_EVENT, legacy);

			// the rerun from the same published state, through the load path
			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			final Evita migrated = start(configuration);
			assertEventsInInstantOrder("after the migration", migrated, shape);
			migrated.close();
			assertEquals(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				lastBootstrap(configuration, CATALOG).storageProtocolVersion()
			);
			assertEquals(
				1, assertRewritten("after the migration", configuration, CATALOG, ENTITY_EVENT, legacy),
				"the sort index of the events must have been rewritten"
			);

			final Evita reloaded = start(configuration);
			assertEventsInInstantOrder("after a reload", reloaded, shape);
			reloaded.close();
			assertRewritten("after a reload", configuration, CATALOG, ENTITY_EVENT, legacy);
		}
	}

	/**
	 * Cases of a sort index whose persisted order only the comparator of the release that wrote it explains.
	 */
	@Nested
	@DisplayName("A localized compound ordered by the collator of the release that wrote it")
	class ReleasedCollationOrder {

		/**
		 * Release 2026.2 compared a `String` element of a localized sort index by the bare collator, which equates two
		 * spellings of `ab` - with and without a zero-width space between the letters. Every pair of entries `2k + 1`
		 * and `2k + 2` carries such a pair of labels, the odd one with the space and the earlier moment, so the release
		 * ordered the pair by its moment: `2k + 1` first. The current comparator breaks the tie between the spellings
		 * and orders the pair the other way, so a check of the persisted order by the current comparator would refuse
		 * these legitimate pages as corruption. The migration must check them by the released comparator, rebuild the
		 * index by the current one, keep every record and leave no legacy page behind.
		 */
		@Test
		@DisplayName("rewrites pages a release ordered by the bare collator, in the order the current index keeps")
		void shouldRewritePagesOrderedByTheReleasedCollator() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("releasedCollation"));
			final Evita engine = start(configuration);
			engine.defineCatalog(CATALOG).updateViaNewSession(engine);
			engine.updateCatalog(
				CATALOG,
				session -> {
					session.defineEntitySchema(ENTITY_ENTRY)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable())
						.withAttribute(ATTRIBUTE_MOMENT, OffsetDateTime.class, thatIs -> thatIs.nullable())
						.withSortableAttributeCompound(
							COMPOUND_LABEL_MOMENT,
							AttributeElement.attributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC),
							AttributeElement.attributeElement(ATTRIBUTE_MOMENT, OrderDirection.ASC)
						)
						.updateVia(session);
					for (int entry = 1; entry <= ENTRY_COUNT; entry++) {
						session.createNewEntity(ENTITY_ENTRY, entry)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, labelOf(entry))
							.setAttribute(ATTRIBUTE_MOMENT, entryMomentOf(entry))
							.upsertVia(session);
					}
				}
			);
			engine.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			engine.close();
			final List<LegacySortIndex> legacy = writeLegacySortIndexes(
				configuration, CATALOG, ENTITY_ENTRY, Set.of(COMPOUND_LABEL_MOMENT),
				entry -> new ComparableArray(new Serializable[]{labelOf(entry), entryMomentOf(entry)})
			);
			assertEquals(1, legacy.size(), "the entries hold one sort index: " + legacy);
			assertTrue(legacy.getFirst().paged(), "the legacy sort index must span several leaf pages");
			// the premise: the current comparator orders every pair the other way than the release did
			final List<Integer> currentOrder = new ArrayList<>(ENTRY_COUNT);
			for (int entry = 1; entry <= ENTRY_COUNT; entry += 2) {
				currentOrder.add(entry + 1);
				currentOrder.add(entry);
			}
			assertEquals(
				currentOrder,
				IntStream.of(legacy.getFirst().writer().toCurrentOwner().getSortedRecords()).boxed().toList(),
				"the current comparator must reverse every pair the release ordered by its moment"
			);
			assertStillAsWritten("as written", configuration, CATALOG, ENTITY_ENTRY, legacy);

			stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
			// the open migrates the catalog - and refuses it, were the pages checked by the current comparator
			withOfflineCatalog(configuration, CATALOG, service -> Boolean.TRUE);
			assertEquals(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				lastBootstrap(configuration, CATALOG).storageProtocolVersion()
			);
			assertEquals(
				1, assertRewritten("after the migration", configuration, CATALOG, ENTITY_ENTRY, legacy),
				"the sort index of the entries must have been rewritten"
			);

			final Evita migrated = start(configuration);
			assertEquals(
				currentOrder, orderedEntries(migrated, COMPOUND_LABEL_MOMENT, Locale.ENGLISH), "the migrated sort index"
			);
			migrated.close();
			assertRewritten("after a reload", configuration, CATALOG, ENTITY_ENTRY, legacy);
		}

		/**
		 * The same pairs of labels in a localized compound whose next element is an integer rank, so the index holds
		 * no temporal value at all. The release ordered every pair by its rank, `2k + 1` first; the current comparator
		 * orders it `2k + 2` first, so the pages fail to load as they were written - the bulk load of a leaf page
		 * refuses keys that do not ascend. The migration must re-sort the index, and the reload that follows must find
		 * nothing left to write.
		 */
		@Test
		@DisplayName("re-sorts a paged compound without a temporal element a release ordered by the bare collator")
		void shouldResortALocalizedCompoundWithoutATemporalElement() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("localizedCompound"));
			final List<LegacySortIndex> legacy = writeLabelAndRankImage(
				configuration, Migration_2026_3_OwnerSortRewriteTest::labelOf
			);
			// the premise: the current comparator orders every pair the other way than the release did
			final List<Integer> currentOrder = new ArrayList<>(ENTRY_COUNT);
			for (int entry = 1; entry <= ENTRY_COUNT; entry += 2) {
				currentOrder.add(entry + 1);
				currentOrder.add(entry);
			}
			assertEquals(
				currentOrder,
				IntStream.of(legacy.getFirst().writer().toCurrentOwner().getSortedRecords()).boxed().toList(),
				"the current comparator must reverse every pair the release ordered by its rank"
			);

			assertResortedByTheMigration(configuration, legacy, COMPOUND_LABEL_RANK, Locale.ENGLISH, currentOrder);
		}

		/**
		 * One pair of labels the collator equates, the only one of the index, placed so that the legacy index ends
		 * its first leaf page with the first of them and starts the second with the other. Each page ascends on its
		 * own under the current comparator; only the boundary between them does not. The migration must re-sort the
		 * index across the boundary.
		 */
		@Test
		@DisplayName("re-sorts a pair a release ordered by the bare collator across a leaf page boundary")
		void shouldResortALocalizedPairStraddlingALeafPageBoundary() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("straddlingPair"));
			final List<LegacySortIndex> legacy = writeLabelAndRankImage(
				configuration, Migration_2026_3_OwnerSortRewriteTest::straddlingLabelOf
			);
			// the premise: the pair is split by a page boundary of the legacy index, the first of it ending a page
			final List<List<Serializable>> pages = pageValues(configuration, CATALOG, ENTITY_ENTRY, legacy.getFirst());
			final Serializable first = new ComparableArray(
				new Serializable[]{straddlingLabelOf(STRADDLING_ENTRY), STRADDLING_ENTRY}
			);
			final Serializable second = new ComparableArray(
				new Serializable[]{straddlingLabelOf(STRADDLING_ENTRY + 1), STRADDLING_ENTRY + 1}
			);
			assertTrue(
				IntStream.range(1, pages.size()).anyMatch(
					page -> first.equals(pages.get(page - 1).getLast()) && second.equals(pages.get(page).getFirst())
				),
				"the pair must straddle a page boundary of the legacy index, its pages end with " +
					pages.stream().map(List::getLast).toList()
			);
			final List<Integer> currentOrder = new ArrayList<>(ENTRY_COUNT);
			for (int entry = 1; entry <= ENTRY_COUNT; entry++) {
				currentOrder.add(
					entry == STRADDLING_ENTRY ? entry + 1 : entry == STRADDLING_ENTRY + 1 ? entry - 1 : entry
				);
			}
			assertEquals(
				currentOrder,
				IntStream.of(legacy.getFirst().writer().toCurrentOwner().getSortedRecords()).boxed().toList(),
				"the current comparator must reverse the pair the release ordered by its rank"
			);

			assertResortedByTheMigration(configuration, legacy, COMPOUND_LABEL_RANK, Locale.ENGLISH, currentOrder);
		}
	}

	/**
	 * Cases of a sort index with a `Locale` element, which release 2026.2 ordered by its language tag alone. The tag
	 * drops an ill-formed variant, so `en-AA` with and without one tied, while the current index orders the one
	 * without it first. Every pair of entries `2k + 1` and `2k + 2` carries such a pair of locales, the odd one with
	 * the variant, and the release ordered the pair by its rank - `2k + 1` first; the current comparator orders it
	 * `2k + 2` first.
	 */
	@Nested
	@DisplayName("A compound with a Locale element ordered by the language tag of the release that wrote it")
	class ReleasedLocaleOrder {

		/**
		 * A compound of the locale and the rank, not localized and paged: its pages fail to load as they were written,
		 * the bulk load of a leaf page refusing keys that do not ascend. The migration must re-sort the index.
		 */
		@Test
		@DisplayName("re-sorts a paged compound whose locales a release ordered by their language tag")
		void shouldResortAPagedLocaleCompound() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("pagedLocaleCompound"));
			final List<LegacySortIndex> legacy = writeLanguageAndRankImage(configuration, ENTRY_COUNT, false);
			assertTrue(legacy.getFirst().paged(), "the legacy sort index must span several leaf pages");

			assertResortedByTheMigration(
				configuration, legacy, COMPOUND_LANGUAGE_RANK, null, swappedPairs(legacy.getFirst(), ENTRY_COUNT)
			);
		}

		/**
		 * The same compound inline: it loads as it was written, its positional record order disagreeing with its
		 * values, so its sort index answers the order of the release. The migration must re-sort the index.
		 */
		@Test
		@DisplayName("re-sorts an inline compound whose locales a release ordered by their language tag")
		void shouldResortAnInlineLocaleCompound() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("inlineLocaleCompound"));
			final List<LegacySortIndex> legacy = writeLanguageAndRankImage(configuration, INLINE_ENTRY_COUNT, false);
			assertFalse(legacy.getFirst().paged(), "the legacy sort index must be inline");

			assertResortedByTheMigration(
				configuration, legacy, COMPOUND_LANGUAGE_RANK, null, swappedPairs(legacy.getFirst(), INLINE_ENTRY_COUNT)
			);
		}

		/**
		 * A localized compound of a label, the locale and the rank, paged: the label makes the index one whose order
		 * the migration checks, and its pages hold the pairs of locales in the order of the release. The pages must be
		 * checked by the comparator of the release, which ties the locales, or the migration refuses these legitimate
		 * pages as corruption.
		 */
		@Test
		@DisplayName("rewrites a localized compound whose Locale element a release ordered by its language tag")
		void shouldRewriteALocalizedCompoundWithALocaleElement() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("localizedLocaleCompound"));
			final List<LegacySortIndex> legacy = writeLanguageAndRankImage(configuration, ENTRY_COUNT, true);
			assertTrue(legacy.getFirst().paged(), "the legacy sort index must span several leaf pages");

			assertResortedByTheMigration(
				configuration, legacy, COMPOUND_LABEL_LANGUAGE_RANK, Locale.ENGLISH,
				swappedPairs(legacy.getFirst(), ENTRY_COUNT)
			);
		}

		/**
		 * Returns the order of the entries under the current comparator - every pair `2k + 1` and `2k + 2` the other
		 * way than the release ordered it - and asserts that the current engine builds exactly this order from the
		 * values of the legacy index.
		 *
		 * @param legacy     the legacy sort index
		 * @param entryCount how many entries there are
		 * @return the primary keys in the current order
		 */
		@Nonnull
		private static List<Integer> swappedPairs(@Nonnull LegacySortIndex legacy, int entryCount) {
			final List<Integer> currentOrder = new ArrayList<>(entryCount);
			for (int entry = 1; entry <= entryCount; entry += 2) {
				currentOrder.add(entry + 1);
				currentOrder.add(entry);
			}
			assertEquals(
				IntStream.rangeClosed(1, entryCount).boxed().toList(),
				List.copyOf(legacy.writer().getValuesByRecord().keySet()),
				"every entry must be in the legacy sort index"
			);
			assertEquals(
				currentOrder, IntStream.of(legacy.writer().toCurrentOwner().getSortedRecords()).boxed().toList(),
				"the current comparator must reverse every pair the release ordered by its rank"
			);
			return currentOrder;
		}
	}

	/**
	 * Cases of a sort index with a `DateTimeRange` element, which release 2026.2 compared at whole epoch seconds and
	 * with an open bound derived from the zone offset of the other one. Every entry carries a period open at its start
	 * that closes `entry` days after 09:00Z, the odd entries written at +01:00 and the even ones at +02:00: the release
	 * ordered every even entry before every odd one, the current index orders them by the moment they close - by
	 * primary key. No two periods tie, so even an index of the period alone is out of the current order.
	 */
	@Nested
	@DisplayName("A DateTimeRange ordered at whole seconds by the release that wrote it")
	class ReleasedRangeOrder {

		/**
		 * A sort-only period, inline: it loads as it was written, its positional record order disagreeing with its
		 * values, so its sort index answers the order of the release. The migration must re-sort the index.
		 */
		@Test
		@DisplayName("re-sorts an inline sort-only DateTimeRange index a release ordered at whole seconds")
		void shouldResortAnInlineSortOnlyRangeIndex() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("inlineRange"));
			final List<LegacySortIndex> legacy = writePeriodImage(configuration, INLINE_ENTRY_COUNT, ATTRIBUTE_PERIOD);
			assertFalse(legacy.getFirst().paged(), "the legacy sort index must be inline");

			assertResortedByTheMigration(
				configuration, legacy, ATTRIBUTE_PERIOD, null, closingOrder(legacy.getFirst(), INLINE_ENTRY_COUNT)
			);
		}

		/**
		 * A compound of the period and the rank, paged: its pages fail to load as they were written - the load re-sorts
		 * at whole seconds an index whose every value is a `DateTimeRange`, never a compound. The migration must
		 * re-sort the index.
		 */
		@Test
		@DisplayName("re-sorts a paged compound whose DateTimeRange element a release ordered at whole seconds")
		void shouldResortAPagedRangeCompound() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("pagedRangeCompound"));
			final List<LegacySortIndex> legacy = writePeriodImage(configuration, ENTRY_COUNT, COMPOUND_PERIOD_RANK);
			assertTrue(legacy.getFirst().paged(), "the legacy sort index must span several leaf pages");

			assertResortedByTheMigration(
				configuration, legacy, COMPOUND_PERIOD_RANK, null, closingOrder(legacy.getFirst(), ENTRY_COUNT)
			);
		}

		/**
		 * A compound of the period and a raw moment, paged: the moment makes the index one the migration rewrites, and
		 * its pages hold the periods in the order of the release. The pages must be checked by the comparator of the
		 * release, or the migration refuses these legitimate pages as corruption.
		 */
		@Test
		@DisplayName("rewrites a paged compound of a DateTimeRange and a raw moment as the release ordered it")
		void shouldRewriteAPagedRangeAndMomentCompound() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("pagedRangeMomentCompound"));
			final List<LegacySortIndex> legacy = writePeriodImage(configuration, ENTRY_COUNT, COMPOUND_PERIOD_MOMENT);
			assertTrue(legacy.getFirst().paged(), "the legacy sort index must span several leaf pages");

			assertResortedByTheMigration(
				configuration, legacy, COMPOUND_PERIOD_MOMENT, null, closingOrder(legacy.getFirst(), ENTRY_COUNT)
			);
		}

		/**
		 * Returns the order of the entries under the current comparator - by primary key, the order their periods
		 * close in - and asserts that the legacy index holds them in the order of the release, every even entry before
		 * every odd one, while the current engine builds the order of primary keys from the same values.
		 *
		 * @param legacy     the legacy sort index
		 * @param entryCount how many entries there are
		 * @return the primary keys in the current order
		 */
		@Nonnull
		private static List<Integer> closingOrder(@Nonnull LegacySortIndex legacy, int entryCount) {
			final List<Integer> releasedOrder = new ArrayList<>(entryCount);
			for (int entry = 2; entry <= entryCount; entry += 2) {
				releasedOrder.add(entry);
			}
			for (int entry = 1; entry <= entryCount; entry += 2) {
				releasedOrder.add(entry);
			}
			assertEquals(
				releasedOrder, IntStream.of(legacy.writer().toInlinePart().getSortedRecords()).boxed().toList(),
				"the release must have ordered every period closing at +02:00 first"
			);
			final List<Integer> currentOrder = IntStream.rangeClosed(1, entryCount).boxed().toList();
			assertEquals(
				currentOrder, IntStream.of(legacy.writer().toCurrentOwner().getSortedRecords()).boxed().toList(),
				"the current comparator must order the periods by the moment they close"
			);
			return currentOrder;
		}
	}

	/**
	 * Migrates the catalog holding a legacy compound of the entries through the load path and asserts that it loads,
	 * serves the order of the current comparator, holds no legacy page, and that a reload neither changes the order nor
	 * writes anything.
	 *
	 * @param configuration the configuration of the engine
	 * @param legacy        the legacy sort indexes written
	 * @param compound      the compound the entries are ordered by
	 * @param locale        the locale the entries are filtered and ordered in, `null` for a compound not localized
	 * @param currentOrder  the order of the entries under the current comparator
	 */
	private void assertResortedByTheMigration(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull List<LegacySortIndex> legacy,
		@Nonnull String compound,
		@Nullable Locale locale,
		@Nonnull List<Integer> currentOrder
	) {
		stamp(configuration, CATALOG, RELEASED_PROTOCOL, RELEASED_PROTOCOL);
		final Evita migrated = start(configuration);
		assertEquals(currentOrder, orderedEntries(migrated, compound, locale), "the migrated sort index");
		migrated.close();
		final CatalogBootstrap published = lastBootstrap(configuration, CATALOG);
		assertEquals(PersistenceService.STORAGE_PROTOCOL_VERSION, published.storageProtocolVersion());
		assertEquals(
			1, assertRewritten("after the migration", configuration, CATALOG, ENTITY_ENTRY, legacy),
			"the sort index of the entries must have been rewritten"
		);

		final Evita reloaded = start(configuration);
		assertEquals(currentOrder, orderedEntries(reloaded, compound, locale), "the reloaded sort index");
		reloaded.close();
		assertEquals(published, lastBootstrap(configuration, CATALOG), "a reload must publish nothing");
		assertRewritten("after a reload", configuration, CATALOG, ENTITY_ENTRY, legacy);
	}

	/**
	 * Characterization of an input the migration does not reach and the engine does not support.
	 */
	@Nested
	@DisplayName("A development-branch catalog of protocol 8 holding raw temporal sort values")
	class UnsupportedDevelopmentCatalog {

		/**
		 * Only a development build of 2026.3 can have written a catalog of protocol 8 whose sort index stores temporal
		 * values raw - directly, or through an archive of such a catalog, a point-in-time backup of which restores as
		 * protocol 8 as well. Development-branch catalogs and archives are not supported: no migration runs for a
		 * catalog of the current protocol, and nothing on the load path normalizes a raw value any more.
		 *
		 * This pins what such a catalog does, so that the policy is concrete rather than assumed:
		 *
		 * - it loads, and is not reported damaged;
		 * - its sort index serves the raw order - the two events of one moment ordered by their local time, the
		 *   +01:00 one first, where a normalized index ties them and orders them by primary key - while ordering the
		 *   fetched entities, which compares their normalized values, gives the normalized order;
		 * - the first write of a new value into that sort index fails: the index compares the normalized `Instant` of
		 *   the new value with the raw `OffsetDateTime` keys it holds, which a `ClassCastException` refuses, and the
		 *   write is rolled back;
		 * - nothing rewrites the stored parts: they hold the raw values after the engine is closed again.
		 */
		@Test
		@DisplayName("loads, serves the raw order, refuses a write into the sort index, and is never rewritten")
		void shouldReadRawTemporalSortValuesAsStoredWhenTheCatalogIsNotUpgraded() {
			final EvitaConfiguration configuration = configurationOf(allocatePaths("developmentCatalog"));
			final List<LegacySortIndex> legacy = writeLegacyImage(configuration, Shape.INLINE);
			assertEquals(
				PersistenceService.STORAGE_PROTOCOL_VERSION,
				lastBootstrap(configuration, CATALOG).storageProtocolVersion(),
				"the image must be published under the current protocol, which no migration starts from"
			);

			final Evita engine = start(configuration);
			assertEquals(CatalogState.ALIVE, engine.getCatalogState(CATALOG).orElseThrow());
			assertEquals(
				List.of(2, 1, 4, 3, 6, 5), orderedEvents(engine, DebugMode.PREFER_INDEX_SCAN, Shape.INLINE.eventCount),
				"the sort index serves the raw order"
			);
			assertEquals(
				IntStream.rangeClosed(1, Shape.INLINE.eventCount).boxed().toList(),
				orderedEvents(engine, DebugMode.PREFER_PREFETCHING, Shape.INLINE.eventCount),
				"ordering the fetched entities compares their normalized values"
			);
			final Exception failure = assertThrows(
				Exception.class,
				() -> engine.updateCatalog(
					CATALOG,
					session -> {
						upsertEvent(session, Shape.INLINE.eventCount + 1);
					}
				)
			);
			final ClassCastException cause = findCause(failure, ClassCastException.class);
			assertTrue(
				cause.getMessage().contains(OffsetDateTime.class.getName()) &&
					cause.getMessage().contains("java.time.Instant"),
				cause.getMessage()
			);
			assertEquals(
				List.of(2, 1, 4, 3, 6, 5), orderedEvents(engine, DebugMode.PREFER_INDEX_SCAN, Shape.INLINE.eventCount),
				"the failed write must have been rolled back"
			);
			engine.close();
			assertStillAsWritten("after the catalog was used", configuration, CATALOG, ENTITY_EVENT, legacy);
		}
	}

	/**
	 * Writes the catalog of events with the engine, takes it live, closes the engine, and replaces the sort index of
	 * the moments with the parts a release storing raw values wrote - published under the current storage protocol.
	 *
	 * @param configuration the configuration of the engine
	 * @param shape         the shape of the sort index
	 * @return the identities of the legacy sort indexes written
	 */
	@Nonnull
	private List<LegacySortIndex> writeLegacyImage(@Nonnull EvitaConfiguration configuration, @Nonnull Shape shape) {
		final Evita engine = start(configuration);
		engine.defineCatalog(CATALOG).updateViaNewSession(engine);
		engine.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_EVENT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_MOMENT, OffsetDateTime.class, thatIs -> thatIs.sortable().nullable())
					.updateVia(session);
				for (int event = 1; event <= shape.eventCount; event++) {
					upsertEvent(session, event);
				}
			}
		);
		engine.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		assertEventsInInstantOrder("as written", engine, shape);
		engine.close();
		final List<LegacySortIndex> legacy = writeLegacySortIndexes(
			configuration, CATALOG, ENTITY_EVENT, Set.of(ATTRIBUTE_MOMENT),
			Migration_2026_3_OwnerSortRewriteTest::momentOf
		);
		assertEquals(1, legacy.size(), "the events hold one sort index: " + legacy);
		assertEquals(shape == Shape.PAGED, legacy.getFirst().paged(), "the legacy sort index must be " + shape);
		assertStillAsWritten("as written", configuration, CATALOG, ENTITY_EVENT, legacy);
		return legacy;
	}

	/**
	 * Returns the moment of an event: events `2k + 1` and `2k + 2` share one moment, the odd one written at +02:00
	 * and the even one at +01:00.
	 *
	 * @param event the primary key of the event
	 * @return the moment
	 */
	@Nonnull
	private static OffsetDateTime momentOf(int event) {
		return NINE_AT_UTC.toInstant()
			.plusSeconds((event - 1) / 2)
			.atOffset(event % 2 == 1 ? ZoneOffset.ofHours(2) : ZoneOffset.ofHours(1));
	}

	/**
	 * Returns the label of an entry: entries `2k + 1` and `2k + 2` carry `ab` followed by `k`, the odd one with a
	 * zero-width space between the letters - two spellings the collator of the locale equates.
	 *
	 * @param entry the primary key of the entry
	 * @return the label
	 */
	@Nonnull
	private static String labelOf(int entry) {
		final String suffix = String.format("%04d", (entry - 1) / 2);
		return (entry % 2 == 1 ? "a\u200Bb" : "ab") + suffix;
	}

	/**
	 * Returns the label of an entry in the case of a pair straddling a page boundary: `ab` followed by the primary key,
	 * except that {@link #STRADDLING_ENTRY} carries a zero-width space between the letters and the entry after it the
	 * number of {@link #STRADDLING_ENTRY} - the only two labels of the index the collator equates.
	 *
	 * @param entry the primary key of the entry
	 * @return the label
	 */
	@Nonnull
	private static String straddlingLabelOf(int entry) {
		if (entry == STRADDLING_ENTRY) {
			return "a​b" + String.format("%04d", entry);
		}
		return "ab" + String.format("%04d", entry == STRADDLING_ENTRY + 1 ? STRADDLING_ENTRY : entry);
	}

	/**
	 * Returns the moment of an entry, one second after that of the previous entry, written at +02:00.
	 *
	 * @param entry the primary key of the entry
	 * @return the moment
	 */
	@Nonnull
	private static OffsetDateTime entryMomentOf(int entry) {
		return NINE_AT_UTC.toInstant().plusSeconds(entry - 1).atOffset(ZoneOffset.ofHours(2));
	}

	/**
	 * Writes a catalog of entries sorted by the localized compound of their label and their rank - the primary key -
	 * with the engine, takes it live, closes the engine, and replaces the sort index of the compound with the parts
	 * release 2026.2 wrote, ordered by its bare collator - published under the current storage protocol.
	 *
	 * @param configuration the configuration of the engine
	 * @param labelOfEntry  the label of an entry
	 * @return the identities of the legacy sort indexes written - one, paged
	 */
	@Nonnull
	private List<LegacySortIndex> writeLabelAndRankImage(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull IntFunction<String> labelOfEntry
	) {
		final Evita engine = start(configuration);
		engine.defineCatalog(CATALOG).updateViaNewSession(engine);
		engine.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_ENTRY)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.nullable())
					.withSortableAttributeCompound(
						COMPOUND_LABEL_RANK,
						AttributeElement.attributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC),
						AttributeElement.attributeElement(ATTRIBUTE_RANK, OrderDirection.ASC)
					)
					.updateVia(session);
				for (int entry = 1; entry <= ENTRY_COUNT; entry++) {
					session.createNewEntity(ENTITY_ENTRY, entry)
						.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, labelOfEntry.apply(entry))
						.setAttribute(ATTRIBUTE_RANK, entry)
						.upsertVia(session);
				}
			}
		);
		engine.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		engine.close();
		final List<LegacySortIndex> legacy = writeLegacySortIndexes(
			configuration, CATALOG, ENTITY_ENTRY, Set.of(COMPOUND_LABEL_RANK),
			entry -> new ComparableArray(new Serializable[]{labelOfEntry.apply(entry), entry})
		);
		assertEquals(1, legacy.size(), "the entries hold one sort index: " + legacy);
		assertTrue(legacy.getFirst().paged(), "the legacy sort index must span several leaf pages");
		assertStillAsWritten("as written", configuration, CATALOG, ENTITY_ENTRY, legacy);
		return legacy;
	}

	/**
	 * Returns the language of an entry: entries `2k + 1` and `2k + 2` carry `en` of a region of their own, the odd one
	 * with an ill-formed variant - which the language tag drops, so the two share the tag `en-AA`, `en-AB` and so on.
	 *
	 * @param entry the primary key of the entry
	 * @return the language
	 */
	@Nonnull
	private static Locale languageOf(int entry) {
		final int pair = (entry - 1) / 2;
		final String region = String.valueOf(new char[]{(char) ('A' + pair / 26), (char) ('A' + pair % 26)});
		return Locale.of("en", region, entry % 2 == 1 ? "ill!formed" : "");
	}

	/**
	 * Writes a catalog of entries sorted by the compound of their language and their rank - the primary key - or, with
	 * `withLabel`, by the localized compound of their label, their language and their rank, with the engine, takes it
	 * live, closes the engine, and replaces the sort index of the compound with the parts release 2026.2 wrote, ordered
	 * by the language tag alone - published under the current storage protocol. The label of entries `2k + 1` and
	 * `2k + 2` is `x` followed by `k`, so it ties within a pair and leaves the order of the pair to the language.
	 *
	 * @param configuration the configuration of the engine
	 * @param entryCount    how many entries to write
	 * @param withLabel     `true` for the localized compound with the label
	 * @return the identities of the legacy sort indexes written - one
	 */
	@Nonnull
	private List<LegacySortIndex> writeLanguageAndRankImage(
		@Nonnull EvitaConfiguration configuration,
		int entryCount,
		boolean withLabel
	) {
		final IntFunction<String> labelOfEntry = entry -> "x" + String.format("%04d", (entry - 1) / 2);
		final String compound = withLabel ? COMPOUND_LABEL_LANGUAGE_RANK : COMPOUND_LANGUAGE_RANK;
		final Evita engine = start(configuration);
		engine.defineCatalog(CATALOG).updateViaNewSession(engine);
		engine.updateCatalog(
			CATALOG,
			session -> {
				final EntitySchemaBuilder schema = session.defineEntitySchema(ENTITY_ENTRY)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_LANGUAGE, Locale.class, thatIs -> thatIs.nullable())
					.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.nullable());
				if (withLabel) {
					schema
						.withLocale(Locale.ENGLISH)
						.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable())
						.withSortableAttributeCompound(
							compound,
							AttributeElement.attributeElement(ATTRIBUTE_LABEL, OrderDirection.ASC),
							AttributeElement.attributeElement(ATTRIBUTE_LANGUAGE, OrderDirection.ASC),
							AttributeElement.attributeElement(ATTRIBUTE_RANK, OrderDirection.ASC)
						);
				} else {
					schema.withSortableAttributeCompound(
						compound,
						AttributeElement.attributeElement(ATTRIBUTE_LANGUAGE, OrderDirection.ASC),
						AttributeElement.attributeElement(ATTRIBUTE_RANK, OrderDirection.ASC)
					);
				}
				schema.updateVia(session);
				for (int entry = 1; entry <= entryCount; entry++) {
					final EntityBuilder builder = session.createNewEntity(ENTITY_ENTRY, entry)
						.setAttribute(ATTRIBUTE_LANGUAGE, languageOf(entry))
						.setAttribute(ATTRIBUTE_RANK, entry);
					if (withLabel) {
						builder.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, labelOfEntry.apply(entry));
					}
					builder.upsertVia(session);
				}
			}
		);
		engine.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		engine.close();
		// the release stored a `Locale` element as the `ComparableLocale` it wrapped it in
		final List<LegacySortIndex> legacy = writeLegacySortIndexes(
			configuration, CATALOG, ENTITY_ENTRY, Set.of(compound),
			entry -> new ComparableArray(
				withLabel ?
					new Serializable[]{labelOfEntry.apply(entry), new ComparableLocale(languageOf(entry)), entry} :
					new Serializable[]{new ComparableLocale(languageOf(entry)), entry}
			)
		);
		assertEquals(1, legacy.size(), "the entries hold one sort index: " + legacy);
		assertStillAsWritten("as written", configuration, CATALOG, ENTITY_ENTRY, legacy);
		return legacy;
	}

	/**
	 * Returns the period of an entry, open at its start: it closes `entry` days after 09:00Z of the first day, the odd
	 * entries written at +01:00 and the even ones at +02:00 - as validity periods spanning a daylight-saving change
	 * are.
	 *
	 * @param entry the primary key of the entry
	 * @return the period
	 */
	@Nonnull
	private static DateTimeRange periodOf(int entry) {
		return DateTimeRange.until(
			NINE_AT_UTC.toInstant().plusSeconds(86_400L * entry)
				.atOffset(entry % 2 == 1 ? ZoneOffset.ofHours(1) : ZoneOffset.ofHours(2))
		);
	}

	/**
	 * Writes a catalog of entries sorted by their sort-only period ({@link #ATTRIBUTE_PERIOD}), or by the compound of
	 * their period and their rank ({@link #COMPOUND_PERIOD_RANK}) or their moment ({@link #COMPOUND_PERIOD_MOMENT}),
	 * with the engine, takes it live, closes the engine, and replaces the sort index with the parts release 2026.2
	 * wrote, ordered at whole seconds - published under the current storage protocol.
	 *
	 * @param configuration the configuration of the engine
	 * @param entryCount    how many entries to write
	 * @param sortedBy      the attribute or compound the entries are sorted by
	 * @return the identities of the legacy sort indexes written - one
	 */
	@Nonnull
	private List<LegacySortIndex> writePeriodImage(
		@Nonnull EvitaConfiguration configuration,
		int entryCount,
		@Nonnull String sortedBy
	) {
		final boolean plain = ATTRIBUTE_PERIOD.equals(sortedBy);
		final boolean withMoment = COMPOUND_PERIOD_MOMENT.equals(sortedBy);
		final Evita engine = start(configuration);
		engine.defineCatalog(CATALOG).updateViaNewSession(engine);
		engine.updateCatalog(
			CATALOG,
			session -> {
				final EntitySchemaBuilder schema = session.defineEntitySchema(ENTITY_ENTRY)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.nullable())
					.withAttribute(ATTRIBUTE_MOMENT, OffsetDateTime.class, thatIs -> thatIs.nullable());
				if (plain) {
					schema.withAttribute(ATTRIBUTE_PERIOD, DateTimeRange.class, thatIs -> thatIs.sortable().nullable());
				} else {
					schema
						.withAttribute(ATTRIBUTE_PERIOD, DateTimeRange.class, thatIs -> thatIs.nullable())
						.withSortableAttributeCompound(
							sortedBy,
							AttributeElement.attributeElement(ATTRIBUTE_PERIOD, OrderDirection.ASC),
							AttributeElement.attributeElement(
								withMoment ? ATTRIBUTE_MOMENT : ATTRIBUTE_RANK, OrderDirection.ASC
							)
						);
				}
				schema.updateVia(session);
				for (int entry = 1; entry <= entryCount; entry++) {
					session.createNewEntity(ENTITY_ENTRY, entry)
						.setAttribute(ATTRIBUTE_PERIOD, periodOf(entry))
						.setAttribute(ATTRIBUTE_RANK, entry)
						.setAttribute(ATTRIBUTE_MOMENT, entryMomentOf(entry))
						.upsertVia(session);
				}
			}
		);
		engine.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		engine.close();
		// the release stored a `DateTimeRange` as it was written, and a moment raw
		final IntFunction<Serializable> rawValueOfEntry = plain ?
			Migration_2026_3_OwnerSortRewriteTest::periodOf :
			entry -> new ComparableArray(
				new Serializable[]{periodOf(entry), withMoment ? entryMomentOf(entry) : Integer.valueOf(entry)}
			);
		final List<LegacySortIndex> legacy = writeLegacySortIndexes(
			configuration, CATALOG, ENTITY_ENTRY, Set.of(sortedBy), rawValueOfEntry
		);
		assertEquals(1, legacy.size(), "the entries hold one sort index: " + legacy);
		assertStillAsWritten("as written", configuration, CATALOG, ENTITY_ENTRY, legacy);
		return legacy;
	}

	/**
	 * Lists the entries in the order of a compound of theirs, served by the sort index.
	 *
	 * @param engine   the engine
	 * @param compound the compound to order by
	 * @param locale   the locale of a localized compound, `null` for one not localized - its entries have no locale
	 * @return the primary keys in order
	 */
	@Nonnull
	private static List<Integer> orderedEntries(
		@Nonnull Evita engine,
		@Nonnull String compound,
		@Nullable Locale locale
	) {
		return engine.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> session
				.queryListOfEntityReferences(
					query(
						collection(ENTITY_ENTRY),
						locale == null ? null : filterBy(entityLocaleEquals(locale)),
						orderBy(attributeNatural(compound)),
						require(page(1, 10_000), debug(DebugMode.PREFER_INDEX_SCAN))
					)
				)
				.stream()
				.map(EntityReferenceContract::getPrimaryKey)
				.toList()
		);
	}

	/**
	 * Stores an event carrying its moment.
	 *
	 * @param session the session
	 * @param event   the primary key of the event
	 */
	private static void upsertEvent(@Nonnull EvitaSessionContract session, int event) {
		session.createNewEntity(ENTITY_EVENT, event)
			.setAttribute(ATTRIBUTE_MOMENT, momentOf(event))
			.upsertVia(session);
	}

	/**
	 * Asserts the sort index serves the events in instant order - every two events of one moment tied and ordered by
	 * primary key - and so does the ordering of the fetched entities.
	 *
	 * @param label  names the moment in failure messages
	 * @param engine the engine
	 * @param shape  the shape, which says how many events there are
	 */
	private static void assertEventsInInstantOrder(
		@Nonnull String label,
		@Nonnull Evita engine,
		@Nonnull Shape shape
	) {
		final List<Integer> byPrimaryKey = IntStream.rangeClosed(1, shape.eventCount).boxed().toList();
		assertEquals(
			byPrimaryKey, orderedEvents(engine, DebugMode.PREFER_INDEX_SCAN, shape.eventCount),
			label + " (sort index)"
		);
		assertEquals(
			byPrimaryKey, orderedEvents(engine, DebugMode.PREFER_PREFETCHING, shape.eventCount),
			label + " (prefetched entities)"
		);
	}

	/**
	 * Lists the events in the order of their moments, forcing the requested route. The events are filtered by their
	 * primary keys, because a query filtering nothing never prefetches its entities.
	 *
	 * @param engine     the engine
	 * @param route      {@link DebugMode#PREFER_INDEX_SCAN} or {@link DebugMode#PREFER_PREFETCHING}
	 * @param eventCount how many events there are
	 * @return the primary keys in order
	 */
	@Nonnull
	private static List<Integer> orderedEvents(@Nonnull Evita engine, @Nonnull DebugMode route, int eventCount) {
		return engine.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<Integer>>) session -> session
				.queryListOfEntityReferences(
					query(
						collection(ENTITY_EVENT),
						filterBy(entityPrimaryKeyInSet(IntStream.rangeClosed(1, eventCount).toArray())),
						orderBy(attributeNatural(ATTRIBUTE_MOMENT)),
						require(page(1, 10_000), debug(route))
					)
				)
				.stream()
				.map(EntityReferenceContract::getPrimaryKey)
				.toList()
		);
	}

	/**
	 * Returns the first throwable of type `type` in the cause chain of `throwable`, failing the test when there is
	 * none.
	 *
	 * @param throwable the throwable
	 * @param type      the type to find
	 * @param <T>       the type to find
	 * @return the throwable found
	 */
	@Nonnull
	private static <T extends Throwable> T findCause(@Nonnull Throwable throwable, @Nonnull Class<T> type) {
		Throwable current = throwable;
		for (int depth = 0; current != null && depth < 16; depth++) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return fail("No " + type.getSimpleName() + " in the cause chain of " + throwable, throwable);
	}

	/**
	 * Builds the configuration of an engine over the directories.
	 *
	 * @param paths the directories
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configurationOf(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Starts an engine and waits until it has loaded every catalog. The engine is closed after the test.
	 *
	 * @param configuration the configuration of the engine
	 * @return the engine
	 */
	@Nonnull
	private Evita start(@Nonnull EvitaConfiguration configuration) {
		if (this.evita != null && this.evita.isActive()) {
			fail("The engine of the test must be closed before another one is started.");
		}
		final Evita started = new Evita(configuration);
		this.evita = started;
		started.waitUntilFullyInitialized();
		return started;
	}

	/**
	 * Allocates fresh test paths, removed after the test.
	 *
	 * @param label names the directories
	 * @return the paths
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths("Migration_2026_3_OwnerSortRewrite_" + label);
		this.allocatedPaths.add(paths);
		return paths;
	}

	/**
	 * The failure the publication case injects.
	 */
	private static final class InjectedFailure extends RuntimeException {
		@Serial private static final long serialVersionUID = 4419208134752336011L;

		InjectedFailure() {
			super("Injected failure of the publication.");
		}
	}

}
