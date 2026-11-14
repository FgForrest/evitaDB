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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Refuses a `scope(...)` below `or(...)` before scope lookup or query planning, across all query sections.
 * `Item.code` is filterable in both scopes: items 1 and 2 are live, items 3 and 4 archived, with codes a and b
 * respectively. Live owners 10, 20, 30 and 40 reference the corresponding item as both target and group.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("scope(...) below or(...)")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
public class ScopeInDisjunctionFunctionalTest {
	private static final String DATA_SET = "scopeInDisjunction";
	private static final String ENTITY_ITEM = "Item";
	private static final String ENTITY_OWNER = "Owner";
	private static final String REF_ITEM = "item";
	private static final String ATTR_CODE = "code";
	/** The live scope, aliased to one letter so the filters below fit on one line. */
	private static final Scope L = Scope.LIVE;
	/** The archived scope, aliased to one letter so the filters below fit on one line. */
	private static final Scope A = Scope.ARCHIVED;
	/** Matches items 1 and 3, the live and the archived item with `code = a`. */
	private static final FilterConstraint CODE_A = attributeEquals(ATTR_CODE, "a");

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUpDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_ITEM).withoutGeneratedPrimaryKey()
				.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(L, A))
				.updateVia(session);
			session.defineEntitySchema(ENTITY_OWNER).withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					REF_ITEM, ENTITY_ITEM, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.withGroupTypeRelatedToEntity(ENTITY_ITEM).indexedInScope(L).facetedInScope(L)
				)
				.updateVia(session);
			session.createNewEntity(ENTITY_ITEM, 1).setAttribute(ATTR_CODE, "a").upsertVia(session);
			session.createNewEntity(ENTITY_ITEM, 2).setAttribute(ATTR_CODE, "b").upsertVia(session);
			session.createNewEntity(ENTITY_ITEM, 3).setAttribute(ATTR_CODE, "a").upsertVia(session);
			session.createNewEntity(ENTITY_ITEM, 4).setAttribute(ATTR_CODE, "b").upsertVia(session);
			session.archiveEntity(ENTITY_ITEM, 3);
			session.archiveEntity(ENTITY_ITEM, 4);
			session.createNewEntity(ENTITY_OWNER, 10)
				.setReference(REF_ITEM, 1, whichIs -> whichIs.setGroup(ENTITY_ITEM, 1))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 20)
				.setReference(REF_ITEM, 2, whichIs -> whichIs.setGroup(ENTITY_ITEM, 2))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 30)
				.setReference(REF_ITEM, 3, whichIs -> whichIs.setGroup(ENTITY_ITEM, 3))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 40)
				.setReference(REF_ITEM, 4, whichIs -> whichIs.setGroup(ENTITY_ITEM, 4))
				.upsertVia(session);
		});
	}

	/**
	 * Supplies the filters that hold a `scope(...)` below `or` at the level of the queried entity,
	 * each with the entity it queries and the scope the refusal has to name.
	 *
	 * @return the label, the queried entity type, the disjunctive scope and the filter
	 */
	@Nonnull
	static Stream<Arguments> disjunctiveScopeFilters() {
		return Stream.of(
			disjoined("or(scope(ARCHIVED), code=a)", ENTITY_ITEM, scope(A), or(scope(A), CODE_A)),
			disjoined(
				"or(and(scope(ARCHIVED), code=b), code=a)", ENTITY_ITEM, scope(A),
				or(and(scope(A), attributeEquals(ATTR_CODE, "b")), CODE_A)
			),
			disjoined("or(scope(LIVE), code=a)", ENTITY_ITEM, scope(L), or(scope(L), CODE_A)),
			disjoined("or(scope(LIVE, ARCHIVED), code=a)", ENTITY_ITEM, scope(L, A), or(scope(L, A), CODE_A)),
			disjoined("or(scope(LIVE), scope(ARCHIVED))", ENTITY_ITEM, scope(L), or(scope(L), scope(A))),
			disjoined(
				"and(or(scope(ARCHIVED), code=a), code=b)", ENTITY_ITEM, scope(A),
				and(or(scope(A), CODE_A), attributeEquals(ATTR_CODE, "b"))
			),
			disjoined(
				"referenceHaving(item, entityHaving(or(scope(ARCHIVED), code=a)))", ENTITY_OWNER, scope(A),
				referenceHaving(REF_ITEM, entityHaving(or(scope(A), CODE_A)))
			)
		);
	}

	/**
	 * Creates one row of {@link #disjunctiveScopeFilters()}.
	 *
	 * @param label            the filter in readable notation
	 * @param entityType       the queried entity type
	 * @param disjunctiveScope the scope the refusal has to name
	 * @param filter           the children of the filter
	 * @return the arguments of the row
	 */
	@Nonnull
	private static Arguments disjoined(
		@Nonnull String label,
		@Nonnull String entityType,
		@Nonnull EntityScope disjunctiveScope,
		@Nonnull FilterConstraint... filter
	) {
		return Arguments.of(label, entityType, disjunctiveScope, filterBy(filter));
	}

	/**
	 * Supplies the filters that keep their `scope(...)` out of any disjunction of its entity level, each with the
	 * entity it queries and the entities it has to match: a scope in the root or in `and`, an `or` of a single child,
	 * which joins nothing and is read as that child, the `inScope` rewrite of a disjunctive scope, and an `or` joining
	 * a container that starts an entity level of its own.
	 *
	 * @return the label, the queried entity type, the filter and the matched primary keys
	 */
	@Nonnull
	static Stream<Arguments> acceptedScopeFilters() {
		return Stream.of(
			accepted("scope(ARCHIVED), code=a", ENTITY_ITEM, List.of(3), scope(A), CODE_A),
			accepted("and(scope(ARCHIVED), code=a)", ENTITY_ITEM, List.of(3), and(scope(A), CODE_A)),
			accepted("or(scope(ARCHIVED))", ENTITY_ITEM, List.of(3, 4), or(scope(A))),
			accepted("or(and(scope(ARCHIVED), code=a))", ENTITY_ITEM, List.of(3), or(and(scope(A), CODE_A))),
			accepted(
				"scope(LIVE, ARCHIVED), inScope(LIVE, code=a)", ENTITY_ITEM, List.of(1, 3, 4),
				scope(L, A), inScope(L, CODE_A)
			),
			accepted(
				"referenceHaving(item, entityHaving(scope(LIVE, ARCHIVED), inScope(LIVE, code=a)))", ENTITY_OWNER,
				List.of(10, 30, 40), referenceHaving(REF_ITEM, entityHaving(scope(L, A), inScope(L, CODE_A)))
			),
			accepted(
				"or(referenceHaving(item, entityHaving(scope(ARCHIVED))), entityPrimaryKeyInSet(20))", ENTITY_OWNER,
				List.of(20, 30, 40), or(referenceHaving(REF_ITEM, entityHaving(scope(A))), entityPrimaryKeyInSet(20))
			)
		);
	}

	/**
	 * Creates one row of {@link #acceptedScopeFilters()}.
	 *
	 * @param label               the filter in readable notation
	 * @param entityType          the queried entity type
	 * @param expectedPrimaryKeys the primary keys the filter matches, in ascending order
	 * @param filter              the children of the filter
	 * @return the arguments of the row
	 */
	@Nonnull
	private static Arguments accepted(
		@Nonnull String label,
		@Nonnull String entityType,
		@Nonnull List<Integer> expectedPrimaryKeys,
		@Nonnull FilterConstraint... filter
	) {
		return Arguments.of(label, entityType, filterBy(filter), expectedPrimaryKeys);
	}

	/**
	 * Returns the diagnostic refusing the scope below `or`.
	 *
	 * @param disjunctiveScope the refused scope
	 * @return the exact message of the refusal
	 */
	@Nonnull
	private static String disjunctiveScopeMessage(@Nonnull EntityScope disjunctiveScope) {
		return "Constraint `" + disjunctiveScope + "` cannot be placed in `or` - it selects the scopes to search in, " +
			"it does not filter entities. Name the scopes you want in `scope(...)` and put a condition that holds in " +
			"one scope only in `inScope(...)`.";
	}

	/**
	 * Returns the diagnostic refusing a scope below `referenceHaving`, which applies no scope of its own.
	 *
	 * @param inertScope the refused scope
	 * @return the exact message of the refusal
	 */
	@Nonnull
	private static String inertScopeMessage(@Nonnull EntityScope inertScope) {
		return "Constraint `" + inertScope + "` has no effect in `referenceHaving` - it selects scopes only in the " +
			"root of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or removed.";
	}

	/**
	 * Asserts that the call is refused with the diagnostic naming the disjunctive scope.
	 *
	 * @param disjunctiveScope the scope the refusal has to name
	 * @param call             the call to refuse
	 */
	private static void assertDisjunctiveScopeRefused(@Nonnull EntityScope disjunctiveScope, @Nonnull Executable call) {
		final EvitaInvalidUsageException refusal = assertThrowsExactly(EvitaInvalidUsageException.class, call);
		assertEquals(disjunctiveScopeMessage(disjunctiveScope), refusal.getMessage());
	}

	/**
	 * Asserts that the call is refused with the diagnostic naming the negated scope, which takes precedence over the
	 * one of `or`.
	 *
	 * @param negatedScope the scope the refusal has to name
	 * @param call         the call to refuse
	 */
	private static void assertNegatedScopeRefused(@Nonnull EntityScope negatedScope, @Nonnull Executable call) {
		final EvitaInvalidUsageException refusal = assertThrowsExactly(EvitaInvalidUsageException.class, call);
		assertEquals(
			"Constraint `" + negatedScope + "` cannot be negated in `not` - it selects the scopes to search in, it " +
				"does not filter entities. Name the scopes you want in `scope(...)` instead.",
			refusal.getMessage()
		);
	}

	/**
	 * Returns the sorted primary keys of the entities the query matches.
	 *
	 * @param session    the session to query in
	 * @param entityType the queried entity type
	 * @param filterBy   the filter of the query
	 * @param require    the requirements of the query
	 * @return the matched primary keys in ascending order
	 */
	@Nonnull
	private static List<Integer> queryPrimaryKeys(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull FilterBy filterBy,
		@Nonnull RequireConstraint... require
	) {
		return session.queryList(query(collection(entityType), filterBy, require(require)), EntityReference.class)
			.stream()
			.map(EntityReference::getPrimaryKey)
			.sorted()
			.toList();
	}

	@Nested
	@DisplayName("The filter section")
	class FilterSection {

		/** Every filter that puts a scope below or at the level of the queried entity gets the refusal. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.reference.ScopeInDisjunctionFunctionalTest#disjunctiveScopeFilters")
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below or")
		void shouldRefuseScopeWhenDisjoined(
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull EntityScope disjunctiveScope,
			@Nonnull FilterBy filterBy,
			@Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDisjunctiveScopeRefused(disjunctiveScope, () -> queryPrimaryKeys(session, entityType, filterBy));
				assertDisjunctiveScopeRefused(
					disjunctiveScope,
					() -> session.queryOne(query(collection(entityType), filterBy), EntityReference.class)
				);
			});
		}

		/**
		 * Root and conjunction scopes, and an `or` of a single child, retain their answers; inScope expresses the
		 * scope-specific condition.
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.reference.ScopeInDisjunctionFunctionalTest#acceptedScopeFilters")
		@UseDataSet(DATA_SET)
		@DisplayName("Accept root and conjunction scopes, a single-child or and the inScope rewrite")
		void shouldAcceptScopeWhenOutsideDisjunction(
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull FilterBy filterBy,
			@Nonnull List<Integer> expectedPrimaryKeys,
			@Nonnull Evita evita
		) {
			final List<Integer> primaryKeys = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return queryPrimaryKeys(session, entityType, filterBy);
				}
			);

			assertEquals(expectedPrimaryKeys, primaryKeys);
		}

		/** Negation is the more specific mistake when both logical containers enclose the scope. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Keep the not diagnostic when not and or both enclose the scope")
		void shouldPreferNegationWhenAlsoInDisjunction(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertNegatedScopeRefused(
					scope(A), () -> queryPrimaryKeys(session, ENTITY_ITEM, filterBy(not(or(scope(A), CODE_A))))
				);
				assertNegatedScopeRefused(
					scope(L), () -> queryPrimaryKeys(session, ENTITY_ITEM, filterBy(or(not(scope(L)), CODE_A)))
				);
			});
		}

		/** The scope a container does not apply at all keeps its own, more fundamental diagnostic. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a disjunctive scope a reference filter would not apply as having no effect")
		void shouldRefuseScopeAsInertWhenEnclosingContainerDoesNotApplyIt(@Nonnull Evita evita) {
			final EvitaInvalidUsageException refusal = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return assertThrowsExactly(
						EvitaInvalidUsageException.class,
						() -> queryPrimaryKeys(
							session, ENTITY_OWNER, filterBy(referenceHaving(REF_ITEM, or(scope(L), CODE_A)))
						)
					);
				}
			);

			assertEquals(inertScopeMessage(scope(L)), refusal.getMessage());
		}
	}

	@Nested
	@DisplayName("The filters of the order section")
	class OrderSection {

		/**
		 * The filter of a segment is translated in the context of the queried entity and needs the same refusal.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below or in the filter of a segment")
		void shouldRefuseScopeWhenDisjoinedInSegmentFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: a filter without a scope selects item 2 for the segment, item 1 follows it
				assertEquals(List.of(2, 1), queryOrderedPrimaryKeys(session, ENTITY_ITEM, entityHaving(not(CODE_A))));
				assertDisjunctiveScopeRefused(
					scope(A), () -> queryOrderedPrimaryKeys(session, ENTITY_ITEM, entityHaving(or(scope(A), CODE_A)))
				);
			});
		}

		/**
		 * A scope below or in a segment container that reads no scope of its own gets the diagnostic naming
		 * that container, as it does in the filter section - whose check does not reach the order section.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a disjunctive scope a reference filter of a segment would not apply as having no effect")
		void shouldRefuseScopeAsInertWhenDisjoinedInSegmentReferenceFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: owner 20 refers to the item without code a, so the segment puts it first
				assertEquals(
					List.of(20, 10, 30, 40),
					queryOrderedPrimaryKeys(
						session, ENTITY_OWNER, entityHaving(referenceHaving(REF_ITEM, entityHaving(not(CODE_A))))
					)
				);
				final EvitaInvalidUsageException refusal = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> queryOrderedPrimaryKeys(
						session, ENTITY_OWNER, entityHaving(referenceHaving(REF_ITEM, or(scope(L), CODE_A)))
					)
				);
				assertEquals(inertScopeMessage(scope(L)), refusal.getMessage());
			});
		}

		/**
		 * Returns the live entities in the order of a single segment, which sorts the entities its filter selects by
		 * their primary key in descending order and puts them before all others.
		 *
		 * @param session       the session to query in
		 * @param entityType    the queried entity type
		 * @param segmentFilter the filter of the segment
		 * @return the primary keys in the order of the query
		 */
		@Nonnull
		private List<Integer> queryOrderedPrimaryKeys(
			@Nonnull EvitaSessionContract session, @Nonnull String entityType, @Nonnull EntityHaving segmentFilter
		) {
			return session.queryList(
					query(
						collection(entityType),
						orderBy(segments(segment(segmentFilter, orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)))))
					),
					EntityReference.class
				)
				.stream()
				.map(EntityReference::getPrimaryKey)
				.toList();
		}
	}

	/** The four relation constraints that carry a group filter. */
	enum Relation {
		CONJUNCTION, DISJUNCTION, NEGATION, EXCLUSIVITY;

		/**
		 * Creates the requirement of this relation over the reference `item`.
		 *
		 * @param groupFilter the filter of the facet groups
		 * @return the requirement
		 */
		@Nonnull
		RequireConstraint create(@Nonnull FilterBy groupFilter) {
			return switch (this) {
				case CONJUNCTION -> facetGroupsConjunction(REF_ITEM, groupFilter);
				case DISJUNCTION -> facetGroupsDisjunction(REF_ITEM, groupFilter);
				case NEGATION -> facetGroupsNegation(REF_ITEM, groupFilter);
				case EXCLUSIVITY -> facetGroupsExclusivity(REF_ITEM, groupFilter);
			};
		}
	}

	@Nested
	@DisplayName("The filters of the require section")
	class RequireFilters {

		/** The group filter of a relation constraint is a filter of the groups and refuses a disjunctive scope. */
		@ParameterizedTest
		@EnumSource(Relation.class)
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below or in the group filter of facetGroups*")
		void shouldRefuseScopeWhenDisjoinedInFacetGroupFilter(@Nonnull Relation relation, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: the same requirement with the scope outside the disjunction is accepted
				assertDoesNotThrow(() -> queryPrimaryKeys(
					session, ENTITY_OWNER, filterBy(), relation.create(filterBy(scope(L), CODE_A))
				));
				assertDisjunctiveScopeRefused(
					scope(L),
					() -> queryPrimaryKeys(
						session, ENTITY_OWNER, filterBy(), relation.create(filterBy(or(scope(L), CODE_A)))
					)
				);
				// the lookup of the scope of the group filter would find two scopes here and fail before any check
				assertDisjunctiveScopeRefused(
					scope(A),
					() -> queryPrimaryKeys(
						session, ENTITY_OWNER, filterBy(),
						relation.create(filterBy(scope(L, A), or(scope(A), CODE_A), inScope(L, CODE_A)))
					)
				);
			});
		}

		/** The group filter of a facet summary refuses a disjunctive scope as any other filter does. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below or in the group filter of a facet summary")
		void shouldRefuseScopeWhenDisjoinedInFacetSummaryGroupFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDoesNotThrow(() -> queryPrimaryKeys(
					session, ENTITY_OWNER, filterBy(),
					facetSummaryOfReference(REF_ITEM, FacetStatisticsDepth.COUNTS, filterGroupBy(scope(L), CODE_A))
				));
				assertDisjunctiveScopeRefused(
					scope(L),
					() -> queryPrimaryKeys(
						session, ENTITY_OWNER, filterBy(),
						facetSummaryOfReference(
							REF_ITEM, FacetStatisticsDepth.COUNTS, filterGroupBy(or(scope(L), CODE_A))
						)
					)
				);
			});
		}

		/** The query planning entry point checks referenceContent filters before the row compiler. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below or in a referenceContent filter while planning")
		void shouldRefuseScopeWhenInReferenceContentFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDisjunctiveScopeRefused(scope(L), () -> queryPrimaryKeys(
					session, ENTITY_OWNER, filterBy(),
					entityFetch(referenceContent(REF_ITEM, filterBy(or(scope(L), CODE_A))))
				));
			});
		}

		/**
		 * A direct fetch of an entity is an entry point of its own and refuses the scope before it looks the entity
		 * up - also where the filter of the fetched references names two scopes for the lookup to stumble over.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below or in a filter of references fetched by getEntity")
		void shouldRefuseScopeWhenDisjoinedInFetchedReferenceFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDisjunctiveScopeRefused(
					scope(L),
					() -> session.getEntity(
						ENTITY_OWNER, 10, referenceContent(REF_ITEM, filterBy(or(scope(L), CODE_A)))
					)
				);
				assertDisjunctiveScopeRefused(
					scope(L),
					() -> session.getEntity(
						ENTITY_OWNER, 10, referenceContent(REF_ITEM, filterBy(entityHaving(or(scope(L), CODE_A))))
					)
				);
				assertDisjunctiveScopeRefused(
					scope(A),
					() -> session.getEntity(
						ENTITY_OWNER, 10,
						referenceContent(
							REF_ITEM, filterBy(scope(L, A), or(scope(A), CODE_A), entityHaving(inScope(L, CODE_A)))
						)
					)
				);
			});
		}

		/**
		 * A direct fetch checks its request without the normalization a query gets, so the check has to accept an `or`
		 * of a single child there, and the fetch answers as it does for the child alone.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Accept a scope in an or of a single child in a filter of references fetched by getEntity")
		void shouldAcceptScopeWhenOrHasSingleChildInFetchedReferenceFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: owner 30 refers to archived item 3, owner 10 to live item 1
				assertEquals(List.of(3), fetchReferencedPrimaryKeys(session, 30, entityHaving(scope(A))));
				assertEquals(List.of(), fetchReferencedPrimaryKeys(session, 10, entityHaving(scope(A))));
				assertEquals(List.of(3), fetchReferencedPrimaryKeys(session, 30, entityHaving(or(scope(A)))));
				assertEquals(List.of(), fetchReferencedPrimaryKeys(session, 10, entityHaving(or(scope(A)))));
			});
		}

		/**
		 * Returns the items the owner refers to that pass the filter of the fetched references.
		 *
		 * @param session         the session to fetch in
		 * @param ownerPrimaryKey the primary key of the fetched owner
		 * @param referenceFilter the filter of the fetched references
		 * @return the primary keys of the referenced items
		 */
		@Nonnull
		private List<Integer> fetchReferencedPrimaryKeys(
			@Nonnull EvitaSessionContract session, int ownerPrimaryKey, @Nonnull EntityHaving referenceFilter
		) {
			return session
				.getEntity(ENTITY_OWNER, ownerPrimaryKey, referenceContent(REF_ITEM, filterBy(referenceFilter)))
				.orElseThrow()
				.getReferences(REF_ITEM)
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.toList();
		}
	}
}
