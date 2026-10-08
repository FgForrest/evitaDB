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
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a lookup by a globally unique catalog attribute in a query targeting one collection matches only entities
 * of that collection.
 *
 * The value is resolved in the unique index of the catalog, which spans every collection. Its answer is an entity
 * reference that may name another collection, and an entity of another collection is no match in a query that
 * targets one: its primary key would be read as a key of the queried collection, returning a key with no entity
 * behind it, or worse, an unrelated entity of the queried collection that happens to share it.
 *
 * ## The fixture
 *
 * | entity | scope | `code` (unique globally) | `url` (localized, unique globally) | `slug` (localized, unique globally within locale) |
 * |---|---|---|---|---|
 * | `holder:1` | LIVE | abc | /abc | abc |
 * | `queried:1` | LIVE | xyz | /xyz | xyz |
 * | `queried:2` | LIVE | def | /def | def |
 * | `queried:5` | ARCHIVED | abc | - | - |
 * | `referrer:1` | LIVE | - | - | - |
 *
 * `queried:1` deliberately shares its primary key with `holder:1`, `empty` holds no entity at all and `referrer:1`
 * references `queried:1`. `queried:5` was written and archived before `holder:1` took the same code, which is the only
 * order the per-scope uniqueness admits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Lookup by a globally unique attribute in a query targeting one collection")
@Tag(TestTags.CONTRACT)
@Tag(TestTags.FILTER)
@Tag(TestTags.ATTRIBUTE)
class GlobalUniqueAttributeCollectionLookupFunctionalTest implements EvitaTestSupport {
	private static final String ENTITY_HOLDER = "holder";
	private static final String ENTITY_QUERIED = "queried";
	private static final String ENTITY_EMPTY = "empty";
	private static final String ENTITY_REFERRER = "referrer";
	private static final String REFERENCE_TARGET = "target";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_URL = "url";
	private static final String ATTRIBUTE_SLUG = "slug";
	private static final int SHARED_PRIMARY_KEY = 1;
	private static final int QUERIED_OWN_PRIMARY_KEY = 2;
	private static final int QUERIED_ARCHIVED_PRIMARY_KEY = 5;
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("GlobalUniqueAttributeCollectionLookupFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(Scope.values()))
			.withAttribute(ATTRIBUTE_URL, String.class, thatIs -> thatIs.localized().uniqueGlobally().nullable())
			.withAttribute(ATTRIBUTE_SLUG, String.class, thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable())
			.updateViaNewSession(this.evita);

		// the fixture is loaded in WARM_UP; the tests that need ALIVE take the catalog live themselves
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (String entityType : List.of(ENTITY_HOLDER, ENTITY_QUERIED, ENTITY_EMPTY)) {
					session.defineEntitySchema(entityType)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withGlobalAttribute(ATTRIBUTE_CODE)
						.withGlobalAttribute(ATTRIBUTE_URL)
						.withGlobalAttribute(ATTRIBUTE_SLUG)
						.updateVia(session);
				}
				session.defineEntitySchema(ENTITY_REFERRER)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_TARGET, ENTITY_QUERIED, Cardinality.ZERO_OR_ONE, whichIs -> whichIs.indexed()
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(ENTITY_QUERIED, QUERIED_ARCHIVED_PRIMARY_KEY)
						.setAttribute(ATTRIBUTE_CODE, "abc")
				);
				session.archiveEntity(ENTITY_QUERIED, QUERIED_ARCHIVED_PRIMARY_KEY);

				upsertWithValues(session, ENTITY_HOLDER, SHARED_PRIMARY_KEY, "abc");
				upsertWithValues(session, ENTITY_QUERIED, SHARED_PRIMARY_KEY, "xyz");
				upsertWithValues(session, ENTITY_QUERIED, QUERIED_OWN_PRIMARY_KEY, "def");
				session.upsertEntity(
					session.createNewEntity(ENTITY_REFERRER, 1)
						.setReference(REFERENCE_TARGET, SHARED_PRIMARY_KEY)
				);
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Writes `value` into every globally unique attribute of the entity - `code` as is, `url` prefixed with a slash and
	 * `slug` as is, the localized two in English.
	 *
	 * @param session    the read-write session
	 * @param entityType the collection to write to
	 * @param pk         the primary key of the entity
	 * @param value      the value the attributes derive from
	 */
	private static void upsertWithValues(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int pk,
		@Nonnull String value
	) {
		session.upsertEntity(
			session.createNewEntity(entityType, pk)
				.setAttribute(ATTRIBUTE_CODE, value)
				.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/" + value)
				.setAttribute(ATTRIBUTE_SLUG, Locale.ENGLISH, value)
		);
	}

	/**
	 * Takes the catalog live, so the following queries run against the transactional state.
	 */
	private void goLive() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.goLiveAndClose();
		}
	}

	/**
	 * Returns the primary keys the query over `entityType` filtered by `filter` matches, in their order.
	 *
	 * @param entityType the collection the query targets
	 * @param filter     the filter constraints
	 * @return the matched primary keys
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(@Nonnull String entityType, @Nonnull FilterConstraint... filter) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			return session.queryListOfEntityReferences(query(collection(entityType), filterBy(filter)))
				.stream()
				.map(EntityReferenceContract::getPrimaryKey)
				.toList();
		}
	}

	@Nested
	@DisplayName("Value held only by another collection")
	class ValueHeldByAnotherCollection {

		@Test
		@DisplayName("Should match nothing in a collection that holds no entity")
		void shouldMatchNothingInEmptyCollection() {
			goLive();
			assertEquals(List.of(), queryPrimaryKeys(ENTITY_EMPTY, attributeEquals(ATTRIBUTE_CODE, "abc")));
			assertEquals(List.of(), queryPrimaryKeys(ENTITY_EMPTY, attributeInSet(ATTRIBUTE_CODE, "abc", "xyz")));
		}

		@Test
		@DisplayName("Should not return the entity of the queried collection that shares the holder's primary key")
		void shouldNotReturnEntitySharingThePrimaryKey() {
			goLive();
			assertEquals(List.of(), queryPrimaryKeys(ENTITY_QUERIED, attributeEquals(ATTRIBUTE_CODE, "abc")));
			// the body fetch is where the wrong answer would carry another entity's data
			try (
				final EvitaSessionContract session =
					GlobalUniqueAttributeCollectionLookupFunctionalTest.this.evita.createReadOnlySession(TEST_CATALOG)
			) {
				final List<SealedEntity> fetched = session.queryListOfSealedEntities(
					query(
						collection(ENTITY_QUERIED),
						filterBy(attributeEquals(ATTRIBUTE_CODE, "abc")),
						require(entityFetch(attributeContent(ATTRIBUTE_CODE)))
					)
				);
				assertTrue(fetched.isEmpty(), "Entity `" + fetched + "` does not carry the code `abc`!");
			}
		}

		@Test
		@DisplayName("Should keep matching the values the queried collection holds itself")
		void shouldKeepMatchingOwnValues() {
			goLive();
			assertEquals(
				List.of(QUERIED_OWN_PRIMARY_KEY),
				queryPrimaryKeys(ENTITY_QUERIED, attributeEquals(ATTRIBUTE_CODE, "def"))
			);
			assertEquals(
				List.of(SHARED_PRIMARY_KEY, QUERIED_OWN_PRIMARY_KEY),
				queryPrimaryKeys(ENTITY_QUERIED, attributeInSet(ATTRIBUTE_CODE, "abc", "xyz", "def"))
			);
			// the catalog-wide lookup keeps finding the holder
			try (
				final EvitaSessionContract session =
					GlobalUniqueAttributeCollectionLookupFunctionalTest.this.evita.createReadOnlySession(TEST_CATALOG)
			) {
				assertEquals(
					List.of(ENTITY_HOLDER + ":" + SHARED_PRIMARY_KEY),
					session.queryListOfEntityReferences(query(filterBy(attributeEquals(ATTRIBUTE_CODE, "abc"))))
						.stream()
						.map(it -> it.getType() + ":" + it.getPrimaryKey())
						.toList()
				);
			}
		}

		@Test
		@DisplayName("Should match nothing while the catalog is still in warm-up")
		void shouldMatchNothingInWarmUp() {
			assertEquals(List.of(), queryPrimaryKeys(ENTITY_EMPTY, attributeEquals(ATTRIBUTE_CODE, "abc")));
			assertEquals(List.of(), queryPrimaryKeys(ENTITY_QUERIED, attributeEquals(ATTRIBUTE_CODE, "abc")));
		}

		@Test
		@DisplayName("Should match nothing for a localized globally unique attribute")
		void shouldMatchNothingForLocalizedAttribute() {
			goLive();
			assertEquals(
				List.of(),
				queryPrimaryKeys(
					ENTITY_QUERIED, attributeEquals(ATTRIBUTE_URL, "/abc"), entityLocaleEquals(Locale.ENGLISH)
				)
			);
			assertEquals(
				List.of(QUERIED_OWN_PRIMARY_KEY),
				queryPrimaryKeys(
					ENTITY_QUERIED, attributeInSet(ATTRIBUTE_URL, "/abc", "/def"), entityLocaleEquals(Locale.ENGLISH)
				)
			);
		}

		@Test
		@DisplayName("Should match nothing for an attribute unique globally within locale")
		void shouldMatchNothingForAttributeUniqueWithinLocale() {
			goLive();
			assertEquals(
				List.of(),
				queryPrimaryKeys(
					ENTITY_QUERIED, attributeEquals(ATTRIBUTE_SLUG, "abc"), entityLocaleEquals(Locale.ENGLISH)
				)
			);
			assertEquals(
				List.of(QUERIED_OWN_PRIMARY_KEY),
				queryPrimaryKeys(
					ENTITY_QUERIED, attributeInSet(ATTRIBUTE_SLUG, "abc", "def"), entityLocaleEquals(Locale.ENGLISH)
				)
			);
		}

		@Test
		@DisplayName("Should move on to the next requested scope when the first one holds the value elsewhere")
		void shouldMoveOnToNextScope() {
			goLive();
			assertEquals(
				List.of(QUERIED_ARCHIVED_PRIMARY_KEY),
				queryPrimaryKeys(
					ENTITY_QUERIED, attributeEquals(ATTRIBUTE_CODE, "abc"), scope(Scope.LIVE, Scope.ARCHIVED)
				)
			);
			assertEquals(
				List.of(QUERIED_ARCHIVED_PRIMARY_KEY),
				queryPrimaryKeys(
					ENTITY_QUERIED, attributeInSet(ATTRIBUTE_CODE, "abc"), scope(Scope.LIVE, Scope.ARCHIVED)
				)
			);
		}

		@Test
		@DisplayName("Should match nothing in the nested query of `entityHaving`")
		void shouldMatchNothingInEntityHaving() {
			goLive();
			assertEquals(
				List.of(),
				queryPrimaryKeys(
					ENTITY_REFERRER,
					referenceHaving(REFERENCE_TARGET, entityHaving(attributeEquals(ATTRIBUTE_CODE, "abc")))
				)
			);
			assertEquals(
				List.of(1),
				queryPrimaryKeys(
					ENTITY_REFERRER,
					referenceHaving(REFERENCE_TARGET, entityHaving(attributeEquals(ATTRIBUTE_CODE, "xyz")))
				)
			);
		}
	}

}
