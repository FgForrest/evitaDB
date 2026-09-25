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
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins `attributeEquals` and `attributeInSet` on a **unique** reference attribute inside a `referenceHaving` body
 * (issue #1584).
 *
 * A unique value is looked up in the unique index of each reduced index (one per referenced entity) rather than in
 * a filter index. The body must still be answered one reference row at a time: an owner matches
 * `not(attributeEquals(u, x1))` when **some** of its rows carries another value, and matches
 * `and(attributeEquals(u, x1), attributeEquals(b, 2))` only when **one** row carries both.
 *
 * ## The fixture
 *
 * `uniqueRowOwner.rows` points at `uniqueRowTarget` 1-3 and carries unique `u` and filterable `b`;
 * `uniqueRowTarget.owners` reflects it. Rows as `(u, b)`:
 *
 * | owner | `rows` |
 * |---|---|
 * | 1 | T1 `(x1, 1)`, T2 `(x2, 2)` |
 * | 2 | T1 `(y1, 2)`, T2 `(w2, 1)` |
 * | 3 | T3 `(z3, 3)` |
 * | 4-20 | T3 `(⊥, 3)` - fillers that make the rewrite pay off from the target end |
 *
 * Owner 1 is the one every row-scoping question turns on: it carries `x1` in one row and `b = 2` in the other.
 * Owner 2's T2 row puts a second value into T2, so that a set spanning `x1` and `w2` keeps both T1 and T2 as
 * candidates - with a single value, candidate discovery alone narrows the body to the one partition holding it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Unique attribute inside a referenceHaving body")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
public class ReferenceHavingUniqueAttributeFunctionalTest {
	private static final String ROW_SCOPED_UNIQUE = "rowScopedUnique";
	private static final String ENTITY_OWNER = "uniqueRowOwner";
	private static final String ENTITY_TARGET = "uniqueRowTarget";
	private static final String REF_ROWS = "rows";
	private static final String REF_OWNERS = "owners";
	/**
	 * `rows`: `String`, unique, nullable.
	 */
	private static final String U = "u";
	/**
	 * `rows`: `Long`, filterable, set on every row.
	 */
	private static final String B = "b";
	private static final int TARGET_COUNT = 3;
	private static final int FIRST_FILLER_PK = 4;
	private static final int LAST_FILLER_PK = 20;
	/**
	 * Prefix of the `PLANNING_FILTER_ALTERNATIVE` argument describing the owner-side reduced-index option.
	 */
	private static final String REFERENCE_INDEX_OPTION_PREFIX = "Index type: REFERENCED_ENTITY composed of ";

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 * @return the owners and targets as stored, for the oracles
	 */
	@DataSet(value = ROW_SCOPED_UNIQUE, destroyAfterClass = true)
	DataCarrier setUpRowScopedUniqueDataSet(@Nonnull Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_ROWS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(U, String.class, thatIs -> thatIs.unique().nullable())
							.withAttribute(B, Long.class, thatIs -> thatIs.filterable())
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_TARGET)
					.withReflectedReferenceToEntity(
						REF_OWNERS, ENTITY_OWNER, REF_ROWS,
						whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttributesInherited()
					)
					.updateVia(session);
				for (int targetPk = 1; targetPk <= TARGET_COUNT; targetPk++) {
					session.upsertEntity(session.createNewEntity(ENTITY_TARGET, targetPk));
				}
				upsertOwner(session, 1, new Row(1, "x1", 1L), new Row(2, "x2", 2L));
				upsertOwner(session, 2, new Row(1, "y1", 2L), new Row(2, "w2", 1L));
				upsertOwner(session, 3, new Row(3, "z3", 3L));
				for (int fillerPk = FIRST_FILLER_PK; fillerPk <= LAST_FILLER_PK; fillerPk++) {
					upsertOwner(session, fillerPk, new Row(3, null, 3L));
				}
				final List<SealedEntity> owners = fetchAll(session, ENTITY_OWNER);
				final List<SealedEntity> targets = fetchAll(session, ENTITY_TARGET);
				assertEquals(LAST_FILLER_PK, owners.size(), "Fixture guard: unexpected owner count!");
				assertEquals(TARGET_COUNT, targets.size(), "Fixture guard: unexpected target count!");
				return new DataCarrier("originalOwners", owners, "originalTargets", targets);
			}
		);
	}

	/**
	 * Asked from the owner end, where the rewrite declines on its cost gate and the reduced indexes of `rows` - one
	 * per target, so one per row of an owner - answer the body.
	 */
	@DisplayName("Owner side")
	@Nested
	class OwnerSide {

		/**
		 * Owner 1 carries `x1` in its T1 row only, so its T2 row satisfies the negation.
		 */
		@DisplayName("Should match an owner whose other row carries another value under not(attributeEquals)")
		@UseDataSet(ROW_SCOPED_UNIQUE)
		@Test
		void shouldMatchAnOwnerWhoseOtherRowCarriesAnotherValueUnderNotAttributeEquals(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						not(attributeEquals(U, "x1")), row -> !Objects.equals(row.getAttribute(U), "x1")
					);
					return null;
				}
			);
		}

		/**
		 * Owner 1 carries `x1` in its T1 row only, so its T2 row (`x2`) satisfies the negation.
		 */
		@DisplayName("Should match an owner whose other row carries another value under not(attributeInSet)")
		@UseDataSet(ROW_SCOPED_UNIQUE)
		@Test
		void shouldMatchAnOwnerWhoseOtherRowCarriesAnotherValueUnderNotAttributeInSet(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						not(attributeInSet(U, "x1", "y1")), row -> !inSet(row, Set.of("x1", "y1"))
					);
					return null;
				}
			);
		}

		/**
		 * Owner 1 carries `x1` with `b = 1` and `b = 2` with `x2`, owner 2 carries `w2` with `b = 1` and `b = 2` with
		 * `y1` - so no row carries a value of the set together with `b = 2`, while both owners have one row of each.
		 * Both T1 and T2 hold a value of the set, so both stay candidates and the rows meet in the per-row evaluation.
		 *
		 * The `attributeEquals` twin of this row is deliberately absent: one unique value lives in one partition, so
		 * candidate discovery narrows such a body to that partition and the conjunction is row-scoped whatever the
		 * leaf carries - measured green with the tagging reverted.
		 */
		@DisplayName("Should not join an attributeInSet match with a sibling met by another row")
		@UseDataSet(ROW_SCOPED_UNIQUE)
		@Test
		void shouldNotJoinAnAttributeInSetMatchWithASiblingMetByAnotherRow(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						and(attributeInSet(U, "x1", "w2"), attributeEquals(B, 2L)),
						row -> inSet(row, Set.of("x1", "w2")) && Objects.equals(row.getAttribute(B), 2L)
					);
					return null;
				}
			);
		}

		/**
		 * Positive controls: shapes that were row-scoped before the unique leaves were tagged, among them the
		 * single-value conjunction that candidate discovery alone narrows to one partition.
		 */
		@DisplayName("Should keep answering positive unique constraints")
		@UseDataSet(ROW_SCOPED_UNIQUE)
		@Test
		void shouldKeepAnsweringPositiveUniqueConstraints(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						and(attributeEquals(U, "x1"), attributeEquals(B, 1L)),
						row -> Objects.equals(row.getAttribute(U), "x1") && Objects.equals(row.getAttribute(B), 1L)
					);
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						attributeEquals(U, "x2"), row -> Objects.equals(row.getAttribute(U), "x2")
					);
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						attributeInSet(U, "x1", "y1"), row -> inSet(row, Set.of("x1", "y1"))
					);
					return null;
				}
			);
		}

		/**
		 * The fetch path evaluates the body against one reduced index at a time, so an untagged leaf is already
		 * row-scoped there; pinned so the fetch path stays in step with the owner-level answers above.
		 */
		@DisplayName("Should fetch exactly the rows the unique constraint selects")
		@UseDataSet(ROW_SCOPED_UNIQUE)
		@Test
		void shouldFetchExactlyTheRowsTheUniqueConstraintSelects(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFetchedRows(
						session, originalOwners, not(attributeEquals(U, "x1")),
						row -> !Objects.equals(row.getAttribute(U), "x1")
					);
					assertFetchedRows(
						session, originalOwners, and(attributeInSet(U, "x1", "y1"), attributeEquals(B, 2L)),
						row -> inSet(row, Set.of("x1", "y1")) &&
							Objects.equals(row.getAttribute(B), 2L)
					);
					return null;
				}
			);
		}
	}

	/**
	 * Asked from the target end, a single leaf is answered by the bidirectional rewrite and a conjunction - which the
	 * rewrite declines - by the targets' own reduced indexes. The route is asserted, so a row cannot pass on the path
	 * it was not written for.
	 *
	 * These rows are pins, not witnesses of the tagging: the rewrite evaluates the body against one owner's
	 * counterpart indexes at a time and complements a negation itself, and on the declined conjunction candidate
	 * discovery already narrows the partitions to the one holding `x1`. Both measured green with the tagging reverted.
	 */
	@DisplayName("Target side")
	@Nested
	class TargetSide {

		@DisplayName("Should answer unique constraints through the rewrite")
		@UseDataSet(ROW_SCOPED_UNIQUE)
		@Test
		void shouldAnswerUniqueConstraintsThroughTheRewrite(Evita evita, List<SealedEntity> originalTargets) {
			final Set<String> set = Set.of("x1", "y1");
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						attributeEquals(U, "x1"), row -> Objects.equals(row.getAttribute(U), "x1")
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						not(attributeEquals(U, "x1")), row -> !Objects.equals(row.getAttribute(U), "x1")
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						not(attributeInSet(U, "x1", "y1")), row -> !inSet(row, set)
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.OWNER_SIDE,
						and(attributeEquals(U, "x1"), attributeEquals(B, 2L)),
						row -> Objects.equals(row.getAttribute(U), "x1") && Objects.equals(row.getAttribute(B), 2L)
					);
					return null;
				}
			);
		}
	}

	/**
	 * The route that answered a `referenceHaving`, as read off the query telemetry.
	 */
	private enum Route {
		/**
		 * The reduced indexes of the queried reference itself answered - the reference option was registered.
		 */
		OWNER_SIDE,
		/**
		 * The bidirectional rewrite answered on the reduced indexes of the counterpart reference - the owner-side
		 * option was never registered although the filter was planned.
		 */
		REWRITE
	}

	/**
	 * Runs `referenceHaving(referenceName, body)` under both index-scan preferences and asserts the entities holding
	 * a row the row predicate selects are returned, and that the index-scan plan took the route. Without
	 * `PREFER_INDEX_SCAN` a collection this small may be answered from prefetched bodies, which records no route.
	 *
	 * @param session       session to query through
	 * @param entityType    collection to query
	 * @param originals     entities of that collection as stored
	 * @param referenceName the reference
	 * @param route         the route the index-scan plan must take
	 * @param body          the `referenceHaving` body
	 * @param rowPredicate  the body, evaluated on one row
	 */
	private static void assertRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull List<SealedEntity> originals,
		@Nonnull String referenceName,
		@Nonnull Route route,
		@Nonnull FilterConstraint body,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		final Set<Integer> expected = originals.stream()
			.filter(it -> it.getReferences(referenceName).stream().anyMatch(rowPredicate))
			.map(SealedEntity::getPrimaryKeyOrThrowException)
			.collect(Collectors.toCollection(TreeSet::new));
		final FilterConstraint filter = referenceHaving(referenceName, body);
		for (boolean preferIndexScan : new boolean[]{true, false}) {
			final EvitaResponse<EntityReference> response = session.query(
				Query.query(
					collection(entityType),
					filterBy(filter),
					require(
						preferIndexScan ?
							debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN) :
							debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						page(1, Integer.MAX_VALUE),
						queryTelemetry()
					)
				),
				EntityReference.class
			);
			final Set<Integer> actual = response.getRecordData().stream()
				.map(EntityReference::getPrimaryKey)
				.collect(Collectors.toCollection(TreeSet::new));
			assertEquals(
				expected, actual,
				"Wrong `" + entityType + "` for `" + filter + "` (preferIndexScan=" + preferIndexScan + ")"
			);
			if (preferIndexScan) {
				assertEquals(
					route, routeOf(response),
					() -> "Wrong route for `" + filter + "`:\n" + response.getExtraResult(QueryTelemetry.class)
				);
			}
		}
	}

	/**
	 * Reads the route off the telemetry through the channel `BidirectionalReferenceRewriteFunctionalTest` documents:
	 * the rewrite decides during index selection and, when it fires, returns before the owner-side reference option
	 * is registered, so that option's `PLANNING_FILTER_ALTERNATIVE` step is present exactly when the owner side
	 * answers.
	 *
	 * @param response the response carrying the telemetry
	 * @return the route, or NULL when the filter was not planned at all
	 */
	@Nullable
	private static Route routeOf(@Nonnull EvitaResponse<EntityReference> response) {
		final QueryTelemetry telemetry = Objects.requireNonNull(
			response.getExtraResult(QueryTelemetry.class), "The query must collect telemetry!"
		);
		if (!hasStep(telemetry, QueryPhase.PLANNING_FILTER, null)) {
			return null;
		}
		return hasStep(telemetry, QueryPhase.PLANNING_FILTER_ALTERNATIVE, REFERENCE_INDEX_OPTION_PREFIX) ?
			Route.OWNER_SIDE : Route.REWRITE;
	}

	/**
	 * Answers whether some telemetry step of the phase exists - carrying an argument starting with the prefix, when
	 * one is passed.
	 *
	 * @param telemetry the telemetry subtree
	 * @param phase     the phase of the step
	 * @param prefix    the argument prefix, or NULL for any step of the phase
	 * @return true when such a step exists
	 */
	private static boolean hasStep(
		@Nonnull QueryTelemetry telemetry,
		@Nonnull QueryPhase phase,
		@Nullable String prefix
	) {
		if (telemetry.getOperation() == phase) {
			if (prefix == null) {
				return true;
			}
			if (telemetry.getArguments() != null) {
				for (String argument : telemetry.getArguments()) {
					if (argument.startsWith(prefix)) {
						return true;
					}
				}
			}
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (hasStep(step, phase, prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Fetches every owner with `rows` filtered by `rowFilter` and asserts that each owner carries exactly the rows
	 * the row predicate selects from its body.
	 *
	 * @param session      session to query through
	 * @param originals    owners as stored
	 * @param rowFilter    the filter applied inside `referenceContent`
	 * @param rowPredicate decides from a row whether it must be fetched
	 */
	private static void assertFetchedRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<SealedEntity> originals,
		@Nonnull FilterConstraint rowFilter,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		final Map<Integer, Set<Integer>> expected = new TreeMap<>();
		for (SealedEntity original : originals) {
			expected.put(original.getPrimaryKeyOrThrowException(), targetsOf(original, rowPredicate));
		}
		assertTrue(
			expected.values().stream().anyMatch(it -> !it.isEmpty()),
			"Fixture guard: some owner must hold a row matching `" + rowFilter + "`!"
		);
		final Map<Integer, Set<Integer>> actual = new TreeMap<>();
		for (SealedEntity fetched : session.queryListOfSealedEntities(
			Query.query(
				collection(ENTITY_OWNER),
				require(entityFetch(referenceContent(REF_ROWS, filterBy(rowFilter))), page(1, Integer.MAX_VALUE))
			)
		)) {
			actual.put(fetched.getPrimaryKeyOrThrowException(), targetsOf(fetched, row -> true));
		}
		assertEquals(expected, actual, "Wrong rows fetched for `" + rowFilter + "`");
	}

	/**
	 * Answers whether the row carries a `u` from the set - the row-level reading of `attributeInSet`, false for a row
	 * without `u`.
	 *
	 * @param row the row
	 * @param set the values
	 * @return true when the row's `u` is one of the values
	 */
	private static boolean inSet(@Nonnull ReferenceContract row, @Nonnull Set<String> set) {
		final String u = row.getAttribute(U);
		return u != null && set.contains(u);
	}

	/**
	 * Returns the targets of the entity's `rows` the predicate selects.
	 *
	 * @param entity       the owner
	 * @param rowPredicate the row selection
	 * @return sorted target primary keys
	 */
	@Nonnull
	private static Set<Integer> targetsOf(
		@Nonnull SealedEntity entity,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return entity.getReferences(REF_ROWS).stream()
			.filter(rowPredicate)
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Fetches every entity of the collection with all its content.
	 *
	 * @param session    session to query through
	 * @param entityType collection to read
	 * @return every entity, fully loaded
	 */
	@Nonnull
	private static List<SealedEntity> fetchAll(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		return session.queryListOfSealedEntities(
			Query.query(
				collection(entityType),
				require(entityFetch(entityFetchAllContent()), page(1, Integer.MAX_VALUE))
			)
		);
	}

	/**
	 * Writes one owner with its `rows` references.
	 *
	 * @param session session to write through
	 * @param pk      primary key of the owner
	 * @param rows    rows of `rows`
	 */
	private static void upsertOwner(@Nonnull EvitaSessionContract session, int pk, @Nonnull Row... rows) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, pk);
		for (Row row : rows) {
			builder.setReference(
				REF_ROWS, row.target(),
				whichIs -> {
					if (row.u() != null) {
						whichIs.setAttribute(U, row.u());
					}
					whichIs.setAttribute(B, row.b());
				}
			);
		}
		session.upsertEntity(builder);
	}

	/**
	 * One row of `rows`; a NULL `u` is left unset.
	 *
	 * @param target referenced target primary key
	 * @param u      value of `u`
	 * @param b      value of `b`
	 */
	private record Row(int target, @Nullable String u, long b) {
	}

}
