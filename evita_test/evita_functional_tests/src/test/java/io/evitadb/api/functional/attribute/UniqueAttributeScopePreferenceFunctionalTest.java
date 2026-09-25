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
import io.evitadb.api.query.require.Debug;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins which entity a unique lookup returns when the same unique value lives in several requested scopes (issue
 * #1584).
 *
 * Uniqueness is enforced per scope, so an archived entity may keep a unique value a live one carries as well. A
 * lookup by that value then has two candidates, and it answers with the one in the scope `scope(...)` lists
 * **first** - `scope(LIVE, ARCHIVED)` prefers the live entity, `scope(ARCHIVED, LIVE)` the archived one. The rule is
 * the same for an entity attribute, a globally unique catalog attribute and a unique reference attribute inside
 * `referenceHaving`. A negated lookup complements the preferred answer, so it returns the entity in the other scope
 * even though that entity carries the value.
 *
 * ## The fixture
 *
 * `scopedUniqueOwner` has a unique `code`, the globally unique `url`, and a `rows` reference to `scopedUniqueTarget`
 * with a unique `u`, everything indexed in both scopes:
 *
 * | owner | scope | `code` | `url` | `rows` |
 * |---|---|---|---|---|
 * | 1 | ARCHIVED | v | /v | T1 `u = rv` |
 * | 2 | LIVE | v | /v | T1 `u = rv` |
 * | 3 | LIVE | w | /w | T2 `u = rw` |
 *
 * Owner 1 is written first and archived before owner 2 takes the same values, which is the only order the per-scope
 * uniqueness checks admit.

 *
 * Every expectation is derived from the entity bodies and the rule above, never hard-coded, and every query runs
 * with and without `PREFER_INDEX_SCAN`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Unique value present in several requested scopes")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class UniqueAttributeScopePreferenceFunctionalTest {
	private static final String SCOPED_UNIQUE = "uniqueValueInSeveralScopes";
	private static final String ENTITY_OWNER = "scopedUniqueOwner";
	private static final String ENTITY_TARGET = "scopedUniqueTarget";
	private static final String REF_ROWS = "rows";
	/**
	 * Owner attribute, unique in both scopes.
	 */
	private static final String CODE = "code";
	/**
	 * Catalog attribute, globally unique in both scopes.
	 */
	private static final String URL = "url";
	/**
	 * `rows` attribute, unique in both scopes.
	 */
	private static final String U = "u";
	private static final Scope[] LIVE_FIRST = {Scope.LIVE, Scope.ARCHIVED};
	private static final Scope[] ARCHIVED_FIRST = {Scope.ARCHIVED, Scope.LIVE};
	private static final List<Scope[]> BOTH_ORDERS = List.of(LIVE_FIRST, ARCHIVED_FIRST);

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 * @return the owners in both scopes
	 */
	@DataSet(value = SCOPED_UNIQUE, destroyAfterClass = true)
	DataCarrier setUpScopedUniqueDataSet(@Nonnull Evita evita) {
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(URL, String.class, thatIs -> thatIs.uniqueGloballyInScope(Scope.values()))
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(URL)
					.withAttribute(CODE, String.class, thatIs -> thatIs.uniqueInScope(Scope.values()))
					.withReferenceToEntity(
						REF_ROWS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(U, String.class, thatIs -> thatIs.uniqueInScope(Scope.values()).nullable())
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TARGET, 1));
				session.upsertEntity(session.createNewEntity(ENTITY_TARGET, 2));
				upsertOwner(session, 1, "v", "/v", 1, "rv");
			}
		);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_OWNER, 1);
		});
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				upsertOwner(session, 2, "v", "/v", 1, "rv");
				upsertOwner(session, 3, "w", "/w", 2, "rw");
				final List<SealedEntity> owners = session.queryListOfSealedEntities(
					Query.query(
						collection(ENTITY_OWNER),
						filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
						require(entityFetch(entityFetchAllContent()), page(1, Integer.MAX_VALUE))
					)
				);
				assertEquals(3, owners.size(), "Fixture guard: unexpected owner count!");
				return new DataCarrier("originalOwners", owners);
			}
		);
	}

	/**
	 * A unique attribute of the queried entity.
	 */
	@DisplayName("Entity attribute")
	@Nested
	class EntityAttribute {

		@DisplayName("Should return the entity in the scope listed first")
		@UseDataSet(SCOPED_UNIQUE)
		@Test
		void shouldReturnTheEntityInTheScopeListedFirst(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : BOTH_ORDERS) {
						assertOwners(
							session, order, true, attributeEquals(CODE, "v"),
							preferredOwners(originalOwners, order, carriesCode("v"))
						);
						assertOwners(
							session, order, true, attributeInSet(CODE, "v", "w"),
							union(
								preferredOwners(originalOwners, order, carriesCode("v")),
								preferredOwners(originalOwners, order, carriesCode("w"))
							)
						);
					}
					return null;
				}
			);
		}

		/**
		 * The negation complements the preferred answer: asked with the live scope first, it returns the archived
		 * owner 1, whose `code` is `v` as well.
		 */
		@DisplayName("Should complement the preferred answer when negated")
		@UseDataSet(SCOPED_UNIQUE)
		@Test
		void shouldComplementThePreferredAnswerWhenNegated(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : BOTH_ORDERS) {
						assertOwners(
							session, order, true, not(attributeEquals(CODE, "v")),
							complement(
								originalOwners,
								preferredOwners(originalOwners, order, carriesCode("v"))
							)
						);
					}
					return null;
				}
			);
		}
	}

	/**
	 * A catalog attribute unique across all collections, looked up both within the owner collection and across the
	 * whole catalog.
	 */
	@DisplayName("Globally unique attribute")
	@Nested
	class GloballyUniqueAttribute {

		@DisplayName("Should return the entity in the scope listed first")
		@UseDataSet(SCOPED_UNIQUE)
		@Test
		void shouldReturnTheEntityInTheScopeListedFirst(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : BOTH_ORDERS) {
						final Set<Integer> expected = preferredOwners(
							originalOwners, order, it -> Objects.equals(it.getAttribute(URL), "/v")
						);
						assertOwners(session, order, true, attributeEquals(URL, "/v"), expected);
						assertOwners(session, order, false, attributeEquals(URL, "/v"), expected);
						assertOwners(session, order, false, attributeInSet(URL, "/v"), expected);
					}
					return null;
				}
			);
		}
	}

	/**
	 * A unique attribute of a reference row, looked up inside `referenceHaving`.
	 */
	@DisplayName("Reference attribute")
	@Nested
	class ReferenceAttribute {

		@DisplayName("Should return the owner in the scope listed first")
		@UseDataSet(SCOPED_UNIQUE)
		@Test
		void shouldReturnTheOwnerInTheScopeListedFirst(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : BOTH_ORDERS) {
						assertOwners(
							session, order, true, referenceHaving(REF_ROWS, attributeEquals(U, "rv")),
							preferredOwners(originalOwners, order, carriesRow("rv"))
						);
						assertOwners(
							session, order, true, referenceHaving(REF_ROWS, attributeInSet(U, "rv", "rw")),
							union(
								preferredOwners(originalOwners, order, carriesRow("rv")),
								preferredOwners(originalOwners, order, carriesRow("rw"))
							)
						);
					}
					return null;
				}
			);
		}

		/**
		 * Negated per row: a row matches unless it carries the value **and** belongs to the preferred scope. So asked
		 * with the live scope first, archived owner 1 matches although its only row carries `rv`; and a value that
		 * lives in one scope only (`rw`) is complemented in every scope, whatever the order.
		 */
		@DisplayName("Should complement the preferred answer per row when negated")
		@UseDataSet(SCOPED_UNIQUE)
		@Test
		void shouldComplementThePreferredAnswerPerRowWhenNegated(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : BOTH_ORDERS) {
						for (String value : List.of("rv", "rw")) {
							final Scope preferred = preferredScope(originalOwners, order, carriesRow(value));
							assertOwners(
								session, order, true, referenceHaving(REF_ROWS, not(attributeEquals(U, value))),
								originalOwners.stream()
									.filter(
										owner -> owner.getReferences(REF_ROWS).stream().anyMatch(
											row -> !(Objects.equals(row.getAttribute(U), value) &&
												owner.getScope() == preferred)
										)
									)
									.map(SealedEntity::getPrimaryKeyOrThrowException)
									.collect(Collectors.toCollection(TreeSet::new))
							);
						}
					}
					return null;
				}
			);
		}
	}

	/**
	 * Runs the filter under the scope order, with and without `PREFER_INDEX_SCAN`, and asserts the primary keys.
	 *
	 * @param session        session to query through
	 * @param order          the scopes, in the order `scope(...)` lists them
	 * @param withCollection whether to query the owner collection or the whole catalog
	 * @param filter         the filter to apply besides the scope
	 * @param expected       expected primary keys
	 */
	private static void assertOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope[] order,
		boolean withCollection,
		@Nonnull FilterConstraint filter,
		@Nonnull Set<Integer> expected
	) {
		assertFalse(expected.isEmpty(), "Fixture guard: the oracle for `" + filter + "` must not be empty!");
		for (boolean preferIndexScan : new boolean[]{true, false}) {
			final Query query = withCollection ?
				Query.query(
					collection(ENTITY_OWNER),
					filterBy(scope(order), filter),
					require(debugModes(preferIndexScan), page(1, Integer.MAX_VALUE))
				) :
				Query.query(
					filterBy(scope(order), filter),
					require(debugModes(preferIndexScan), page(1, Integer.MAX_VALUE))
				);
			final Set<Integer> actual = session.queryList(query, EntityReference.class)
				.stream()
				.map(EntityReference::getPrimaryKey)
				.collect(Collectors.toCollection(TreeSet::new));
			assertEquals(
				expected, actual,
				"Wrong owners for `" + filter + "` in `" + Arrays.toString(order) + "` (collection=" +
					withCollection + ", preferIndexScan=" + preferIndexScan + ")"
			);
		}
	}

	/**
	 * Returns the debug requirement for the index-scan preference.
	 *
	 * @param preferIndexScan whether to forbid answering from prefetched entity bodies
	 * @return the debug requirement
	 */
	@Nonnull
	private static Debug debugModes(boolean preferIndexScan) {
		return preferIndexScan ?
			debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN) :
			debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES);
	}

	/**
	 * Returns the first scope of the order in which some owner carries the value - the scope the lookup prefers.
	 *
	 * @param owners  owners as stored
	 * @param order   the scopes, in the order `scope(...)` lists them
	 * @param carries decides whether an owner carries the value
	 * @return the preferred scope, or NULL when no owner carries the value
	 */
	@Nullable
	private static Scope preferredScope(
		@Nonnull List<SealedEntity> owners,
		@Nonnull Scope[] order,
		@Nonnull Predicate<SealedEntity> carries
	) {
		for (Scope scope : order) {
			if (owners.stream().anyMatch(it -> it.getScope() == scope && carries.test(it))) {
				return scope;
			}
		}
		return null;
	}

	/**
	 * Returns the owners carrying the value in the preferred scope.
	 *
	 * @param owners  owners as stored
	 * @param order   the scopes, in the order `scope(...)` lists them
	 * @param carries decides whether an owner carries the value
	 * @return sorted primary keys
	 */
	@Nonnull
	private static Set<Integer> preferredOwners(
		@Nonnull List<SealedEntity> owners,
		@Nonnull Scope[] order,
		@Nonnull Predicate<SealedEntity> carries
	) {
		final Scope preferred = preferredScope(owners, order, carries);
		return owners.stream()
			.filter(it -> it.getScope() == preferred && carries.test(it))
			.map(SealedEntity::getPrimaryKeyOrThrowException)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Returns the primary keys of the owners not in the set.
	 *
	 * @param owners   owners as stored
	 * @param excluded primary keys to leave out
	 * @return sorted primary keys
	 */
	@Nonnull
	private static Set<Integer> complement(@Nonnull List<SealedEntity> owners, @Nonnull Set<Integer> excluded) {
		return owners.stream()
			.map(SealedEntity::getPrimaryKeyOrThrowException)
			.filter(it -> !excluded.contains(it))
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Returns the union of two primary key sets.
	 *
	 * @param first  first set
	 * @param second second set
	 * @return sorted union
	 */
	@Nonnull
	private static Set<Integer> union(@Nonnull Set<Integer> first, @Nonnull Set<Integer> second) {
		final Set<Integer> result = new TreeSet<>(first);
		result.addAll(second);
		return result;
	}

	/**
	 * Returns a predicate matching owners whose `code` is the value.
	 *
	 * @param value the `code` value
	 * @return the predicate
	 */
	@Nonnull
	private static Predicate<SealedEntity> carriesCode(@Nonnull String value) {
		return owner -> Objects.equals(owner.getAttribute(CODE), value);
	}

	/**
	 * Returns an owner-level predicate matching owners holding a `rows` row that carries the value.
	 *
	 * @param value the `u` value
	 * @return the predicate
	 */
	@Nonnull
	private static Predicate<SealedEntity> carriesRow(@Nonnull String value) {
		return owner -> owner.getReferences(REF_ROWS).stream()
			.anyMatch(row -> Objects.equals(row.getAttribute(U), value));
	}

	/**
	 * Writes one owner with one `rows` row.
	 *
	 * @param session  session to write through
	 * @param pk       primary key of the owner
	 * @param code     value of `code`
	 * @param url      value of `url`
	 * @param targetPk the target of the row
	 * @param u        value of `u` on the row
	 */
	private static void upsertOwner(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String code,
		@Nonnull String url,
		int targetPk,
		@Nonnull String u
	) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_OWNER, pk)
				.setAttribute(CODE, code)
				.setAttribute(URL, url)
				.setReference(REF_ROWS, targetPk, whichIs -> whichIs.setAttribute(U, u))
		);
	}

}
