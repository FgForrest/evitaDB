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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a standalone unique tree treats a temporal value as its instant through writes, removals and a reload.
 *
 * The catalog's `GlobalUniqueIndex` (a `uniqueGlobally` / `uniqueGloballyWithinLocale` attribute) and a collection's
 * `OwnerUniqueIndex` (a localized attribute unique across locales) key an `OffsetDateTime` by its millisecond
 * `Instant`. The same instant written at another offset is therefore the same unique value: it is refused for a
 * second entity - in another collection, too, for a catalog attribute - it releases and re-claims as one value, and
 * it is found by any offset after the catalog has been closed and loaded back from disk. Plain lookups are covered by
 * {@link TemporalUniqueAttributeLookupFunctionalTest}.
 *
 * ## The fixture
 *
 * Entity `i` of {@link #EVENT} holds {@link #slot(int) slot i} (written at `+01:00`) in {@link #GLOBAL_SLOT}
 * (catalog, `uniqueGlobally`) and, in English, in {@link #GLOBAL_LOCAL_SLOT} (catalog, localized,
 * `uniqueGloballyWithinLocale`). Entity `i` of {@link #OWNER} holds slot `i` in English in {@link #LOCALIZED_SLOT}
 * (entity, localized, `unique`). {@link #OTHER_EVENT} declares both catalog attributes and starts empty.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Temporal unique attribute keyed by its instant")
@Tag(INDEXING)
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class TemporalUniqueAttributeInstantKeyTest implements EvitaTestSupport {
	private static final String EVENT = "event";
	private static final String OTHER_EVENT = "otherEvent";
	private static final String OWNER = "ownerSlot";
	private static final String GLOBAL_SLOT = "globalSlot";
	private static final String GLOBAL_LOCAL_SLOT = "globalLocalSlot";
	private static final String LOCALIZED_SLOT = "localizedSlot";
	private static final ZoneOffset PLUS_ONE = ZoneOffset.ofHours(1);
	private static final ZoneOffset MINUS_FIVE_THIRTY = ZoneOffset.ofHoursMinutes(-5, -30);
	private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, PLUS_ONE);
	/**
	 * More values than one 256-entry leaf holds, so both standalone unique trees persist in the `PAGED` shape.
	 */
	private static final int PAGED_COUNT = 300;

	private TestPaths paths;
	private Evita evita;

	/**
	 * Returns the value entity `index` holds, written at `+01:00`.
	 *
	 * @param index the entity primary key
	 * @return the value
	 */
	@Nonnull
	private static OffsetDateTime slot(int index) {
		return BASE.plusMinutes(index);
	}

	/**
	 * Returns the instant of {@link #slot(int)} written at another offset.
	 *
	 * @param index  the entity primary key
	 * @param offset the offset to write the instant with
	 * @return the same instant at `offset`
	 */
	@Nonnull
	private static OffsetDateTime slot(int index, @Nonnull ZoneOffset offset) {
		return slot(index).withOffsetSameInstant(offset);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("TemporalUniqueAttributeInstantKey");
		this.evita = new Evita(configuration());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Builds the fixture described on the class with `count` entities per filled collection in warm-up, then makes
	 * the catalog ALIVE, which flushes it to disk.
	 *
	 * @param count the number of entities in {@link #EVENT} and in {@link #OWNER}
	 */
	private void createFixture(int count) {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(GLOBAL_SLOT, OffsetDateTime.class, thatIs -> thatIs.uniqueGlobally().nullable())
			.withAttribute(
				GLOBAL_LOCAL_SLOT, OffsetDateTime.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable()
			)
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(EVENT)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withGlobalAttribute(GLOBAL_SLOT).withGlobalAttribute(GLOBAL_LOCAL_SLOT)
					.updateVia(session);
				session.defineEntitySchema(OTHER_EVENT)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withGlobalAttribute(GLOBAL_SLOT).withGlobalAttribute(GLOBAL_LOCAL_SLOT)
					.updateVia(session);
				session.defineEntitySchema(OWNER)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withAttribute(
						LOCALIZED_SLOT, OffsetDateTime.class, thatIs -> thatIs.localized().unique().nullable()
					)
					.updateVia(session);
				for (int i = 1; i <= count; i++) {
					session.createNewEntity(EVENT, i)
						.setAttribute(GLOBAL_SLOT, slot(i))
						.setAttribute(GLOBAL_LOCAL_SLOT, Locale.ENGLISH, slot(i))
						.upsertVia(session);
					session.createNewEntity(OWNER, i)
						.setAttribute(LOCALIZED_SLOT, Locale.ENGLISH, slot(i))
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Closes the engine and opens it again on the same data, so every index is loaded back from disk.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Builds the per-test configuration over the test path triplet, which stays stable across restarts.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Runs `filter` (in `locale` when given) within `collection`, or across the catalog when `collection` is `null`,
	 * and renders the result as `type:pk` strings.
	 *
	 * @param collection the queried collection, or `null` for a catalog-wide query
	 * @param locale     the query locale, or `null`
	 * @param filter     the equality constraint
	 * @return the found entities as `type:pk`
	 */
	@Nonnull
	private List<String> find(@Nullable String collection, @Nullable Locale locale, @Nonnull FilterConstraint filter) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
					collection == null
						? query(filterBy(constraint))
						: query(collection(collection), filterBy(constraint))
				).stream().map(TemporalUniqueAttributeInstantKeyTest::describe).toList();
			}
		);
	}

	/**
	 * Renders a reference as `type:pk` so a mismatch reads directly in the assertion message.
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Asserts that slot `index`, written at `offset`, finds exactly entity `index` through every standalone tree:
	 * both catalog attributes within {@link #EVENT} and catalog-wide, and {@link #LOCALIZED_SLOT} within {@link #OWNER}.
	 *
	 * @param index  the entity primary key (and slot)
	 * @param offset the offset the lookup writes the instant with
	 */
	private void assertEveryTreeFinds(int index, @Nonnull ZoneOffset offset) {
		final OffsetDateTime value = slot(index, offset);
		final List<String> event = List.of(EVENT + ":" + index);
		assertEquals(event, find(EVENT, null, attributeEquals(GLOBAL_SLOT, value)), "globalSlot " + value);
		assertEquals(event, find(null, null, attributeEquals(GLOBAL_SLOT, value)), "catalog-wide globalSlot " + value);
		assertEquals(
			event, find(EVENT, Locale.ENGLISH, attributeEquals(GLOBAL_LOCAL_SLOT, value)), "globalLocalSlot " + value
		);
		assertEquals(
			event, find(null, Locale.ENGLISH, attributeEquals(GLOBAL_LOCAL_SLOT, value)),
			"catalog-wide globalLocalSlot " + value
		);
		assertEquals(
			List.of(OWNER + ":" + index), find(OWNER, Locale.ENGLISH, attributeEquals(LOCALIZED_SLOT, value)),
			"localizedSlot " + value
		);
	}

	/**
	 * Asserts that slot `index` re-written at `offset` is refused for every entity it does not belong to: another
	 * collection for both catalog attributes, and another entity - in either locale - for {@link #LOCALIZED_SLOT}.
	 * A refused write changes nothing, so the assertions can run one after another.
	 *
	 * @param index  the slot already held by entity `index`
	 * @param offset the offset the competing write uses
	 */
	private void assertEveryTreeRefusesSecondOwner(int index, @Nonnull ZoneOffset offset) {
		final OffsetDateTime value = slot(index, offset);
		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(OTHER_EVENT, 1).setAttribute(GLOBAL_SLOT, value).upsertVia(session);
				}
			)
		);
		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(OTHER_EVENT, 1)
						.setAttribute(GLOBAL_LOCAL_SLOT, Locale.ENGLISH, value).upsertVia(session);
				}
			)
		);
		for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
			assertThrows(
				UniqueValueViolationException.class,
				() -> this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(OWNER, 10_000).setAttribute(LOCALIZED_SLOT, locale, value)
							.upsertVia(session);
					}
				)
			);
		}
	}

	/**
	 * Returns the catalog's live scope `GlobalUniqueIndex` of `attributeName`, for the attribute's English locale when
	 * it is unique within locale.
	 *
	 * @param attributeName the catalog attribute
	 * @return the index
	 */
	@Nonnull
	private GlobalUniqueIndex globalUniqueIndex(@Nonnull String attributeName) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final GlobalUniqueIndex index = catalog.getCatalogIndex(Scope.LIVE).getGlobalUniqueIndex(
			catalog.getSchema().getAttribute(attributeName).orElseThrow(),
			GLOBAL_LOCAL_SLOT.equals(attributeName) ? Locale.ENGLISH : null
		);
		assertNotNull(index, "The global unique index of `" + attributeName + "` must exist!");
		return index;
	}

	/**
	 * Returns the `OwnerUniqueIndex` of {@link #LOCALIZED_SLOT} in the global entity index of {@link #OWNER}.
	 *
	 * @return the index
	 */
	@Nonnull
	private OwnerUniqueIndex ownerUniqueIndex() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(OWNER).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "The global entity index must exist!");
		return (OwnerUniqueIndex) globalIndex.getUniqueIndex(
			null, collection.getSchema().getAttribute(LOCALIZED_SLOT).orElseThrow(), Locale.ENGLISH
		);
	}

	@Nested
	@DisplayName("Uniqueness")
	class Uniqueness {

		@Test
		@DisplayName("Should refuse the same instant at another offset for a second entity")
		void shouldRefuseTheSameInstantAtAnotherOffsetForSecondEntity() {
			createFixture(1);

			assertEveryTreeRefusesSecondOwner(1, ZoneOffset.UTC);
			assertEveryTreeRefusesSecondOwner(1, MINUS_FIVE_THIRTY);
			// the refused attempts took nothing from the owner
			assertEveryTreeFinds(1, PLUS_ONE);
		}

		@Test
		@DisplayName("Should accept another instant, and the same instant in another locale where uniqueness is per locale")
		void shouldAcceptAnotherInstantAndAnotherLocaleWhereUniqueWithinLocale() {
			createFixture(1);

			TemporalUniqueAttributeInstantKeyTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(OTHER_EVENT, 1)
						.setAttribute(GLOBAL_SLOT, slot(2, ZoneOffset.UTC))
						.setAttribute(GLOBAL_LOCAL_SLOT, Locale.GERMAN, slot(1, ZoneOffset.UTC))
						.upsertVia(session);
					session.createNewEntity(OWNER, 2)
						.setAttribute(LOCALIZED_SLOT, Locale.GERMAN, slot(2, ZoneOffset.UTC))
						.upsertVia(session);
				}
			);

			assertEquals(List.of(OTHER_EVENT + ":1"), find(null, null, attributeEquals(GLOBAL_SLOT, slot(2))));
			assertEquals(
				List.of(OTHER_EVENT + ":1"),
				find(OTHER_EVENT, Locale.GERMAN, attributeEquals(GLOBAL_LOCAL_SLOT, slot(1, MINUS_FIVE_THIRTY)))
			);
			assertEquals(
				List.of(OWNER + ":2"), find(OWNER, Locale.GERMAN, attributeEquals(LOCALIZED_SLOT, slot(2)))
			);
		}
	}

	@Nested
	@DisplayName("Update and removal")
	class UpdateAndRemoval {

		@Test
		@DisplayName("Should keep the value when it is rewritten at another offset, and release it for another entity")
		void shouldKeepValueRewrittenAtAnotherOffsetAndReleaseIt() {
			createFixture(1);

			// the same instant at another offset replaces the value as one unique value
			TemporalUniqueAttributeInstantKeyTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(EVENT, 1, entityFetchAllContent()).orElseThrow().openForWrite()
						.setAttribute(GLOBAL_SLOT, slot(1, ZoneOffset.UTC))
						.setAttribute(GLOBAL_LOCAL_SLOT, Locale.ENGLISH, slot(1, ZoneOffset.UTC))
						.upsertVia(session);
					session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
						.setAttribute(LOCALIZED_SLOT, Locale.ENGLISH, slot(1, ZoneOffset.UTC))
						.upsertVia(session);
				}
			);
			assertEveryTreeFinds(1, PLUS_ONE);
			assertEveryTreeRefusesSecondOwner(1, MINUS_FIVE_THIRTY);

			// moving entity 1 away from the instant and removing its owner value frees it for everyone else
			TemporalUniqueAttributeInstantKeyTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(EVENT, 1, entityFetchAllContent()).orElseThrow().openForWrite()
						.setAttribute(GLOBAL_SLOT, slot(500))
						.removeAttribute(GLOBAL_LOCAL_SLOT, Locale.ENGLISH)
						.upsertVia(session);
					session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
						.removeAttribute(LOCALIZED_SLOT, Locale.ENGLISH)
						.upsertVia(session);
				}
			);
			assertEquals(List.of(), find(null, null, attributeEquals(GLOBAL_SLOT, slot(1))));
			assertEquals(List.of(), find(null, Locale.ENGLISH, attributeEquals(GLOBAL_LOCAL_SLOT, slot(1))));
			assertEquals(List.of(), find(OWNER, Locale.ENGLISH, attributeEquals(LOCALIZED_SLOT, slot(1))));

			TemporalUniqueAttributeInstantKeyTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(OTHER_EVENT, 1)
						.setAttribute(GLOBAL_SLOT, slot(1, MINUS_FIVE_THIRTY))
						.setAttribute(GLOBAL_LOCAL_SLOT, Locale.ENGLISH, slot(1, MINUS_FIVE_THIRTY))
						.upsertVia(session);
					session.createNewEntity(OWNER, 2)
						.setAttribute(LOCALIZED_SLOT, Locale.GERMAN, slot(1, MINUS_FIVE_THIRTY))
						.upsertVia(session);
				}
			);
			final List<String> otherEvent = List.of(OTHER_EVENT + ":1");
			assertEquals(otherEvent, find(null, null, attributeEquals(GLOBAL_SLOT, slot(1))));
			assertEquals(otherEvent, find(null, Locale.ENGLISH, attributeEquals(GLOBAL_LOCAL_SLOT, slot(1))));
			assertEquals(
				List.of(OWNER + ":2"), find(OWNER, Locale.GERMAN, attributeEquals(LOCALIZED_SLOT, slot(1)))
			);
		}
	}

	@Nested
	@DisplayName("Reload")
	class Reload {

		@Test
		@DisplayName("Should find a value by any offset and keep refusing it after the catalog is loaded back")
		void shouldFindAndRefuseAfterReload() {
			createFixture(3);
			restart();

			for (int i = 1; i <= 3; i++) {
				assertEveryTreeFinds(i, ZoneOffset.UTC);
				assertEveryTreeFinds(i, MINUS_FIVE_THIRTY);
			}
			assertEveryTreeRefusesSecondOwner(2, ZoneOffset.UTC);
		}

		@Test
		@DisplayName("Should find every value by any offset after a paged index is loaded back")
		void shouldFindEveryValueAfterPagedReload() {
			createFixture(PAGED_COUNT);
			assertTrue(globalUniqueIndex(GLOBAL_SLOT).isPaged(), "The global unique index should be PAGED!");
			assertTrue(globalUniqueIndex(GLOBAL_LOCAL_SLOT).isPaged(), "The locale unique index should be PAGED!");
			assertTrue(ownerUniqueIndex().isPaged(), "The owner unique index should be PAGED!");

			restart();

			assertTrue(globalUniqueIndex(GLOBAL_SLOT).isPaged(), "The reloaded global unique index should be PAGED!");
			assertTrue(ownerUniqueIndex().isPaged(), "The reloaded owner unique index should be PAGED!");
			for (int i = 1; i <= PAGED_COUNT; i++) {
				assertEveryTreeFinds(i, ZoneOffset.UTC);
			}
			assertEveryTreeRefusesSecondOwner(PAGED_COUNT, MINUS_FIVE_THIRTY);

			// a reloaded paged tree takes a write and survives another reload
			TemporalUniqueAttributeInstantKeyTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(OWNER, 7, entityFetchAllContent()).orElseThrow().openForWrite()
						.setAttribute(LOCALIZED_SLOT, Locale.ENGLISH, slot(PAGED_COUNT + 7, ZoneOffset.UTC))
						.upsertVia(session);
				}
			);
			restart();
			assertEquals(
				List.of(OWNER + ":7"),
				find(OWNER, Locale.ENGLISH, attributeEquals(LOCALIZED_SLOT, slot(PAGED_COUNT + 7)))
			);
			assertEquals(List.of(), find(OWNER, Locale.ENGLISH, attributeEquals(LOCALIZED_SLOT, slot(7))));
		}
	}

}
