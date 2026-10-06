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
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that a lookup by a globally unique attribute scoped to one collection returns entities of that collection
 * only, even though the value is resolved in the catalog-wide unique index that every collection shares.
 *
 * The catalog-wide index maps a value to the entity holding it - type and primary key. A query naming a collection
 * must drop the holder when it belongs to another collection: answering with its primary key alone would pass off
 * an entity of the queried collection that happens to share the key (or one that does not exist at all) as the
 * holder. The query without a collection is the control - it must keep returning the true holder.
 *
 * ## The fixture
 *
 * Two catalog attributes, both unique in both scopes and written with the same number on every entity:
 * `globalCode` (`uniqueGlobally`) and `globalLocalCode` (localized, `uniqueGloballyWithinLocale`, written in English).
 *
 * | entity | scope | value |
 * |---|---|---|
 * | `alpha:1` | LIVE | 10 |
 * | `alpha:2` | LIVE | 20 |
 * | `beta:1` | LIVE | 11 |
 * | `beta:3` | LIVE | 30 |
 * | `beta:4` | ARCHIVED | 10 |
 *
 * So `10` is held by `alpha:1` in `beta`'s live view, where `beta` has an entity with the same primary key, and `20`
 * by `alpha:2`, where it has none. `beta:4` is written and archived before `alpha:1` claims `10`, which is the only
 * order the per-scope uniqueness admits; it makes `beta` hold `10` in the archived scope only.
 *
 * Every lookup runs both as an entity reference query and as an entity body query, and checks the total record count
 * as well as the returned page.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Collection-scoped lookup by a globally unique attribute held in another collection")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class GlobalUniqueAttributeForeignCollectionLookupFunctionalTest {
	private static final String FOREIGN_UNIQUE = "globalUniqueForeignCollectionLookup";
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final Scope[] LIVE_ONLY = {Scope.LIVE};
	private static final Scope[] LIVE_AND_ARCHIVED = {Scope.LIVE, Scope.ARCHIVED};

	/**
	 * One globally unique attribute of the fixture.
	 */
	enum GlobalAttribute {
		UNIQUE_GLOBALLY("globalCode", false),
		UNIQUE_GLOBALLY_WITHIN_LOCALE("globalLocalCode", true);

		private final String attributeName;
		private final boolean localized;

		GlobalAttribute(@Nonnull String attributeName, boolean localized) {
			this.attributeName = attributeName;
			this.localized = localized;
		}
	}

	/**
	 * The form in which the matching entities are returned.
	 */
	enum Fetch {
		ENTITY_REFERENCE,
		ENTITY_BODY
	}

	/**
	 * One lookup and the entities it must return, rendered as `type:pk`.
	 *
	 * @param description what the lookup checks, used in the assertion message
	 * @param collection  the queried collection, `null` for a catalog-wide query
	 * @param scopes      the requested scopes
	 * @param filter      the filter, without the locale and scope constraints
	 * @param expected    the entities the lookup must return, in primary key order
	 */
	private record Lookup(
		@Nonnull String description,
		@Nullable String collection,
		@Nonnull Scope[] scopes,
		@Nonnull FilterConstraint filter,
		@Nonnull List<String> expected
	) {
	}

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FOREIGN_UNIQUE, destroyAfterClass = true)
	void setUpForeignUniqueDataSet(@Nonnull Evita evita) {
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				GlobalAttribute.UNIQUE_GLOBALLY.attributeName, Integer.class,
				thatIs -> thatIs.uniqueGloballyInScope(Scope.values()).nullable()
			)
			.withAttribute(
				GlobalAttribute.UNIQUE_GLOBALLY_WITHIN_LOCALE.attributeName, Integer.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocaleInScope(Scope.values()).nullable()
			)
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (String entityType : List.of(ALPHA, BETA)) {
					session.defineEntitySchema(entityType)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withGlobalAttribute(GlobalAttribute.UNIQUE_GLOBALLY.attributeName)
						.withGlobalAttribute(GlobalAttribute.UNIQUE_GLOBALLY_WITHIN_LOCALE.attributeName)
						.updateVia(session);
				}
				writeEntity(session, BETA, 4, 10);
			}
		);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(BETA, 4);
		});
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				writeEntity(session, ALPHA, 1, 10);
				writeEntity(session, ALPHA, 2, 20);
				writeEntity(session, BETA, 1, 11);
				writeEntity(session, BETA, 3, 30);
			}
		);
	}

	/**
	 * Returns every attribute of the fixture combined with every fetch form.
	 *
	 * @return the arguments of the parameterized test
	 */
	@Nonnull
	static Stream<Arguments> attributesAndFetches() {
		return Arrays.stream(GlobalAttribute.values())
			.flatMap(attribute -> Arrays.stream(Fetch.values()).map(fetch -> Arguments.of(attribute, fetch)));
	}

	/**
	 * Every lookup of the fixture returns exactly the entities of the queried collection holding the value, and the
	 * catalog-wide control returns the true holder.
	 */
	@DisplayName("Should return only entities of the queried collection")
	@UseDataSet(FOREIGN_UNIQUE)
	@ParameterizedTest(name = "{0} as {1}")
	@MethodSource("attributesAndFetches")
	void shouldReturnOnlyEntitiesOfTheQueriedCollection(
		@Nonnull GlobalAttribute attribute,
		@Nonnull Fetch fetch,
		@Nonnull EvitaSessionContract session
	) {
		final String name = attribute.attributeName;
		final List<Lookup> lookups = List.of(
			new Lookup(
				"foreign holder shares its primary key with an entity of the queried collection",
				BETA, LIVE_ONLY, attributeEquals(name, 10), List.of()
			),
			new Lookup(
				"foreign holder's primary key is absent from the queried collection",
				BETA, LIVE_ONLY, attributeEquals(name, 20), List.of()
			),
			new Lookup(
				"local holder (control)",
				BETA, LIVE_ONLY, attributeEquals(name, 11), List.of("beta:1")
			),
			new Lookup(
				"holder in its own collection (control)",
				ALPHA, LIVE_ONLY, attributeEquals(name, 10), List.of("alpha:1")
			),
			new Lookup(
				"set of a local and a foreign holder sharing a primary key",
				BETA, LIVE_ONLY, attributeInSet(name, 30, 10), List.of("beta:3")
			),
			new Lookup(
				"set of a local and a foreign holder with an absent primary key",
				BETA, LIVE_ONLY, attributeInSet(name, 30, 20), List.of("beta:3")
			),
			new Lookup(
				"set of foreign holders only",
				BETA, LIVE_ONLY, attributeInSet(name, 10, 20), List.of()
			),
			new Lookup(
				"set of local holders (control)",
				BETA, LIVE_ONLY, attributeInSet(name, 11, 30), List.of("beta:1", "beta:3")
			),
			new Lookup(
				"foreign holder combined with the primary key it shares",
				BETA, LIVE_ONLY, and(attributeEquals(name, 10), entityPrimaryKeyInSet(1)), List.of()
			),
			new Lookup(
				"negated foreign holder",
				BETA, LIVE_ONLY, not(attributeEquals(name, 10)), List.of("beta:1", "beta:3")
			),
			new Lookup(
				"negated set of foreign holders",
				BETA, LIVE_ONLY, not(attributeInSet(name, 10, 20)), List.of("beta:1", "beta:3")
			),
			new Lookup(
				"foreign live holder, local archived holder",
				BETA, LIVE_AND_ARCHIVED, attributeEquals(name, 10), List.of("beta:4")
			),
			new Lookup(
				"set with a foreign live holder and a local archived holder of the same value",
				BETA, LIVE_AND_ARCHIVED, attributeInSet(name, 30, 10), List.of("beta:3", "beta:4")
			),
			new Lookup(
				"catalog-wide lookup (control)",
				null, LIVE_ONLY, attributeEquals(name, 10), List.of("alpha:1")
			),
			new Lookup(
				"catalog-wide set lookup (control)",
				null, LIVE_ONLY, attributeInSet(name, 10, 11), List.of("alpha:1", "beta:1")
			)
		);

		final List<Executable> checks = new ArrayList<>(lookups.size() * 2);
		for (Lookup lookup : lookups) {
			// the catalog-wide query fetches no bodies, a query without a collection is an entity reference query
			if (fetch == Fetch.ENTITY_BODY && lookup.collection() == null) {
				continue;
			}
			final Query query = toQuery(attribute, fetch, lookup);
			final String message = lookup.description() + " - `" + query.getFilterBy() + "` in " +
				(lookup.collection() == null ? "the whole catalog" : "`" + lookup.collection() + "`");
			final LookupResult result = run(session, fetch, query);
			checks.add(() -> assertEquals(lookup.expected(), result.found(), message + " returned other entities!"));
			checks.add(() -> assertEquals(
				lookup.expected().size(), result.totalRecordCount(), message + " counted other entities!"
			));
		}
		assertAll(checks);
	}

	/**
	 * Writes entity `pk` into `entityType` holding `value` in both catalog attributes.
	 *
	 * @param session    the read-write session
	 * @param entityType the collection of the entity
	 * @param pk         the primary key of the entity
	 * @param value      the value of both attributes
	 */
	private static void writeEntity(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int pk,
		int value
	) {
		session.createNewEntity(entityType, pk)
			.setAttribute(GlobalAttribute.UNIQUE_GLOBALLY.attributeName, value)
			.setAttribute(GlobalAttribute.UNIQUE_GLOBALLY_WITHIN_LOCALE.attributeName, Locale.ENGLISH, value)
			.upsertVia(session);
	}

	/**
	 * Builds the query of `lookup`, adding the requested scopes, the English locale for a localized attribute and the
	 * fetch of the bodies when they are requested.
	 *
	 * @param attribute the attribute under test
	 * @param fetch     the form in which the entities are returned
	 * @param lookup    the lookup
	 * @return the query
	 */
	@Nonnull
	private static Query toQuery(@Nonnull GlobalAttribute attribute, @Nonnull Fetch fetch, @Nonnull Lookup lookup) {
		final FilterBy filterBy = attribute.localized ?
			filterBy(scope(lookup.scopes()), lookup.filter(), entityLocaleEquals(Locale.ENGLISH)) :
			filterBy(scope(lookup.scopes()), lookup.filter());
		final var require = fetch == Fetch.ENTITY_BODY ?
			require(page(1, 100), entityFetch(attributeContentAll(), dataInLocalesAll())) :
			require(page(1, 100));
		return lookup.collection() == null ?
			Query.query(filterBy, require) :
			Query.query(collection(lookup.collection()), filterBy, require);
	}

	/**
	 * The entities a lookup returned, rendered as `type:pk`, and the total record count it reported.
	 *
	 * @param found            the returned entities
	 * @param totalRecordCount the total record count
	 */
	private record LookupResult(@Nonnull List<String> found, int totalRecordCount) {
	}

	/**
	 * Runs `query` and renders the result.
	 *
	 * @param session the read-only session
	 * @param fetch   the form in which the entities are returned
	 * @param query   the query
	 * @return the rendered result
	 */
	@Nonnull
	private static LookupResult run(
		@Nonnull EvitaSessionContract session,
		@Nonnull Fetch fetch,
		@Nonnull Query query
	) {
		if (fetch == Fetch.ENTITY_BODY) {
			final EvitaResponse<SealedEntity> response = session.query(query, SealedEntity.class);
			return new LookupResult(
				response.getRecordData().stream().map(it -> it.getType() + ":" + it.getPrimaryKey()).toList(),
				response.getTotalRecordCount()
			);
		} else {
			final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);
			return new LookupResult(
				response.getRecordData().stream().map(it -> it.getType() + ":" + it.getPrimaryKey()).toList(),
				response.getTotalRecordCount()
			);
		}
	}

}
