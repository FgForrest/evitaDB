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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.PlanPreference;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins a `scope(...)` nested inside a container that switches to another entity - `entityHaving`, `groupHaving`,
 * `hierarchyWithin` - to the entities that container reaches.
 *
 * The queried entities keep their own scope: the one `scope(...)` outside every such container, or `LIVE` when there
 * is none. A nested `scope(...)` must neither become the query's scope nor collide with it, and inside `entityHaving`
 * / `groupHaving` its order decides which target a unique lookup prefers when several scopes hold the value.
 *
 * ## The fixture
 *
 * `nestedScopeTarget` is hierarchical and has a `code` unique in both scopes; targets 11 (ARCHIVED) and 12 (LIVE) are
 * both roots with `code = t`. `nestedScopeOwner.picks` references a target and uses the target type as its group:
 *
 * | owner | scope | `picks` |
 * |---|---|---|
 * | 1 | LIVE | target 11 in group 11 |
 * | 2 | LIVE | target 12 in group 12 |
 * | 3 | ARCHIVED | target 12 in group 12 |
 *
 * Owner 3 is the witness of a leak: a nested `scope(ARCHIVED)` that became the query's scope would make it a
 * candidate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("scope(...) nested in a container reaching another entity")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
public class NestedEntityScopeFunctionalTest {
	private static final String NESTED_SCOPE = "nestedEntityScope";
	private static final String ENTITY_OWNER = "nestedScopeOwner";
	private static final String ENTITY_TARGET = "nestedScopeTarget";
	private static final String REF_PICKS = "picks";
	private static final String CODE = "code";
	private static final int ARCHIVED_TARGET_PK = 11;
	private static final int LIVE_TARGET_PK = 12;
	private static final Scope[] ARCHIVED_FIRST = {Scope.ARCHIVED, Scope.LIVE};
	private static final Scope[] LIVE_FIRST = {Scope.LIVE, Scope.ARCHIVED};

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 * @return owners and targets in both scopes
	 */
	@DataSet(value = NESTED_SCOPE, destroyAfterClass = true)
	DataCarrier setUpNestedScopeDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET)
					.withoutGeneratedPrimaryKey()
					.withHierarchyIndexedInScope(Scope.values())
					.withAttribute(CODE, String.class, thatIs -> thatIs.uniqueInScope(Scope.values()))
					.updateVia(session);
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_PICKS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> {
							whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
								.withGroupTypeRelatedToEntity(ENTITY_TARGET);
							for (Scope scope : Scope.values()) {
								whichIs.indexedWithComponentsInScope(
									scope, ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								);
							}
						}
					)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_TARGET, ARCHIVED_TARGET_PK).setAttribute(CODE, "t")
				);
			}
		);
		// the per-scope uniqueness check admits the second `t` only once the first one is archived
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_TARGET, ARCHIVED_TARGET_PK);
		});
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_TARGET, LIVE_TARGET_PK).setAttribute(CODE, "t"));
				upsertOwner(session, 1, ARCHIVED_TARGET_PK);
				upsertOwner(session, 2, LIVE_TARGET_PK);
				upsertOwner(session, 3, LIVE_TARGET_PK);
			}
		);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(ENTITY_OWNER, 3);
		});
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> owners = fetchInBothScopes(session, ENTITY_OWNER);
				final List<SealedEntity> targets = fetchInBothScopes(session, ENTITY_TARGET);
				assertEquals(3, owners.size(), "Fixture guard: unexpected owner count!");
				assertEquals(2, targets.size(), "Fixture guard: unexpected target count!");
				return new DataCarrier("originalOwners", owners, "originalTargets", targets);
			}
		);
	}

	/**
	 * `entityHaving` plans a nested query on the referenced collection, which honours its own `scope(...)`.
	 */
	@DisplayName("entityHaving")
	@Nested
	class EntityHavingScope {

		/**
		 * Next to an outer `scope(LIVE)` the nested scope used to collide with it (`MoreThanSingleResultException`);
		 * its order decides which of the two `code = t` targets the lookup resolves to.
		 */
		@DisplayName("Should prefer the target in the scope the nested scope lists first")
		@UseDataSet(NESTED_SCOPE)
		@Test
		void shouldPreferTheTargetInTheScopeTheNestedScopeListsFirst(
			Evita evita,
			List<SealedEntity> originalOwners,
			List<SealedEntity> originalTargets
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] nestedOrder : List.of(ARCHIVED_FIRST, LIVE_FIRST)) {
						assertOwners(
							session, Scope.LIVE,
							referenceHaving(
								REF_PICKS, entityHaving(and(scope(nestedOrder), attributeEquals(CODE, "t")))
							),
							liveOwnersPicking(
								originalOwners, preferredTargets(originalTargets, nestedOrder),
								ReferenceContract::getReferencedPrimaryKey
							)
						);
					}
					return null;
				}
			);
		}

		/**
		 * Without an outer scope the query searches `LIVE`; the nested `scope(ARCHIVED)` used to become the query's
		 * scope and hand back archived owner 3's world instead.
		 */
		@DisplayName("Should keep a nested scope from becoming the scope of the query")
		@UseDataSet(NESTED_SCOPE)
		@Test
		void shouldKeepANestedScopeFromBecomingTheScopeOfTheQuery(
			Evita evita,
			List<SealedEntity> originalOwners,
			List<SealedEntity> originalTargets
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertOwners(
						session, null,
						referenceHaving(
							REF_PICKS, entityHaving(and(scope(Scope.ARCHIVED), attributeEquals(CODE, "t")))
						),
						liveOwnersPicking(
							originalOwners, preferredTargets(originalTargets, new Scope[]{Scope.ARCHIVED}),
							ReferenceContract::getReferencedPrimaryKey
						)
					);
					return null;
				}
			);
		}
	}

	/**
	 * `groupHaving` plans its nested query on the group collection the same way.
	 */
	@DisplayName("groupHaving")
	@Nested
	class GroupHavingScope {

		@DisplayName("Should apply the nested scope to the groups only")
		@UseDataSet(NESTED_SCOPE)
		@Test
		void shouldApplyTheNestedScopeToTheGroupsOnly(
			Evita evita,
			List<SealedEntity> originalOwners,
			List<SealedEntity> originalTargets
		) {
			final Function<ReferenceContract, Integer> groupOf = pick -> pick.getGroup()
				.map(it -> it.getPrimaryKey())
				.orElse(null);
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertOwners(
						session, Scope.LIVE,
						referenceHaving(REF_PICKS, groupHaving(and(scope(ARCHIVED_FIRST), attributeEquals(CODE, "t")))),
						liveOwnersPicking(originalOwners, preferredTargets(originalTargets, ARCHIVED_FIRST), groupOf)
					);
					assertOwners(
						session, null,
						referenceHaving(
							REF_PICKS, groupHaving(and(scope(Scope.ARCHIVED), attributeEquals(CODE, "t")))
						),
						liveOwnersPicking(
							originalOwners, preferredTargets(originalTargets, new Scope[]{Scope.ARCHIVED}), groupOf
						)
					);
					return null;
				}
			);
		}
	}

	/**
	 * `hierarchyWithin` accepts a nested `scope(...)` in its parent filter and in its `having` specification, but
	 * resolves the parent among the hierarchy nodes of the scope being queried and does not apply the nested scope -
	 * a hierarchy is traversed within one scope. These rows pin that as it stands, so that a change to it is a
	 * deliberate one: the nested scope neither collides with the outer one nor leaks into the query.
	 */
	@DisplayName("hierarchyWithin")
	@Nested
	class HierarchyWithinScope {

		@DisplayName("Should resolve the parent in the queried scope whatever the nested scope says")
		@UseDataSet(NESTED_SCOPE)
		@Test
		void shouldResolveTheParentInTheQueriedScopeWhateverTheNestedScopeSays(
			Evita evita,
			List<SealedEntity> originalOwners,
			List<SealedEntity> originalTargets
		) {
			final Set<Integer> liveParents = preferredTargets(originalTargets, new Scope[]{Scope.LIVE});
			final Set<Integer> expected = liveOwnersPicking(
				originalOwners, liveParents, ReferenceContract::getReferencedPrimaryKey
			);
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertOwners(
						session, Scope.LIVE,
						hierarchyWithin(REF_PICKS, and(scope(ARCHIVED_FIRST), attributeEquals(CODE, "t"))),
						expected
					);
					assertOwners(
						session, null,
						hierarchyWithin(REF_PICKS, and(scope(Scope.ARCHIVED), attributeEquals(CODE, "t"))),
						expected
					);
					assertOwners(
						session, Scope.LIVE,
						hierarchyWithin(REF_PICKS, attributeEquals(CODE, "t"), having(scope(Scope.ARCHIVED))),
						expected
					);
					return null;
				}
			);
		}
	}

	/**
	 * Runs the query on both plans of {@link PlanPreference} and asserts the owner primary keys, and that each plan was
	 * the one taken. The prefetch plan is narrowed to every owner of both scopes, which is neutral to the answer.
	 *
	 * @param session    session to query through
	 * @param outerScope the scope of the query itself, or NULL to leave the default
	 * @param filter     the filter besides the outer scope
	 * @param expected   expected owner primary keys
	 */
	private static void assertOwners(
		@Nonnull EvitaSessionContract session,
		@Nullable Scope outerScope,
		@Nonnull FilterConstraint filter,
		@Nonnull Set<Integer> expected
	) {
		assertFalse(expected.isEmpty(), "Fixture guard: the oracle for `" + filter + "` must not be empty!");
		final int[] candidates = session.queryList(
				Query.query(
					collection(ENTITY_OWNER),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
					require(page(1, Integer.MAX_VALUE))
				),
				EntityReference.class
			).stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
		for (PlanPreference plan : PlanPreference.values()) {
			final EvitaResponse<EntityReference> response = session.query(
				Query.query(
					collection(ENTITY_OWNER),
					filterBy(
						outerScope == null ?
							plan.filter(candidates, filter) : plan.filter(candidates, scope(outerScope), filter)
					),
					require(plan.debug(), page(1, Integer.MAX_VALUE), queryTelemetry())
				),
				EntityReference.class
			);
			final Set<Integer> actual = response.getRecordData().stream()
				.map(EntityReference::getPrimaryKey)
				.collect(Collectors.toCollection(TreeSet::new));
			assertEquals(
				expected, actual,
				"Wrong owners for `" + filter + "` under outer scope " + outerScope + " (" + plan + ")"
			);
			plan.assertTaken(response);
		}
	}

	/**
	 * Returns the targets with `code = t` in the first scope of the order that holds one - the ones a unique lookup
	 * prefers.
	 *
	 * @param targets targets as stored, in both scopes
	 * @param order   the scopes in their requested order
	 * @return sorted target primary keys
	 */
	@Nonnull
	private static Set<Integer> preferredTargets(@Nonnull List<SealedEntity> targets, @Nonnull Scope[] order) {
		for (Scope scope : order) {
			final Set<Integer> inScope = targets.stream()
				.filter(it -> it.getScope() == scope && Objects.equals(it.getAttribute(CODE), "t"))
				.map(SealedEntity::getPrimaryKeyOrThrowException)
				.collect(Collectors.toCollection(TreeSet::new));
			if (!inScope.isEmpty()) {
				return inScope;
			}
		}
		throw new AssertionError("Fixture guard: no target carries `t` in " + Arrays.toString(order) + "!");
	}

	/**
	 * Returns the live owners holding a `picks` row whose key - the target or the group - is among the passed ones.
	 *
	 * @param owners owners as stored, in both scopes
	 * @param keys   the accepted targets or groups
	 * @param keyOf  extracts the key from a row
	 * @return sorted owner primary keys
	 */
	@Nonnull
	private static Set<Integer> liveOwnersPicking(
		@Nonnull List<SealedEntity> owners,
		@Nonnull Set<Integer> keys,
		@Nonnull Function<ReferenceContract, Integer> keyOf
	) {
		return owners.stream()
			.filter(it -> it.getScope() == Scope.LIVE)
			.filter(it -> it.getReferences(REF_PICKS).stream().anyMatch(pick -> keys.contains(keyOf.apply(pick))))
			.map(SealedEntity::getPrimaryKeyOrThrowException)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Fetches every entity of the collection in both scopes with all its content.
	 *
	 * @param session    session to query through
	 * @param entityType collection to read
	 * @return every entity, fully loaded
	 */
	@Nonnull
	private static List<SealedEntity> fetchInBothScopes(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType
	) {
		return session.queryListOfSealedEntities(
			Query.query(
				collection(entityType),
				filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
				require(entityFetch(entityFetchAllContent()), page(1, Integer.MAX_VALUE))
			)
		);
	}

	/**
	 * Writes one owner picking the target within the group of the same primary key.
	 *
	 * @param session  session to write through
	 * @param pk       primary key of the owner
	 * @param targetPk the picked target, also used as the group
	 */
	private static void upsertOwner(@Nonnull EvitaSessionContract session, int pk, int targetPk) {
		session.upsertEntity(
			session.createNewEntity(ENTITY_OWNER, pk)
				.setReference(REF_PICKS, targetPk, whichIs -> whichIs.setGroup(targetPk))
		);
	}

}
