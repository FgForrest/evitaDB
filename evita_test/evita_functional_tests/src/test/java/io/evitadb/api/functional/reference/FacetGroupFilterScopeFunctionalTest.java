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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the `scope(...)` and `inScope(...)` constraints in the group filter of the `facetGroups*` requirements and
 * of the group filter of the facet summary: the filter searches the groups of the scopes it names, as `groupHaving`
 * does, whatever the scopes of the query.
 *
 * Fixture: `TagGroup.op` is filterable in `LIVE` only, `TagGroup.st` in both scopes. Groups: 1 `LIVE`
 * `op=AND`, 2 `LIVE` `op=OR`, 3 `ARCHIVED` `op=OR`, all with `st=A`. Tags 11, 12 belong to group 1, tags 21, 22 to
 * group 2, tags 31, 32 to group 3. The reference `Product.tags` is indexed and faceted in `LIVE` only. Live products:
 * 200 (all tags), 201 (11, 21, 31), 202 (12, 22, 32), 203 (11).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Group filter scope of facetGroups* requirements")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FILTER)
@Tag(FACET)
public class FacetGroupFilterScopeFunctionalTest {
	private static final String DATA_SET = "facetGroupFilterScope";
	private static final Scope[] BOTH = Scope.values();

	/** Builds the fixture described in the class comment. */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUpDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("TagGroup").withoutGeneratedPrimaryKey()
				.withAttribute("op", String.class, a -> a.filterableInScope(Scope.LIVE))
				.withAttribute("st", String.class, a -> a.filterableInScope(BOTH))
				.updateVia(session);
			session.defineEntitySchema("Tag").withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					"tags", "Tag", Cardinality.ZERO_OR_MORE,
					r -> r.withGroupTypeRelatedToEntity("TagGroup").indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE)
				)
				.updateVia(session);
			session.createNewEntity("TagGroup", 1).setAttribute("op", "AND").setAttribute("st", "A").upsertVia(session);
			session.createNewEntity("TagGroup", 2).setAttribute("op", "OR").setAttribute("st", "A").upsertVia(session);
			session.createNewEntity("TagGroup", 3).setAttribute("op", "OR").setAttribute("st", "A").upsertVia(session);
			for (final int tag : new int[]{11, 12, 21, 22, 31, 32}) {
				session.createNewEntity("Tag", tag).upsertVia(session);
			}
			upsertProduct(session, 200, 11, 12, 21, 22, 31, 32);
			upsertProduct(session, 201, 11, 21, 31);
			upsertProduct(session, 202, 12, 22, 32);
			upsertProduct(session, 203, 11);
			session.archiveEntity("TagGroup", 3);
		});
	}

	private static void upsertProduct(
		@Nonnull io.evitadb.api.EvitaSessionContract session, int pk, @Nonnull int... tags
	) {
		final var product = session.createNewEntity("Product", pk);
		for (final int tag : tags) {
			product.setReference("tags", tag, r -> r.setGroup("TagGroup", tag / 10));
		}
		product.upsertVia(session);
	}

	/** The four relation constraints that carry a group filter. */
	enum Relation {
		CONJUNCTION, DISJUNCTION, NEGATION, EXCLUSIVITY;

		/** Creates the requirement of this relation over the reference `tags`. */
		@Nonnull
		RequireConstraint create(@Nonnull FacetGroupRelationLevel level, @Nonnull FilterBy groupFilter) {
			return switch (this) {
				case CONJUNCTION -> facetGroupsConjunction("tags", level, groupFilter);
				case DISJUNCTION -> facetGroupsDisjunction("tags", level, groupFilter);
				case NEGATION -> facetGroupsNegation("tags", level, groupFilter);
				case EXCLUSIVITY -> facetGroupsExclusivity("tags", level, groupFilter);
			};
		}
	}

	/** Pairs of tags selected by the client; the groups of the tags are 1, 2 and the archived 3. */
	private static final int[][] SELECTIONS = {{11, 12}, {21, 22}, {31, 32}, {11, 32}, {21, 32}, {12, 31}, {11, 21, 32}};

	private static String run(
		@Nonnull Evita evita, @Nonnull FilterBy mainFilter, @Nonnull RequireConstraint relation
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			return session.queryListOfSealedEntities(query(collection("Product"), mainFilter, require(relation)))
				.stream().map(SealedEntity::getPrimaryKey).sorted().toList().toString();
		});
	}

	private static FilterBy select(int... tags) {
		return filterBy(scope(BOTH), inScope(Scope.LIVE, userFilter(facetHaving("tags", entityPrimaryKeyInSet(tags)))));
	}

	/** Returns the groups a filter naming the scope can select: the live ones 1 and 2, the archived 3. */
	private static int[] groupsOf(@Nonnull Scope... scopes) {
		return Stream.of(scopes).flatMapToInt(it -> it == Scope.LIVE ? java.util.stream.IntStream.of(1, 2) : java.util.stream.IntStream.of(3)).toArray();
	}

	@Nested
	@DisplayName("Result of the relation constraints")
	class RelationResult {

		/** A filter naming a scope selects exactly the groups an explicit key filter selects among the groups of it. */
		@ParameterizedTest
		@EnumSource(Relation.class)
		@UseDataSet(DATA_SET)
		@DisplayName("A scope in the group filter limits the groups it selects")
		void shouldSelectOnlyGroupsOfNamedScope(@Nonnull Relation relation, @Nonnull Evita evita) {
			for (final FacetGroupRelationLevel level : FacetGroupRelationLevel.values()) {
				for (final int[] selection : SELECTIONS) {
					final FilterBy main = select(selection);
					for (final Scope[] scopes : new Scope[][]{{Scope.LIVE}, {Scope.ARCHIVED}, BOTH}) {
						final String named = run(
							evita, main, relation.create(level, filterBy(scope(scopes), attributeEquals("st", "A")))
						);
						final String oracle = run(
							evita, main,
							relation.create(
								level,
								filterBy(entityPrimaryKeyInSet(groupsOf(scopes)), attributeEquals("st", "A"))
							)
						);
						assertEquals(
							oracle, named,
							relation + " " + level + " select " + java.util.Arrays.toString(selection) + " scope " + List.of(scopes)
						);
					}
				}
			}
		}

		/** Pins the cell the oracle test would not catch if it matched by accident: the scope changes the answer. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Naming the live scope drops the archived group from the conjunction")
		void shouldNotConjoinArchivedGroupWhenLiveScopeIsNamed(@Nonnull Evita evita) {
			final FacetGroupRelationLevel level = FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
			assertEquals(
				"[200]",
				run(evita, select(31, 32), facetGroupsConjunction("tags", level, filterBy(attributeEquals("st", "A"))))
			);
			assertEquals(
				"[200, 201, 202]",
				run(
					evita, select(31, 32),
					facetGroupsConjunction("tags", level, filterBy(scope(Scope.LIVE), attributeEquals("st", "A")))
				)
			);
		}

		/** The group filter on an attribute indexed in the live scope only is answerable once the scope is named. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Naming the live scope makes a live-only attribute filterable")
		void shouldSelectLiveGroupsByLiveOnlyAttributeWhenLiveScopeIsNamed(@Nonnull Evita evita) {
			final FilterBy liveOnly = filterBy(scope(Scope.LIVE), attributeEquals("op", "AND"));
			assertEquals("[200]", run(evita, select(11, 12), facetGroupsConjunction("tags", liveOnly)));
			assertEquals("[200, 201, 202]", run(evita, select(21, 22), facetGroupsConjunction("tags", liveOnly)));
			assertEquals("[200, 201, 202]", run(evita, select(31, 32), facetGroupsConjunction("tags", liveOnly)));
			assertEquals(
				"[200, 201, 202, 203]",
				run(evita, filterBy(scope(BOTH)), facetGroupsConjunction("tags", liveOnly))
			);
		}

		/** The archived scope is searched when it is named, so the live-only attribute is refused there. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse a live-only attribute when the archived scope is named")
		void shouldRefuseLiveOnlyAttributeWhenArchivedScopeIsNamed(@Nonnull Evita evita) {
			final io.evitadb.core.exception.AttributeNotFilterableException error = assertThrows(
				io.evitadb.core.exception.AttributeNotFilterableException.class,
				() -> run(
					evita, select(31, 32),
					facetGroupsConjunction("tags", filterBy(scope(Scope.ARCHIVED), attributeEquals("op", "AND")))
				)
			);
			assertTrue(error.getMessage().contains("\"unique\" in scope `ARCHIVED` and cannot be filtered by"), error.getMessage());
			assertThrows(
				io.evitadb.core.exception.AttributeNotFilterableException.class,
				() -> run(
					evita, select(31, 32),
					facetGroupsConjunction("tags", filterBy(scope(BOTH), attributeEquals("op", "AND")))
				)
			);
			assertThrows(
				io.evitadb.core.exception.AttributeNotFilterableException.class,
				() -> run(evita, select(31, 32), facetGroupsConjunction("tags", filterBy(attributeEquals("op", "AND"))))
			);
		}

		/** An `inScope` lets the groups of the other scope through, so an archived group is selected by it. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("An inScope selects the groups of the other scope")
		void shouldSelectArchivedGroupWhenOnlyInScopeLimitsLiveGroups(@Nonnull Evita evita) {
			final FilterBy inScopeFilter = filterBy(inScope(Scope.LIVE, attributeEquals("op", "AND")));
			assertEquals("[200]", run(evita, select(11, 12), facetGroupsConjunction("tags", inScopeFilter)));
			assertEquals("[200, 201, 202]", run(evita, select(21, 22), facetGroupsConjunction("tags", inScopeFilter)));
			// group 3 is archived and has op=OR, yet the inScope lets it through
			assertEquals("[200]", run(evita, select(31, 32), facetGroupsConjunction("tags", inScopeFilter)));
		}
	}

	@Nested
	@DisplayName("Group filter of the facet summary")
	class SummaryGroups {

		private String summaryGroups(@Nonnull Evita evita, @Nonnull FilterGroupBy groupFilter) {
			return evita.queryCatalog(TEST_CATALOG, session -> {
				final var response = session.query(
					query(
						collection("Product"),
						filterBy(scope(BOTH), inScope(Scope.LIVE, userFilter(facetHaving("tags", entityPrimaryKeyInSet(11))))),
						require(
							inScope(
								Scope.LIVE,
								facetSummaryOfReference(
									"tags", FacetStatisticsDepth.COUNTS, (FilterBy) null, groupFilter,
									(OrderBy) null, (OrderGroupBy) null
								)
							)
						)
					),
					EntityReference.class
				);
				return response.getExtraResult(FacetSummary.class).getReferenceStatistics().stream()
					.map(it -> it.getGroupEntity().getPrimaryKey())
					.sorted().toList().toString();
			});
		}

		/** A filter naming a scope lists the groups an explicit key filter lists among the groups of that scope. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("A scope in the group filter limits the groups listed")
		void shouldListOnlyGroupsOfNamedScope(@Nonnull Evita evita) {
			assertEquals("[1, 2]", summaryGroups(evita, filterGroupBy(attributeEquals("st", "A"))));
			assertEquals("[1, 2]", summaryGroups(evita, filterGroupBy(scope(Scope.LIVE), attributeEquals("st", "A"))));
			assertEquals("[3]", summaryGroups(evita, filterGroupBy(scope(Scope.ARCHIVED), attributeEquals("st", "A"))));
		}
	}
}
