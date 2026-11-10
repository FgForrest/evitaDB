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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.function.Executable;

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
 * Pins that a `scope(...)` below `not(...)` is refused with a diagnostic naming the mistake, before anything of the
 * query is evaluated - instead of an internal error from the translation of `not`, a `MoreThanSingleResultException`
 * from the lookup of the query's scope, or a scope silently applied as if it stood outside the negation. A negated
 * container that starts its own entity level - `entityHaving`, `groupHaving` - still takes a `scope(...)` of that
 * level.
 *
 * ## The fixture
 *
 * `Item.code` is filterable in both scopes. `Owner.item` references an item, uses the item type as its group and is
 * indexed and faceted in the live scope:
 *
 * | entity | scope | data |
 * |---|---|---|
 * | item 1 | LIVE | `code = a` |
 * | item 2 | LIVE | `code = b` |
 * | item 3 | ARCHIVED | `code = a` |
 * | item 4 | ARCHIVED | `code = b` |
 * | owner 10 | LIVE | item 1 in group 1 |
 * | owner 20 | LIVE | item 2 in group 2 |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("scope(...) below not(...)")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
public class NegatedScopeFunctionalTest {
	private static final String DATA_SET = "negatedScope";
	private static final String ENTITY_ITEM = "Item";
	private static final String ENTITY_OWNER = "Owner";
	private static final String REF_ITEM = "item";
	private static final String ATTR_CODE = "code";
	private static final Scope L = Scope.LIVE;
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
		});
	}

	/**
	 * Supplies the filters of the issue that hold a `scope(...)` below `not` at the level of the queried entity,
	 * each with the entity it queries and the scope the refusal has to name.
	 *
	 * @return the label, the queried entity type, the negated scope and the filter
	 */
	@Nonnull
	static Stream<Arguments> negatedScopeFilters() {
		return Stream.of(
			negated("not(scope(LIVE))", ENTITY_ITEM, scope(L), not(scope(L))),
			negated("not(scope(LIVE, ARCHIVED))", ENTITY_ITEM, scope(L, A), not(scope(L, A))),
			negated("not(and(scope(LIVE)))", ENTITY_ITEM, scope(L), not(and(scope(L)))),
			negated("not(not(scope(LIVE)))", ENTITY_ITEM, scope(L), not(not(scope(L)))),
			negated("and(not(scope(LIVE)), code=a)", ENTITY_ITEM, scope(L), and(not(scope(L)), CODE_A)),
			negated("or(not(scope(LIVE)), code=a)", ENTITY_ITEM, scope(L), or(not(scope(L)), CODE_A)),
			negated("not(and(scope(ARCHIVED), code=a))", ENTITY_ITEM, scope(A), not(and(scope(A), CODE_A))),
			negated("not(or(scope(ARCHIVED), code=a))", ENTITY_ITEM, scope(A), not(or(scope(A), CODE_A))),
			negated("not(inScope(LIVE, scope(LIVE)))", ENTITY_ITEM, scope(L), not(inScope(L, scope(L)))),
			// the lookup of the scope of the query would find two scopes here and fail before any check
			negated(
				"scope(LIVE, ARCHIVED), not(scope(ARCHIVED))", ENTITY_ITEM, scope(A), scope(L, A), not(scope(A))
			),
			negated(
				"referenceHaving(item, entityHaving(not(scope(LIVE))))", ENTITY_OWNER, scope(L),
				referenceHaving(REF_ITEM, entityHaving(not(scope(L))))
			),
			// the lookup of the scope of the nested level would find two scopes here and fail before any check
			negated(
				"referenceHaving(item, entityHaving(scope(LIVE, ARCHIVED), not(scope(ARCHIVED)), inScope(...)))",
				ENTITY_OWNER, scope(A),
				referenceHaving(REF_ITEM, entityHaving(and(scope(L, A), not(scope(A)), inScope(L, CODE_A))))
			)
		);
	}

	/**
	 * Creates one row of {@link #negatedScopeFilters()}.
	 *
	 * @param label        the filter as the issue writes it
	 * @param entityType   the queried entity type
	 * @param negatedScope the scope the refusal has to name
	 * @param filter       the children of the filter
	 * @return the arguments of the row
	 */
	@Nonnull
	private static Arguments negated(
		@Nonnull String label,
		@Nonnull String entityType,
		@Nonnull EntityScope negatedScope,
		@Nonnull FilterConstraint... filter
	) {
		return Arguments.of(label, entityType, negatedScope, filterBy(filter));
	}

	/**
	 * Returns the diagnostic refusing the scope below `not`.
	 *
	 * @param negatedScope the refused scope
	 * @return the exact message of the refusal
	 */
	@Nonnull
	private static String negatedScopeMessage(@Nonnull EntityScope negatedScope) {
		return "Constraint `" + negatedScope + "` cannot be negated in `not` - it selects the scopes to search in, " +
			"it does not filter entities. Name the scopes you want in `scope(...)` instead.";
	}

	/**
	 * Asserts that the call is refused with the diagnostic naming the negated scope.
	 *
	 * @param negatedScope the scope the refusal has to name
	 * @param call         the call to refuse
	 */
	private static void assertNegatedScopeRefused(@Nonnull EntityScope negatedScope, @Nonnull Executable call) {
		final EvitaInvalidUsageException refusal = assertThrowsExactly(EvitaInvalidUsageException.class, call);
		assertEquals(negatedScopeMessage(negatedScope), refusal.getMessage());
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

		/** Every filter of the issue that negates a scope at the level of the queried entity gets the refusal. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.reference.NegatedScopeFunctionalTest#negatedScopeFilters")
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below not")
		void shouldRefuseScopeWhenNegated(
			@Nonnull String label,
			@Nonnull String entityType,
			@Nonnull EntityScope negatedScope,
			@Nonnull FilterBy filterBy,
			@Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertNegatedScopeRefused(negatedScope, () -> queryPrimaryKeys(session, entityType, filterBy));
			});
		}

		/** A negated container starting its own entity level takes the scope of that level, as before. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Accept a scope of an entity level the negation encloses")
		void shouldAcceptScopeWhenNegatedContainerStartsOwnEntityLevel(@Nonnull Evita evita) {
			final List<Integer> owners = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return queryPrimaryKeys(
						session, ENTITY_OWNER,
						filterBy(not(referenceHaving(REF_ITEM, entityHaving(and(scope(L), CODE_A)))))
					);
				}
			);

			assertEquals(List.of(20), owners);
		}

		/** A scope beside the negation keeps selecting the scope of the query. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Accept a scope beside not")
		void shouldAcceptScopeWhenBesideNegation(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(4), queryPrimaryKeys(session, ENTITY_ITEM, filterBy(scope(A), not(CODE_A))));
				assertEquals(List.of(2, 4), queryPrimaryKeys(session, ENTITY_ITEM, filterBy(scope(L, A), not(CODE_A))));
			});
		}

		/**
		 * A scope in a disjunction applies to the whole filter, as it did before - whether `or` should be refused too
		 * was left open.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Keep a scope in a disjunction applying to the whole filter")
		void shouldApplyScopeToWholeFilterWhenInDisjunction(@Nonnull Evita evita) {
			final List<Integer> items = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return queryPrimaryKeys(session, ENTITY_ITEM, filterBy(or(scope(A), CODE_A)));
				}
			);

			assertEquals(List.of(3), items);
		}

		/** The scope a container does not apply at all keeps its own, more fundamental diagnostic. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a negated scope a reference filter would not apply as having no effect")
		void shouldRefuseScopeAsInertWhenEnclosingContainerDoesNotApplyIt(@Nonnull Evita evita) {
			final EvitaInvalidUsageException refusal = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return assertThrowsExactly(
						EvitaInvalidUsageException.class,
						() -> queryPrimaryKeys(
							session, ENTITY_OWNER, filterBy(referenceHaving(REF_ITEM, not(scope(L))))
						)
					);
				}
			);

			assertEquals(
				"Constraint `scope(LIVE)` has no effect in `referenceHaving` - it selects scopes only in the root " +
					"of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or removed.",
				refusal.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("The filters of the order section")
	class OrderSection {

		/**
		 * The filter of a segment is translated in the context of the queried entity, where a negated scope used to
		 * end in the internal error of the `not` translation as well.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below not in the filter of a segment")
		void shouldRefuseScopeWhenNegatedInSegmentFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: a negation without a scope selects item 2 for the segment, item 1 follows it
				assertEquals(List.of(2, 1), queryOrderedPrimaryKeys(session, ENTITY_ITEM, entityHaving(not(CODE_A))));
				assertNegatedScopeRefused(
					scope(L), () -> queryOrderedPrimaryKeys(session, ENTITY_ITEM, entityHaving(not(scope(L))))
				);
			});
		}

		/**
		 * A negated scope in a container of a segment filter that reads no scope of its own gets the diagnostic naming
		 * that container, as it does in the filter section - whose check does not reach the order section.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a negated scope a reference filter of a segment would not apply as having no effect")
		void shouldRefuseScopeAsInertWhenNegatedInSegmentReferenceFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: owner 20 refers to the item without code a, so the segment puts it first
				assertEquals(
					List.of(20, 10),
					queryOrderedPrimaryKeys(
						session, ENTITY_OWNER, entityHaving(referenceHaving(REF_ITEM, entityHaving(not(CODE_A))))
					)
				);
				final EvitaInvalidUsageException refusal = assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> queryOrderedPrimaryKeys(
						session, ENTITY_OWNER, entityHaving(referenceHaving(REF_ITEM, not(scope(L))))
					)
				);
				assertEquals(
					"Constraint `scope(LIVE)` has no effect in `referenceHaving` - it selects scopes only in the " +
						"root of a filter and in `entityHaving` or `groupHaving`, so it must be placed there or " +
						"removed.",
					refusal.getMessage()
				);
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

		/** The group filter of a relation constraint is a filter of the groups and refuses a negated scope. */
		@ParameterizedTest
		@EnumSource(Relation.class)
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below not in the group filter of facetGroups*")
		void shouldRefuseScopeWhenNegatedInFacetGroupFilter(@Nonnull Relation relation, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				// the control: the same requirement with the scope outside the negation is accepted
				assertDoesNotThrow(() -> queryPrimaryKeys(
					session, ENTITY_OWNER, filterBy(), relation.create(filterBy(scope(L), CODE_A))
				));
				assertNegatedScopeRefused(
					scope(L),
					() -> queryPrimaryKeys(
						session, ENTITY_OWNER, filterBy(), relation.create(filterBy(not(scope(L))))
					)
				);
				// the lookup of the scope of the group filter would find two scopes here and fail before any check
				assertNegatedScopeRefused(
					scope(A),
					() -> queryPrimaryKeys(
						session, ENTITY_OWNER, filterBy(),
						relation.create(filterBy(scope(L, A), not(scope(A)), inScope(L, CODE_A)))
					)
				);
			});
		}

		/** The group filter of a facet summary refuses a negated scope as any other filter does. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below not in the group filter of a facet summary")
		void shouldRefuseScopeWhenNegatedInFacetSummaryGroupFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDoesNotThrow(() -> queryPrimaryKeys(
					session, ENTITY_OWNER, filterBy(),
					facetSummaryOfReference(REF_ITEM, FacetStatisticsDepth.COUNTS, filterGroupBy(scope(L), CODE_A))
				));
				assertNegatedScopeRefused(
					scope(L),
					() -> queryPrimaryKeys(
						session, ENTITY_OWNER, filterBy(),
						facetSummaryOfReference(REF_ITEM, FacetStatisticsDepth.COUNTS, filterGroupBy(not(scope(L))))
					)
				);
			});
		}

		/**
		 * A direct fetch of an entity is an entry point of its own and refuses the scope before it looks the entity
		 * up - also where the filter of the fetched references names two scopes for the lookup to stumble over.
		 */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a scope below not in a filter of references fetched by getEntity")
		void shouldRefuseScopeWhenNegatedInFetchedReferenceFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertNegatedScopeRefused(
					scope(L),
					() -> session.getEntity(ENTITY_OWNER, 10, referenceContent(REF_ITEM, filterBy(not(scope(L)))))
				);
				assertNegatedScopeRefused(
					scope(L),
					() -> session.getEntity(
						ENTITY_OWNER, 10, referenceContent(REF_ITEM, filterBy(entityHaving(not(scope(L)))))
					)
				);
				assertNegatedScopeRefused(
					scope(A),
					() -> session.getEntity(
						ENTITY_OWNER, 10,
						referenceContent(
							REF_ITEM, filterBy(scope(L, A), not(scope(A)), entityHaving(inScope(L, CODE_A)))
						)
					)
				);
			});
		}
	}
}
