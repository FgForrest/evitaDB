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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that an equality lookup finds an entity by a temporal attribute whatever unique structure holds the value.
 *
 * A non-localized `unique` attribute keeps its values in the shared filter tree, keyed by the filter normalizer's
 * output (an `Instant` for `OffsetDateTime` and `LocalDateTime`). A `uniqueGlobally` / `uniqueGloballyWithinLocale`
 * catalog attribute lives in the catalog's standalone unique tree, and a localized attribute unique across locales in
 * the collection's standalone owner tree. A standalone unique tree stores the value as written, so the query
 * translators must probe it with that raw value, while the shared filter tree normalizes the probe itself; each of
 * these lookups must find the entity holding the value it was written with.
 *
 * A standalone unique tree compares values by their natural order, so an `OffsetDateTime` keeps its offset: the same
 * instant written with a different offset is a distinct unique value, and a lookup finds exactly the entity written
 * with the offset it asks for.
 *
 * ## The fixture
 *
 * | collection | attribute | declared as | type | entity 1 holds |
 * |---|---|---|---|---|
 * | `globalSlot` | `globalSlot` | catalog, `uniqueGlobally` | `OffsetDateTime` | {@link #GLOBAL_SLOT} |
 * | `globalSlotUtc` | `globalSlot` | catalog, `uniqueGlobally` | `OffsetDateTime` | {@link #GLOBAL_SLOT} in UTC |
 * | `globalLocalSlot` | `globalLocalSlot` | catalog, localized, `uniqueGloballyWithinLocale` | `OffsetDateTime` | {@link #GLOBAL_LOCAL_SLOT} (en) |
 * | `globalLocalDate` | `globalLocalDate` | catalog, `uniqueGlobally` | `LocalDateTime` | {@link #GLOBAL_LOCAL_DATE} |
 * | `ownerSlot` | `localizedSlot` | entity, localized, `unique` | `OffsetDateTime` | {@link #LOCALIZED_SLOT} (en) |
 * | `ownerSlot` | `localizedLocalDate` | entity, localized, `unique` | `LocalDateTime` | {@link #LOCALIZED_LOCAL_DATE} (en) |
 * | `ownerSlot` | `foldedSlot` | entity, `unique` | `OffsetDateTime` | {@link #FOLDED_SLOT} |
 * | `globalCode` | `globalCode` | catalog, `uniqueGlobally` | `String` | `code-1` |
 *
 * Entity 2 of `ownerSlot` holds {@link #LOCALIZED_SLOT} (en) at the same instant as entity 1, but in UTC. The
 * same instant is kept in another collection for `globalSlot`, because within one collection a globally unique
 * attribute is also unique in the collection's filter tree, which compares instants and rejects the second offset.
 * `foldedSlot` and `globalCode` are controls: a folded temporal unique attribute and a standalone non-temporal one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Equality lookup of a temporal unique attribute")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class TemporalUniqueAttributeLookupFunctionalTest {
	private static final String TEMPORAL_UNIQUE = "temporalUniqueLookup";
	private static final String GLOBAL_SLOT_ENTITY = "globalSlot";
	private static final String GLOBAL_SLOT_UTC_ENTITY = "globalSlotUtc";
	private static final String GLOBAL_LOCAL_SLOT_ENTITY = "globalLocalSlot";
	private static final String GLOBAL_LOCAL_DATE_ENTITY = "globalLocalDate";
	private static final String GLOBAL_CODE_ENTITY = "globalCode";
	private static final String OWNER_ENTITY = "ownerSlot";
	private static final String GLOBAL_SLOT = "globalSlot";
	private static final String GLOBAL_LOCAL_SLOT = "globalLocalSlot";
	private static final String GLOBAL_LOCAL_DATE = "globalLocalDate";
	private static final String GLOBAL_CODE = "globalCode";
	private static final String LOCALIZED_SLOT = "localizedSlot";
	private static final String LOCALIZED_LOCAL_DATE = "localizedLocalDate";
	private static final String FOLDED_SLOT = "foldedSlot";
	private static final ZoneOffset PLUS_ONE = ZoneOffset.ofHours(1);
	private static final OffsetDateTime GLOBAL_SLOT_VALUE = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, PLUS_ONE);
	private static final OffsetDateTime GLOBAL_SLOT_UTC_VALUE = GLOBAL_SLOT_VALUE.withOffsetSameInstant(ZoneOffset.UTC);
	private static final OffsetDateTime GLOBAL_LOCAL_SLOT_VALUE = OffsetDateTime.of(2026, 1, 2, 10, 0, 0, 0, PLUS_ONE);
	private static final LocalDateTime GLOBAL_LOCAL_DATE_VALUE = LocalDateTime.of(2026, 1, 3, 10, 0, 0);
	private static final OffsetDateTime LOCALIZED_SLOT_VALUE = OffsetDateTime.of(2026, 1, 4, 10, 0, 0, 0, PLUS_ONE);
	private static final OffsetDateTime LOCALIZED_SLOT_UTC_VALUE =
		LOCALIZED_SLOT_VALUE.withOffsetSameInstant(ZoneOffset.UTC);
	private static final LocalDateTime LOCALIZED_LOCAL_DATE_VALUE = LocalDateTime.of(2026, 1, 5, 10, 0, 0);
	private static final OffsetDateTime FOLDED_SLOT_VALUE = OffsetDateTime.of(2026, 1, 6, 10, 0, 0, 0, PLUS_ONE);
	private static final String GLOBAL_CODE_VALUE = "code-1";

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = TEMPORAL_UNIQUE, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(GLOBAL_SLOT, OffsetDateTime.class, thatIs -> thatIs.uniqueGlobally())
			.withAttribute(
				GLOBAL_LOCAL_SLOT, OffsetDateTime.class, thatIs -> thatIs.localized().uniqueGloballyWithinLocale()
			)
			.withAttribute(GLOBAL_LOCAL_DATE, LocalDateTime.class, thatIs -> thatIs.uniqueGlobally())
			.withAttribute(GLOBAL_CODE, String.class, thatIs -> thatIs.uniqueGlobally())
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(GLOBAL_SLOT_ENTITY)
					.withoutGeneratedPrimaryKey().withGlobalAttribute(GLOBAL_SLOT).updateVia(session);
				session.defineEntitySchema(GLOBAL_SLOT_UTC_ENTITY)
					.withoutGeneratedPrimaryKey().withGlobalAttribute(GLOBAL_SLOT).updateVia(session);
				session.defineEntitySchema(GLOBAL_LOCAL_SLOT_ENTITY)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH).withGlobalAttribute(GLOBAL_LOCAL_SLOT)
					.updateVia(session);
				session.defineEntitySchema(GLOBAL_LOCAL_DATE_ENTITY)
					.withoutGeneratedPrimaryKey().withGlobalAttribute(GLOBAL_LOCAL_DATE).updateVia(session);
				session.defineEntitySchema(GLOBAL_CODE_ENTITY)
					.withoutGeneratedPrimaryKey().withGlobalAttribute(GLOBAL_CODE).updateVia(session);
				session.defineEntitySchema(OWNER_ENTITY)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(LOCALIZED_SLOT, OffsetDateTime.class, thatIs -> thatIs.localized().unique().nullable())
					.withAttribute(
						LOCALIZED_LOCAL_DATE, LocalDateTime.class, thatIs -> thatIs.localized().unique().nullable()
					)
					.withAttribute(FOLDED_SLOT, OffsetDateTime.class, thatIs -> thatIs.unique().nullable())
					.updateVia(session);

				session.createNewEntity(GLOBAL_SLOT_ENTITY, 1)
					.setAttribute(GLOBAL_SLOT, GLOBAL_SLOT_VALUE).upsertVia(session);
				session.createNewEntity(GLOBAL_SLOT_UTC_ENTITY, 1)
					.setAttribute(GLOBAL_SLOT, GLOBAL_SLOT_UTC_VALUE).upsertVia(session);
				session.createNewEntity(GLOBAL_LOCAL_SLOT_ENTITY, 1)
					.setAttribute(GLOBAL_LOCAL_SLOT, Locale.ENGLISH, GLOBAL_LOCAL_SLOT_VALUE).upsertVia(session);
				session.createNewEntity(GLOBAL_LOCAL_DATE_ENTITY, 1)
					.setAttribute(GLOBAL_LOCAL_DATE, GLOBAL_LOCAL_DATE_VALUE).upsertVia(session);
				session.createNewEntity(GLOBAL_CODE_ENTITY, 1)
					.setAttribute(GLOBAL_CODE, GLOBAL_CODE_VALUE).upsertVia(session);
				session.createNewEntity(OWNER_ENTITY, 1)
					.setAttribute(LOCALIZED_SLOT, Locale.ENGLISH, LOCALIZED_SLOT_VALUE)
					.setAttribute(LOCALIZED_LOCAL_DATE, Locale.ENGLISH, LOCALIZED_LOCAL_DATE_VALUE)
					.setAttribute(FOLDED_SLOT, FOLDED_SLOT_VALUE)
					.upsertVia(session);
				session.createNewEntity(OWNER_ENTITY, 2)
					.setAttribute(LOCALIZED_SLOT, Locale.ENGLISH, LOCALIZED_SLOT_UTC_VALUE)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Asserts that the query, run within `collection` (or across the catalog when `null`) and optionally in `locale`,
	 * returns exactly entity `expectedPk` of `expectedType`.
	 *
	 * @param session      the read session
	 * @param collection   the queried collection, or `null` for a catalog-wide query
	 * @param locale       the query locale, or `null` for none
	 * @param filter       the equality constraint under test
	 * @param expectedType the collection the entity is expected to belong to
	 * @param expectedPk   the primary key of the only entity expected
	 */
	private static void assertFindsEntity(
		@Nonnull EvitaSessionContract session,
		@Nullable String collection,
		@Nullable Locale locale,
		@Nonnull FilterConstraint filter,
		@Nonnull String expectedType,
		int expectedPk
	) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		final List<String> found = session.queryListOfEntityReferences(
			collection == null
				? Query.query(filterBy(constraint))
				: Query.query(collection(collection), filterBy(constraint))
		).stream().map(TemporalUniqueAttributeLookupFunctionalTest::describe).toList();
		assertEquals(
			List.of(expectedType + ":" + expectedPk), found,
			"Lookup by " + filter + " did not find exactly entity " + expectedPk + "!"
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
	 * Runs `attributeEquals` and `attributeInSet` for `value` and expects entity 1 - see
	 * {@link #assertBothEqualityConstraintsFind(Evita, String, Locale, String, Serializable, boolean, int)}.
	 */
	private static void assertBothEqualityConstraintsFind(
		@Nonnull Evita evita,
		@Nonnull String collection,
		@Nullable Locale locale,
		@Nonnull String attributeName,
		@Nonnull Serializable value,
		boolean catalogWide
	) {
		assertBothEqualityConstraintsFind(evita, collection, locale, attributeName, value, catalogWide, 1);
	}

	/**
	 * Runs `attributeEquals` and `attributeInSet` for `value` within the collection and, where `catalogWide` is set,
	 * across the whole catalog as well, expecting exactly entity `expectedPk`. Every lookup is reported on its own, so
	 * one failing route does not hide the outcome of the others.
	 */
	private static void assertBothEqualityConstraintsFind(
		@Nonnull Evita evita,
		@Nonnull String collection,
		@Nullable Locale locale,
		@Nonnull String attributeName,
		@Nonnull Serializable value,
		boolean catalogWide,
		int expectedPk
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<Executable> lookups = new ArrayList<>(4);
				lookups.add(() -> assertFindsEntity(
					session, collection, locale, attributeEquals(attributeName, value), collection, expectedPk
				));
				lookups.add(() -> assertFindsEntity(
					session, collection, locale, attributeInSet(attributeName, value), collection, expectedPk
				));
				if (catalogWide) {
					lookups.add(() -> assertFindsEntity(
						session, null, locale, attributeEquals(attributeName, value), collection, expectedPk
					));
					lookups.add(() -> assertFindsEntity(
						session, null, locale, attributeInSet(attributeName, value), collection, expectedPk
					));
				}
				assertAll(lookups);
				return null;
			}
		);
	}

	/**
	 * Catalog attributes held in the catalog's standalone unique tree.
	 */
	@DisplayName("Globally unique attribute")
	@Nested
	class GloballyUniqueAttribute {

		@DisplayName("Should find the entity by an OffsetDateTime value")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByOffsetDateTime(Evita evita) {
			assertBothEqualityConstraintsFind(evita, GLOBAL_SLOT_ENTITY, null, GLOBAL_SLOT, GLOBAL_SLOT_VALUE, true);
		}

		@DisplayName("Should find the entity written with the queried offset of an instant held in two collections")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByOffsetDateTimeInAnotherOffset(Evita evita) {
			assertBothEqualityConstraintsFind(
				evita, GLOBAL_SLOT_UTC_ENTITY, null, GLOBAL_SLOT, GLOBAL_SLOT_UTC_VALUE, true
			);
		}

		@DisplayName("Should find the entity by a localized OffsetDateTime value unique within locale")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByLocalizedOffsetDateTimeUniqueWithinLocale(Evita evita) {
			assertBothEqualityConstraintsFind(
				evita, GLOBAL_LOCAL_SLOT_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_SLOT, GLOBAL_LOCAL_SLOT_VALUE, true
			);
		}

		@DisplayName("Should find the entity by a LocalDateTime value")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByLocalDateTime(Evita evita) {
			assertBothEqualityConstraintsFind(
				evita, GLOBAL_LOCAL_DATE_ENTITY, null, GLOBAL_LOCAL_DATE, GLOBAL_LOCAL_DATE_VALUE, true
			);
		}

		@DisplayName("Should find the entity by a String value")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByString(Evita evita) {
			assertBothEqualityConstraintsFind(evita, GLOBAL_CODE_ENTITY, null, GLOBAL_CODE, GLOBAL_CODE_VALUE, true);
		}
	}

	/**
	 * Entity attributes, unique within their collection.
	 */
	@DisplayName("Attribute unique within the collection")
	@Nested
	class CollectionUniqueAttribute {

		@DisplayName("Should find the entity by a localized OffsetDateTime value unique across locales")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByLocalizedOffsetDateTime(Evita evita) {
			assertBothEqualityConstraintsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_SLOT, LOCALIZED_SLOT_VALUE, false
			);
		}

		@DisplayName("Should find the entity written with the queried offset of a shared localized instant")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByLocalizedOffsetDateTimeInAnotherOffset(Evita evita) {
			assertBothEqualityConstraintsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_SLOT, LOCALIZED_SLOT_UTC_VALUE, false, 2
			);
		}

		@DisplayName("Should find the entity by a localized LocalDateTime value unique across locales")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByLocalizedLocalDateTime(Evita evita) {
			assertBothEqualityConstraintsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_LOCAL_DATE, LOCALIZED_LOCAL_DATE_VALUE, false
			);
		}

		@DisplayName("Should find the entity by a non-localized OffsetDateTime value")
		@UseDataSet(TEMPORAL_UNIQUE)
		@Test
		void shouldFindEntityByNonLocalizedOffsetDateTime(Evita evita) {
			assertBothEqualityConstraintsFind(evita, OWNER_ENTITY, null, FOLDED_SLOT, FOLDED_SLOT_VALUE, false);
		}
	}

}
