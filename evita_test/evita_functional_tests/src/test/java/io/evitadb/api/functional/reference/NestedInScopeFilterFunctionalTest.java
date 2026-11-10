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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.FacetStatisticsDepth.COUNTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nested `inScope` admission must depend on requested scopes, and evaluation on processed scopes, regardless of data.
 *
 * The two collection families share this fixture. Attributes x, y and g are filterable in LIVE only; status and
 * reference components are indexed in both scopes. Only product 1 and value 1 carry an English localized name.
 * Products 2 and 5 are archived. The live-only family omits targets and products 4–6 and tag/group 3.
 *
 * | entity    | scope    | attributes        | references / groups                  |
 * |-----------|----------|-------------------|--------------------------------------|
 * | tags 1,2  | LIVE     | y=1,2             |                                      |
 * | tag 3     | ARCHIVED | y=3               | no value references this tag         |
 * | groups 1,2| LIVE     | g=1,2             |                                      |
 * | group 3   | ARCHIVED | g=2               |                                      |
 * | value 1   | LIVE     | x=1, ACTIVE       | tag 1                                |
 * | value 2   | LIVE     | x=2, ACTIVE       | tag 2                                |
 * | value 3   | LIVE     | x=1, PRIVATE      | tag 1                                |
 * | value 4   | ARCHIVED | x=2, ACTIVE       | tag 1                                |
 * | value 5   | ARCHIVED | x=1, PRIVATE      | no tags                              |
 * | value 6   | ARCHIVED | no x, ACTIVE      | tag 2                                |
 * | product 1 | LIVE     | English name      | values 1,2,3 / groups 1,2,1          |
 * | product 2 | ARCHIVED |                   | values 1,2 / groups 1,2              |
 * | product 3 | LIVE     |                   | value 2 / group 2                    |
 * | product 4 | LIVE     |                   | value 4 / group 3                    |
 * | product 5 | ARCHIVED |                   | value 5 / group 3                    |
 * | product 6 | LIVE     |                   | value 6 / group 3                    |
 * | product 7 | LIVE     |                   | no references                        |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Nested inScope filters")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FILTER)
@Tag(REFERENCE)
public class NestedInScopeFilterFunctionalTest {
	private static final String DATA_SET = "nestedInScope";
	private static final String WARM_UP_DATA_SET = "nestedInScopeWarmUp";
	private static final String WARM_UP_NODE = "warmUpNode";
	private static final String HIERARCHY_DATA_SET = "nestedInScopeHierarchy";
	private static final String NODE = "node";
	private static final String NODE_OWNER = "nodeOwner";
	private static final Scope[] BOTH = Scope.values();

	/** Collection families with and without archived target indexes. */
	enum Target {
		LIVE_ONLY("liveOnlyProduct", "liveOnlyValue", "liveOnlyGroup", "liveOnlyTag", 3),
		ARCHIVED("archivedProduct", "archivedValue", "archivedGroup", "archivedTag", 6);

		private final String product;
		private final String value;
		private final String group;
		private final String tag;
		private final int lastTarget;

		/** Names the collections and the last target in this family. */
		Target(
			@Nonnull String product, @Nonnull String value, @Nonnull String group, @Nonnull String tag, int lastTarget
		) {
			this.product = product;
			this.value = value;
			this.group = group;
			this.tag = tag;
			this.lastTarget = lastTarget;
		}

		/** Keeps the explicit expected rows of owners belonging to this family. */
		@Nonnull
		Map<Integer, List<Integer>> rows(@Nonnull Map<Integer, List<Integer>> allRows) {
			final Map<Integer, List<Integer>> result = new TreeMap<>(allRows);
			result.keySet().removeIf(id -> id > this.lastTarget && id != 7);
			return result;
		}
	}

	/** Builds both independent collection families in one data set. */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUpDataSet(@Nonnull Evita evita) {
		setUp(evita, Target.LIVE_ONLY);
		setUp(evita, Target.ARCHIVED);
	}

	/** Builds one family in a catalog left in warm-up, where no transaction undoes a write refused too late. */
	@DataSet(
		value = WARM_UP_DATA_SET, readOnly = false, destroyAfterClass = true,
		expectedCatalogState = CatalogState.WARMING_UP
	)
	void setUpWarmUpDataSet(@Nonnull Evita evita) {
		setUp(evita, Target.LIVE_ONLY);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(WARM_UP_NODE).withoutGeneratedPrimaryKey().withHierarchy()
				.withAttribute("marker", Integer.class).updateVia(session);
			session.createNewEntity(WARM_UP_NODE, 1).setAttribute("marker", 1).upsertVia(session);
			session.createNewEntity(WARM_UP_NODE, 2).setParent(1).setAttribute("marker", 1).upsertVia(session);
		});
	}

	/**
	 * Builds the live hierarchy of nodes 1 ← 2 ← 3, whose `level` equals the key and is filterable in the live scope
	 * only, and two owners referencing node 3: the archived owner 1, which also references the live owner 2 through
	 * `related`, and that owner 2.
	 */
	@DataSet(value = HIERARCHY_DATA_SET, destroyAfterClass = true)
	void setUpHierarchyDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(NODE).withoutGeneratedPrimaryKey().withHierarchy()
				.withAttribute("level", Integer.class, a -> a.filterableInScope(Scope.LIVE)).updateVia(session);
			session.createNewEntity(NODE, 1).setAttribute("level", 1).upsertVia(session);
			session.createNewEntity(NODE, 2).setParent(1).setAttribute("level", 2).upsertVia(session);
			session.createNewEntity(NODE, 3).setParent(2).setAttribute("level", 3).upsertVia(session);
			session.defineEntitySchema(NODE_OWNER).withoutGeneratedPrimaryKey()
				.withReferenceToEntity("nodes", NODE, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioningInScope(BOTH))
				.withReferenceToEntity("related", NODE_OWNER, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioningInScope(BOTH))
				.updateVia(session);
			session.createNewEntity(NODE_OWNER, 2).setReference("nodes", 3).upsertVia(session);
			session.createNewEntity(NODE_OWNER, 1).setReference("nodes", 3).setReference("related", 2)
				.upsertVia(session);
			session.archiveEntity(NODE_OWNER, 1);
		});
	}

	/** Builds real indexed entities and reference rows for both variants. */
	private static void setUp(@Nonnull Evita evita, @Nonnull Target target) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(target.tag).withoutGeneratedPrimaryKey()
				.withAttribute("y", Integer.class, a -> a.filterableInScope(Scope.LIVE)).updateVia(session);
			session.defineEntitySchema(target.group).withoutGeneratedPrimaryKey()
				.withAttribute("g", Integer.class, a -> a.filterableInScope(Scope.LIVE)).updateVia(session);
			for (int id = 1; id <= (target == Target.ARCHIVED ? 3 : 2); id++) {
				session.createNewEntity(target.tag, id).setAttribute("y", id).upsertVia(session);
				session.createNewEntity(target.group, id).setAttribute("g", id == 1 ? 1 : 2).upsertVia(session);
			}
			session.defineEntitySchema(target.value).withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().nullable())
				.withAttribute("x", Integer.class, a -> a.filterableInScope(Scope.LIVE).nullable())
				.withAttribute("status", String.class, a -> a.filterableInScope(BOTH))
				.withReferenceToEntity("tags", target.tag, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioningInScope(BOTH)).updateVia(session);
			for (int id = 1; id <= target.lastTarget; id++) {
				final EntityBuilder value = session.createNewEntity(target.value, id)
					.setAttribute("status", id == 3 || id == 5 ? "PRIVATE" : "ACTIVE");
				if (id != 6) {
					value.setAttribute("x", id == 2 || id == 4 ? 2 : 1);
				}
				if (id != 5) {
					value.setReference("tags", id == 2 || id == 6 ? 2 : 1);
				}
				if (id == 1) {
					value.setAttribute("name", Locale.ENGLISH, "Value one");
				}
				value.upsertVia(session);
			}
			session.defineEntitySchema(target.product).withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().nullable())
				.withReferenceToEntity("values", target.value, Cardinality.ZERO_OR_MORE,
					r -> r.withGroupTypeRelatedToEntity(target.group)
						.indexedForFilteringAndPartitioningInScope(BOTH)
						.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
						.indexedWithComponentsInScope(Scope.ARCHIVED, ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
						.facetedInScope(BOTH)).updateVia(session);
			upsertProduct(session, target, 1, 1, 2, 3);
			upsertProduct(session, target, 2, 1, 2);
			upsertProduct(session, target, 3, 2);
			upsertProduct(session, target, 7);
			session.archiveEntity(target.product, 2);
			if (target == Target.ARCHIVED) {
				session.archiveEntity(target.tag, 3);
				session.archiveEntity(target.group, 3);
				for (int id = 4; id <= 6; id++) {
					session.archiveEntity(target.value, id);
					upsertProduct(session, target, id, id);
				}
				session.archiveEntity(target.product, 5);
			}
		});
	}

	/** Assigns live rows to their live groups and archived targets to archived group 3. */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session, @Nonnull Target target, int id, @Nonnull int... values
	) {
		final EntityBuilder product = session.createNewEntity(target.product, id);
		for (final int value : values) {
			product.setReference("values", value, r -> r.setGroup(value > 3 ? 3 : value == 2 ? 2 : 1));
		}
		if (id == 1) {
			product.setAttribute("name", Locale.ENGLISH, "Product one");
		}
		product.upsertVia(session);
	}

	/** Scoped targets retain their scope semantics at every nesting level. */
	@Nested
	@DisplayName("Scoped targets")
	class ScopedTargets {
		/** Fetch matching live rows and unrestricted archived targets. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch matching live rows and unrestricted archived targets")
		@UseDataSet(DATA_SET)
		void shouldFetchScopedReferencesWhenBothScopesAreRequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertRows(evita, target, liveX(1), target.rows(Map.of(
				1, List.of(1, 3), 2, List.of(1), 3, List.of(),
				4, List.of(4), 5, List.of(5), 6, List.of(6), 7, List.of()
			)));
		}

		/** Get scoped references of live owner. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Get scoped references of live owner")
		@UseDataSet(DATA_SET)
		void shouldGetScopedReferencesForLiveOwnerWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertEquals(List.of(1, 3), evita.queryCatalog(TEST_CATALOG, session -> {
				return rowIds(session.getEntity(target.product, 1, BOTH,
					referenceContent("values", filterBy(liveX(1)))).orElseThrow());
			}));
		}

		/** Enrich scoped references of live owner. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Enrich scoped references of live owner")
		@UseDataSet(DATA_SET)
		void shouldEnrichScopedReferencesForLiveOwnerWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertEquals(List.of(1, 3), evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity owner = session.getEntity(
					target.product, 1, BOTH, attributeContentAll()
				).orElseThrow();
				return rowIds(session.enrichEntity(owner, referenceContent("values", filterBy(liveX(1)))));
			}));
		}

		/** Get scoped references of archived owner. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Get scoped references of archived owner")
		@UseDataSet(DATA_SET)
		void shouldGetScopedReferencesForArchivedOwnerWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertEquals(List.of(1), evita.queryCatalog(TEST_CATALOG, session -> {
				return rowIds(session.getEntity(target.product, 2, BOTH,
					referenceContent("values", filterBy(liveX(1)))).orElseThrow());
			}));
		}

		/** Enrich scoped references of archived owner. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Enrich scoped references of archived owner")
		@UseDataSet(DATA_SET)
		void shouldEnrichScopedReferencesForArchivedOwnerWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertEquals(List.of(1), evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity owner = session.getEntity(
					target.product, 2, BOTH, attributeContentAll()
				).orElseThrow();
				return rowIds(session.enrichEntity(owner, referenceContent("values", filterBy(liveX(1)))));
			}));
		}

		/** Filter owners by matching live or unrestricted archived targets. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Filter owners by matching live or unrestricted archived targets")
		@UseDataSet(DATA_SET)
		void shouldFilterOwnersByScopedReferencesWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target, liveX(1), BOTH,
				target == Target.ARCHIVED ? List.of(1, 2, 4, 5, 6) : List.of(1, 2));
		}

		/** Fetch using inScope beside a both-scopes attribute. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch using inScope beside a both-scopes attribute")
		@UseDataSet(DATA_SET)
		void shouldFetchScopedReferencesBesideStatusWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertRows(evita, target, entityHaving(and(inScope(Scope.LIVE, attributeEquals("x", 1)),
				attributeEquals("status", "ACTIVE"))),
				target.rows(Map.of(
					1, List.of(1), 2, List.of(1), 3, List.of(),
				4, List.of(4), 5, List.of(), 6, List.of(6), 7, List.of()
			)));
		}

		/** Filter owners using inScope beside a both-scopes attribute. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Filter owners using inScope beside a both-scopes attribute")
		@UseDataSet(DATA_SET)
		void shouldFilterOwnersByScopedReferencesBesideStatusWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target, entityHaving(and(inScope(Scope.LIVE, attributeEquals("x", 1)),
				attributeEquals("status", "ACTIVE"))),
				BOTH, target == Target.ARCHIVED ? List.of(1, 2, 4, 6) : List.of(1, 2));
		}

		/** A non-matching live value still admits archived targets. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("A non-matching live value still admits archived targets")
		@UseDataSet(DATA_SET)
		void shouldFetchNonMatchingScopedReferencesWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertRows(evita, target, liveX(99), target.rows(Map.of(
				1, List.of(), 2, List.of(), 3, List.of(),
				4, List.of(4), 5, List.of(5), 6, List.of(6), 7, List.of()
			)));
		}

		/** Non-matching live references leave only archived target owners. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Non-matching live references leave only archived target owners")
		@UseDataSet(DATA_SET)
		void shouldFilterOwnersByNonMatchingScopedReferencesWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target, liveX(99), BOTH, target == Target.ARCHIVED ? List.of(4, 5, 6) : List.of());
		}

		/** Fetch using scoped group attributes. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch using scoped group attributes")
		@UseDataSet(DATA_SET)
		void shouldFetchScopedGroupsWhenBothScopesAreRequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertRows(evita, target, groupHaving(inScope(Scope.LIVE, attributeEquals("g", 1))),
				target.rows(Map.of(
					1, List.of(1, 3), 2, List.of(1), 3, List.of(),
					4, List.of(4), 5, List.of(5), 6, List.of(6), 7, List.of()
				)));
		}

		/** Filter owners using scoped group attributes. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Filter owners using scoped group attributes")
		@UseDataSet(DATA_SET)
		void shouldFilterOwnersByScopedGroupsWhenBothScopesAreRequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertOwners(evita, target, groupHaving(inScope(Scope.LIVE, attributeEquals("g", 1))), BOTH,
				target == Target.ARCHIVED ? List.of(1, 2, 4, 5, 6) : List.of(1, 2));
		}

		/** Fetch two nested reference levels preserving requested scopes. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch two nested reference levels preserving requested scopes")
		@UseDataSet(DATA_SET)
		void shouldFetchTwoLevelScopedReferencesWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			// The ARCHIVED pass searches archived tags only: tag 3 is referenced by no value.
			assertRows(evita, target, deepFilter(false, true), target.rows(Map.of(
				1, List.of(1, 3), 2, List.of(1), 3, List.of(),
				4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
			)));
		}

		/** Filter two nested reference levels preserving requested scopes. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Filter two nested reference levels preserving requested scopes")
		@UseDataSet(DATA_SET)
		void shouldFilterOwnersByTwoLevelScopedReferencesWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target, deepFilter(false, true), BOTH, List.of(1, 2));
		}
	}

	/** Independent entity scopes retain their scope semantics at every nesting level. */
	@Nested
	@DisplayName("Independent entity scopes")
	class IndependentEntityScopes {
		/** Fetch a target naming its own live scope. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch a target naming its own live scope")
		@UseDataSet(DATA_SET)
		void shouldFetchNestedOwnScopeWhenBothScopesAreRequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertRows(evita, target,
				entityHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1)))),
				target.rows(Map.of(
					1, List.of(1, 3), 2, List.of(1), 3, List.of(),
					4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
				)));
		}

		/** Filter by a target naming its own live scope. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Filter by a target naming its own live scope")
		@UseDataSet(DATA_SET)
		void shouldFilterOwnersByNestedOwnScopeWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target,
				entityHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1)))),
				BOTH, List.of(1, 2));
		}

		/** Two entity levels of one filter may name a scope of their own, and each is judged by its own. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch by entity and group levels naming the same own scope")
		@UseDataSet(DATA_SET)
		void shouldFetchRowsWhenEntityAndGroupLevelNameSameOwnScope(@Nonnull Target target, @Nonnull Evita evita) {
			assertEquals(
				target.rows(Map.of(
					1, List.of(1, 3), 2, List.of(1), 3, List.of(),
					4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
				)),
				fetchedRows(evita, target, filterBy(
					entityHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1)))),
					groupHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("g", 1))))
				), BOTH)
			);
		}

		/** No target is live and has an archived group at once, so nothing matches and nothing is refused. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch by entity and group levels naming different own scopes")
		@UseDataSet(DATA_SET)
		void shouldFetchNoRowsWhenEntityAndGroupLevelNameDifferentOwnScopes(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertEquals(
				target.rows(Map.of(
					1, List.of(), 2, List.of(), 3, List.of(),
					4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
				)),
				fetchedRows(evita, target, filterBy(
					entityHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1)))),
					groupHaving(and(scope(Scope.ARCHIVED), inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(3))))
				), BOTH)
			);
		}

		/** An archived row filter can widen a live query at planning and fetch. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("An archived row filter can widen a live query at planning and fetch")
		@UseDataSet(DATA_SET)
		void shouldWidenRootScopeWhenPlanningAndFetching(@Nonnull Target target, @Nonnull Evita evita) {
			assertEquals(target == Target.ARCHIVED ?
				Map.of(1, List.of(1, 2, 3), 3, List.of(2), 4, List.of(4), 6, List.of(6), 7, List.of()) :
				Map.of(1, List.of(1, 2, 3), 3, List.of(2), 7, List.of()), fetchedRows(evita, target,
				filterBy(scope(Scope.ARCHIVED), entityHaving(inScope(Scope.ARCHIVED,
					attributeEquals("status", "ACTIVE")))), new Scope[]{Scope.LIVE}));
		}

		/** The root scope admits what the nested filter names also below a row-level inScope narrowing its pass. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("A root scope keeps widening below a row-level inScope")
		@UseDataSet(DATA_SET)
		void shouldKeepRootWideningWhenNestedFilterIsBelowRowLevelInScope(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final Scope[] live = {Scope.LIVE};
			final FilterBy filter = filterBy(scope(Scope.ARCHIVED), inScope(Scope.LIVE, archivedActive()));
			final Map<Integer, List<Integer>> expected = target.rows(Map.of(
				1, List.of(1, 2, 3), 3, List.of(2), 4, List.of(), 6, List.of(), 7, List.of()
			));

			final Map<Integer, List<Integer>> fetched = fetchedRows(evita, target, filter, live);
			final Map<Integer, List<Integer>> directlyFetched = directlyFetchedRows(
				evita, target, filter, live, expected.keySet()
			);

			assertEquals(expected, fetched);
			assertEquals(expected, directlyFetched);
		}

		/** An inner archived container restricts nothing in an outer live pass. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("An inner archived container restricts nothing in an outer live pass")
		@UseDataSet(DATA_SET)
		void shouldAcceptOuterAndInnerInScopeWhenBothScopesAreRequested(@Nonnull Target target, @Nonnull Evita evita) {
			final List<Integer> result = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.queryListOfSealedEntities(query(
					collection(target.product), filterBy(scope(BOTH), inScope(Scope.LIVE, referenceHaving("values",
						entityHaving(inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE")))))),
					require(entityFetch()))).stream().map(SealedEntity::getPrimaryKey).sorted().toList();
			});
			assertEquals(target == Target.ARCHIVED ? List.of(1, 2, 3, 5) : List.of(1, 2, 3), result);
		}

		/** Preserve a deep live scope with an attribute. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Preserve a deep live scope with an attribute")
		@UseDataSet(DATA_SET)
		void shouldPreserveDeepOwnScopeWithAttributeWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target, deepFilter(true, false), BOTH,
				target == Target.ARCHIVED ? List.of(1, 2, 4) : List.of(1, 2));
		}

		/** Preserve a deep live scope with a scope guard. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Preserve a deep live scope with a scope guard")
		@UseDataSet(DATA_SET)
		void shouldPreserveDeepOwnScopeWithInScopeWhenBothScopesAreRequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertOwners(evita, target, deepFilter(true, true), BOTH,
				target == Target.ARCHIVED ? List.of(1, 2, 4) : List.of(1, 2));
		}
	}

	/** Refused filters retain their scope semantics at every nesting level. */
	@Nested
	@DisplayName("Refused filters")
	class RefusedFilters {
		/** Refuse an unrequested scope as the sole child. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse an unrequested scope as the sole child")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeSoleChildWhenScopeIsUnrequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertScopeError(Scope.LIVE, () -> owners(evita, target, liveX(1), new Scope[]{Scope.ARCHIVED}));
		}

		/** Refuse an unrequested scope beside a primary key. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse an unrequested scope beside a primary key")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeBesidePrimaryKeyWhenScopeIsUnrequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertScopeError(Scope.LIVE, () -> owners(evita, target, entityHaving(and(entityPrimaryKeyInSet(1),
				inScope(Scope.LIVE, attributeEquals("x", 1)))), new Scope[]{Scope.ARCHIVED}));
		}

		/** Refuse an unrequested scope in a live query. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse an unrequested scope in a live query")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeArchivedInLiveQueryWhenScopeIsUnrequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertScopeError(Scope.ARCHIVED, () -> owners(evita, target, entityHaving(inScope(Scope.ARCHIVED,
				attributeEquals("status", "ACTIVE"))), new Scope[]{Scope.LIVE}));
		}

		/** Refuse an unrequested scope through two reference levels. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse an unrequested scope through two reference levels")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeTwoLevelsWhenScopeIsUnrequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertScopeError(Scope.LIVE,
				() -> owners(evita, target, deepFilter(false, true), new Scope[]{Scope.ARCHIVED}));
		}

		/** Refuse unrequested scope for an archived owner with rows. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse unrequested scope for an archived owner with rows")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeDuringFetchWhenScopeIsUnrequested(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertScopeError(Scope.LIVE, () -> fetchedRows(evita, target, filterBy(liveX(1)),
				new Scope[]{Scope.ARCHIVED}));
		}

		/** Refuse unrequested scope when getting an archived owner. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse unrequested scope when getting an archived owner")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeDuringGetWhenScopeIsUnrequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertScopeError(Scope.LIVE, () -> evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(target.product, 2, new Scope[]{Scope.ARCHIVED},
					referenceContent("values", filterBy(liveX(1))));
			}));
		}

		/** Refuse a nonfilterable attribute without a scope guard. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse a nonfilterable attribute without a scope guard")
		@UseDataSet(DATA_SET)
		void shouldRefuseNonFilterableAttributeWithoutInScopeWhenArchivedScopeIsChecked(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final AttributeNotFilterableException error = assertThrowsExactly(AttributeNotFilterableException.class,
				() -> owners(evita, target, entityHaving(attributeEquals("x", 1)), BOTH));
			assertEquals("Attribute with name `x` in entity `" + target.value +
				"` is not marked as \"filterable\" or " +
				"\"unique\" in scope `ARCHIVED` and cannot be filtered by. Filtering by without having an index would be slow.",
				error.getMessage());
		}

		/** Refuse a nonfilterable attribute inside its processed scope. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse a nonfilterable attribute inside its processed scope")
		@UseDataSet(DATA_SET)
		void shouldRefuseNonFilterableAttributeInsideProcessedScopeWhenArchivedScopeIsChecked(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final AttributeNotFilterableException error = assertThrowsExactly(AttributeNotFilterableException.class,
				() -> owners(evita, target, entityHaving(inScope(Scope.ARCHIVED, attributeEquals("x", 1))), BOTH));
			assertEquals("Attribute with name `x` in entity `" + target.value +
				"` is not marked as \"filterable\" or " +
				"\"unique\" in scope `ARCHIVED` and cannot be filtered by. Filtering by without having an index would be slow.",
				error.getMessage());
		}

		/** Facet summary refuses an unrequested scope without an internal error. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Facet summary refuses an unrequested scope without an internal error")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedFacetScopeWhenScopeIsUnrequested(@Nonnull Target target, @Nonnull Evita evita) {
			assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
				return session.query(query(collection(target.product), filterBy(scope(Scope.LIVE)),
					require(facetSummaryOfReference("values", COUNTS,
						filterBy(inScope(Scope.ARCHIVED, attributeEquals("x", 1)))))), SealedEntity.class);
			}));
		}

		/** A facet filter naming its own scope is judged by that scope, whatever the query requests. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Facet summary filter naming its own scope refuses a foreign scope")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignFacetScopeWhenSummaryFilterNamesOwnScope(@Nonnull Target target, @Nonnull Evita evita) {
			assertScopeError(Scope.ARCHIVED, () -> summarizedFacets(evita, target,
				filterBy(scope(Scope.LIVE), inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(4)))));
		}

		/** The filter is admitted by its own scope, or by the scopes of the query when it names none. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Facet summary filter admits its own scope and the scopes of the query")
		@UseDataSet(DATA_SET)
		void shouldAcceptFacetScopeWhenOwnScopeOrQueryRequestsIt(@Nonnull Target target, @Nonnull Evita evita) {
			final List<Integer> byOwnScope = summarizedFacets(evita, target,
				filterBy(scope(Scope.LIVE), inScope(Scope.LIVE, entityPrimaryKeyInSet(1))));
			final List<Integer> byQueryScopes = summarizedFacets(evita, target,
				filterBy(inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(4))));

			assertEquals(List.of(1), byOwnScope);
			// the live pass does not process the archived container, so it restricts no live value
			assertEquals(List.of(1, 2, 3), byQueryScopes);
		}

		/** A locale derives a context of its own, which must keep judging by the own scope of the filter. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Facet summary filter with a locale keeps refusing a foreign scope")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignFacetScopeWhenSummaryFilterNamesOwnScopeAndLocale(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final List<FilterBy> filters = List.of(
				filterBy(scope(Scope.LIVE), inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(4))),
				filterBy(
					scope(Scope.LIVE), entityLocaleEquals(Locale.ENGLISH),
					inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(4))
				)
			);
			for (final FilterBy filter : filters) {
				// the summary searches both scopes here, so only the scope of its filter can refuse the foreign one
				assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
					return session.query(query(collection(target.product), filterBy(scope(BOTH)),
						require(facetSummaryOfReference("values", COUNTS, filter))), SealedEntity.class);
				}));
			}
		}

		/** The `scope` of a sibling `entityHaving` belongs to that entity level and admits nothing beside it. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("A scope of a sibling entity level does not admit a foreign group scope")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignGroupScopeWhenSiblingEntityHavingNamesOwnScope(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			assertScopeError(Scope.ARCHIVED, () -> fetchedRows(evita, target, filterBy(
				entityHaving(and(scope(Scope.ARCHIVED), attributeEquals("status", "ACTIVE"))),
				groupHaving(inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(3)))
			), new Scope[]{Scope.LIVE}));
		}

		/** A direct child of `referenceHaving` filters the owner's references, where no scope applies. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse a scope placed directly in referenceHaving")
		@UseDataSet(DATA_SET)
		void shouldRefuseScopeWhenPlacedDirectlyInReferenceHaving(@Nonnull Target target, @Nonnull Evita evita) {
			final EntityScope inert = scope(Scope.LIVE);

			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> owners(evita, target, and(inert, entityHaving(attributeEquals("status", "ACTIVE"))), BOTH)
			);

			assertTrue(
				error.getMessage().startsWith("Constraint `" + inert + "` has no effect in `referenceHaving`"),
				error.getMessage()
			);
		}

		/** A filter of a facet summary follows the same rule as the filter of the query. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Refuse a scope placed directly in referenceHaving of a facet summary filter")
		@UseDataSet(DATA_SET)
		void shouldRefuseScopeWhenPlacedInReferenceHavingOfFacetSummaryFilter(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final EntityScope inert = scope(Scope.LIVE);

			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> summarizedFacets(evita, target, filterBy(referenceHaving("tags", inert)))
			);

			assertTrue(
				error.getMessage().startsWith("Constraint `" + inert + "` has no effect in `referenceHaving`"),
				error.getMessage()
			);
		}

		/** A nested plan reused for an equal filter must not spare its narrower sibling the refusal. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("A broader sibling does not hide a foreign scope in either order")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignScopeWhenBroaderSiblingPlansEqualFilter(@Nonnull Target target, @Nonnull Evita evita) {
			final FilterConstraint tags = referenceHaving("tags",
				entityHaving(inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(3))));
			final FilterConstraint broad = referenceHaving("values", entityHaving(tags));
			final FilterConstraint narrow = referenceHaving("values", entityHaving(and(scope(Scope.LIVE), tags)));
			for (final FilterConstraint[] siblings : new FilterConstraint[][]{{broad, narrow}, {narrow, broad}}) {
				assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
					return session.queryListOfSealedEntities(query(collection(target.product),
						filterBy(scope(BOTH), siblings[0], siblings[1]), require(entityFetch())));
				}));
			}
		}
	}

	/** Boolean containers retain the same meaning as at the top level. */
	@Nested
	@DisplayName("Boolean parity")
	class BooleanParity {
		/** A foreign scope stands for every entity in the processed scope inside an OR. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("OR admits active live values and every archived value")
		@UseDataSet(DATA_SET)
		void shouldAdmitAllArchivedTargetsWhenForeignScopeIsInsideOr(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final FilterConstraint filter = or(inScope(Scope.LIVE, attributeEquals("x", 1)),
				attributeEquals("status", "ACTIVE"));
			final Map<Integer, List<Integer>> expected = target.rows(Map.of(
				1, List.of(1, 2, 3), 2, List.of(1, 2), 3, List.of(2),
				4, List.of(4), 5, List.of(5), 6, List.of(6), 7, List.of()
			));
			assertRows(evita, target, entityHaving(filter), expected);
			assertOwners(evita, target, entityHaving(filter), BOTH,
				target == Target.ARCHIVED ? List.of(1, 2, 3, 4, 5, 6) : List.of(1, 2, 3));
			assertTopLevelParity(evita, target, filter, expected,
				target == Target.ARCHIVED ? List.of(1, 2, 3, 4, 5, 6) : List.of(1, 2, 3));
		}

		/** Negating the universe of an unprocessed scope leaves no archived target. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("NOT excludes every archived value")
		@UseDataSet(DATA_SET)
		void shouldExcludeArchivedTargetsWhenForeignScopeIsNegated(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final FilterConstraint filter = not(inScope(Scope.LIVE, attributeEquals("x", 1)));
			final Map<Integer, List<Integer>> expected = target.rows(Map.of(
				1, List.of(2), 2, List.of(2), 3, List.of(2),
				4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
			));
			assertRows(evita, target, entityHaving(filter), expected);
			assertOwners(evita, target, entityHaving(filter), BOTH, List.of(1, 2, 3));
			assertTopLevelParity(evita, target, filter, expected, List.of(2));
		}
	}

	/** Locale derivation and nested fetches retain the enclosing request's admitted scopes. */
	@Nested
	@DisplayName("Locale and nested fetches")
	class LocaleAndNestedFetches {
		/** A reference locale narrows owners without losing admission of archived filters below LIVE. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Reference locale keeps both requested scopes through query and direct get")
		@UseDataSet(DATA_SET)
		void shouldKeepRequestedScopesWhenReferenceLocaleNarrowsProcessing(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final FilterBy filter = filterBy(entityLocaleEquals(Locale.ENGLISH),
				inScope(Scope.LIVE, entityHaving(inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE")))));
			final Map<Integer, List<Integer>> expected = target.rows(Map.of(
				1, List.of(1, 2, 3), 2, List.of(), 3, List.of(),
				4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
			));
			assertEquals(expected, fetchedRows(evita, target, filter, BOTH));
			assertEquals(expected, directlyFetchedRows(evita, target, filter));
		}

		/** The otherwise identical reference filter remains valid without a locale. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Reference filter without a locale admits both requested scopes")
		@UseDataSet(DATA_SET)
		void shouldKeepRequestedScopesWhenReferenceHasNoLocale(@Nonnull Target target, @Nonnull Evita evita) {
			final FilterBy filter = filterBy(
				inScope(Scope.LIVE, entityHaving(inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE")))));
			final Map<Integer, List<Integer>> expected = target.rows(Map.of(
				1, List.of(1, 2, 3), 2, List.of(1, 2), 3, List.of(2),
				4, List.of(), 5, List.of(5), 6, List.of(), 7, List.of()
			));
			assertEquals(expected, fetchedRows(evita, target, filter, BOTH));
			assertEquals(expected, directlyFetchedRows(evita, target, filter));
		}

		/** Root widening survives a reference-level locale context as well. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("A localized reference filter can widen a live query")
		@UseDataSet(DATA_SET)
		void shouldWidenRootScopeWhenReferenceHasLocale(@Nonnull Target target, @Nonnull Evita evita) {
			final FilterBy filter = filterBy(scope(Scope.ARCHIVED), entityLocaleEquals(Locale.ENGLISH),
				entityHaving(inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE"))));
			assertEquals(target.rows(Map.of(
				1, List.of(1, 2, 3), 3, List.of(), 4, List.of(), 6, List.of(), 7, List.of()
			)), fetchedRows(evita, target, filter, new Scope[]{Scope.LIVE}));
		}

		/** The fetch request for a live value must still admit the scopes requested for its owner. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Nested reference fetch keeps requested scopes through query and direct get")
		@UseDataSet(DATA_SET)
		void shouldFetchNestedTagsWhenBothScopesWereRequested(@Nonnull Target target, @Nonnull Evita evita) {
			final ReferenceContent content = referenceContent("values", entityFetch(
				referenceContent("tags", filterBy(entityHaving(inScope(Scope.LIVE, attributeEquals("y", 1)))))
			));
			final int visitedValues = evita.queryCatalog(TEST_CATALOG, session -> {
				final List<SealedEntity> products = session.queryListOfSealedEntities(query(
					collection(target.product), filterBy(scope(BOTH)), require(entityFetch(content))
				));
				int visited = 0;
				for (final SealedEntity product : products) {
					visited += assertNestedTags(product);
					visited += assertNestedTags(session.getEntity(
						target.product, product.getPrimaryKey(), BOTH, content
					).orElseThrow());
				}
				return visited;
			});

			// each product is read twice, through the query and directly
			assertEquals(target == Target.ARCHIVED ? 18 : 12, visitedValues);
		}
	}

	/**
	 * The `scope(...)` an `entityHaving` of a `referenceContent` filter names is the scope its bodies are fetched in,
	 * and a `referenceContent` of those bodies is judged by it, whatever the rows.
	 */
	@Nested
	@DisplayName("Scopes of fetched bodies")
	class FetchedBodyScopes {
		/** The values are fetched in the live scope their level names, so their tag filter may not name another. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Query refuses a scope the bodies are not fetched in whether rows reach it or not")
		@UseDataSet(DATA_SET)
		void shouldRefuseScopeExcludedByBodyScopeWhenQuerySelectsOwnerWithOrWithoutRows(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			for (final int id : new int[]{1, 7}) {
				assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
					return session.queryListOfSealedEntities(query(collection(target.product),
						filterBy(scope(BOTH), entityPrimaryKeyInSet(id)),
						require(entityFetch(scopedValues(Scope.LIVE, filterBy(archivedTag()))))));
				}));
			}
		}

		/** A direct get plans no check of the fetched references, and must still not depend on the rows. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Direct get refuses a scope the bodies are not fetched in whether rows reach it or not")
		@UseDataSet(DATA_SET)
		void shouldRefuseScopeExcludedByBodyScopeWhenGotOwnerHasOrLacksRows(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			for (final int id : new int[]{1, 7}) {
				assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
					return session.getEntity(
						target.product, id, BOTH, scopedValues(Scope.LIVE, filterBy(archivedTag()))
					);
				}));
			}
		}

		/** `y` is filterable in the live scope only, which is the only scope the values are fetched in. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Fetch tags of live bodies by an attribute filterable in the live scope only")
		@UseDataSet(DATA_SET)
		void shouldFetchTagsByLiveOnlyAttributeWhenBodiesAreFetchedInLiveScope(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			final ReferenceContent content = scopedValues(Scope.LIVE, filterBy(entityHaving(attributeEquals("y", 1))));
			final Map<Integer, Map<Integer, List<Integer>>> expected = new TreeMap<>(Map.of(
				1, Map.of(1, List.of(1), 2, List.of()),
				2, Map.of(1, List.of(1), 2, List.of()),
				3, Map.of(2, List.of()),
				7, Map.of()
			));
			if (target == Target.ARCHIVED) {
				// their values are archived, and so excluded by the scope of the value level
				expected.put(4, Map.of());
				expected.put(5, Map.of());
				expected.put(6, Map.of());
			}

			final Map<Integer, Map<Integer, List<Integer>>> queried = evita.queryCatalog(TEST_CATALOG, session -> {
				final Map<Integer, Map<Integer, List<Integer>>> result = new TreeMap<>();
				for (final SealedEntity product : session.queryListOfSealedEntities(query(
					collection(target.product), filterBy(scope(BOTH)), require(entityFetch(content))
				))) {
					result.put(product.getPrimaryKey(), tagIdsByValue(product));
				}
				return result;
			});
			final Map<Integer, Map<Integer, List<Integer>>> got = evita.queryCatalog(TEST_CATALOG, session -> {
				final Map<Integer, Map<Integer, List<Integer>>> result = new TreeMap<>();
				for (final int id : expected.keySet()) {
					result.put(id, tagIdsByValue(session.getEntity(target.product, id, BOTH, content).orElseThrow()));
				}
				return result;
			});

			assertEquals(expected, queried);
			assertEquals(expected, got);
		}

		/** An archived query fetches the values in the live scope their level names, so their tags may name it. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Admit below the bodies the scope they are fetched in, though the query does not request it")
		@UseDataSet(DATA_SET)
		void shouldAdmitBodyScopeWhenQueryDoesNotRequestIt(@Nonnull Target target, @Nonnull Evita evita) {
			final Scope[] archived = {Scope.ARCHIVED};
			final ReferenceContent content = scopedValues(Scope.LIVE, filterBy(liveY(1)));
			final Map<Integer, Map<Integer, List<Integer>>> expected = new TreeMap<>(Map.of(
				2, Map.of(1, List.of(1), 2, List.of())
			));
			if (target == Target.ARCHIVED) {
				// its only value is archived and private
				expected.put(5, Map.of());
			}

			final Map<Integer, Map<Integer, List<Integer>>> queried = evita.queryCatalog(TEST_CATALOG, session -> {
				final Map<Integer, Map<Integer, List<Integer>>> result = new TreeMap<>();
				for (final SealedEntity product : session.queryListOfSealedEntities(query(
					collection(target.product), filterBy(scope(archived)), require(entityFetch(content))
				))) {
					result.put(product.getPrimaryKey(), tagIdsByValue(product));
				}
				return result;
			});
			final Map<Integer, Map<Integer, List<Integer>>> got = evita.queryCatalog(TEST_CATALOG, session -> {
				final Map<Integer, Map<Integer, List<Integer>>> result = new TreeMap<>();
				for (final int id : expected.keySet()) {
					result.put(
						id, tagIdsByValue(session.getEntity(target.product, id, archived, content).orElseThrow())
					);
				}
				return result;
			});

			assertEquals(expected, queried);
			assertEquals(expected, got);
		}

		/**
		 * An archived owner fetches its nodes in the live scope their level names, and their parents are walked in it,
		 * so the bound of that walk may name it. Node 2 is the bound, so node 1 is fetched only if the bound is lost.
		 */
		@Test
		@DisplayName("Admit in the hierarchy bound of the bodies the scope they are fetched in")
		@UseDataSet(HIERARCHY_DATA_SET)
		void shouldAdmitBodyScopeInHierarchyBoundWhenQueryDoesNotRequestIt(@Nonnull Evita evita) {
			final Scope[] archived = {Scope.ARCHIVED};
			final ReferenceContent content = referenceContent(
				"nodes", filterBy(entityHaving(scope(Scope.LIVE))),
				entityFetch(hierarchyContent(stopAt(node(filterBy(inScope(Scope.LIVE, entityPrimaryKeyInSet(2)))))))
			);
			final Map<Integer, List<Integer>> expected = Map.of(3, List.of(2));

			final Map<Integer, List<Integer>> got = evita.queryCatalog(TEST_CATALOG, session -> {
				return parentIdsByNode(session.getEntity(NODE_OWNER, 1, archived, content).orElseThrow());
			});
			assertEquals(expected, got);

			final Map<Integer, List<Integer>> queried = evita.queryCatalog(TEST_CATALOG, session -> {
				return parentIdsByNode(session.queryOneSealedEntity(query(
					collection(NODE_OWNER), filterBy(scope(archived), entityPrimaryKeyInSet(1)),
					require(entityFetch(content))
				)).orElseThrow());
			});
			assertEquals(expected, queried);
		}

		/**
		 * The parents of nodes fetched in the live scope are walked in it alone, so the bound may read `level`, which
		 * is filterable in the live scope only, although the query searches the archived scope too.
		 */
		@Test
		@DisplayName("Check the hierarchy bound of the bodies in the scope they are fetched in")
		@UseDataSet(HIERARCHY_DATA_SET)
		void shouldCheckHierarchyBoundInBodyScopeWhenQueryRequestsAnotherScopeToo(@Nonnull Evita evita) {
			final ReferenceContent content = referenceContent(
				"nodes", filterBy(entityHaving(scope(Scope.LIVE))),
				entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEquals("level", 2))))))
			);
			final Map<Integer, Map<Integer, List<Integer>>> expected = Map.of(
				1, Map.of(3, List.of(2)),
				2, Map.of(3, List.of(2))
			);

			final Map<Integer, Map<Integer, List<Integer>>> queried = evita.queryCatalog(TEST_CATALOG, session -> {
				final Map<Integer, Map<Integer, List<Integer>>> result = new TreeMap<>();
				for (final SealedEntity owner : session.queryListOfSealedEntities(query(
					collection(NODE_OWNER), filterBy(scope(BOTH)), require(entityFetch(content))
				))) {
					result.put(owner.getPrimaryKey(), parentIdsByNode(owner));
				}
				return result;
			});
			final Map<Integer, Map<Integer, List<Integer>>> got = evita.queryCatalog(TEST_CATALOG, session -> {
				final Map<Integer, Map<Integer, List<Integer>>> result = new TreeMap<>();
				for (final int id : expected.keySet()) {
					result.put(id, parentIdsByNode(session.getEntity(NODE_OWNER, id, BOTH, content).orElseThrow()));
				}
				return result;
			});

			assertEquals(expected, queried);
			assertEquals(expected, got);
		}

		/**
		 * The related owners are of the queried type and fetched in its locale, yet in the live scope their level
		 * names, so their references are checked in that scope rather than in the archived one the query requests.
		 */
		@Test
		@DisplayName("Admit below bodies of the queried type the scope they are fetched in")
		@UseDataSet(HIERARCHY_DATA_SET)
		void shouldAdmitBodyScopeBelowBodiesOfQueriedTypeWhenQueryDoesNotRequestIt(@Nonnull Evita evita) {
			final ReferenceContent content = referenceContent(
				"related", filterBy(entityHaving(scope(Scope.LIVE))),
				entityFetch(referenceContent(
					"nodes", filterBy(entityHaving(inScope(Scope.LIVE, entityPrimaryKeyInSet(3))))
				))
			);

			final Map<Integer, List<Integer>> nodeIdsByRelated = evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity owner = session.queryOneSealedEntity(query(
					collection(NODE_OWNER), filterBy(scope(Scope.ARCHIVED), entityPrimaryKeyInSet(1)),
					require(entityFetch(content))
				)).orElseThrow();
				final Map<Integer, List<Integer>> result = new TreeMap<>();
				for (final ReferenceContract reference : owner.getReferences("related")) {
					final SealedEntity related = reference.getReferencedEntity().orElseThrow();
					result.put(related.getPrimaryKey(), related.getReferences("nodes").stream()
						.map(ReferenceContract::getReferencedPrimaryKey).sorted().toList());
				}
				return result;
			});

			assertEquals(Map.of(2, List.of(3)), nodeIdsByRelated);
		}
	}

	/** Own scopes are checked even if a fetched owner has no reference rows. */
	@Nested
	@DisplayName("Admission independent of reference rows")
	class AdmissionWithoutRows {
		/** Entity and group levels each replace inherited scope admission with their own scope. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Direct get refuses foreign entity and group scopes with and without rows")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignScopeWhenOwnScopeHasOrLacksRows(@Nonnull Target target, @Nonnull Evita evita) {
			for (final int id : new int[]{1, 7}) {
				for (final FilterConstraint filter : ownScopeRefusals()) {
					assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
						return session.getEntity(target.product, id, BOTH,
							referenceContent("values", filterBy(filter)));
					}));
				}
			}
		}

		/** An owner the requested scope does not hold reaches no fetch, and the verdict must not depend on that. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Direct get refuses an unrequested scope whether the owner is found or not")
		@UseDataSet(DATA_SET)
		void shouldRefuseUnrequestedScopeWhenGotOwnerLivesInAnotherScope(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			for (final int id : new int[]{1, 999}) {
				assertScopeError(Scope.LIVE, () -> evita.queryCatalog(TEST_CATALOG, session -> {
					return session.getEntity(target.product, id, new Scope[]{Scope.ARCHIVED},
						referenceContent("values", filterBy(liveX(1))));
				}));
			}
		}

		/** Planning applies the same own-scope admission as direct fetching. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Query refuses foreign entity and group scopes with and without rows")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignScopeWhenQuerySelectsOwnerWithOrWithoutRows(
			@Nonnull Target target, @Nonnull Evita evita
		) {
			for (final int id : new int[]{1, 7}) {
				for (final FilterConstraint filter : ownScopeRefusals()) {
					assertScopeError(Scope.ARCHIVED, () -> evita.queryCatalog(TEST_CATALOG, session -> {
						return session.queryListOfSealedEntities(query(collection(target.product),
							filterBy(scope(BOTH), entityPrimaryKeyInSet(id)),
							require(entityFetch(referenceContent("values", filterBy(filter))))));
					}));
				}
			}
		}

		/** Owner selection reaches the nested planner without the fetched-row compiler's admission walk. */
		@ParameterizedTest
		@EnumSource(Target.class)
		@DisplayName("Owner filtering judges a nested container by its own scope")
		@UseDataSet(DATA_SET)
		void shouldRefuseForeignScopeWhenOwnerFilterNamesOwnScope(@Nonnull Target target, @Nonnull Evita evita) {
			for (final FilterConstraint filter : ownScopeRefusals()) {
				assertScopeError(Scope.ARCHIVED, () -> owners(evita, target, filter, BOTH));
			}
		}
	}

	/** A catalog in warm-up keeps a write that was refused too late, so only a refusal made first leaves it intact. */
	@Nested
	@DisplayName("Refusal before a mutation")
	class RefusalBeforeMutation {
		/** The scope of the entity would change first and the fetch of the archived entity would refuse afterwards. */
		@Test
		@DisplayName("Archiving refuses a foreign scope before the entity changes its scope")
		@UseDataSet(WARM_UP_DATA_SET)
		void shouldRefuseForeignScopeBeforeEntityChangesScopeWhenArchiving(@Nonnull Evita evita) {
			final String product = Target.LIVE_ONLY.product;

			evita.updateCatalog(TEST_CATALOG, session -> {
				assertEquals(CatalogState.WARMING_UP, session.getCatalogState());
				assertScopeError(Scope.ARCHIVED, () -> session.archiveEntity(
					product, 3, referenceContent("values", filterBy(archivedActive()))
				));
			});

			final Scope scope = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(product, 3, BOTH).orElseThrow().getScope();
			});
			assertEquals(Scope.LIVE, scope);
		}

		/** The new attribute value would be written first and the fetch of the entity would refuse afterwards. */
		@Test
		@DisplayName("Upserting and fetching refuses a foreign scope before the entity is written")
		@UseDataSet(WARM_UP_DATA_SET)
		void shouldRefuseForeignScopeBeforeEntityIsWrittenWhenUpsertingAndFetching(@Nonnull Evita evita) {
			final String product = Target.LIVE_ONLY.product;

			evita.updateCatalog(TEST_CATALOG, session -> {
				assertEquals(CatalogState.WARMING_UP, session.getCatalogState());
				final EntityBuilder renamed = session.getEntity(product, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute("name", Locale.ENGLISH, "Renamed");
				assertScopeError(Scope.ARCHIVED, () -> session.upsertAndFetchEntity(
					renamed, referenceContent("values", filterBy(archivedActive()))
				));
			});

			final String name = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(product, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.getAttribute("name", Locale.ENGLISH, String.class);
			});
			assertEquals("Product one", name);
		}

		/** The bound of a fetched hierarchy is filtered when the parents are fetched, after the entity is written. */
		@Test
		@DisplayName("Upserting and fetching refuses a foreign scope of a hierarchy bound before the entity is written")
		@UseDataSet(WARM_UP_DATA_SET)
		void shouldRefuseForeignScopeOfHierarchyBoundBeforeEntityIsWrittenWhenUpsertingAndFetching(@Nonnull Evita evita) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				assertEquals(CatalogState.WARMING_UP, session.getCatalogState());
				final EntityBuilder changed = session.getEntity(WARM_UP_NODE, 2, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute("marker", 2);
				assertScopeError(Scope.ARCHIVED, () -> session.upsertAndFetchEntity(changed, foreignHierarchyBound()));
			});

			final Integer marker = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(WARM_UP_NODE, 2, attributeContentAll())
					.orElseThrow()
					.getAttribute("marker", Integer.class);
			});
			assertEquals(1, marker);
		}

		/** A missing entity and an existing one get the same verdict, although only the latter reaches the bound. */
		@Test
		@DisplayName("Getting refuses a foreign scope of a hierarchy bound whether the entity is found or not")
		@UseDataSet(WARM_UP_DATA_SET)
		void shouldRefuseForeignScopeOfHierarchyBoundWhenGotEntityIsFoundOrNot(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertScopeError(
					Scope.ARCHIVED, () -> session.getEntity(WARM_UP_NODE, 2, foreignHierarchyBound())
				);
				assertScopeError(
					Scope.ARCHIVED, () -> session.getEntity(WARM_UP_NODE, 999, foreignHierarchyBound())
				);
			});
		}

		/**
		 * The archiving request admits the live scope, but the values are fetched in the archived one their level
		 * names - and the product has no value, so no row would ever reach the tag filter to refuse it. No other test
		 * of this data set reads the product, so a late refusal cannot leak into them.
		 */
		@Test
		@DisplayName("Archiving refuses a scope the bodies are not fetched in before the entity changes its scope")
		@UseDataSet(WARM_UP_DATA_SET)
		void shouldRefuseScopeExcludedByBodyScopeBeforeEntityChangesScopeWhenArchiving(@Nonnull Evita evita) {
			final String product = Target.LIVE_ONLY.product;

			evita.updateCatalog(TEST_CATALOG, session -> {
				assertEquals(CatalogState.WARMING_UP, session.getCatalogState());
				assertScopeError(Scope.LIVE, () -> session.archiveEntity(
					product, 7, scopedValues(Scope.ARCHIVED, filterBy(liveY(1)))
				));
			});

			final Scope scope = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(product, 7, BOTH).orElseThrow().getScope();
			});
			assertEquals(Scope.LIVE, scope);
		}

		/** Creates a fetch of parents stopping at a node selected in a scope a live request does not name. */
		@Nonnull
		private static HierarchyContent foreignHierarchyBound() {
			return hierarchyContent(stopAt(node(filterBy(inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1))))));
		}
	}

	/** Computes the facet summary of live owners and values, filtered by the passed filter of the summary. */
	@Nonnull
	private static List<Integer> summarizedFacets(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterBy filter
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final FacetSummary summary = session.query(query(collection(target.product), filterBy(scope(BOTH)),
				require(entityFetch(), inScope(Scope.LIVE, facetSummaryOfReference("values", COUNTS, filter)))),
				SealedEntity.class).getExtraResult(FacetSummary.class);
			return Objects.requireNonNull(summary).getReferenceStatistics().stream()
				.map(FacetSummary.FacetGroupStatistics::getFacetStatistics)
				.flatMap(Collection::stream)
				.map(facet -> facet.getFacetEntity().getPrimaryKey())
				.sorted()
				.toList();
		});
	}

	/** Supplies separate entity and group levels with contradictory requested scopes. */
	@Nonnull
	private static List<FilterConstraint> ownScopeRefusals() {
		return List.of(
			entityHaving(and(scope(Scope.LIVE), inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE")))),
			groupHaving(and(scope(Scope.LIVE), inScope(Scope.ARCHIVED, attributeEquals("g", 1))))
		);
	}

	/** Pins the tags materialized inside each fetched value and returns how many values were examined. */
	private static int assertNestedTags(@Nonnull SealedEntity product) {
		final Collection<ReferenceContract> values = product.getReferences("values");
		for (final ReferenceContract reference : values) {
			final SealedEntity value = reference.getReferencedEntity().orElseThrow();
			assertEquals(value.getPrimaryKey() == 1 || value.getPrimaryKey() == 3 || value.getPrimaryKey() == 4 ?
				List.of(1) : List.of(), value.getReferences("tags").stream()
				.map(r -> r.getReferencedPrimaryKey()).sorted().toList());
		}
		return values.size();
	}

	/** Fetches every owner directly so admission is exercised independently of query planning. */
	@Nonnull
	private static Map<Integer, List<Integer>> directlyFetchedRows(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterBy filter
	) {
		return directlyFetchedRows(evita, target, filter, BOTH, target.rows(Map.of(
			1, List.of(), 2, List.of(), 3, List.of(), 4, List.of(), 5, List.of(), 6, List.of(), 7, List.of()
		)).keySet());
	}

	/** Fetches the passed owners directly in the passed scopes, each of which must hold its owner. */
	@Nonnull
	private static Map<Integer, List<Integer>> directlyFetchedRows(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterBy filter,
		@Nonnull Scope[] scopes, @Nonnull Collection<Integer> ownerIds
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final Map<Integer, List<Integer>> result = new TreeMap<>();
			for (final int id : ownerIds) {
				result.put(id, rowIds(session.getEntity(target.product, id, scopes,
					referenceContent("values", filter)).orElseThrow()));
			}
			return result;
		});
	}

	/** Compares top-level results with the union of values admitted by fetched reference rows. */
	private static void assertTopLevelParity(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterConstraint filter,
		@Nonnull Map<Integer, List<Integer>> rows, @Nonnull List<Integer> expected
	) {
		final List<Integer> topLevel = evita.queryCatalog(TEST_CATALOG, session -> {
			return session.queryListOfSealedEntities(query(collection(target.value),
				filterBy(scope(BOTH), filter), require(entityFetch())))
				.stream().map(SealedEntity::getPrimaryKey).sorted().toList();
		});
		assertEquals(expected, topLevel);
		assertEquals(topLevel, rows.values().stream().flatMap(List::stream).distinct().sorted().toList());
	}

	/** Creates the single-child nested filter from the issue. */
	@Nonnull
	private static FilterConstraint liveX(int x) {
		return entityHaving(inScope(Scope.LIVE, attributeEquals("x", x)));
	}

	/**
	 * Fetches the active values in the scope their own level names, with their tags filtered by the passed filter.
	 */
	@Nonnull
	private static ReferenceContent scopedValues(@Nonnull Scope valueScope, @Nonnull FilterBy tagFilter) {
		return referenceContent(
			"values",
			filterBy(entityHaving(and(scope(valueScope), attributeEquals("status", "ACTIVE")))),
			entityFetch(referenceContent("tags", tagFilter))
		);
	}

	/** Creates the tag filter naming the live scope, the only one `y` is filterable in. */
	@Nonnull
	private static FilterConstraint liveY(int y) {
		return entityHaving(inScope(Scope.LIVE, attributeEquals("y", y)));
	}

	/** Creates the tag filter naming the archived scope, which holds tag 3 in the archived family. */
	@Nonnull
	private static FilterConstraint archivedTag() {
		return entityHaving(inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(3)));
	}

	/** Returns the fetched tags of every fetched value of the product, keyed by the value. */
	@Nonnull
	private static Map<Integer, List<Integer>> tagIdsByValue(@Nonnull SealedEntity product) {
		final Map<Integer, List<Integer>> result = new TreeMap<>();
		for (final ReferenceContract reference : product.getReferences("values")) {
			final SealedEntity value = reference.getReferencedEntity().orElseThrow();
			result.put(value.getPrimaryKey(), value.getReferences("tags").stream()
				.map(ReferenceContract::getReferencedPrimaryKey).sorted().toList());
		}
		return result;
	}

	/** Returns the fetched parents of every fetched node of the owner, nearest first, keyed by the node. */
	@Nonnull
	private static Map<Integer, List<Integer>> parentIdsByNode(@Nonnull SealedEntity owner) {
		final Map<Integer, List<Integer>> result = new TreeMap<>();
		for (final ReferenceContract reference : owner.getReferences("nodes")) {
			final SealedEntity node = reference.getReferencedEntity().orElseThrow();
			final List<Integer> parentIds = new ArrayList<>(2);
			EntityClassifierWithParent parent = node.getParentEntity().orElse(null);
			while (parent != null) {
				parentIds.add(parent.getPrimaryKey());
				parent = parent.getParentEntity().orElse(null);
			}
			result.put(node.getPrimaryKey(), parentIds);
		}
		return result;
	}

	/** Creates the single-child nested filter naming the archived scope. */
	@Nonnull
	private static FilterConstraint archivedActive() {
		return entityHaving(inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE")));
	}

	/** Creates a two-level filter, optionally naming an independent scope at the tag level. */
	@Nonnull
	private static FilterConstraint deepFilter(boolean ownScope, boolean guarded) {
		final FilterConstraint attribute = attributeEquals("y", 1);
		final FilterConstraint filter = guarded ? inScope(Scope.LIVE, attribute) : attribute;
		return entityHaving(referenceHaving("tags", entityHaving(ownScope ? and(scope(Scope.LIVE), filter) : filter)));
	}

	/** Pins both owner identities and admitted rows. */
	private static void assertRows(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterConstraint filter,
		@Nonnull Map<Integer, List<Integer>> expected
	) {
		assertEquals(expected, fetchedRows(evita, target, filterBy(filter), BOTH));
	}

	/** Fetches rows through query planning and entity materialization. */
	@Nonnull
	private static Map<Integer, List<Integer>> fetchedRows(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterBy filter, @Nonnull Scope[] scopes
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final Map<Integer, List<Integer>> result = new TreeMap<>();
			for (final SealedEntity product : session.queryListOfSealedEntities(query(collection(target.product),
				filterBy(scope(scopes)), require(entityFetch(referenceContent("values", filter)))))) {
				result.put(product.getPrimaryKey(), rowIds(product));
			}
			return result;
		});
	}

	/** Returns admitted reference identities in a deterministic order. */
	@Nonnull
	private static List<Integer> rowIds(@Nonnull SealedEntity entity) {
		return entity.getReferences("values").stream().map(r -> r.getReferencedPrimaryKey()).sorted().toList();
	}

	/** Asserts the exact owners matched through referenceHaving. */
	private static void assertOwners(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterConstraint filter, @Nonnull Scope[] scopes,
		@Nonnull List<Integer> expected
	) {
		assertEquals(expected, owners(evita, target, filter, scopes));
	}

	/** Executes the owner filter with real indexes. */
	@Nonnull
	private static List<Integer> owners(
		@Nonnull Evita evita, @Nonnull Target target, @Nonnull FilterConstraint filter, @Nonnull Scope[] scopes
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			return session.queryListOfSealedEntities(query(collection(target.product),
				filterBy(scope(scopes), referenceHaving("values", filter)), require(entityFetch())))
				.stream().map(SealedEntity::getPrimaryKey).sorted().toList();
		});
	}

	/** Checks the public exception type and the translator's exact diagnostic. */
	private static void assertScopeError(@Nonnull Scope scope,
		@Nonnull Executable action) {
		final EvitaInvalidUsageException error = assertThrowsExactly(EvitaInvalidUsageException.class, action);
		assertEquals("Scope `" + scope +
			"` used in `inScope` filter container was not requested by `scope` constraint!", error.getMessage());
	}
}
