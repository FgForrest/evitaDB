/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.Page;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link QueryUtils} - utility methods for accessing and manipulating
 * {@link Query} constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("QueryUtils")
@Tag(CONTRACT)
@Tag(QUERY)
class QueryUtilsTest {
	/** An `inScope` of the live scope, which a query requesting the archived scope alone refuses. */
	private static final FilterConstraint LIVE_X = inScope(Scope.LIVE, attributeEquals("x", 1));

	/**
	 * Checks the allowed root path and uniqueness independently of scope lookup.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Scope placement and uniqueness")
	class ScopePlacement {
		/** The number of filter roots {@link #scopeInEveryFilterRoot(int)} places a scope in. */
		private static final int FILTER_ROOTS = 6;

		/** Every non-conjunctive wrapper retains its identity through conjunctions, and the nearest one is named. */
		@Test
		@DisplayName("Refuse scope inside inScope and userFilter, naming the nearest of them")
		void shouldRefuseScopeWhenInsideForbiddenContainer() {
			final List<Map.Entry<FilterConstraint, String>> refusals = List.of(
				Map.entry(inScope(Scope.LIVE, scope(Scope.LIVE)), "inScope"),
				Map.entry(inScope(Scope.LIVE, and(and(scope(Scope.LIVE), attributeEquals("x", 1)))), "inScope"),
				Map.entry(and(inScope(Scope.LIVE, scope(Scope.LIVE))), "inScope"),
				Map.entry(userFilter(scope(Scope.LIVE)), "userFilter"),
				Map.entry(userFilter(and(scope(Scope.LIVE), attributeEquals("x", 1))), "userFilter"),
				Map.entry(inScope(Scope.LIVE, userFilter(scope(Scope.LIVE))), "userFilter"),
				Map.entry(userFilter(inScope(Scope.LIVE, scope(Scope.LIVE))), "inScope")
			);

			for (final Map.Entry<FilterConstraint, String> refusal : refusals) {
				assertPlacement(query(collection("a"), filterBy(refusal.getKey())), refusal.getValue());
			}
		}

		/** Each filter root starts an entity level of its own, so one scope per root is valid. */
		@Test
		@DisplayName("Accept a scope in every filter root and refuse a second one in any of them")
		void shouldAcceptScopeWhenEachFilterRootHasOwnScope() {
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(scopeInEveryFilterRoot(-1)));

			for (int repeatedRoot = 0; repeatedRoot < FILTER_ROOTS; repeatedRoot++) {
				final Query query = scopeInEveryFilterRoot(repeatedRoot);
				assertEquals(
					"Constraint `scope(ARCHIVED)` repeats the scope selection of `scope(LIVE)` in the same entity " +
						"level - name all the scopes, in the order you prefer them, in one `scope(...)`.",
					assertThrowsExactly(EvitaInvalidUsageException.class,
						() -> QueryUtils.assertScopePlacementValid(query), query::toString
					).getMessage()
				);
			}
		}

		/** Every scope-applying level permits an arbitrary root conjunction path. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#allowedScopeApplyingLevels")
		@DisplayName("Accept conjunction chains at every scope-applying level")
		void shouldAcceptScopeWhenOnRootConjunctionPath(
			@Nonnull String description, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			final FilterConstraint filter = level.apply(or(and(and(scope(Scope.ARCHIVED), attributeEquals("x", 1)))));
			for (final Query query : List.of(
				query(collection("a"), filterBy(filter)),
				query(collection("a"), orderBy(segments(segment(
					entityHaving(filter), orderBy(attributeNatural("x"))
				)))),
				query(collection("a"), require(entityFetch(referenceContent("r", filterBy(filter)))))
			)) {
				assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query), description);
			}
		}

		/** Inert scopes are refused even without a logical wrapper in an order filter. */
		@Test
		@DisplayName("Refuse an inert scope in the order section without not or or")
		void shouldRefuseScopeWhenInertInOrderSection() {
			final Query query = query(collection("a"), orderBy(segments(segment(
				entityHaving(referenceHaving("r", scope(Scope.LIVE))), orderBy(attributeNatural("x"))
			))));
			assertEquals(
				"Constraint `scope(LIVE)` has no effect in `referenceHaving` - it selects scopes only in the root " +
					"of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or removed.",
				assertThrowsExactly(
					EvitaInvalidUsageException.class, () -> QueryUtils.assertScopePlacementValid(query)
				).getMessage()
			);
		}

		/** Separate levels reset both the forbidden path and sibling aggregation. */
		@Test
		@DisplayName("Accept independent levels and repeated values in one scope")
		void shouldAcceptScopeWhenLevelsAreIndependent() {
			for (final FilterConstraint filter : List.of(
				inScope(Scope.LIVE, referenceHaving("r", entityHaving(scope(Scope.ARCHIVED)))),
				and(scope(Scope.LIVE), entityHaving(scope(Scope.ARCHIVED))),
				and(entityHaving(scope(Scope.LIVE)), entityHaving(scope(Scope.ARCHIVED))),
				and(referenceHaving("r", entityHaving(scope(Scope.LIVE))),
					referenceHaving("s", entityHaving(scope(Scope.ARCHIVED)))),
				scope(Scope.LIVE, Scope.ARCHIVED, Scope.LIVE)
			)) {
				assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(
					query(collection("a"), filterBy(filter))
				), filter::toString);
			}
		}

		/** The first selection of a level must be unambiguous, even for equal constraints. */
		@Test
		@DisplayName("Refuse two scope constraints in one level including identical selections")
		void shouldRefuseScopeWhenRepeatedInSameLevel() {
			for (final Scope second : Scope.values()) {
				for (final FilterConstraint filter : List.of(
					and(scope(Scope.LIVE), scope(second)),
					and(scope(Scope.LIVE), and(and(scope(second)))),
					entityHaving(scope(Scope.LIVE), scope(second)),
					groupHaving(and(scope(Scope.LIVE), scope(second)))
				)) {
					for (final Query query : List.of(
						query(collection("a"), filterBy(filter)),
						query(collection("a"), require(entityFetch(referenceContent("r", filterBy(filter))))),
						query(collection("a"), orderBy(segments(segment(
							entityHaving(filter), orderBy(attributeNatural("x"))
						))))
					)) {
						assertEquals(
							"Constraint `scope(" + second + ")` repeats the scope selection of `scope(LIVE)` in the " +
								"same entity level - name all the scopes, in the order you prefer them, " +
								"in one `scope(...)`.",
							assertThrowsExactly(EvitaInvalidUsageException.class,
								() -> QueryUtils.assertScopePlacementValid(query), query::toString
							).getMessage()
						);
					}
				}
			}
		}

		/** Placement wins even over a duplicate in an earlier subtree or section. */
		@Test
		@DisplayName("Report placement before duplicates throughout the request")
		void shouldPreferPlacementWhenDuplicateOccursEarlier() {
			final Query negated = query(collection("a"), filterBy(
				scope(Scope.LIVE), scope(Scope.ARCHIVED), not(scope(Scope.LIVE))
			));
			assertEquals(
				"Constraint `scope(LIVE)` cannot be negated in `not` - it selects the scopes to search in, it " +
					"does not filter entities. Name the scopes you want in `scope(...)` instead.",
				assertThrowsExactly(EvitaInvalidUsageException.class,
					() -> QueryUtils.assertScopePlacementValid(negated)).getMessage()
			);
			assertPlacement(query(collection("a"), filterBy(
				and(scope(Scope.LIVE), scope(Scope.ARCHIVED)), userFilter(scope(Scope.LIVE))
			)), "userFilter");
			assertPlacement(query(collection("a"), filterBy(scope(Scope.LIVE), scope(Scope.ARCHIVED)),
				require(entityFetch(referenceContent("r", filterBy(userFilter(scope(Scope.LIVE))))))
			), "userFilter");
		}

		/** Pins the complete placement diagnostic and its extra inScope advice. */
		private void assertPlacement(@Nonnull Query query, @Nonnull String container) {
			assertEquals(
				"Constraint `scope(LIVE)` cannot be placed in `" + container + "` - it selects the scopes its " +
					"whole entity level is searched in, so it may stand only at the root of the filter of that " +
					"level or in `and`." + (container.equals("inScope") ?
					" Name the scopes in a `scope(...)` at the root of that level and keep only the conditions " +
						"in `inScope(...)`." : ""),
				assertThrowsExactly(EvitaInvalidUsageException.class,
					() -> QueryUtils.assertScopePlacementValid(query), query::toString).getMessage()
			);
		}

		/**
		 * Builds a query with `scope(LIVE)` in each of its filter roots: the filter section, the `entityHaving` of an
		 * order segment, the filters of two fetched references and both filters of a facet summary.
		 *
		 * @param repeatedRoot the index of the root that also holds `scope(ARCHIVED)`, -1 for none
		 * @return the query
		 */
		@Nonnull
		private Query scopeInEveryFilterRoot(int repeatedRoot) {
			final FilterConstraint[] roots = new FilterConstraint[FILTER_ROOTS];
			for (int i = 0; i < roots.length; i++) {
				roots[i] = i == repeatedRoot ? and(scope(Scope.LIVE), scope(Scope.ARCHIVED)) : scope(Scope.LIVE);
			}
			return query(
				collection("a"),
				filterBy(roots[0]),
				orderBy(segments(segment(entityHaving(roots[1]), orderBy(attributeNatural("x"))))),
				require(
					entityFetch(referenceContent("r", filterBy(roots[2])), referenceContent("s", filterBy(roots[3]))),
					facetSummaryOfReference(
						"t", FacetStatisticsDepth.COUNTS, filterBy(roots[4]), filterGroupBy(roots[5])
					)
				)
			);
		}
	}

	/** Supplies scope-applying levels whose root path is conjunctive. */
	@Nonnull
	static Stream<Arguments> allowedScopeApplyingLevels() {
		return scopeApplyingLevels().filter(arguments -> !arguments.get()[0].equals("or below the root"));
	}

	/** Admission follows the scope of each entity level, independently of reference data. */
	@Nested
	@DisplayName("Requested filter scopes")
	class RequestedFilterScopes {
		/** Both inherited scopes remain admissible at nested levels without their own scope. */
		@Test
		@DisplayName("Accept requested scopes throughout inherited levels")
		void shouldAcceptScopesWhenInheritedByNestedLevels() {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				filterBy(inScope(Scope.LIVE, attributeEquals("x", 1)), referenceHaving("values",
					entityHaving(inScope(Scope.ARCHIVED, attributeEquals("status", "ACTIVE"))))),
				Set.of(Scope.values())
			));
		}

		/** Own admission replaces inherited admission even when only the own scope permits the container. */
		@Test
		@DisplayName("Accept a container permitted by its own scope but not by inherited scopes")
		void shouldAcceptScopeWhenSeparateLevelRequestsIt() {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				filterBy(referenceHaving("values", entityHaving(and(
					scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1))
				)))), Set.of(Scope.ARCHIVED)
			));
		}

		/** Inherited admission cannot authorize a container excluded by an entity level's own scope. */
		@Test
		@DisplayName("Refuse a container excluded by its own scope even if inherited scopes permit it")
		void shouldRefuseScopeWhenSeparateLevelDoesNotRequestIt() {
			assertUnrequested(filterBy(entityHaving(and(
				scope(Scope.ARCHIVED), inScope(Scope.LIVE, attributeEquals("x", 1))
			))), Set.of(Scope.LIVE));
		}

		/** A sibling resumes inherited admission after an independently scoped entity level. */
		@Test
		@DisplayName("Refuse an unrequested sibling after an independent entity level")
		void shouldRefuseScopeWhenSiblingDoesNotRequestIt() {
			assertUnrequested(filterBy(
				entityHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1)))),
				inScope(Scope.LIVE, attributeEquals("x", 1))
			), Set.of(Scope.ARCHIVED));
		}

		/** The root inherits admission directly. */
		@Test
		@DisplayName("Refuse an unrequested scope at the root")
		void shouldRefuseScopeWhenRootDoesNotRequestIt() {
			assertUnrequested(filterBy(inScope(Scope.LIVE, attributeEquals("x", 1))), Set.of(Scope.ARCHIVED));
		}

		/** Boolean containers preserve inherited admission. */
		@Test
		@DisplayName("Refuse an unrequested scope inside a conjunction")
		void shouldRefuseScopeWhenConjunctionDoesNotRequestIt() {
			assertUnrequested(filterBy(and(
				entityPrimaryKeyInSet(1), inScope(Scope.LIVE, attributeEquals("x", 1))
			)), Set.of(Scope.ARCHIVED));
		}

		/** Several separate levels without own scopes still inherit the same admission. */
		@Test
		@DisplayName("Refuse an unrequested scope below nested entity filters")
		void shouldRefuseScopeWhenNestedEntityDoesNotRequestIt() {
			assertUnrequested(filterBy(entityHaving(referenceHaving("tags", entityHaving(
				inScope(Scope.LIVE, attributeEquals("y", 1))
			)))), Set.of(Scope.ARCHIVED));
		}

		/** A group level replaces inherited admission by its own scope as an entity level does. */
		@Test
		@DisplayName("Judge a group level by its own scope")
		void shouldJudgeGroupLevelByOwnScope() {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				filterBy(groupHaving(and(scope(Scope.LIVE), inScope(Scope.LIVE, attributeEquals("x", 1))))),
				Set.of(Scope.ARCHIVED)
			));
			assertUnrequested(
				filterBy(groupHaving(and(scope(Scope.ARCHIVED), inScope(Scope.LIVE, attributeEquals("x", 1))))),
				Set.of(Scope.LIVE)
			);
		}

		/** Disjunction and negation inherit admission as a conjunction does. */
		@Test
		@DisplayName("Refuse an unrequested scope inside a disjunction and a negation")
		void shouldRefuseScopeWhenDisjunctionOrNegationDoesNotRequestIt() {
			assertUnrequested(
				filterBy(or(attributeEquals("y", 1), inScope(Scope.LIVE, attributeEquals("x", 1)))),
				Set.of(Scope.ARCHIVED)
			);
			assertUnrequested(filterBy(not(inScope(Scope.LIVE, attributeEquals("x", 1)))), Set.of(Scope.ARCHIVED));
		}

		/** The scope of a level says nothing about a deeper level naming a scope of its own. */
		@Test
		@DisplayName("Judge a deeper entity level by its own scope")
		void shouldAcceptScopeWhenDeeperEntityLevelRequestsIt() {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				filterBy(entityHaving(and(
					scope(Scope.LIVE),
					referenceHaving("r", entityHaving(and(
						scope(Scope.ARCHIVED), inScope(Scope.ARCHIVED, attributeEquals("x", 1))
					)))
				))),
				Set.of(Scope.LIVE)
			));
		}

		/** A container that reads no scope of its own inherits admission like a boolean container. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeRefusingContainers")
		@DisplayName("Judge an inScope below a container reading no own scope by inherited scopes")
		void shouldInheritRequestedScopesWhenContainerReadsNoOwnScope(
			@Nonnull String container, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			final FilterConstraint inScope = inScope(Scope.LIVE, attributeEquals("x", 1));

			assertUnrequested(filterBy(level.apply(inScope)), Set.of(Scope.ARCHIVED));
			assertDoesNotThrow(
				() -> QueryUtils.assertFilterScopesApplicable(filterBy(level.apply(inScope)), Set.of(Scope.LIVE))
			);
		}

		/** A scope a container would silently ignore is refused, whatever the scopes the query requests. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeRefusingContainers")
		@DisplayName("Refuse a scope in a container that does not apply it")
		void shouldRefuseScopeWhenContainerDoesNotApplyIt(
			@Nonnull String container, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			assertInertScope(filterBy(level.apply(scope(Scope.LIVE))), scope(Scope.LIVE), container);
		}

		/** Two such scopes get the same clear refusal, even when the walk reaches them through a nested level. */
		@Test
		@DisplayName("Refuse two scopes of one hierarchy with the same diagnostic")
		void shouldRefuseScopesWhenNestedHierarchyHoldsTwoOfThem() {
			assertInertScope(
				filterBy(referenceHaving("r", entityHaving(hierarchyWithinSelf(
					and(scope(Scope.LIVE), attributeEquals("code", "t")),
					having(scope(Scope.ARCHIVED))
				)))),
				scope(Scope.LIVE), "hierarchyWithinSelf"
			);
		}

		/** The levels that apply a scope stay valid, the root and boolean containers below it included. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeApplyingLevels")
		@DisplayName("Accept a scope at a level that applies it")
		void shouldAcceptScopeWhenLevelAppliesIt(
			@Nonnull String levelName, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				filterBy(level.apply(scope(Scope.ARCHIVED))), Set.of(Scope.LIVE)
			));
		}

		/** Pins the public exception and the diagnostic naming the constraint and the container ignoring it. */
		private void assertInertScope(
			@Nonnull FilterBy filter, @Nonnull EntityScope inertScope, @Nonnull String containerName
		) {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(filter, Set.of(Scope.values()))
			);
			assertEquals(
				"Constraint `" + inertScope + "` has no effect in `" + containerName + "` - it selects scopes only " +
					"in the root of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or " +
					"removed.",
				error.getMessage()
			);
		}

		/** Pins the public exception and exact scope diagnostic. */
		private void assertUnrequested(@Nonnull FilterBy filter, @Nonnull Set<Scope> requestedScopes) {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(filter, requestedScopes)
			);
			assertEquals(
				"Scope `LIVE` used in `inScope` filter container was not requested by `scope` constraint!",
				error.getMessage()
			);
		}
	}

	/**
	 * Supplies every container whose children filter a different entity, each wrapping the passed filter as its
	 * children.
	 *
	 * @return the container name and the function wrapping a filter into it
	 */
	@Nonnull
	static Stream<Arguments> differentEntityContainers() {
		return Stream.of(
			Arguments.of("entityHaving", (Function<FilterConstraint, FilterConstraint>) it -> entityHaving(it)),
			Arguments.of("groupHaving", (Function<FilterConstraint, FilterConstraint>) it -> groupHaving(it)),
			Arguments.of(
				"hierarchyWithin", (Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithin("category", it)
			),
			Arguments.of(
				"hierarchyWithinRoot",
				(Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithinRoot("category", having(it))
			)
		);
	}

	/** The check of a whole query judges the filter section and the filter of every fetched reference. */
	@Nested
	@DisplayName("Scopes of a whole query")
	class QueryFilterScopes {
		/** An `inScope` of the filter section is judged directly by the requested scopes. */
		@Test
		@DisplayName("Refuse an unrequested scope in the filter section")
		void shouldRefuseScopeWhenFilterSectionDoesNotRequestIt() {
			assertUnrequested(query(collection("a"), filterBy(LIVE_X)));
		}

		/** A filter of fetched references is judged even though no row may ever reach it. */
		@Test
		@DisplayName("Refuse an unrequested scope in a filter of fetched references")
		void shouldRefuseScopeWhenFetchedReferenceFilterDoesNotRequestIt() {
			assertUnrequested(query(
				collection("a"),
				require(entityFetch(referenceContent("r", filterBy(entityHaving(LIVE_X)))))
			));
		}

		/** A requirement confined to one scope still belongs to the query. */
		@Test
		@DisplayName("Refuse an unrequested scope in a filter of references fetched in one scope")
		void shouldRefuseScopeWhenFetchedReferenceFilterInRequireInScopeDoesNotRequestIt() {
			assertUnrequested(query(
				collection("a"),
				require(inScope(Scope.ARCHIVED, entityFetch(referenceContent("r", filterBy(entityHaving(LIVE_X))))))
			));
		}

		/** A `referenceContent` of a fetched body is judged as one of the query. */
		@Test
		@DisplayName("Refuse an unrequested scope in a filter of references of a fetched body")
		void shouldRefuseScopeWhenNestedFetchedReferenceFilterDoesNotRequestIt() {
			assertUnrequested(query(
				collection("a"),
				require(entityFetch(referenceContent(
					"r", entityFetch(referenceContent("s", filterBy(entityHaving(LIVE_X))))
				)))
			));
		}

		/** The root of the filter of fetched references widens the scopes the references are looked up in. */
		@Test
		@DisplayName("Accept a scope the root of a filter of fetched references names")
		void shouldAcceptScopeWhenRootOfFetchedReferenceFilterNamesIt() {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				query(
					collection("a"),
					require(entityFetch(referenceContent("r", filterBy(scope(Scope.LIVE), entityHaving(LIVE_X)))))
				),
				Set.of(Scope.ARCHIVED)
			));
		}

		/** The root scope of one filter says nothing about the filter of a `referenceContent` of its bodies. */
		@Test
		@DisplayName("Refuse a scope only the filter of the enclosing fetched references names")
		void shouldRefuseScopeWhenOnlyEnclosingFetchedReferenceFilterNamesIt() {
			assertUnrequested(query(
				collection("a"),
				require(entityFetch(referenceContent(
					"r", filterBy(scope(Scope.LIVE)),
					entityFetch(referenceContent("s", filterBy(entityHaving(LIVE_X))))
				)))
			));
		}

		/** A scope of a sibling entity level belongs to that level and admits nothing beside it. */
		@Test
		@DisplayName("Refuse a scope only a sibling entity level names")
		void shouldRefuseScopeWhenOnlySiblingEntityLevelNamesIt() {
			assertUnrequested(query(
				collection("a"),
				require(entityFetch(referenceContent("r", filterBy(
					entityHaving(and(scope(Scope.LIVE), attributeEquals("y", 1))),
					groupHaving(LIVE_X)
				))))
			));
		}

		/** A scope the engine would ignore is refused in a filter of fetched references too. */
		@Test
		@DisplayName("Refuse an inert scope in a filter of fetched references")
		void shouldRefuseInertScopeWhenFetchedReferenceFilterHoldsIt() {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(
					query(
						collection("a"),
						require(entityFetch(referenceContent(
							"r", filterBy(entityHaving(hierarchyWithinSelf(scope(Scope.LIVE))))
						)))
					),
					Set.of(Scope.LIVE)
				)
			);

			assertTrue(
				error.getMessage().startsWith("Constraint `scope(LIVE)` has no effect in `hierarchyWithinSelf`"),
				error.getMessage()
			);
		}

		/** The bound of a fetched hierarchy is filtered when the parents are fetched, which follows any write. */
		@Test
		@DisplayName("Refuse an unrequested scope in a filter of a fetched hierarchy bound")
		void shouldRefuseScopeWhenHierarchyBoundFilterDoesNotRequestIt() {
			assertUnrequested(query(
				collection("a"),
				require(entityFetch(hierarchyContent(stopAt(node(filterBy(LIVE_X))))))
			));
		}

		/** A facet summary filter stands aside of the children of its requirement, as every require filter does. */
		@Test
		@DisplayName("Refuse an unrequested scope in a filter of a facet summary")
		void shouldRefuseScopeWhenFacetSummaryFilterDoesNotRequestIt() {
			assertUnrequested(query(
				collection("a"),
				require(facetSummaryOfReference("r", FacetStatisticsDepth.COUNTS, filterBy(entityHaving(LIVE_X))))
			));
		}

		/** The filter of the facet groups is a filter container of its own type. */
		@Test
		@DisplayName("Refuse an unrequested scope in a group filter of a facet summary")
		void shouldRefuseScopeWhenFacetSummaryGroupFilterDoesNotRequestIt() {
			assertUnrequested(query(
				collection("a"),
				require(facetSummaryOfReference(
					"r", FacetStatisticsDepth.COUNTS, filterBy(attributeEquals("y", 1)), filterGroupBy(entityHaving(LIVE_X))
				))
			));
		}

		/** The promise of the documentation holds for a require filter as for any other. */
		@Test
		@DisplayName("Refuse an inert scope in a filter of a facet summary")
		void shouldRefuseInertScopeWhenFacetSummaryFilterHoldsIt() {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(
					query(
						collection("a"),
						require(facetSummaryOfReference(
							"r", FacetStatisticsDepth.COUNTS, filterBy(referenceHaving("s", scope(Scope.LIVE)))
						))
					),
					Set.of(Scope.LIVE)
				)
			);

			assertTrue(
				error.getMessage().startsWith("Constraint `scope(LIVE)` has no effect in `referenceHaving`"),
				error.getMessage()
			);
		}

		/** The narrower reading of a scope in the root of a facet summary filter is left to its translator. */
		@Test
		@DisplayName("Accept an unrequested scope the root of a facet summary filter names")
		void shouldAcceptScopeWhenRootOfFacetSummaryFilterNamesIt() {
			assertDoesNotThrow(() -> QueryUtils.assertFilterScopesApplicable(
				query(
					collection("a"),
					require(facetSummaryOfReference(
						"r", FacetStatisticsDepth.COUNTS, filterBy(scope(Scope.LIVE), entityHaving(LIVE_X))
					))
				),
				Set.of(Scope.ARCHIVED)
			));
		}

		/** A query naming no filter has nothing to judge. */
		@Test
		@DisplayName("Accept a query without filter and requirements")
		void shouldAcceptQueryWhenItHasNoFilterAndNoRequirements() {
			assertDoesNotThrow(
				() -> QueryUtils.assertFilterScopesApplicable(query(collection("a")), Set.of(Scope.LIVE))
			);
		}

		/** Pins the public exception and the scope diagnostic of a query requesting the archived scope alone. */
		private void assertUnrequested(@Nonnull Query query) {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(query, Set.of(Scope.ARCHIVED))
			);
			assertEquals(
				"Scope `LIVE` used in `inScope` filter container was not requested by `scope` constraint!",
				error.getMessage()
			);
		}
	}

	/** A `scope(...)` below `not` is refused whatever the scopes, at every entity level that applies a scope. */
	@Nested
	@DisplayName("Negated scopes")
	class NegatedScopes {
		/** The ways a negation can reach a scope within one entity level. */
		private static final List<FilterConstraint> NEGATIONS = List.of(
			not(scope(Scope.ARCHIVED)),
			not(and(scope(Scope.ARCHIVED), attributeEquals("y", 1))),
			not(or(attributeEquals("y", 1), scope(Scope.ARCHIVED))),
			not(not(scope(Scope.ARCHIVED))),
			not(inScope(Scope.LIVE, scope(Scope.ARCHIVED)))
		);

		/** Every level a scope applies at refuses it below `not`, at any depth of that level. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeApplyingLevels")
		@DisplayName("Refuse a scope below not at a level that applies it")
		void shouldRefuseScopeWhenNegatedAtLevelApplyingIt(
			@Nonnull String levelName, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			for (final FilterConstraint negation : NEGATIONS) {
				assertNegated(query(collection("a"), filterBy(level.apply(negation))), scope(Scope.ARCHIVED));
			}
		}

		/** A negated container starting an entity level of its own negates the container, not its scope. */
		@Test
		@DisplayName("Accept a scope of an entity level the negation encloses")
		void shouldAcceptScopeWhenNegatedContainerStartsOwnEntityLevel() {
			for (final FilterConstraint filter : List.of(
				not(entityHaving(scope(Scope.ARCHIVED))),
				not(groupHaving(and(scope(Scope.ARCHIVED), attributeEquals("y", 1)))),
				not(referenceHaving("r", entityHaving(and(scope(Scope.ARCHIVED), attributeEquals("y", 1))))),
				not(facetHaving("r", groupHaving(scope(Scope.ARCHIVED)))),
				not(hierarchyWithin("r", referenceHaving("s", entityHaving(scope(Scope.ARCHIVED)))))
			)) {
				assertDoesNotThrow(
					() -> QueryUtils.assertScopePlacementValid(query(collection("a"), filterBy(filter))),
					filter::toString
				);
			}
		}

		/** A scope beside a negation and a negation without a scope are what the check must let through. */
		@Test
		@DisplayName("Accept a scope beside not and a not without a scope")
		void shouldAcceptScopeWhenBesideNegation() {
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query(
				collection("a"), filterBy(scope(Scope.ARCHIVED), not(attributeEquals("y", 1)))
			)));
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query(
				collection("a"),
				orderBy(segments(segment(
					entityHaving(and(scope(Scope.ARCHIVED), not(attributeEquals("y", 1)))),
					orderBy(attributeNatural("y"))
				)))
			)));
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query(collection("a"))));
		}

		/** The lookup of the scope of the query fails on two scopes, which the check needs no lookup to refuse. */
		@Test
		@DisplayName("Refuse a negated scope beside another scope of the same level")
		void shouldRefuseScopeWhenNegatedBesideAnotherScope() {
			final Query query = query(
				collection("a"), filterBy(scope(Scope.LIVE, Scope.ARCHIVED), not(scope(Scope.ARCHIVED)))
			);

			assertNegated(query, scope(Scope.ARCHIVED));
			assertNegated(
				query(
					collection("a"),
					filterBy(entityHaving(and(
						scope(Scope.LIVE, Scope.ARCHIVED), not(scope(Scope.ARCHIVED)), LIVE_X
					)))
				),
				scope(Scope.ARCHIVED)
			);
		}

		/**
		 * A negated scope a container would not apply at all gets the diagnostic naming the container, the same in the
		 * order section, which only this walk reaches, as in the filter section.
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeRefusingContainers")
		@DisplayName("Refuse a negated scope a container would not apply as having no effect")
		void shouldRefuseScopeAsInertWhenNegatedInContainerNotApplyingIt(
			@Nonnull String container, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			final FilterBy filter = filterBy(not(level.apply(not(scope(Scope.LIVE)))));
			final String inert = "Constraint `scope(LIVE)` has no effect in `" + container + "` - it selects scopes " +
				"only in the root of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or " +
				"removed.";

			for (final Query query : List.of(
				query(collection("a"), filter),
				query(
					collection("a"),
					orderBy(segments(segment(
						entityHaving(level.apply(not(scope(Scope.LIVE)))), orderBy(attributeNatural("y"))
					)))
				)
			)) {
				final EvitaInvalidUsageException error = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> QueryUtils.assertScopePlacementValid(query),
					query::toString
				);
				assertEquals(inert, error.getMessage());
			}
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(filter, Set.of(Scope.values()))
			);
			assertEquals(inert, error.getMessage());
		}

		/** Every filter nested in the order and the require section is checked, at any depth. */
		@Test
		@DisplayName("Refuse a scope below not in a filter of the order or the require section")
		void shouldRefuseScopeWhenNegatedInNestedFilter() {
			final FilterConstraint negation = not(scope(Scope.ARCHIVED));
			for (final Query query : List.of(
				query(
					collection("a"),
					orderBy(segments(
						segment(orderBy(attributeNatural("y"))),
						segment(entityHaving(negation), orderBy(attributeNatural("y")))
					))
				),
				query(collection("a"), require(entityFetch(referenceContent("r", filterBy(negation))))),
				query(
					collection("a"),
					require(entityFetch(referenceContent(
						"r", entityFetch(referenceContent("s", filterBy(entityHaving(negation))))
					)))
				),
				query(collection("a"), require(entityFetch(hierarchyContent(stopAt(node(filterBy(negation))))))),
				query(
					collection("a"),
					require(facetSummaryOfReference("r", FacetStatisticsDepth.COUNTS, filterBy(negation)))
				),
				query(
					collection("a"),
					require(facetSummaryOfReference(
						"r", FacetStatisticsDepth.COUNTS, filterBy(attributeEquals("y", 1)), filterGroupBy(negation)
					))
				),
				query(
					collection("a"),
					require(referenceSummaryOfReference("r", FacetStatisticsDepth.COUNTS, filterBy(negation)))
				),
				query(
					collection("a"),
					require(referenceSummaryOfReference(
						"r", FacetStatisticsDepth.COUNTS, filterBy(attributeEquals("y", 1)), filterGroupBy(negation)
					))
				),
				query(collection("a"), require(facetGroupsConjunction("r", filterBy(negation))))
			)) {
				assertNegated(query, scope(Scope.ARCHIVED));
			}
		}

		/** Pins the public exception and the diagnostic naming the negated scope. */
		private void assertNegated(@Nonnull Query query, @Nonnull EntityScope negatedScope) {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertScopePlacementValid(query),
				query::toString
			);
			assertEquals(
				"Constraint `" + negatedScope + "` cannot be negated in `not` - it selects the scopes to search in, " +
					"it does not filter entities. Name the scopes you want in `scope(...)` instead.",
				error.getMessage()
			);
		}
	}

	/** A `scope(...)` below `or` is refused whatever the scopes, at every entity level that applies a scope. */
	@Nested
	@DisplayName("Disjunctive scopes")
	class DisjunctiveScopes {
		/** The ways a disjunction can reach a scope within one entity level. */
		private static final List<FilterConstraint> DISJUNCTIONS = List.of(
			or(scope(Scope.ARCHIVED), attributeEquals("y", 1)),
			or(and(scope(Scope.ARCHIVED), attributeEquals("y", 1)), attributeEquals("y", 2)),
			and(or(attributeEquals("y", 1), scope(Scope.ARCHIVED)), attributeEquals("y", 2)),
			or(attributeEquals("y", 2), and(attributeEquals("y", 1), or(scope(Scope.ARCHIVED)))),
			or(inScope(Scope.LIVE, scope(Scope.ARCHIVED)), attributeEquals("y", 1))
		);

		/** Every level a scope applies at refuses it below `or`, at any depth of that level. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeApplyingLevels")
		@DisplayName("Refuse a scope below or at a level that applies it")
		void shouldRefuseScopeWhenDisjoinedAtLevelApplyingIt(
			@Nonnull String levelName, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			for (final FilterConstraint disjunction : DISJUNCTIONS) {
				assertDisjoined(query(collection("a"), filterBy(level.apply(disjunction))), scope(Scope.ARCHIVED));
			}
		}

		/** A container starting its own entity level selects that level's scopes even when enclosed by or. */
		@Test
		@DisplayName("Accept a scope of an entity level the disjunction encloses")
		void shouldAcceptScopeWhenDisjoinedContainerStartsOwnEntityLevel() {
			for (final FilterConstraint filter : List.of(
				or(entityHaving(scope(Scope.ARCHIVED)), attributeEquals("y", 2)),
				or(groupHaving(and(scope(Scope.ARCHIVED), attributeEquals("y", 1))), attributeEquals("y", 2)),
				or(
					referenceHaving("r", entityHaving(and(scope(Scope.ARCHIVED), attributeEquals("y", 1)))),
					attributeEquals("y", 2)
				),
				or(facetHaving("r", groupHaving(scope(Scope.ARCHIVED))), attributeEquals("y", 2)),
				or(
					hierarchyWithin("r", referenceHaving("s", entityHaving(scope(Scope.ARCHIVED)))),
					attributeEquals("y", 2)
				)
			)) {
				assertAccepted(filter);
			}
		}

		/** A scope beside a disjunction and a disjunction without a scope are what the check must let through. */
		@Test
		@DisplayName("Accept a scope beside or and an or without a scope")
		void shouldAcceptScopeWhenBesideDisjunction() {
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query(
				collection("a"), filterBy(scope(Scope.ARCHIVED), or(attributeEquals("y", 1), attributeEquals("y", 2)))
			)));
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query(
				collection("a"),
				orderBy(segments(segment(
					entityHaving(and(scope(Scope.ARCHIVED), or(attributeEquals("y", 1), attributeEquals("y", 2)))),
					orderBy(attributeNatural("y"))
				)))
			)));
			assertDoesNotThrow(() -> QueryUtils.assertScopePlacementValid(query(collection("a"))));
			for (final FilterConstraint filter : List.of(
				scope(Scope.ARCHIVED),
				and(and(scope(Scope.ARCHIVED), attributeEquals("y", 1))),
				and(scope(Scope.LIVE, Scope.ARCHIVED), inScope(Scope.LIVE, attributeEquals("y", 1)))
			)) {
				assertAccepted(filter);
			}
		}

		/**
		 * An `or` of a single child joins nothing: the normalization of a query flattens it into that child, and the
		 * check reads it the same way on the paths that are not normalized.
		 */
		@Test
		@DisplayName("Accept a scope in an or of a single child, which joins nothing")
		void shouldAcceptScopeWhenOrHasSingleChild() {
			for (final FilterConstraint filter : List.of(
				or(scope(Scope.ARCHIVED)),
				or(and(scope(Scope.ARCHIVED), attributeEquals("y", 1))),
				or(or(scope(Scope.ARCHIVED))),
				entityHaving(or(scope(Scope.ARCHIVED)))
			)) {
				assertAccepted(filter);
			}
		}

		/** The lookup of the scope of the query fails on two scopes, which the check needs no lookup to refuse. */
		@Test
		@DisplayName("Refuse a disjoined scope beside another scope of the same level")
		void shouldRefuseScopeWhenDisjoinedBesideAnotherScope() {
			final Query query = query(
				collection("a"),
				filterBy(scope(Scope.LIVE, Scope.ARCHIVED), or(scope(Scope.ARCHIVED), attributeEquals("y", 1)))
			);

			assertDisjoined(query, scope(Scope.ARCHIVED));
			assertDisjoined(
				query(collection("a"), filterBy(or(scope(Scope.LIVE), scope(Scope.ARCHIVED)))), scope(Scope.LIVE)
			);
			assertDisjoined(
				query(
					collection("a"),
					filterBy(entityHaving(and(
						scope(Scope.LIVE, Scope.ARCHIVED), or(scope(Scope.ARCHIVED), attributeEquals("y", 1)), LIVE_X
					)))
				),
				scope(Scope.ARCHIVED)
			);
		}

		/**
		 * A disjoined scope a container would not apply at all gets the diagnostic naming the container, the
		 * same in the order section, which only this walk reaches, as in the filter section.
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#scopeRefusingContainers")
		@DisplayName("Refuse a disjoined scope a container would not apply as having no effect")
		void shouldRefuseScopeAsInertWhenDisjoinedInContainerNotApplyingIt(
			@Nonnull String container, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			final FilterConstraint disjunction = or(scope(Scope.LIVE), attributeEquals("y", 1));
			final FilterBy filter = filterBy(or(level.apply(disjunction)));
			final String inert = "Constraint `scope(LIVE)` has no effect in `" + container + "` - it selects scopes " +
				"only in the root of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or " +
				"removed.";

			for (final Query query : List.of(
				query(collection("a"), filter),
				query(
					collection("a"),
					orderBy(segments(segment(entityHaving(level.apply(disjunction)), orderBy(attributeNatural("y")))))
				)
			)) {
				final EvitaInvalidUsageException error = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> QueryUtils.assertScopePlacementValid(query),
					query::toString
				);
				assertEquals(inert, error.getMessage());
			}
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertFilterScopesApplicable(filter, Set.of(Scope.values()))
			);
			assertEquals(inert, error.getMessage());
		}

		/** Every filter nested in the order and the require section is checked, at any depth. */
		@Test
		@DisplayName("Refuse a scope below or in a filter of the order or the require section")
		void shouldRefuseScopeWhenDisjoinedInNestedFilter() {
			final FilterConstraint disjunction = or(scope(Scope.ARCHIVED), attributeEquals("y", 1));
			for (final Query query : List.of(
				query(
					collection("a"),
					orderBy(segments(
						segment(orderBy(attributeNatural("y"))),
						segment(entityHaving(disjunction), orderBy(attributeNatural("y")))
					))
				),
				query(collection("a"), require(entityFetch(referenceContent("r", filterBy(disjunction))))),
				query(
					collection("a"),
					require(entityFetch(referenceContent(
						"r", entityFetch(referenceContent("s", filterBy(entityHaving(disjunction))))
					)))
				),
				query(collection("a"), require(entityFetch(hierarchyContent(stopAt(node(filterBy(disjunction))))))),
				query(
					collection("a"),
					require(facetSummaryOfReference("r", FacetStatisticsDepth.COUNTS, filterBy(disjunction)))
				),
				query(
					collection("a"),
					require(facetSummaryOfReference(
						"r", FacetStatisticsDepth.COUNTS, filterBy(attributeEquals("y", 1)), filterGroupBy(disjunction)
					))
				),
				query(
					collection("a"),
					require(referenceSummaryOfReference("r", FacetStatisticsDepth.COUNTS, filterBy(disjunction)))
				),
				query(
					collection("a"),
					require(referenceSummaryOfReference(
						"r", FacetStatisticsDepth.COUNTS, filterBy(attributeEquals("y", 1)), filterGroupBy(disjunction)
					))
				),
				query(collection("a"), require(facetGroupsConjunction("r", filterBy(disjunction)))),
				query(collection("a"), require(facetGroupsDisjunction("r", filterBy(disjunction)))),
				query(collection("a"), require(facetGroupsNegation("r", filterBy(disjunction)))),
				query(collection("a"), require(facetGroupsExclusivity("r", filterBy(disjunction)))),
				query(collection("a"), require(facetSummary(FacetStatisticsDepth.COUNTS, filterBy(disjunction)))),
				query(collection("a"), require(referenceSummary(FacetStatisticsDepth.COUNTS, filterBy(disjunction))))
			)) {
				assertDisjoined(query, scope(Scope.ARCHIVED));
			}
		}

		/** Asserts that the check lets the filter through, naming the filter when it does not. */
		private void assertAccepted(@Nonnull FilterConstraint filter) {
			assertDoesNotThrow(
				() -> QueryUtils.assertScopePlacementValid(query(collection("a"), filterBy(filter))),
				filter::toString
			);
		}

		/** Pins the public exception and the diagnostic naming the disjoined scope. */
		private void assertDisjoined(@Nonnull Query query, @Nonnull EntityScope disjoinedScope) {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertScopePlacementValid(query),
				query::toString
			);
			assertEquals(
				"Constraint `" + disjoinedScope + "` cannot be placed in `or` - it selects the scopes to search " +
					"in, it does not filter entities. Name the scopes you want in `scope(...)` and put a " +
					"condition that holds in one scope only in `inScope(...)`.",
				error.getMessage()
			);
		}

		/** In both nesting orders the diagnostic of `not` takes precedence over the one of `or`. */
		@Test
		@DisplayName("Prefer not over or within the same entity level")
		void shouldPreferNegationWhenBothContainersEncloseScope() {
			for (final FilterConstraint filter : List.of(
				not(or(scope(Scope.ARCHIVED), attributeEquals("y", 1))),
				or(not(scope(Scope.ARCHIVED)), attributeEquals("y", 1))
			)) {
				assertRefusedAsNegated(filter);
			}
		}

		/**
		 * An `or` of a single child is no disjunction, so it neither lifts the refusal of a scope below `not` nor
		 * changes its diagnostic, whichever of the two encloses the other.
		 */
		@Test
		@DisplayName("Refuse a negated scope in an or of a single child with the not diagnostic")
		void shouldRefuseScopeAsNegatedWhenOrHasSingleChild() {
			for (final FilterConstraint filter : List.of(
				not(or(scope(Scope.ARCHIVED))),
				or(not(scope(Scope.ARCHIVED)))
			)) {
				assertRefusedAsNegated(filter);
			}
		}

		/** Pins the public exception and the diagnostic of `not` for `scope(ARCHIVED)` in the filter. */
		private void assertRefusedAsNegated(@Nonnull FilterConstraint filter) {
			final EvitaInvalidUsageException error = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertScopePlacementValid(query(collection("a"), filterBy(filter))),
				filter::toString
			);
			assertEquals(
				"Constraint `scope(ARCHIVED)` cannot be negated in `not` - it selects the scopes to " +
					"search in, it does not filter entities. Name the scopes you want in `scope(...)` instead.",
				error.getMessage()
			);
		}
	}

	/**
	 * Supplies every container that separates an entity level without reading a `scope(...)` of its own, each wrapping
	 * the passed constraint as its child.
	 *
	 * @return the name the container reports and the function wrapping a constraint into it
	 */
	@Nonnull
	static Stream<Arguments> scopeRefusingContainers() {
		return Stream.of(
			Arguments.of(
				"hierarchyWithin", (Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithin("r", it)
			),
			Arguments.of(
				"hierarchyWithinSelf", (Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithinSelf(it)
			),
			Arguments.of(
				"hierarchyWithin",
				(Function<FilterConstraint, FilterConstraint>) it ->
					hierarchyWithin("r", entityPrimaryKeyInSet(1), having(it))
			),
			Arguments.of(
				"hierarchyWithin",
				(Function<FilterConstraint, FilterConstraint>) it ->
					hierarchyWithin("r", entityPrimaryKeyInSet(1), excluding(it))
			),
			Arguments.of(
				"hierarchyWithinRoot",
				(Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithinRoot("r", having(it))
			),
			Arguments.of(
				"hierarchyWithinRoot",
				(Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithinRoot("r", excluding(it))
			),
			Arguments.of(
				"hierarchyWithinRootSelf",
				(Function<FilterConstraint, FilterConstraint>) it -> hierarchyWithinRootSelf(having(it))
			),
			Arguments.of(
				"referenceHaving", (Function<FilterConstraint, FilterConstraint>) it -> referenceHaving("r", it)
			),
			Arguments.of(
				"referenceHaving",
				(Function<FilterConstraint, FilterConstraint>) it ->
					referenceHaving("r", and(attributeEquals("y", 1), it))
			),
			Arguments.of("facetHaving", (Function<FilterConstraint, FilterConstraint>) it -> facetHaving("r", it)),
			Arguments.of(
				"includingChildrenHaving",
				(Function<FilterConstraint, FilterConstraint>) it -> facetHaving("r", includingChildrenHaving(it))
			),
			Arguments.of(
				"includingChildrenExcept",
				(Function<FilterConstraint, FilterConstraint>) it -> facetHaving("r", includingChildrenExcept(it))
			)
		);
	}

	/**
	 * Supplies every level a `scope(...)` applies at, each wrapping the passed constraint.
	 *
	 * @return the description of the level and the function wrapping a constraint into it
	 */
	@Nonnull
	static Stream<Arguments> scopeApplyingLevels() {
		return Stream.of(
			Arguments.of("root", (Function<FilterConstraint, FilterConstraint>) it -> it),
			Arguments.of(
				"and below the root",
				(Function<FilterConstraint, FilterConstraint>) it -> and(it, attributeEquals("y", 1))
			),
			Arguments.of(
				"or below the root",
				(Function<FilterConstraint, FilterConstraint>) it -> or(it, attributeEquals("y", 1))
			),
			Arguments.of("entityHaving", (Function<FilterConstraint, FilterConstraint>) it -> entityHaving(it)),
			Arguments.of("groupHaving", (Function<FilterConstraint, FilterConstraint>) it -> groupHaving(it)),
			Arguments.of(
				"and in entityHaving",
				(Function<FilterConstraint, FilterConstraint>) it -> entityHaving(and(it, attributeEquals("y", 1)))
			),
			Arguments.of(
				"entityHaving in referenceHaving",
				(Function<FilterConstraint, FilterConstraint>) it -> referenceHaving("r", entityHaving(it))
			),
			Arguments.of(
				"groupHaving in facetHaving",
				(Function<FilterConstraint, FilterConstraint>) it -> facetHaving("r", groupHaving(it))
			),
			Arguments.of(
				"entityHaving below a hierarchy",
				(Function<FilterConstraint, FilterConstraint>) it ->
					hierarchyWithin("r", referenceHaving("s", entityHaving(it)))
			)
		);
	}

	/**
	 * Covers own opinions independently from the caller's inheritance policy.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Entity-level locale resolver")
	class LocaleTest {
		/** A level without any locale constraint has no opinion of its own. */
		@Test
		@DisplayName("Returns no locale when the level names none")
		void shouldReturnNullWhenNoOpinion() {
			assertNull(QueryUtils.resolveLocale(null), "A missing filter has no opinion");
			assertNull(QueryUtils.resolveLocale(filterBy(attributeEquals("code", "a"))),
				"A filter without a locale constraint has no opinion");
		}

		/** Identical opinions of one level coalesce into a single locale. */
		@Test
		@DisplayName("Coalesces own locale opinions")
		void shouldCoalesceWhenOpinionsEqual() {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(entityLocaleEquals(Locale.ENGLISH))),
				"A single opinion is the level's locale");
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH))),
				"A repeated opinion coalesces");
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(entityLocaleEquals(Locale.ENGLISH),
				referenceHaving("brand", entityLocaleEquals(Locale.ENGLISH)))),
				"An equal opinion in a reference container coalesces with the owner's");
		}

		/** Each container whose children filter a different entity hides their opinion from the enclosing level. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.query.QueryUtilsTest#differentEntityContainers")
		@DisplayName("Stops at a container whose children filter a different entity")
		void shouldStopWhenChildrenFilterDifferentEntity(
			@Nonnull String container, @Nonnull Function<FilterConstraint, FilterConstraint> level
		) {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				entityLocaleEquals(Locale.ENGLISH), level.apply(entityLocaleEquals(Locale.FRENCH)))),
				"The nested French opinion must not conflict with the enclosing English one");
			assertNull(QueryUtils.resolveLocale(filterBy(level.apply(entityLocaleEquals(Locale.FRENCH)))),
				"A nested-only opinion must not become the enclosing level's locale");
		}

		/** Reference and facet containers keep their direct children at the owner's level. */
		@Test
		@DisplayName("Descends into reference and facet containers")
		void shouldDescendWhenContainerDoesNotStartLevel() {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				referenceHaving("brand", entityLocaleEquals(Locale.ENGLISH)))));
			assertEquals(Locale.ENGLISH, QueryUtils.resolveLocale(filterBy(
				facetHaving("brand", entityLocaleEquals(Locale.ENGLISH)))));
		}

		/** An opinion under a reference or facet container belongs to the owner and so conflicts with it. */
		@ParameterizedTest(name = "facet: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Rejects a reference or facet opinion differing from its owner")
		void shouldRejectWhenReferenceOrFacetOpinionConflictsWithOwner(boolean facet) {
			final FilterBy filter = filterBy(entityLocaleEquals(Locale.ENGLISH), facet ?
				facetHaving("brand", entityLocaleEquals(Locale.FRENCH)) :
				referenceHaving("brand", entityLocaleEquals(Locale.FRENCH)));
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> QueryUtils.resolveLocale(filter));
			assertEquals("Conflicting locales `en` and `fr` at one entity level in constraint `" + filter + "`.",
				error.getMessage());
		}

		/** A conflicting disjunction still refers to one entity level. */
		@Test
		@DisplayName("Reports conflicting locales and the filter")
		void shouldRejectWhenOpinionsConflict() {
			final FilterBy filter = filterBy(or(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.FRENCH)));
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> QueryUtils.resolveLocale(filter));
			assertEquals("Conflicting locales `en` and `fr` at one entity level in constraint `" + filter + "`.",
				error.getMessage());
		}
	}

	/**
	 * Covers the chain resolving the locale of a referenced entity or group body: the body level's own opinion, then
	 * the reference level's, then the enclosing level's locale.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Reference body locale resolver")
	class ReferenceBodyLocaleTest {

		/** Without a reference filter the body inherits the enclosing level's locale. */
		@ParameterizedTest(name = "group: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Inherits the enclosing locale when the reference has no filter")
		void shouldInheritEnclosingLocaleWhenReferenceFilterMissing(boolean group) {
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(null, group, Locale.GERMAN));
			assertNull(QueryUtils.resolveReferenceBodyLocale(null, group, null),
				"Without any level naming a locale the body has none");
		}

		/** Each level decides only when the levels inside it have no opinion. */
		@ParameterizedTest(name = "group: {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Prefers the body opinion, then the reference opinion, then the enclosing locale")
		void shouldPreferInnermostOpinionWhenLevelsDiffer(boolean group) {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveReferenceBodyLocale(
				filterBy(entityLocaleEquals(Locale.FRENCH), body(group, entityLocaleEquals(Locale.ENGLISH))),
				group, Locale.GERMAN
			), "The body's own opinion wins over the reference level and the enclosing level");
			assertEquals(Locale.FRENCH, QueryUtils.resolveReferenceBodyLocale(
				filterBy(entityLocaleEquals(Locale.FRENCH), body(group, attributeEquals("code", "a"))),
				group, Locale.GERMAN
			), "The reference level's opinion wins over the enclosing level");
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(
				filterBy(attributeEquals("order", 1), body(group, attributeEquals("code", "a"))),
				group, Locale.GERMAN
			), "A body without any opinion inherits the enclosing level's locale");
		}

		/** The referenced entity and its group are separate levels, so neither inherits the other's opinion. */
		@Test
		@DisplayName("Keeps the entity and group levels apart")
		void shouldNotShareOpinionBetweenEntityAndGroup() {
			final FilterBy both = filterBy(
				entityHaving(entityLocaleEquals(Locale.ENGLISH)), groupHaving(entityLocaleEquals(Locale.FRENCH))
			);
			final FilterBy entityOnly = filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH)));

			assertEquals(Locale.ENGLISH, QueryUtils.resolveReferenceBodyLocale(both, false, Locale.GERMAN));
			assertEquals(Locale.FRENCH, QueryUtils.resolveReferenceBodyLocale(both, true, Locale.GERMAN));
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(entityOnly, true, Locale.GERMAN),
				"The group must not inherit the referenced entity's opinion");
		}

		/** A body container below a nested reference describes the target of that other reference. */
		@Test
		@DisplayName("Ignores a body container nested in another reference")
		void shouldIgnoreBodyWhenNestedInAnotherReference() {
			assertEquals(Locale.GERMAN, QueryUtils.resolveReferenceBodyLocale(
				filterBy(referenceHaving("brand", entityHaving(entityLocaleEquals(Locale.ENGLISH)))),
				false, Locale.GERMAN
			));
		}

		/** Several body containers of one reference form a single level whose opinions must agree. */
		@Test
		@DisplayName("Treats several body containers as one level")
		void shouldCoalesceOrRejectWhenSeveralBodyContainersHaveOpinions() {
			assertEquals(Locale.ENGLISH, QueryUtils.resolveReferenceBodyLocale(
				filterBy(
					entityHaving(entityLocaleEquals(Locale.ENGLISH)), entityHaving(entityLocaleEquals(Locale.ENGLISH))
				),
				false, Locale.GERMAN
			), "Equal opinions of one body level coalesce");
			assertThrows(EvitaInvalidUsageException.class, () -> QueryUtils.resolveReferenceBodyLocale(
				filterBy(
					entityHaving(entityLocaleEquals(Locale.ENGLISH)), entityHaving(entityLocaleEquals(Locale.FRENCH))
				),
				false, Locale.GERMAN
			), "Different opinions of one body level conflict");
		}

		/** The reference level is always resolved, so its conflict surfaces even when the body decides. */
		@Test
		@DisplayName("Rejects a reference level conflict even when the body names a locale")
		void shouldRejectWhenReferenceLevelConflictsAndBodyDecides() {
			assertThrows(EvitaInvalidUsageException.class, () -> QueryUtils.resolveReferenceBodyLocale(
				filterBy(
					or(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.FRENCH)),
					entityHaving(entityLocaleEquals(Locale.GERMAN))
				),
				false, null
			));
		}

		/**
		 * Wraps the children into the container of the body level under test.
		 *
		 * @param group    true for the group body, false for the referenced entity body
		 * @param children the filter of the body level
		 * @return `groupHaving` or `entityHaving` holding the children
		 */
		@Nonnull
		private FilterConstraint body(boolean group, @Nonnull FilterConstraint... children) {
			return group ? groupHaving(children) : entityHaving(children);
		}
	}

	@Nested
	@DisplayName("Find constraint")
	class FindConstraintTest {

		@Test
		@DisplayName("should find constraint by type")
		void shouldFindConstraintByType() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final AttributeEquals found = QueryUtils.findConstraint(filterBy, AttributeEquals.class);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}

		@Test
		@DisplayName("should find constraint by predicate")
		void shouldFindConstraintByPredicate() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"), attributeEquals("name", "xyz"))
			);
			final Constraint<?> found = QueryUtils.findConstraint(
				filterBy,
				c -> c instanceof AttributeEquals && "name".equals(c.getArguments()[0])
			);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null when constraint not found")
		void shouldReturnNullWhenNotFound() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final Page found = QueryUtils.findConstraint(filterBy, Page.class);
			assertNull(found);
		}

		@Test
		@DisplayName("should respect stop container type")
		void shouldRespectStopContainerType() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					or(
						attributeEquals("name", "xyz")
					)
				)
			);
			// Looking for AttributeEquals but stopping at Or - should find "code" not "name"
			final AttributeEquals found = QueryUtils.findConstraint(
				filterBy, AttributeEquals.class, Or.class
			);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}
	}

	@Nested
	@DisplayName("Find constraints (list)")
	class FindConstraintsTest {

		@Test
		@DisplayName("should find all constraints of type")
		void shouldFindAllOfType() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					attributeEquals("name", "xyz")
				)
			);
			final List<AttributeEquals> found = QueryUtils.findConstraints(filterBy, AttributeEquals.class);
			assertEquals(2, found.size());
		}

		@Test
		@DisplayName("should return empty list when none found")
		void shouldReturnEmptyListWhenNoneFound() {
			final FilterBy filterBy = filterBy(
				and(attributeEquals("code", "abc"))
			);
			final List<Page> found = QueryUtils.findConstraints(filterBy, Page.class);
			assertTrue(found.isEmpty());
		}

		@Test
		@DisplayName("should find constraints with stop container")
		void shouldFindConstraintsWithStopContainer() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					or(
						attributeEquals("name", "xyz")
					)
				)
			);
			// Stop at Or - should find only "code"
			final List<AttributeEquals> found = QueryUtils.findConstraints(
				filterBy, AttributeEquals.class, Or.class
			);
			assertEquals(1, found.size());
			assertEquals("code", found.getFirst().getArguments()[0]);
		}

		@Test
		@DisplayName("should find constraints by predicate")
		void shouldFindConstraintsByPredicate() {
			final FilterBy filterBy = filterBy(
				and(
					attributeEquals("code", "abc"),
					attributeEquals("name", "xyz")
				)
			);
			final List<Constraint<?>> found = QueryUtils.findConstraints(
				filterBy,
				c -> c instanceof AttributeEquals
			);
			assertEquals(2, found.size());
		}
	}

	@Nested
	@DisplayName("Find filter")
	class FindFilterTest {

		@Test
		@DisplayName("should find filter constraint from query")
		void shouldFindFilterFromQuery() {
			final Query q = query(
				collection("product"),
				filterBy(attributeEquals("code", "abc"))
			);
			final AttributeEquals found = QueryUtils.findFilter(q, AttributeEquals.class);
			assertNotNull(found);
			assertEquals("code", found.getArguments()[0]);
		}

		@Test
		@DisplayName("should return null for query without filter")
		void shouldReturnNullForQueryWithoutFilter() {
			final Query q = query(collection("product"));
			final AttributeEquals found = QueryUtils.findFilter(q, AttributeEquals.class);
			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Find order")
	class FindOrderTest {

		@Test
		@DisplayName("should find order constraint from query")
		void shouldFindOrderFromQuery() {
			final Query q = query(
				collection("product"),
				orderBy(attributeNatural("name"))
			);
			final AttributeNatural found = QueryUtils.findOrder(q, AttributeNatural.class);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null for query without order")
		void shouldReturnNullForQueryWithoutOrder() {
			final Query q = query(collection("product"));
			final AttributeNatural found = QueryUtils.findOrder(q, AttributeNatural.class);
			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Find require")
	class FindRequireTest {

		@Test
		@DisplayName("should find require constraint from query")
		void shouldFindRequireFromQuery() {
			final Query q = query(
				collection("product"),
				require(page(1, 5))
			);
			final Page found = QueryUtils.findRequire(q, Page.class);
			assertNotNull(found);
		}

		@Test
		@DisplayName("should return null for query without require")
		void shouldReturnNullForQueryWithoutRequire() {
			final Query q = query(collection("product"));
			final Page found = QueryUtils.findRequire(q, Page.class);
			assertNull(found);
		}

		@Test
		@DisplayName("should find all requires of type")
		void shouldFindAllRequiresOfType() {
			final Query q = query(
				collection("product"),
				require(page(1, 5))
			);
			final List<Page> found = QueryUtils.findRequires(q, Page.class);
			assertEquals(1, found.size());
		}

		@Test
		@DisplayName("should return empty list for missing require")
		void shouldReturnEmptyListForMissingRequire() {
			final Query q = query(collection("product"));
			final List<Page> found = QueryUtils.findRequires(q, Page.class);
			assertTrue(found.isEmpty());
		}
	}

	@Nested
	@DisplayName("Value differs")
	class ValueDiffersTest {

		@Test
		@DisplayName("should return false for null vs null")
		void shouldReturnFalseForNullVsNull() {
			assertFalse(QueryUtils.valueDiffers(null, null));
		}

		@Test
		@DisplayName("should return true for null vs value")
		void shouldReturnTrueForNullVsValue() {
			assertTrue(QueryUtils.valueDiffers(null, "abc"));
		}

		@Test
		@DisplayName("should return true for value vs null")
		void shouldReturnTrueForValueVsNull() {
			assertTrue(QueryUtils.valueDiffers("abc", null));
		}

		@Test
		@DisplayName("should use compareTo for BigDecimal")
		void shouldUseCompareToForBigDecimal() {
			// BigDecimal: 10.0 equals 10.00 via compareTo, but not via equals
			assertFalse(QueryUtils.valueDiffers(new BigDecimal("10.0"), new BigDecimal("10.00")));
		}

		@Test
		@DisplayName("should return true for different BigDecimals")
		void shouldReturnTrueForDifferentBigDecimals() {
			assertTrue(QueryUtils.valueDiffers(new BigDecimal("10.0"), new BigDecimal("20.0")));
		}

		@Test
		@DisplayName("should use equals for non-comparable types")
		void shouldUseEqualsForNonComparableTypes() {
			assertFalse(QueryUtils.valueDiffers("abc", "abc"));
			assertTrue(QueryUtils.valueDiffers("abc", "xyz"));
		}

		@Test
		@DisplayName("should compare arrays element by element")
		void shouldCompareArraysElementByElement() {
			assertFalse(QueryUtils.valueDiffers(
				new String[]{"a", "b"},
				new String[]{"a", "b"}
			));
		}

		@Test
		@DisplayName("should detect different array lengths")
		void shouldDetectDifferentArrayLengths() {
			assertTrue(QueryUtils.valueDiffers(
				new String[]{"a"},
				new String[]{"a", "b"}
			));
		}

		@Test
		@DisplayName("should detect different array elements")
		void shouldDetectDifferentArrayElements() {
			assertTrue(QueryUtils.valueDiffers(
				new String[]{"a", "b"},
				new String[]{"a", "c"}
			));
		}

		@Test
		@DisplayName("should return true for array vs non-array")
		void shouldReturnTrueForArrayVsNonArray() {
			assertTrue(QueryUtils.valueDiffers(new String[]{"a"}, "a"));
		}
	}

	@Nested
	@DisplayName("Assert no nested scope containers")
	class AssertNoNestedScopeContainersTest {

		@Test
		@DisplayName("should reject filter inScope of the other scope nested directly")
		void shouldRejectFilterInScopeOfOtherScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals("code", "a"))))
					)
				)
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
			assertTrue(exception.getMessage().contains("`LIVE`"), exception.getMessage());
			assertTrue(exception.getMessage().contains("`ARCHIVED`"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject filter inScope of the same scope nested directly")
		void shouldRejectFilterInScopeOfSameScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(inScope(Scope.ARCHIVED, inScope(Scope.ARCHIVED, attributeEquals("code", "a"))))
					)
				)
			);

			assertTrue(exception.getMessage().contains("redundant"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject filter inScope nested deeper in logical containers")
		void shouldRejectFilterInScopeNestedDeeperInLogicalContainers() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								entityPrimaryKeyInSet(1),
								and(not(inScope(Scope.ARCHIVED, attributeEquals("code", "a"))))
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should reject filter inScope nested in the body of referenceHaving")
		void shouldRejectFilterInScopeNestedInReferenceHavingBody() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								referenceHaving("brand", inScope(Scope.ARCHIVED, attributeEquals("order", 1)))
							)
						)
					)
				)
			);
		}

		/** A facet's direct body remains in the owner's scope context just like a reference filter. */
		@Test
		@DisplayName("rejects nested inScope in the direct body of facetHaving")
		void shouldRejectNestedScopeWhenFacetBodyFiltersOwnerReferences() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(collection("product"), filterBy(inScope(Scope.LIVE,
						facetHaving("brand", inScope(Scope.ARCHIVED, attributeEquals("order", 1))))))),
				"Facet reference filtering must keep the owner's scope context"
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject nesting introduced by copying a container with new children")
		void shouldRejectNestingIntroducedByCopyingContainerWithNewChildren() {
			final FilterInScope valid = inScope(Scope.LIVE, attributeEquals("code", "a"));
			final FilterConstraint copy = valid.getCopyWithNewChildren(
				new FilterConstraint[]{inScope(Scope.ARCHIVED, attributeEquals("code", "a"))},
				new Constraint<?>[0]
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(query(collection("product"), filterBy(copy)))
			);
		}

		@Test
		@DisplayName("should reject filter nesting within the referenced entity's own context")
		void shouldRejectFilterNestingWithinReferencedEntityContext() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							referenceHaving(
								"brand",
								entityHaving(
									inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should reject filter nesting inside a requirement")
		void shouldRejectFilterNestingInsideRequirement() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						require(
							entityFetch(
								referenceContent(
									"brand",
									filterBy(
										inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeEquals("order", 1)))
									)
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept filter inScope restricting another entity inside an inScope")
		void shouldAcceptFilterInScopeRestrictingAnotherEntityInsideInScope() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								referenceHaving(
									"brand", entityHaving(inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
								),
								hierarchyWithin("categories", inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
							)
						)
					)
				)
			);
		}

		/** The entity body of a facet filter restricts the facet entity, which has scopes of its own. */
		@Test
		@DisplayName("should accept inScope in the entity body of facetHaving inside an inScope")
		void shouldAcceptInScopeInsideEntityHavingBodyOfFacetHaving() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(
								Scope.LIVE,
								facetHaving(
									"brand", entityHaving(inScope(Scope.ARCHIVED, attributeEquals("code", "a")))
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept inScope containers side by side")
		void shouldAcceptInScopeContainersSideBySide() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						filterBy(
							inScope(Scope.LIVE, attributeEquals("code", "a")),
							inScope(Scope.ARCHIVED, attributeEquals("code", "b"))
						),
						orderBy(
							inScope(Scope.LIVE, attributeNatural("code", OrderDirection.ASC)),
							inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.DESC))
						),
						require(
							inScope(Scope.LIVE, facetSummary()),
							inScope(Scope.ARCHIVED, attributeHistogram(10, "code"))
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept a query without any section")
		void shouldAcceptQueryWithoutAnySection() {
			assertDoesNotThrow(() -> QueryUtils.assertNoNestedScopeContainers(query(collection("product"))));
		}

		@Test
		@DisplayName("should reject order inScope of the other scope nested directly")
		void shouldRejectOrderInScopeOfOtherScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(Scope.LIVE, inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.ASC)))
						)
					)
				)
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject order inScope of the same scope nested directly")
		void shouldRejectOrderInScopeOfSameScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(inScope(Scope.LIVE, inScope(Scope.LIVE, attributeNatural("code", OrderDirection.ASC))))
					)
				)
			);

			assertTrue(exception.getMessage().contains("redundant"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject order inScope nested in the body of referenceProperty")
		void shouldRejectOrderInScopeNestedInReferencePropertyBody() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(
								Scope.LIVE,
								referenceProperty(
									"brand", inScope(Scope.ARCHIVED, attributeNatural("order", OrderDirection.ASC))
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept order inScope ordering by the referenced entity's own properties")
		void shouldAcceptOrderInScopeOrderingByReferencedEntityProperties() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(
								Scope.LIVE,
								referenceProperty(
									"brand",
									pickFirstByEntityProperty(
										inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.ASC))
									),
									entityProperty(
										inScope(Scope.ARCHIVED, attributeNatural("name", OrderDirection.DESC))
									)
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should accept order inScope ordering by the referenced group entity's own properties")
		void shouldAcceptOrderInScopeOrderingByReferencedGroupProperties() {
			assertDoesNotThrow(
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						orderBy(
							inScope(
								Scope.LIVE,
								referenceProperty(
									"brand",
									entityGroupProperty(
										inScope(Scope.ARCHIVED, attributeNatural("code", OrderDirection.ASC))
									)
								)
							)
						)
					)
				)
			);
		}

		@Test
		@DisplayName("should reject require inScope of the other scope nested directly")
		void shouldRejectRequireInScopeOfOtherScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						require(inScope(Scope.LIVE, inScope(Scope.ARCHIVED, facetSummary())))
					)
				)
			);

			assertTrue(exception.getMessage().contains("contradictory"), exception.getMessage());
		}

		@Test
		@DisplayName("should reject require inScope of the same scope nested directly")
		void shouldRejectRequireInScopeOfSameScopeNestedDirectly() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> QueryUtils.assertNoNestedScopeContainers(
					query(
						collection("product"),
						require(inScope(Scope.ARCHIVED, inScope(Scope.ARCHIVED, facetSummary())))
					)
				)
			);

			assertTrue(exception.getMessage().contains("redundant"), exception.getMessage());
		}
	}
}
