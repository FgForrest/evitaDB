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
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
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
import java.util.Locale;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins `attributeIsNull` inside a `referenceHaving` body (issue #1584) against a small fixture whose partitions are
 * shaped so that every way of getting it wrong changes the answer.
 *
 * A `referenceHaving(R, body)` matches an owner when **one** row of `R` satisfies the whole body. The engine answers
 * it in two stages: candidate discovery asks the type-level index which partitions (reduced indexes, one per
 * referenced entity) could hold a matching row, and the body is then evaluated row by row inside each candidate.
 * `attributeIsNull` used to break both stages:
 *
 * - **discovery** computed "partitions none of whose rows carries the attribute" exactly, although the type-level
 *   index can only tell that *some* row carries it - so a partition holding a null row next to a non-null one was
 *   never a candidate;
 * - **per-row evaluation** built its per-partition leaves untagged, so the row-scoping rebuild read them as
 *   index-independent and combined a null row of one partition with a sibling constraint satisfied by another;
 * - a **unique** attribute skipped every partition that has no unique index for it, although such a partition is
 *   exactly one in which every row is null.
 *
 * Every expectation is computed from the entity bodies, never from another query, and every owner-level query runs
 * with and without `PREFER_INDEX_SCAN` so both plans are held to the same answer.
 *
 * ## The fixture
 *
 * `rowOwner` holds three references to `rowTarget` (targets 1-3 are the partitions): `rows` (`a`, `b`, unique `u`),
 * `links` (`x`, array `arr`, localized `loc`) and `empty`, which is never written. `rowTarget.owners` reflects
 * `rows`, which is the direction in which the bidirectional rewrite answers. Rows as `(a, b, u)`:
 *
 * | owner | `rows` |
 * |---|---|
 * | 1 | T1 `(⊥, 1, ⊥)`, T2 `(5, 2, x1)` |
 * | 2 | T1 `(⊥, 2, ⊥)` |
 * | 3 | T2 `(5, 2, x3)` |
 * | 4 | T3 `(⊥, 1, ⊥)`, T2 `(5, 1, x4)` |
 * | 5 | T3 `(7, 2, x5)` |
 * | 6-25 | T2 `(9, 3, ⊥)` - fillers that make the rewrite pay off from the target end |
 *
 * So for `a`, T1 is a partition in which **no** row carries it (it has no filter index for `a` at all), T3 mixes a
 * null row with a non-null one, and T2 carries it on every row. `links` repeats the pattern for `x`, and adds an
 * array attribute and a localized one - see {@link #setUpRowScopedNullDataSet(Evita)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("attributeIsNull inside a referenceHaving body")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
public class ReferenceHavingAttributeIsNullFunctionalTest {
	private static final String ROW_SCOPED_NULL = "rowScopedNull";
	private static final String ENTITY_OWNER = "rowOwner";
	private static final String ENTITY_TARGET = "rowTarget";
	private static final String REF_ROWS = "rows";
	private static final String REF_LINKS = "links";
	private static final String REF_EMPTY = "empty";
	private static final String REF_OWNERS = "owners";
	private static final String ATTR_NAME = "name";
	/**
	 * `rows`: `Long`, filterable, nullable - the attribute each partition shape is built around.
	 */
	private static final String A = "a";
	/**
	 * `rows`: `Long`, filterable, set on every row - the sibling constraint a null row is conjoined with.
	 */
	private static final String B = "b";
	/**
	 * `rows`: `String`, unique, nullable.
	 */
	private static final String U = "u";
	/**
	 * `links`: `Long`, filterable, nullable.
	 */
	private static final String X = "x";
	/**
	 * `links`: `Long[]`, filterable, nullable.
	 */
	private static final String ARR = "arr";
	/**
	 * `links`: `String`, filterable, localized, nullable.
	 */
	private static final String LOC = "loc";
	/**
	 * `empty`: `Long`, filterable, nullable - declared on a reference that never receives a row.
	 */
	private static final String E = "e";
	/**
	 * An attribute no reference of the fixture declares.
	 */
	private static final String UNKNOWN = "unknownAttribute";
	private static final int TARGET_COUNT = 3;
	private static final int FIRST_FILLER_PK = 6;
	private static final int LAST_FILLER_PK = 25;

	/**
	 * Builds the fixture described on the class.
	 *
	 * `links` rows as `(x, arr, loc)`, `loc` listed per locale:
	 *
	 * | owner | `links` |
	 * |---|---|
	 * | 1 | T1 `(⊥, [1, 2], en)`, T2 `(3, ⊥, de)` |
	 * | 2 | T1 `(4, ⊥, -)` |
	 * | 3 | T3 `(⊥, [5], en + de)` |
	 * | 4 | T2 `(⊥, [1, 6], de)` |
	 * | 5 | T1 `(7, [9], de)` |
	 *
	 * In German, T1 therefore mixes a row carrying `loc` only in English (owner 1) and a row carrying none (owner 2)
	 * with a row carrying it (owner 5); T1 and T2 each mix an array-valued row with a null one. Every owner carries
	 * a `name` in both locales, so `entityLocaleEquals` keeps every owner in play and the oracle need not narrow.
	 *
	 * @param evita the engine instance provided by the test extension
	 * @return the owners and targets as stored, for the oracles
	 */
	@DataSet(value = ROW_SCOPED_NULL, destroyAfterClass = true)
	DataCarrier setUpRowScopedNullDataSet(@Nonnull Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_OWNER)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized())
					.withReferenceToEntity(
						REF_ROWS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.faceted()
							.withAttribute(A, Long.class, thatIs -> thatIs.filterable().nullable())
							.withAttribute(B, Long.class, thatIs -> thatIs.filterable())
							.withAttribute(U, String.class, thatIs -> thatIs.unique().nullable())
					)
					.withReferenceToEntity(
						REF_LINKS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(X, Long.class, thatIs -> thatIs.filterable().nullable())
							.withAttribute(ARR, Long[].class, thatIs -> thatIs.filterable().nullable())
							.withAttribute(LOC, String.class, thatIs -> thatIs.filterable().localized().nullable())
					)
					.withReferenceToEntity(
						REF_EMPTY, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(E, Long.class, thatIs -> thatIs.filterable().nullable())
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

				upsertOwner(
					session, 1,
					new Row[]{row(1, null, 1L, null), row(2, 5L, 2L, "x1")},
					new Link[]{link(1, null, new Long[]{1L, 2L}, "p1en", null), link(2, 3L, null, null, "p1de")}
				);
				upsertOwner(
					session, 2,
					new Row[]{row(1, null, 2L, null)},
					new Link[]{link(1, 4L, null, null, null)}
				);
				upsertOwner(
					session, 3,
					new Row[]{row(2, 5L, 2L, "x3")},
					new Link[]{link(3, null, new Long[]{5L}, "p3en", "p3de")}
				);
				upsertOwner(
					session, 4,
					new Row[]{row(3, null, 1L, null), row(2, 5L, 1L, "x4")},
					new Link[]{link(2, null, new Long[]{1L, 6L}, null, "p4de")}
				);
				upsertOwner(
					session, 5,
					new Row[]{row(3, 7L, 2L, "x5")},
					new Link[]{link(1, 7L, new Long[]{9L}, null, "p5de")}
				);
				for (int fillerPk = FIRST_FILLER_PK; fillerPk <= LAST_FILLER_PK; fillerPk++) {
					upsertOwner(session, fillerPk, new Row[]{row(2, 9L, 3L, null)}, new Link[0]);
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
	 * Candidate discovery must keep every partition that holds at least one null row, although the type-level index
	 * it consults can only tell that some row of the partition carries the attribute.
	 */
	@DisplayName("Candidate discovery")
	@Nested
	class CandidateDiscovery {

		/**
		 * Owner 4 holds its only null `a` row in T3, next to owner 5's non-null one, and owner 1 holds its only null
		 * `x` row in T1 next to two non-null ones. An exact subtraction at the type level removes T3 (and T1 for
		 * `x`) because *some* row there carries the attribute, and the owners go missing.
		 */
		@DisplayName("Should keep a partition that mixes null and non-null rows")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldKeepAPartitionThatMixesNullAndNonNullRows(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertOwners(
						session, originalOwners, referenceHaving(REF_ROWS, attributeIsNull(A)),
						anyRow(REF_ROWS, row -> row.getAttribute(A) == null)
					);
					assertOwners(
						session, originalOwners, referenceHaving(REF_LINKS, attributeIsNull(X)),
						anyRow(REF_LINKS, row -> row.getAttribute(X) == null)
					);
					return null;
				}
			);
		}

		/**
		 * The fetch path discovers its partitions through the same type-level evaluation, so a mixed partition lost
		 * there loses its rows from the fetched entity. The row sets are compared exactly, owner by owner.
		 */
		@DisplayName("Should fetch exactly the null rows")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldFetchExactlyTheNullRows(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFetchedRows(
						session, originalOwners, REF_ROWS, attributeIsNull(A), row -> row.getAttribute(A) == null
					);
					assertFetchedRows(
						session, originalOwners, REF_LINKS, attributeIsNull(X), row -> row.getAttribute(X) == null
					);
					return null;
				}
			);
		}

		/**
		 * Widening the discovery must not skip resolving the attribute: an attribute the reference does not declare is
		 * still refused.
		 *
		 * Only on a populated reference. On one that never received a row the body is never translated at all -
		 * discovery finds no type-level index and returns nothing before reaching it - so the unknown attribute is
		 * silently accepted there, with or without this change.
		 */
		@DisplayName("Should still refuse an attribute the reference does not declare")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldStillRefuseAnAttributeTheReferenceDoesNotDeclare(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (boolean preferIndexScan : new boolean[]{true, false}) {
						assertThrows(
							AttributeNotFoundException.class,
							() -> queryOwners(
								session, referenceHaving(REF_ROWS, attributeIsNull(UNKNOWN)), preferIndexScan
							),
							"`" + UNKNOWN + "` is not declared on `" + REF_ROWS + "` and must be refused " +
								"(preferIndexScan=" + preferIndexScan + ")"
						);
					}
					return null;
				}
			);
		}

		/**
		 * A reference that never received a row has no owner to return, whatever its body asks for.
		 */
		@DisplayName("Should return nothing for a reference without rows")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldReturnNothingForAReferenceWithoutRows(Evita evita, List<SealedEntity> originalOwners) {
			assertTrue(
				originalOwners.stream().allMatch(it -> it.getReferences(REF_EMPTY).isEmpty()),
				"Fixture guard: `" + REF_EMPTY + "` must hold no row!"
			);
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (boolean preferIndexScan : new boolean[]{true, false}) {
						assertTrue(
							pks(queryOwners(session, referenceHaving(REF_EMPTY, attributeIsNull(E)), preferIndexScan))
								.isEmpty(),
							"A reference without rows matches no owner (preferIndexScan=" + preferIndexScan + ")"
						);
					}
					return null;
				}
			);
		}
	}

	/**
	 * Runs the filter against the owner collection under both index-scan preferences and asserts the owners equal the
	 * ones the oracle selects from the entity bodies.
	 *
	 * @param session   session to query through
	 * @param originals owners as stored
	 * @param filter    the filter to apply
	 * @param oracle    decides from an owner's body whether it must be returned
	 */
	private static void assertOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<SealedEntity> originals,
		@Nonnull FilterConstraint filter,
		@Nonnull Predicate<SealedEntity> oracle
	) {
		final Set<Integer> expected = selectPks(originals, oracle);
		for (boolean preferIndexScan : new boolean[]{true, false}) {
			assertEquals(
				expected, pks(queryOwners(session, filter, preferIndexScan)),
				"Wrong owners for `" + filter + "` (preferIndexScan=" + preferIndexScan + ")"
			);
		}
	}

	/**
	 * Fetches every owner with the reference filtered by `rowFilter` and asserts that each owner carries exactly the
	 * rows the row predicate selects from its body - no more, no fewer.
	 *
	 * @param session       session to query through
	 * @param originals     owners as stored
	 * @param referenceName reference whose rows are fetched
	 * @param rowFilter     the filter applied inside `referenceContent`
	 * @param rowPredicate  decides from a row whether it must be fetched
	 */
	private static void assertFetchedRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<SealedEntity> originals,
		@Nonnull String referenceName,
		@Nonnull FilterConstraint rowFilter,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		final Map<Integer, Set<Integer>> expected = new TreeMap<>();
		for (SealedEntity original : originals) {
			expected.put(
				original.getPrimaryKeyOrThrowException(),
				original.getReferences(referenceName).stream()
					.filter(rowPredicate)
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(TreeSet::new))
			);
		}
		assertTrue(
			expected.values().stream().anyMatch(it -> !it.isEmpty()),
			"Fixture guard: some owner must hold a row matching `" + rowFilter + "` on `" + referenceName + "`!"
		);
		final Map<Integer, Set<Integer>> actual = new TreeMap<>();
		for (SealedEntity fetched : session.queryListOfSealedEntities(
			Query.query(
				collection(ENTITY_OWNER),
				require(
					entityFetch(referenceContent(referenceName, filterBy(rowFilter))),
					page(1, Integer.MAX_VALUE)
				)
			)
		)) {
			actual.put(
				fetched.getPrimaryKeyOrThrowException(),
				fetched.getReferences(referenceName).stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toCollection(TreeSet::new))
			);
		}
		assertEquals(expected, actual, "Wrong rows fetched for `" + rowFilter + "` on `" + referenceName + "`");
	}

	/**
	 * Queries the owner collection with the passed filter, collecting the telemetry the route assertions read.
	 *
	 * @param session         session to query through
	 * @param filter          the filter to apply
	 * @param preferIndexScan whether to forbid answering from prefetched entity bodies
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint filter,
		boolean preferIndexScan
	) {
		return query(session, ENTITY_OWNER, filter, preferIndexScan);
	}

	/**
	 * Queries the passed collection with the passed filter, collecting the telemetry the route assertions read.
	 *
	 * @param session         session to query through
	 * @param entityType      collection to query
	 * @param filter          the filter to apply
	 * @param preferIndexScan whether to forbid answering from prefetched entity bodies
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> query(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull FilterConstraint filter,
		boolean preferIndexScan
	) {
		return session.query(
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
	}

	/**
	 * Returns an owner-level oracle matching owners that hold at least one row of the reference satisfying the
	 * predicate - the existential reading of `referenceHaving`.
	 *
	 * @param referenceName reference whose rows are examined
	 * @param rowPredicate  the body, evaluated on one row
	 * @return the owner-level oracle
	 */
	@Nonnull
	private static Predicate<SealedEntity> anyRow(
		@Nonnull String referenceName,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		return entity -> entity.getReferences(referenceName).stream().anyMatch(rowPredicate);
	}

	/**
	 * Returns the primary keys of the passed entities the predicate selects.
	 *
	 * @param entities  entities to select from
	 * @param predicate the selection
	 * @return sorted primary keys
	 */
	@Nonnull
	private static Set<Integer> selectPks(@Nonnull List<SealedEntity> entities, @Nonnull Predicate<SealedEntity> predicate) {
		return entities.stream()
			.filter(predicate)
			.map(SealedEntity::getPrimaryKeyOrThrowException)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Returns the primary keys of the returned records.
	 *
	 * @param response the response to read
	 * @return sorted primary keys
	 */
	@Nonnull
	private static Set<Integer> pks(@Nonnull EvitaResponse<EntityReference> response) {
		return response.getRecordData().stream()
			.map(EntityReference::getPrimaryKey)
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
				require(entityFetch(entityFetchAllContent()), dataInLocalesAll(), page(1, Integer.MAX_VALUE))
			)
		);
	}

	/**
	 * Writes one owner with its `rows` and `links` references and a `name` in both locales.
	 *
	 * @param session session to write through
	 * @param pk      primary key of the owner
	 * @param rows    rows of `rows`
	 * @param links   rows of `links`
	 */
	private static void upsertOwner(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull Row[] rows,
		@Nonnull Link[] links
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_OWNER, pk)
			.setAttribute(ATTR_NAME, Locale.ENGLISH, "owner" + pk)
			.setAttribute(ATTR_NAME, Locale.GERMAN, "Besitzer" + pk);
		for (Row row : rows) {
			builder.setReference(
				REF_ROWS, row.target(),
				whichIs -> {
					if (row.a() != null) {
						whichIs.setAttribute(A, row.a());
					}
					whichIs.setAttribute(B, row.b());
					if (row.u() != null) {
						whichIs.setAttribute(U, row.u());
					}
				}
			);
		}
		for (Link link : links) {
			builder.setReference(
				REF_LINKS, link.target(),
				whichIs -> {
					if (link.x() != null) {
						whichIs.setAttribute(X, link.x());
					}
					if (link.arr() != null) {
						whichIs.setAttribute(ARR, link.arr());
					}
					if (link.locEn() != null) {
						whichIs.setAttribute(LOC, Locale.ENGLISH, link.locEn());
					}
					if (link.locDe() != null) {
						whichIs.setAttribute(LOC, Locale.GERMAN, link.locDe());
					}
				}
			);
		}
		session.upsertEntity(builder);
	}

	/**
	 * Shorthand for a {@link Row}.
	 */
	@Nonnull
	private static Row row(int target, @Nullable Long a, long b, @Nullable String u) {
		return new Row(target, a, b, u);
	}

	/**
	 * Shorthand for a {@link Link}.
	 */
	@Nonnull
	private static Link link(
		int target, @Nullable Long x, @Nullable Long[] arr, @Nullable String locEn, @Nullable String locDe
	) {
		return new Link(target, x, arr, locEn, locDe);
	}

	/**
	 * One row of `rows`; a NULL component is left unset.
	 *
	 * @param target referenced target primary key
	 * @param a      value of `a`
	 * @param b      value of `b`
	 * @param u      value of `u`
	 */
	private record Row(int target, @Nullable Long a, long b, @Nullable String u) {
	}

	/**
	 * One row of `links`; a NULL component is left unset.
	 *
	 * @param target referenced target primary key
	 * @param x      value of `x`
	 * @param arr    value of `arr`
	 * @param locEn  English value of `loc`
	 * @param locDe  German value of `loc`
	 */
	private record Link(
		int target, @Nullable Long x, @Nullable Long[] arr, @Nullable String locEn, @Nullable String locDe
	) {
	}

}
