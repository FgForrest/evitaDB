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
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
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
import java.util.Locale;
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
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins `attributeIsNull` inside a `referenceHaving` body against a small fixture whose partitions are shaped so that
 * every way of getting it wrong changes the answer.
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
 * `links` (`x`, array `arr`, localized `loc`, localized unique `lu`) and `empty`, which is never written.
 * `rowTarget.owners` reflects `rows`, which is the direction in which the bidirectional rewrite answers. Rows as
 * `(a, b, u)`:
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
 * null row with a non-null one, and T2 carries it on every row. For `u`, T1 has no index at all and T2 and T3 are
 * mixed. `links` repeats the pattern for `x`, and adds an array attribute and two localized ones - see
 * {@link #setUpRowScopedNullDataSet(Evita)}. The multi-scope rows use a fixture of their own, so that no archived
 * entity can change which route the single-scope rows take - see {@link #setUpMultiScopeNullDataSet(Evita)} - and so
 * do the rows of a localized attribute whose uniqueness differs per scope - see
 * {@link #setUpMixedUniquenessNullDataSet(Evita)}.
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
	private static final String MULTI_SCOPE_NULL = "rowScopedNullMultiScope";
	private static final String MIXED_UNIQUENESS_NULL = "rowScopedNullMixedUniqueness";
	private static final String ENTITY_MIXED_OWNER = "mixedOwner";
	private static final String ENTITY_MIXED_TARGET = "mixedTarget";
	private static final String REF_TAGS = "tags";
	/**
	 * `mixedOwner`: `String`, localized, nullable, unique across locales in LIVE and within a locale in ARCHIVED.
	 */
	private static final String LABEL = "label";
	/**
	 * `tags`: `String`, localized, nullable, unique across locales in LIVE and within a locale in ARCHIVED.
	 */
	private static final String TAG = "tag";
	/**
	 * Every locale `mixedOwner` declares.
	 */
	private static final List<Locale> MIXED_LOCALES = List.of(Locale.ENGLISH, Locale.GERMAN);
	private static final String ENTITY_OWNER = "rowOwner";
	private static final String ENTITY_TARGET = "rowTarget";
	private static final String ENTITY_SCOPED_OWNER = "scopedRowOwner";
	private static final String ENTITY_SCOPED_TARGET = "scopedRowTarget";
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
	 * `links`: `String`, localized, unique across locales (not within a locale), nullable.
	 */
	private static final String LU = "lu";
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
	 * Prefix of the `PLANNING_FILTER_ALTERNATIVE` argument describing the owner-side reduced-index option.
	 */
	private static final String REFERENCE_INDEX_OPTION_PREFIX = "Index type: REFERENCED_ENTITY composed of ";

	/**
	 * Builds the fixture described on the class.
	 *
	 * `links` rows as `(x, arr, loc, lu)`, the localized values listed per locale:
	 *
	 * | owner | `links` |
	 * |---|---|
	 * | 1 | T1 `(⊥, [1, 2], en, -)`, T2 `(3, ⊥, de, -)` |
	 * | 2 | T1 `(4, ⊥, -, en)` |
	 * | 3 | T3 `(⊥, [5], en + de, de)` |
	 * | 4 | T2 `(⊥, [1, 6], de, -)` |
	 * | 5 | T1 `(7, [9], de, -)` |
	 *
	 * In German, T1 therefore mixes a row carrying `loc` only in English (owner 1) and a row carrying none (owner 2)
	 * with a row carrying it (owner 5); T1 and T2 each mix an array-valued row with a null one. Owner 2's only row
	 * carries `lu` in English alone, which is what tells "no value in the query locale" from "no value at all" apart.
	 * Every owner carries a `name` in both locales, so `entityLocaleEquals` keeps every owner in play and the oracle
	 * need not narrow.
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
							.withAttribute(LU, String.class, thatIs -> thatIs.unique().localized().nullable())
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
					new Link[]{
						link(1, null, new Long[]{1L, 2L}, "p1en", null, null, null),
						link(2, 3L, null, null, "p1de", null, null)
					}
				);
				upsertOwner(
					session, 2,
					new Row[]{row(1, null, 2L, null)},
					new Link[]{link(1, 4L, null, null, null, "lu2en", null)}
				);
				upsertOwner(
					session, 3,
					new Row[]{row(2, 5L, 2L, "x3")},
					new Link[]{link(3, null, new Long[]{5L}, "p3en", "p3de", null, "lu3de")}
				);
				upsertOwner(
					session, 4,
					new Row[]{row(3, null, 1L, null), row(2, 5L, 1L, "x4")},
					new Link[]{link(2, null, new Long[]{1L, 6L}, null, "p4de", null, null)}
				);
				upsertOwner(
					session, 5,
					new Row[]{row(3, 7L, 2L, "x5")},
					new Link[]{link(1, 7L, new Long[]{9L}, null, "p5de", null, null)}
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
	 * Builds the multi-scope fixture: `scopedRowOwner` with `rows` (`a`, `b`) indexed in both scopes, pointing at
	 * two live targets. Rows as `(a, b)`:
	 *
	 * | owner | scope | `rows` |
	 * |---|---|---|
	 * | 1 | LIVE | T1 `(⊥, 1)`, T2 `(5, 2)` |
	 * | 2 | LIVE | T1 `(9, 2)` |
	 * | 3 | LIVE | T1 `(⊥, 2)` |
	 * | 4 | ARCHIVED | T1 `(⊥, 1)`, T2 `(7, 1)` |
	 * | 5 | ARCHIVED | T2 `(8, 2)` |
	 * | 6 | ARCHIVED | T2 `(⊥, 2)` |
	 *
	 * Each scope has its own partition family: in LIVE T1 is mixed and T2 fully carries `a`; in ARCHIVED T1 carries
	 * no `a` at all and T2 is mixed - so both scopes hold a partition the old exact subtraction dropped.
	 *
	 * @param evita the engine instance provided by the test extension
	 * @return the owners as stored, in both scopes
	 */
	@DataSet(value = MULTI_SCOPE_NULL, destroyAfterClass = true)
	DataCarrier setUpMultiScopeNullDataSet(@Nonnull Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_SCOPED_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_SCOPED_OWNER)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_ROWS, ENTITY_SCOPED_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(A, Long.class, thatIs -> thatIs.filterableInScope(Scope.values()).nullable())
							.withAttribute(B, Long.class, thatIs -> thatIs.filterableInScope(Scope.values()))
					)
					.updateVia(session);
				for (int targetPk = 1; targetPk <= 2; targetPk++) {
					session.upsertEntity(session.createNewEntity(ENTITY_SCOPED_TARGET, targetPk));
				}
				upsertScopedOwner(session, 1, row(1, null, 1L, null), row(2, 5L, 2L, null));
				upsertScopedOwner(session, 2, row(1, 9L, 2L, null));
				upsertScopedOwner(session, 3, row(1, null, 2L, null));
				upsertScopedOwner(session, 4, row(1, null, 1L, null), row(2, 7L, 1L, null));
				upsertScopedOwner(session, 5, row(2, 8L, 2L, null));
				upsertScopedOwner(session, 6, row(2, null, 2L, null));
				for (int archivedPk = 4; archivedPk <= 6; archivedPk++) {
					session.archiveEntity(ENTITY_SCOPED_OWNER, archivedPk);
				}

				final List<SealedEntity> owners = session.queryListOfSealedEntities(
					Query.query(
						collection(ENTITY_SCOPED_OWNER),
						filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
						require(entityFetch(entityFetchAllContent()), page(1, Integer.MAX_VALUE))
					)
				);
				assertEquals(
					3, owners.stream().filter(it -> it.getScope() == Scope.ARCHIVED).count(),
					"Fixture guard: three owners must be archived!"
				);
				return new DataCarrier("originalScopedOwners", owners);
			}
		);
	}

	/**
	 * Builds the fixture of a localized attribute whose uniqueness differs per scope: `mixedOwner` (locales EN, DE)
	 * with the entity attribute `label` and the `tags` reference attribute `tag`, both unique across locales in LIVE
	 * and unique within a locale in ARCHIVED. The schema builder cannot express that mix - a per-scope uniqueness call
	 * replaces the whole per-scope map - so it is set by a raw {@link SetAttributeSchemaUniqueMutation}, the shape
	 * the external schema APIs accept. Values as `label`, then `tags` rows as `target (tag)`:
	 *
	 * | owner | scope | `label` | `tags` |
	 * |---|---|---|---|
	 * | 1 | LIVE | en | T1 `(en)` |
	 * | 2 | LIVE | ⊥ | T1 `(⊥)` |
	 * | 3 | ARCHIVED | de | T1 `(de)` |
	 * | 4 | ARCHIVED | ⊥ | T1 `(⊥)` |
	 * | 5 | ARCHIVED | en | T2 `(en)` |
	 * | 6 | ARCHIVED | en + de | T1 `(en)`, T2 `(⊥)` |
	 *
	 * The archived owners 3 and 5 carry a value in one locale only, and in different ones, so a null test that
	 * consults a single locale - or none - misreads at least one of them.
	 *
	 * @param evita the engine instance provided by the test extension
	 * @return the owners as stored, in both scopes and every locale
	 */
	@DataSet(value = MIXED_UNIQUENESS_NULL, destroyAfterClass = true)
	DataCarrier setUpMixedUniquenessNullDataSet(@Nonnull Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_MIXED_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_MIXED_OWNER)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withAttribute(
						LABEL, String.class, thatIs -> thatIs.uniqueInScope(Scope.values()).localized().nullable()
					)
					.withReferenceToEntity(
						REF_TAGS, ENTITY_MIXED_TARGET, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioningInScope(Scope.values())
							.withAttribute(
								TAG, String.class, thatIs -> thatIs.uniqueInScope(Scope.values()).localized().nullable()
							)
					)
					.updateVia(session);
				final ScopedAttributeUniquenessType[] mixedUniqueness = {
					new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
					new ScopedAttributeUniquenessType(
						Scope.ARCHIVED, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
					)
				};
				session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						ENTITY_MIXED_OWNER,
						new SetAttributeSchemaUniqueMutation(LABEL, mixedUniqueness),
						new ModifyReferenceAttributeSchemaMutation(
							REF_TAGS, new SetAttributeSchemaUniqueMutation(TAG, mixedUniqueness)
						)
					)
				);
				for (int targetPk = 1; targetPk <= 2; targetPk++) {
					session.upsertEntity(session.createNewEntity(ENTITY_MIXED_TARGET, targetPk));
				}

				upsertMixedOwner(session, 1, Map.of(Locale.ENGLISH, "label-1"), tagRow(1, Locale.ENGLISH, "tag-1"));
				upsertMixedOwner(session, 2, Map.of(), tagRow(1, null, null));
				upsertMixedOwner(session, 3, Map.of(Locale.GERMAN, "label-3"), tagRow(1, Locale.GERMAN, "tag-3"));
				upsertMixedOwner(session, 4, Map.of(), tagRow(1, null, null));
				upsertMixedOwner(session, 5, Map.of(Locale.ENGLISH, "label-5"), tagRow(2, Locale.ENGLISH, "tag-5"));
				upsertMixedOwner(
					session, 6, Map.of(Locale.ENGLISH, "label-6en", Locale.GERMAN, "label-6de"),
					tagRow(1, Locale.ENGLISH, "tag-6"), tagRow(2, null, null)
				);
				for (int archivedPk = 3; archivedPk <= 6; archivedPk++) {
					session.archiveEntity(ENTITY_MIXED_OWNER, archivedPk);
				}

				final List<SealedEntity> owners = session.queryListOfSealedEntities(
					Query.query(
						collection(ENTITY_MIXED_OWNER),
						filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
						require(entityFetch(entityFetchAllContent()), dataInLocalesAll(), page(1, Integer.MAX_VALUE))
					)
				);
				assertEquals(6, owners.size(), "Fixture guard: unexpected owner count!");
				final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(ENTITY_MIXED_OWNER);
				for (AttributeSchemaContract attributeSchema : List.of(
					schema.getAttribute(LABEL).orElseThrow(),
					schema.getReferenceOrThrowException(REF_TAGS).getAttribute(TAG).orElseThrow()
				)) {
					assertEquals(
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, attributeSchema.getUniquenessType(Scope.LIVE),
						"Fixture guard: `" + attributeSchema.getName() + "` must be unique across locales in LIVE!"
					);
					assertEquals(
						AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE,
						attributeSchema.getUniquenessType(Scope.ARCHIVED),
						"Fixture guard: `" + attributeSchema.getName() + "` must be unique within a locale in ARCHIVED!"
					);
				}
				return new DataCarrier("originalMixedOwners", owners);
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
						session, originalOwners, anyRow(REF_ROWS, row -> row.getAttribute(A) == null),
						referenceHaving(REF_ROWS, attributeIsNull(A))
					);
					assertOwners(
						session, originalOwners, anyRow(REF_LINKS, row -> row.getAttribute(X) == null),
						referenceHaving(REF_LINKS, attributeIsNull(X))
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
							() -> query(
								session, ENTITY_OWNER, preferIndexScan,
								referenceHaving(REF_ROWS, attributeIsNull(UNKNOWN))
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
							pks(
								query(
									session, ENTITY_OWNER, preferIndexScan,
									referenceHaving(REF_EMPTY, attributeIsNull(E))
								)
							).isEmpty(),
							"A reference without rows matches no owner (preferIndexScan=" + preferIndexScan + ")"
						);
					}
					return null;
				}
			);
		}
	}

	/**
	 * The null test must be answered against one row at a time, like every other leaf of the body. Each shape below
	 * combines T1 (no row carries `a`, so the partition has no filter index for it) with T3 (a null row next to a
	 * non-null one) under a different connective - the combination that fails when a partition without the index is
	 * read as contributing nothing instead of contributing every row.
	 */
	@DisplayName("Row scoping of a filterable attribute")
	@Nested
	class RowScopingOfAFilterableAttribute {

		@DisplayName("Should answer every connective against a single row")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldAnswerEveryConnectiveAgainstASingleRow(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// owner 1 holds a null row with b = 1 and a non-null row with b = 2 - a cross-row reading adds it
					assertRows(
						session, originalOwners, REF_ROWS,
						and(attributeIsNull(A), attributeEquals(B, 2L)),
						row -> row.getAttribute(A) == null && Objects.equals(row.getAttribute(B), 2L)
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						and(attributeIsNull(A), attributeEquals(B, 1L)),
						row -> row.getAttribute(A) == null && Objects.equals(row.getAttribute(B), 1L)
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						and(attributeIsNull(A), not(attributeEquals(B, 1L))),
						row -> row.getAttribute(A) == null && !Objects.equals(row.getAttribute(B), 1L)
					);
					// owners 1 and 4 hold a non-null row next to a null one, and must not be taken away by it
					assertRows(
						session, originalOwners, REF_ROWS,
						not(attributeIsNull(A)),
						row -> row.getAttribute(A) != null
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						not(and(attributeIsNull(A), attributeEquals(B, 2L))),
						row -> !(row.getAttribute(A) == null && Objects.equals(row.getAttribute(B), 2L))
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						and(not(attributeIsNull(A)), attributeEquals(B, 2L)),
						row -> row.getAttribute(A) != null && Objects.equals(row.getAttribute(B), 2L)
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						or(attributeIsNull(A), not(attributeEquals(B, 2L))),
						row -> row.getAttribute(A) == null || !Objects.equals(row.getAttribute(B), 2L)
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						or(
							and(attributeIsNull(A), attributeEquals(B, 1L)),
							and(attributeIsNotNull(A), attributeEquals(B, 3L))
						),
						row -> row.getAttribute(A) == null ?
							Objects.equals(row.getAttribute(B), 1L) : Objects.equals(row.getAttribute(B), 3L)
					);
					// controls: the positive spelling was row-scoped all along
					assertRows(
						session, originalOwners, REF_ROWS,
						and(attributeIsNotNull(A), attributeEquals(B, 2L)),
						row -> row.getAttribute(A) != null && Objects.equals(row.getAttribute(B), 2L)
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						not(attributeIsNotNull(A)),
						row -> row.getAttribute(A) == null
					);
					return null;
				}
			);
		}
	}

	/**
	 * A unique attribute is answered through the filter index every unique attribute also maintains, so a partition
	 * without the attribute contributes every row, exactly as for a filterable one. Checked on both routes the
	 * engine can take and on the fetch path.
	 */
	@DisplayName("Unique attribute")
	@Nested
	class UniqueAttribute {

		/**
		 * Asked from the owner end, the rewrite declines on its cost gate and the reduced indexes of `rows` answer.
		 */
		@DisplayName("Should answer the null test of a unique attribute on the owner side")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldAnswerTheNullTestOfAUniqueAttributeOnTheOwnerSide(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						attributeIsNull(U), row -> row.getAttribute(U) == null
					);
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						not(attributeIsNull(U)), row -> row.getAttribute(U) != null
					);
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						and(attributeIsNull(U), attributeEquals(B, 2L)),
						row -> row.getAttribute(U) == null && Objects.equals(row.getAttribute(B), 2L)
					);
					assertRows(
						session, ENTITY_OWNER, originalOwners, REF_ROWS, Route.OWNER_SIDE,
						attributeIsNotNull(U), row -> row.getAttribute(U) != null
					);
					return null;
				}
			);
		}

		/**
		 * Asked from the target end, a single leaf is answered by the bidirectional rewrite on the owners' reduced
		 * indexes, and a conjunction - which the rewrite declines - by the targets' own. The route is asserted, so
		 * a row cannot pass on the path it was not written for.
		 */
		@DisplayName("Should answer the null test of a unique attribute through the rewrite")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldAnswerTheNullTestOfAUniqueAttributeThroughTheRewrite(
			Evita evita,
			List<SealedEntity> originalTargets
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						attributeIsNull(U), row -> row.getAttribute(U) == null
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						not(attributeIsNull(U)), row -> row.getAttribute(U) != null
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.OWNER_SIDE,
						and(attributeIsNull(U), attributeEquals(B, 2L)),
						row -> row.getAttribute(U) == null && Objects.equals(row.getAttribute(B), 2L)
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						attributeIsNull(A), row -> row.getAttribute(A) == null
					);
					assertRows(
						session, ENTITY_TARGET, originalTargets, REF_OWNERS, Route.REWRITE,
						not(attributeIsNull(A)), row -> row.getAttribute(A) != null
					);
					return null;
				}
			);
		}

		@DisplayName("Should fetch exactly the rows lacking a unique attribute")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldFetchExactlyTheRowsLackingAUniqueAttribute(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFetchedRows(
						session, originalOwners, REF_ROWS, attributeIsNull(U), row -> row.getAttribute(U) == null
					);
					assertFetchedRows(
						session, originalOwners, REF_ROWS, not(attributeIsNull(U)), row -> row.getAttribute(U) != null
					);
					return null;
				}
			);
		}
	}

	/**
	 * Array-valued and localized attributes reach the filter index in their own ways - one row contributes several
	 * values, or a value exists only in some locales - and the null test must still mean "this row, in this locale,
	 * carries nothing".
	 */
	@DisplayName("Array and localized attributes")
	@Nested
	class ArrayAndLocalizedAttributes {

		@DisplayName("Should find the null rows of an array attribute")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldFindTheNullRowsOfAnArrayAttribute(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, originalOwners, REF_LINKS,
						attributeIsNull(ARR), row -> row.getAttribute(ARR) == null
					);
					assertRows(
						session, originalOwners, REF_LINKS,
						not(attributeIsNull(ARR)), row -> row.getAttribute(ARR) != null
					);
					assertRows(
						session, originalOwners, REF_LINKS,
						and(attributeIsNull(ARR), attributeEquals(X, 3L)),
						row -> row.getAttribute(ARR) == null && Objects.equals(row.getAttribute(X), 3L)
					);
					assertFetchedRows(
						session, originalOwners, REF_LINKS, attributeIsNull(ARR), row -> row.getAttribute(ARR) == null
					);
					return null;
				}
			);
		}

		/**
		 * Owner 1's T1 row carries `loc` in English only, so asked in German it is a null row - in a partition where
		 * owner 5's row does carry a German value.
		 */
		@DisplayName("Should find the rows lacking a localized attribute in the query locale")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldFindTheRowsLackingALocalizedAttributeInTheQueryLocale(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
						assertOwners(
							session, originalOwners,
							anyRow(REF_LINKS, row -> row.getAttribute(LOC, locale) == null),
							entityLocaleEquals(locale), referenceHaving(REF_LINKS, attributeIsNull(LOC))
						);
						assertOwners(
							session, originalOwners,
							anyRow(REF_LINKS, row -> row.getAttribute(LOC, locale) != null),
							entityLocaleEquals(locale), referenceHaving(REF_LINKS, not(attributeIsNull(LOC)))
						);
						assertOwners(
							session, originalOwners,
							anyRow(
								REF_LINKS,
								row -> row.getAttribute(LOC, locale) == null && row.getAttribute(X) == null
							),
							entityLocaleEquals(locale),
							referenceHaving(REF_LINKS, and(attributeIsNull(LOC), attributeIsNull(X)))
						);
					}
					return null;
				}
			);
		}

		/**
		 * A localized attribute that is unique across locales (not within one) keeps one unique index for all of its
		 * locales, while its filter index is split per locale. Its null test therefore keeps meaning "carries no value
		 * in any locale" - the reading its `attributeIsNotNull` has always had, so the two still partition the rows -
		 * and still works without a query locale, which such an attribute allows. Owner 2's only row carries `lu` in
		 * English alone, so it is the owner a per-locale reading would add in German.
		 */
		@DisplayName("Should read a localized unique attribute across all its locales")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldReadALocalizedUniqueAttributeAcrossAllItsLocales(Evita evita, List<SealedEntity> originalOwners) {
			final Predicate<ReferenceContract> lacksLuEverywhere =
				row -> row.getAttribute(LU, Locale.ENGLISH) == null && row.getAttribute(LU, Locale.GERMAN) == null;
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertOwners(
						session, originalOwners, anyRow(REF_LINKS, lacksLuEverywhere),
						entityLocaleEquals(Locale.GERMAN), referenceHaving(REF_LINKS, attributeIsNull(LU))
					);
					assertOwners(
						session, originalOwners, anyRow(REF_LINKS, lacksLuEverywhere.negate()),
						entityLocaleEquals(Locale.GERMAN), referenceHaving(REF_LINKS, attributeIsNotNull(LU))
					);
					assertOwners(
						session, originalOwners, anyRow(REF_LINKS, lacksLuEverywhere),
						referenceHaving(REF_LINKS, attributeIsNull(LU))
					);
					return null;
				}
			);
		}
	}

	/**
	 * Null tests on two references in one query, and one nested inside another reference's body through
	 * `entityHaving`, must each stay scoped to the rows of their own reference.
	 */
	@DisplayName("Several references in one query")
	@Nested
	class SeveralReferencesInOneQuery {

		@DisplayName("Should scope sibling null tests to their own reference")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldScopeSiblingNullTestsToTheirOwnReference(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertOwners(
						session, originalOwners,
						anyRow(REF_ROWS, row -> row.getAttribute(A) == null)
							.and(anyRow(REF_LINKS, row -> row.getAttribute(X) == null)),
						referenceHaving(REF_ROWS, attributeIsNull(A)),
						referenceHaving(REF_LINKS, attributeIsNull(X))
					);
					assertOwners(
						session, originalOwners,
						anyRow(REF_ROWS, row -> row.getAttribute(A) == null && Objects.equals(row.getAttribute(B), 1L))
							.and(anyRow(REF_LINKS, row -> row.getAttribute(X) != null)),
						referenceHaving(REF_ROWS, and(attributeIsNull(A), attributeEquals(B, 1L))),
						referenceHaving(REF_LINKS, not(attributeIsNull(X)))
					);
					return null;
				}
			);
		}

		/**
		 * The inner `referenceHaving(owners, attributeIsNull(a))` selects the targets that some owner references
		 * through a row lacking `a`; the outer body then asks for a row that points at such a target and itself
		 * lacks (or carries) `a`.
		 *
		 * This row guards the nesting against regressions but cannot catch a cross-row reading: `entityHaving` narrows
		 * the candidate partitions to exactly the matching targets, so no candidate holds a row it rejects, and every
		 * partition of the reflected `owners` holds a single row. Measured green with the per-index tagging reverted;
		 * the sibling row above is the one that goes red.
		 */
		@DisplayName("Should scope a null test nested through entityHaving")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldScopeANullTestNestedThroughEntityHaving(
			Evita evita,
			List<SealedEntity> originalOwners,
			List<SealedEntity> originalTargets
		) {
			final Set<Integer> targetsWithANullRow = selectPks(
				originalTargets, anyRow(REF_OWNERS, row -> row.getAttribute(A) == null)
			);
			assertFalse(targetsWithANullRow.isEmpty(), "Fixture guard: some target must have a null owner row!");
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertRows(
						session, originalOwners, REF_ROWS,
						and(attributeIsNull(A), entityHaving(referenceHaving(REF_OWNERS, attributeIsNull(A)))),
						row -> row.getAttribute(A) == null &&
							targetsWithANullRow.contains(row.getReferencedPrimaryKey())
					);
					assertRows(
						session, originalOwners, REF_ROWS,
						and(not(attributeIsNull(A)), entityHaving(referenceHaving(REF_OWNERS, attributeIsNull(A)))),
						row -> row.getAttribute(A) != null &&
							targetsWithANullRow.contains(row.getReferencedPrimaryKey())
					);
					return null;
				}
			);
		}
	}

	/**
	 * Every scope has its own partition family, and `inScope` applies a body to one of them only.
	 */
	@DisplayName("Several scopes")
	@Nested
	class SeveralScopes {

		@DisplayName("Should find the null rows in every requested scope")
		@UseDataSet(MULTI_SCOPE_NULL)
		@Test
		void shouldFindTheNullRowsInEveryRequestedScope(Evita evita, List<SealedEntity> originalScopedOwners) {
			final Predicate<ReferenceContract> lacksA = row -> row.getAttribute(A) == null;
			final Predicate<ReferenceContract> lacksAWithB2 =
				row -> row.getAttribute(A) == null && Objects.equals(row.getAttribute(B), 2L);
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatches(
						session, ENTITY_SCOPED_OWNER, originalScopedOwners, anyRow(REF_ROWS, lacksA), null,
						scope(Scope.LIVE, Scope.ARCHIVED), referenceHaving(REF_ROWS, attributeIsNull(A))
					);
					// the archived owners are not constrained at all
					assertMatches(
						session, ENTITY_SCOPED_OWNER, originalScopedOwners,
						it -> it.getScope() == Scope.ARCHIVED || anyRow(REF_ROWS, lacksA).test(it), null,
						scope(Scope.LIVE, Scope.ARCHIVED),
						inScope(Scope.LIVE, referenceHaving(REF_ROWS, attributeIsNull(A)))
					);
					assertMatches(
						session, ENTITY_SCOPED_OWNER, originalScopedOwners, anyRow(REF_ROWS, lacksAWithB2), null,
						scope(Scope.LIVE, Scope.ARCHIVED),
						inScope(
							Scope.LIVE,
							referenceHaving(REF_ROWS, and(attributeIsNull(A), attributeEquals(B, 2L)))
						),
						inScope(
							Scope.ARCHIVED,
							referenceHaving(REF_ROWS, and(attributeIsNull(A), attributeEquals(B, 2L)))
						)
					);
					assertMatches(
						session, ENTITY_SCOPED_OWNER, originalScopedOwners,
						it -> it.getScope() == Scope.ARCHIVED && anyRow(REF_ROWS, lacksA.negate()).test(it), null,
						scope(Scope.ARCHIVED), referenceHaving(REF_ROWS, not(attributeIsNull(A)))
					);
					return null;
				}
			);
		}
	}

	/**
	 * A localized attribute unique across locales in one requested scope and only within a locale in another. The
	 * first scope lets the query through without a locale, so in the second the null test must read "no value in
	 * any locale" too - the reading the first scope gives it - rather than "no value in the missing locale", which
	 * would report every record of that scope as null.
	 */
	@DisplayName("Localized attribute with a scope-dependent uniqueness")
	@Nested
	class LocalizedAttributeWithScopeDependentUniqueness {

		@DisplayName("Should read a localized entity attribute in every locale when no locale is requested")
		@UseDataSet(MIXED_UNIQUENESS_NULL)
		@Test
		void shouldReadALocalizedEntityAttributeInEveryLocaleWhenNoLocaleIsRequested(
			Evita evita,
			List<SealedEntity> originalMixedOwners
		) {
			final Predicate<SealedEntity> lacksLabel =
				owner -> MIXED_LOCALES.stream().allMatch(locale -> owner.getAttribute(LABEL, locale) == null);
			assertArchivedOwnersSplit(originalMixedOwners, lacksLabel, "label");
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : new Scope[][]{{Scope.LIVE, Scope.ARCHIVED}, {Scope.ARCHIVED, Scope.LIVE}}) {
						assertMatches(
							session, ENTITY_MIXED_OWNER, originalMixedOwners, lacksLabel, null,
							scope(order), attributeIsNull(LABEL)
						);
					}
					return null;
				}
			);
		}

		@DisplayName("Should read a localized reference attribute in every locale when no locale is requested")
		@UseDataSet(MIXED_UNIQUENESS_NULL)
		@Test
		void shouldReadALocalizedReferenceAttributeInEveryLocaleWhenNoLocaleIsRequested(
			Evita evita,
			List<SealedEntity> originalMixedOwners
		) {
			final Predicate<ReferenceContract> lacksTag =
				row -> MIXED_LOCALES.stream().allMatch(locale -> row.getAttribute(TAG, locale) == null);
			assertArchivedOwnersSplit(originalMixedOwners, anyRow(REF_TAGS, lacksTag), "a `tags` row without `tag`");
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (Scope[] order : new Scope[][]{{Scope.LIVE, Scope.ARCHIVED}, {Scope.ARCHIVED, Scope.LIVE}}) {
						assertMatches(
							session, ENTITY_MIXED_OWNER, originalMixedOwners, anyRow(REF_TAGS, lacksTag), null,
							scope(order), referenceHaving(REF_TAGS, attributeIsNull(TAG))
						);
					}
					return null;
				}
			);
		}

		/**
		 * Guards the oracle against passing vacuously: among the archived owners, some must match it and some must
		 * not, and among those that do not, one must carry its value in German only - so that neither a null test
		 * reading no locale nor one reading English alone can meet the expectation.
		 *
		 * @param originalMixedOwners owners as stored
		 * @param oracle              the null test, evaluated on an owner's body
		 * @param description         what the oracle selects, for the failure message
		 */
		private static void assertArchivedOwnersSplit(
			@Nonnull List<SealedEntity> originalMixedOwners,
			@Nonnull Predicate<SealedEntity> oracle,
			@Nonnull String description
		) {
			final List<SealedEntity> archived = originalMixedOwners.stream()
				.filter(it -> it.getScope() == Scope.ARCHIVED)
				.toList();
			assertTrue(
				archived.stream().anyMatch(oracle),
				"Fixture guard: some archived owner must hold " + description + "!"
			);
			assertTrue(
				archived.stream().anyMatch(oracle.negate()),
				"Fixture guard: some archived owner must not hold " + description + "!"
			);
			assertTrue(
				archived.stream()
					.filter(oracle.negate())
					.anyMatch(it -> it.getLocales().equals(Set.of(Locale.GERMAN))),
				"Fixture guard: an archived owner outside " + description + " must carry German values only!"
			);
		}
	}

	/**
	 * `facetHaving` is the one caller that evaluates its body on the type-level index **in place**: nothing
	 * re-examines the rows behind the answer, so a null test there reads "a facet none of whose rows carries the
	 * attribute", while its positive leaves read "a facet some of whose rows do". That asymmetry is consistent with
	 * how a negation is resolved in place, and whether it is what `facetHaving` should mean is an open specification
	 * question - these rows pin the answer as it stands so that a change to it is deliberate.
	 */
	@DisplayName("facetHaving")
	@Nested
	@Tag(FACET)
	class FacetHavingNullTest {

		@DisplayName("Should keep reading the null test of facetHaving at the facet level")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldKeepReadingTheNullTestOfFacetHavingAtTheFacetLevel(Evita evita, List<SealedEntity> originalOwners) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					for (String attributeName : List.of(A, U)) {
						final Set<Integer> facetsWithoutAnyValue = new TreeSet<>();
						for (int targetPk = 1; targetPk <= TARGET_COUNT; targetPk++) {
							final int theTargetPk = targetPk;
							final boolean noRowCarries = originalOwners.stream()
								.flatMap(it -> it.getReferences(REF_ROWS).stream())
								.filter(row -> row.getReferencedPrimaryKey() == theTargetPk)
								.allMatch(row -> row.getAttribute(attributeName) == null);
							if (noRowCarries) {
								facetsWithoutAnyValue.add(targetPk);
							}
						}
						assertFalse(facetsWithoutAnyValue.isEmpty(), "Fixture guard: some facet must lack `" +
							attributeName + "` on every row!");
						assertOwners(
							session, originalOwners,
							anyRow(REF_ROWS, row -> facetsWithoutAnyValue.contains(row.getReferencedPrimaryKey())),
							facetHaving(REF_ROWS, attributeIsNull(attributeName))
						);
					}
					return null;
				}
			);
		}
	}

	/**
	 * Identity I1 of the row-scoped semantics (`documentation/adr/2026-09-17-row-scoped-reference-having-body/
	 * row-scoped-semantics.md`, §6): `RH(φ) ∪ RH(¬φ) = RH()` - every row is either null or not. Each side is also
	 * held to its body oracle, and the fixture guard insists on an owner that lands on both sides, so the identity
	 * cannot hold because one side is empty.
	 */
	@DisplayName("Identities")
	@Nested
	class Identities {

		@DisplayName("Should split every owner with a row between the null test and its negation")
		@UseDataSet(ROW_SCOPED_NULL)
		@Test
		void shouldSplitEveryOwnerWithARowBetweenTheNullTestAndItsNegation(
			Evita evita,
			List<SealedEntity> originalOwners
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertIdentityOne(
						session, originalOwners, attributeIsNull(A), row -> row.getAttribute(A) == null
					);
					assertIdentityOne(
						session, originalOwners, attributeIsNotNull(A), row -> row.getAttribute(A) != null
					);
					assertIdentityOne(
						session, originalOwners, attributeIsNull(U), row -> row.getAttribute(U) == null
					);
					return null;
				}
			);
		}

		/**
		 * Asserts `RH(φ) ∪ RH(¬φ) = RH()` on `rows`, with both sides checked against the bodies and non-vacuous.
		 *
		 * @param session      session to query through
		 * @param originals    owners as stored
		 * @param phi          the body φ
		 * @param rowPredicate φ evaluated on one row
		 */
		private static void assertIdentityOne(
			@Nonnull EvitaSessionContract session,
			@Nonnull List<SealedEntity> originals,
			@Nonnull FilterConstraint phi,
			@Nonnull Predicate<ReferenceContract> rowPredicate
		) {
			final Set<Integer> positive = selectPks(originals, anyRow(REF_ROWS, rowPredicate));
			final Set<Integer> negative = selectPks(originals, anyRow(REF_ROWS, rowPredicate.negate()));
			final Set<Integer> both = new TreeSet<>(positive);
			both.retainAll(negative);
			assertFalse(both.isEmpty(), "Fixture guard: some owner must hold a row on each side of `" + phi + "`!");
			for (boolean preferIndexScan : new boolean[]{true, false}) {
				final Set<Integer> phiSide = pks(
					query(session, ENTITY_OWNER, preferIndexScan, referenceHaving(REF_ROWS, phi))
				);
				final Set<Integer> notPhiSide = pks(
					query(session, ENTITY_OWNER, preferIndexScan, referenceHaving(REF_ROWS, not(phi)))
				);
				final Set<Integer> bare = pks(query(session, ENTITY_OWNER, preferIndexScan, referenceHaving(REF_ROWS)));
				assertEquals(positive, phiSide, "RH(" + phi + ") (preferIndexScan=" + preferIndexScan + ")");
				assertEquals(negative, notPhiSide, "RH(not(" + phi + ")) (preferIndexScan=" + preferIndexScan + ")");
				final Set<Integer> union = new TreeSet<>(phiSide);
				union.addAll(notPhiSide);
				assertEquals(bare, union, "I1 for `" + phi + "` (preferIndexScan=" + preferIndexScan + ")");
				assertEquals(
					selectPks(originals, it -> !it.getReferences(REF_ROWS).isEmpty()), bare,
					"RH() (preferIndexScan=" + preferIndexScan + ")"
				);
			}
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
	 * Asserts on the owner collection that `referenceHaving(referenceName, body)` returns the owners holding a row
	 * the row predicate selects.
	 *
	 * @param session       session to query through
	 * @param originals     owners as stored
	 * @param referenceName the reference
	 * @param body          the `referenceHaving` body
	 * @param rowPredicate  the body, evaluated on one row
	 */
	private static void assertRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<SealedEntity> originals,
		@Nonnull String referenceName,
		@Nonnull FilterConstraint body,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		assertRows(session, ENTITY_OWNER, originals, referenceName, null, body, rowPredicate);
	}

	/**
	 * Asserts that `referenceHaving(referenceName, body)` returns the entities holding a row the row predicate
	 * selects, and - when a route is passed - that the index-scan plan took that route.
	 *
	 * @param session       session to query through
	 * @param entityType    collection to query
	 * @param originals     entities of that collection as stored
	 * @param referenceName the reference
	 * @param route         the route the index-scan plan must take, or NULL when any will do
	 * @param body          the `referenceHaving` body
	 * @param rowPredicate  the body, evaluated on one row
	 */
	private static void assertRows(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull List<SealedEntity> originals,
		@Nonnull String referenceName,
		@Nullable Route route,
		@Nonnull FilterConstraint body,
		@Nonnull Predicate<ReferenceContract> rowPredicate
	) {
		assertMatches(
			session, entityType, originals, anyRow(referenceName, rowPredicate), route,
			referenceHaving(referenceName, body)
		);
	}

	/**
	 * Asserts on the owner collection that the filter returns the owners the oracle selects.
	 *
	 * @param session   session to query through
	 * @param originals owners as stored
	 * @param oracle    decides from an owner's body whether it must be returned
	 * @param filter    the filter constraints
	 */
	private static void assertOwners(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<SealedEntity> originals,
		@Nonnull Predicate<SealedEntity> oracle,
		@Nonnull FilterConstraint... filter
	) {
		assertMatches(session, ENTITY_OWNER, originals, oracle, null, filter);
	}

	/**
	 * Runs the filter under both index-scan preferences and asserts the entities equal the ones the oracle selects
	 * from the entity bodies. The route is asserted on the index-scan plan only: without `PREFER_INDEX_SCAN` a
	 * collection this small may be answered from prefetched bodies, which records no route at all.
	 *
	 * @param session    session to query through
	 * @param entityType collection to query
	 * @param originals  entities of that collection as stored
	 * @param oracle     decides from an entity's body whether it must be returned
	 * @param route      the route the index-scan plan must take, or NULL when any will do
	 * @param filter     the filter constraints
	 */
	private static void assertMatches(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull List<SealedEntity> originals,
		@Nonnull Predicate<SealedEntity> oracle,
		@Nullable Route route,
		@Nonnull FilterConstraint... filter
	) {
		final Set<Integer> expected = selectPks(originals, oracle);
		final String description = String.join(", ", Arrays.stream(filter).map(Object::toString).toList());
		for (boolean preferIndexScan : new boolean[]{true, false}) {
			final EvitaResponse<EntityReference> response = query(session, entityType, preferIndexScan, filter);
			assertEquals(
				expected, pks(response),
				"Wrong `" + entityType + "` for `" + description + "` (preferIndexScan=" + preferIndexScan + ")"
			);
			if (route != null && preferIndexScan) {
				assertEquals(
					route, routeOf(response),
					() -> "Wrong route for `" + description + "`:\n" + response.getExtraResult(QueryTelemetry.class)
				);
			}
		}
	}

	/**
	 * Reads the route that answered the query off its telemetry, through the channel
	 * `BidirectionalReferenceRewriteFunctionalTest` documents: the rewrite decides during index selection and, when
	 * it fires, returns before the owner-side reference option is registered - so that option's
	 * `PLANNING_FILTER_ALTERNATIVE` step is present exactly when the owner side answers. The channel holds only for a
	 * `referenceHaving` directly under the filter or an `and`, which is where every route-asserting row puts it.
	 *
	 * @param response the response carrying the telemetry
	 * @return the route, or NULL when the filter was not planned at all
	 */
	@Nullable
	private static Route routeOf(@Nonnull EvitaResponse<EntityReference> response) {
		final QueryTelemetry telemetry = Objects.requireNonNull(
			response.getExtraResult(QueryTelemetry.class), "The query must collect telemetry!"
		);
		if (!hasStep(telemetry, QueryPhase.PLANNING_FILTER)) {
			return null;
		} else if (hasStepArgument(telemetry, QueryPhase.PLANNING_FILTER_ALTERNATIVE, REFERENCE_INDEX_OPTION_PREFIX)) {
			return Route.OWNER_SIDE;
		} else {
			return Route.REWRITE;
		}
	}

	/**
	 * Answers whether some telemetry step belongs to the phase.
	 *
	 * @param telemetry the telemetry subtree
	 * @param phase     the phase looked for
	 * @return true when such a step exists
	 */
	private static boolean hasStep(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return true;
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (hasStep(step, phase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Answers whether some telemetry step of the phase carries an argument starting with the prefix.
	 *
	 * @param telemetry the telemetry subtree
	 * @param phase     the phase of the step
	 * @param prefix    the argument prefix
	 * @return true when such a step exists
	 */
	private static boolean hasStepArgument(
		@Nonnull QueryTelemetry telemetry,
		@Nonnull QueryPhase phase,
		@Nonnull String prefix
	) {
		if (telemetry.getOperation() == phase && telemetry.getArguments() != null) {
			for (String argument : telemetry.getArguments()) {
				if (argument.startsWith(prefix)) {
					return true;
				}
			}
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (hasStepArgument(step, phase, prefix)) {
				return true;
			}
		}
		return false;
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
	 * Queries the passed collection with the passed filter, collecting the telemetry the route assertions read.
	 *
	 * @param session         session to query through
	 * @param entityType      collection to query
	 * @param preferIndexScan whether to forbid answering from prefetched entity bodies
	 * @param filter          the filter constraints
	 * @return the response
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> query(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		boolean preferIndexScan,
		@Nonnull FilterConstraint... filter
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
	private static Set<Integer> selectPks(
		@Nonnull List<SealedEntity> entities,
		@Nonnull Predicate<SealedEntity> predicate
	) {
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
		setRows(builder, rows);
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
					if (link.luEn() != null) {
						whichIs.setAttribute(LU, Locale.ENGLISH, link.luEn());
					}
					if (link.luDe() != null) {
						whichIs.setAttribute(LU, Locale.GERMAN, link.luDe());
					}
				}
			);
		}
		session.upsertEntity(builder);
	}

	/**
	 * Writes one owner of the multi-scope fixture with its `rows` references.
	 *
	 * @param session session to write through
	 * @param pk      primary key of the owner
	 * @param rows    rows of `rows`; `u` is ignored, the fixture does not declare it
	 */
	private static void upsertScopedOwner(@Nonnull EvitaSessionContract session, int pk, @Nonnull Row... rows) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_SCOPED_OWNER, pk);
		setRows(builder, rows);
		session.upsertEntity(builder);
	}

	/**
	 * Writes one owner of the mixed-uniqueness fixture with its `label` values and `tags` rows.
	 *
	 * @param session session to write through
	 * @param pk      primary key of the owner
	 * @param labels  values of `label` per locale
	 * @param rows    rows of `tags`
	 */
	private static void upsertMixedOwner(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull Map<Locale, String> labels,
		@Nonnull TagRow... rows
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_MIXED_OWNER, pk);
		labels.forEach((locale, label) -> builder.setAttribute(LABEL, locale, label));
		for (TagRow row : rows) {
			builder.setReference(
				REF_TAGS, row.target(),
				whichIs -> {
					if (row.locale() != null) {
						whichIs.setAttribute(TAG, row.locale(), row.tag());
					}
				}
			);
		}
		session.upsertEntity(builder);
	}

	/**
	 * Sets the `rows` references on the builder.
	 *
	 * @param builder builder of the owner
	 * @param rows    rows to set
	 */
	private static void setRows(@Nonnull EntityBuilder builder, @Nonnull Row[] rows) {
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
		int target, @Nullable Long x, @Nullable Long[] arr,
		@Nullable String locEn, @Nullable String locDe, @Nullable String luEn, @Nullable String luDe
	) {
		return new Link(target, x, arr, locEn, locDe, luEn, luDe);
	}

	/**
	 * Shorthand for a {@link TagRow}.
	 */
	@Nonnull
	private static TagRow tagRow(int target, @Nullable Locale locale, @Nullable String tag) {
		return new TagRow(target, locale, tag);
	}

	/**
	 * One row of `tags`, carrying `tag` in a single locale or - when the locale is NULL - in none.
	 *
	 * @param target referenced target primary key
	 * @param locale locale of `tag`
	 * @param tag    value of `tag`
	 */
	private record TagRow(int target, @Nullable Locale locale, @Nullable String tag) {
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
	 * @param luEn   English value of `lu`
	 * @param luDe   German value of `lu`
	 */
	private record Link(
		int target, @Nullable Long x, @Nullable Long[] arr,
		@Nullable String locEn, @Nullable String locDe, @Nullable String luEn, @Nullable String luDe
	) {
	}

}
