/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.attributeIs;
import static io.evitadb.api.query.QueryConstraints.attributeIsNotNull;
import static io.evitadb.api.query.QueryConstraints.attributeIsNull;
import static io.evitadb.api.query.QueryConstraints.attributeLessThanEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceSummary;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.utils.AssertionUtils.assertResultEquals;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the planning-time skip that
 * `io.evitadb.core.query.filter.translator.attribute.AttributeIsTranslator` applies to
 * `attributeIs(<name>, NULL)`.
 *
 * The constraint used to be planned as one `NotFormula(recordsHavingTheAttribute, everything)` per entity index.
 * The translator now proves, per index and at planning time, that the subtracted set already contains the whole
 * superset — and when it does, it emits **nothing** for that index. When every index proves empty the constraint
 * collapses to `EmptyFormula` during *planning*, where it previously collapsed only during *execution*. Results are
 * unchanged by construction; the timing is not, and consumers that walk the planned filter tree (facet summaries,
 * attribute histograms) see a shape they used to see only rarely.
 *
 * The class is therefore three things at once:
 *
 * - **positive rows** — the skip fires and the answer is the empty set (or, for the un-collapsed indexes, the right
 *   non-empty set);
 * - **negative rows** — the skip must *not* fire when some record genuinely lacks the attribute; an inverted subset
 *   test turns these red;
 * - **regression rows** — the boolean algebra around a planning-time `EmptyFormula`, plus the confirmed defect R3,
 *   where a `userFilter` that collapses at planning time destroys the carrier the reference summary needs.
 *
 * Every row carries `DebugMode.PREFER_INDEX_SCAN`: without it the planner answers a 12-row collection from prefetched
 * entity bodies and the translator under test never runs.
 *
 * The B-side entity attributes are exercised on `CATEGORY` (12 rows — enough, because the headline assertion is "the
 * result is empty") and the reference attributes from `PRODUCT` against `categories`, which is the direction in which
 * the bidirectional reference rewrite declines. The `attributeIs(NULL)` translator is therefore always observed on the
 * ordinary, un-rewritten path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Planning-time skip of attributeIs(NULL) subtractions")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(FILTER)
public class AttributeIsNullPlanningSkipFunctionalTest extends AbstractBidirectionalReferenceRewriteFunctionalTest {

	/**
	 * Key under which a non-grouped `ReferenceGroupStatistics` is filed in the projection built by
	 * {@link #projectReferenceSummary(ReferenceSummary)}. Primary keys are always positive, so the sentinel cannot
	 * collide with a real group id.
	 */
	private static final int NON_GROUPED_KEY = -1;

	/**
	 * Number of buckets requested from every attribute histogram in this class. The value is irrelevant to what the
	 * rows assert — they only ever ask whether the histogram was produced at all — but a histogram requires one.
	 */
	private static final int HISTOGRAM_BUCKET_COUNT = 20;
	/**
	 * Threshold the `attributeLessThanEquals` of the histogram carrier row compares against. The fixture sets
	 * `sometimesSet` to the category primary key on categories 1..8, so this splits the populated range in half
	 * and leaves buckets on both sides of the flag.
	 */
	private static final long HISTOGRAM_THRESHOLD = 4L;

	/* ---------------------------------------------------------------------------------------------------------- */
	/*  B-positive — the skip fires                                                                                 */
	/* ---------------------------------------------------------------------------------------------------------- */

	/**
	 * The headline row. Every category carries `alwaysSet`, so every per-index subtraction is provably empty, the
	 * disjunction collapses to `EmptyFormula` at planning time and the answer is the empty set.
	 *
	 * `AssertionUtils.assertResultIs` cannot be used here — it asserts the expectation is non-empty
	 * (`AssertionUtils.java:338`) — so the emptiness is paired with a fixture guard proving that no category could
	 * have matched. Without that guard the row would pass just as happily against a fixture that lost the attribute.
	 */
	@DisplayName("Should return nothing when every record carries the filterable attribute")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnEmptyWhenEveryRecordCarriesTheFilterableAttribute(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or this row proves nothing!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeIsNull(ATTR_ALWAYS_SET))
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"No category lacks `" + ATTR_ALWAYS_SET + "`, so the answer must be empty but was " +
						primaryKeysOf(result) + "!"
				);
				return null;
			}
		);
	}

	/**
	 * The reference-attribute counterpart of the headline row, and the one where the skip actually pays: the loop runs
	 * once per reduced index, i.e. once per referenced category, and every one of those subtractions is provably
	 * empty.
	 */
	@DisplayName("Should return nothing when every reference row carries the attribute")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnEmptyWhenEveryReferenceRowCarriesTheAttribute(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		assertTrue(
			originalProducts.stream().anyMatch(it -> !it.getReferences(REF_PRODUCT_CATEGORIES).isEmpty()),
			"Fixture guard: at least one product must reference a category!"
		);
		assertTrue(
			originalProducts.stream()
				.flatMap(it -> it.getReferences(REF_PRODUCT_CATEGORIES).stream())
				.allMatch(it -> it.getAttribute(REF_ATTR_ALWAYS_SET) != null),
			"Fixture guard: every `" + REF_PRODUCT_CATEGORIES + "` row must carry `" + REF_ATTR_ALWAYS_SET + "`!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.PRODUCT,
					filterBy(
						referenceHaving(
							REF_PRODUCT_CATEGORIES,
							attributeIsNull(REF_ATTR_ALWAYS_SET)
						)
					)
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"No `" + REF_PRODUCT_CATEGORIES + "` row lacks `" + REF_ATTR_ALWAYS_SET +
						"`, so the answer must be empty but was " + primaryKeysOf(result) + "!"
				);
				return null;
			}
		);
	}

	/**
	 * The complement of the row above. `NULL` and `NOT_NULL` must partition the collection: the null side is empty and
	 * the not-null side is every live owner that has a `categories` row at all. Asserted separately from its sibling so
	 * that a translator which answers *both* questions with the empty set cannot hide behind a single green row.
	 */
	@DisplayName("Should return every owner when the complement of the reference attribute is requested")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnEveryOwnerWhenTheComplementOfTheReferenceAttributeIsRequested(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.PRODUCT,
					filterBy(
						referenceHaving(
							REF_PRODUCT_CATEGORIES,
							attributeIsNotNull(REF_ATTR_ALWAYS_SET)
						)
					)
				);
				assertResultIs(
					"`NULL` and `NOT_NULL` must partition the collection",
					originalProducts,
					it -> it.getScope() == Scope.LIVE && !it.getReferences(REF_PRODUCT_CATEGORIES).isEmpty(),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * The unique-index variant of the headline row — `code` is `unique()` and set on every category, so the skip
	 * fires inside `createNullUniqueSubtractionFormula`. The unique path matters separately because
	 * `OwnerUniqueIndex.getRecordIdsFormula()` builds its `ConstantFormula` without the empty-bitmap guard the
	 * filterable path has, so it reaches the subset test with shapes the filterable path never produces.
	 */
	@DisplayName("Should return nothing when every record carries the unique attribute")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnEmptyWhenEveryRecordCarriesTheUniqueAttribute(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_CODE) != null),
			"Fixture guard: every category must carry the unique `" + ATTR_CODE + "` or this row proves nothing!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeIsNull(ATTR_CODE))
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"No category lacks `" + ATTR_CODE + "`, so the answer must be empty but was " +
						primaryKeysOf(result) + "!"
				);
				return null;
			}
		);
	}

	/**
	 * The locale is resolved once, before the per-index loop, rather than once per index. A regression that resolves
	 * it under the wrong processing scope yields a wrong-but-plausible set rather than an exception, so the row is
	 * built so that a wrong locale changes the answer.
	 *
	 * It asks in **English**, where every category carries a value: the subtraction is provably empty in every index
	 * and the answer is the empty set. Consult the German index instead and four live categories carry no German
	 * label, the subtraction survives planning and the query answers with them — the emptiness asserted here is
	 * therefore a statement about *which* locale was resolved, not merely about the skip firing.
	 *
	 * The companion query establishes that the two locale indexes really are different populations; without it the
	 * claim "a wrong locale would have been visible" would be hollow.
	 *
	 * The design's variant — asking in German and expecting the German-less categories back — cannot be stated on
	 * this fixture. `localizedLabel` is CATEGORY's only localized attribute, so "carries the German locale" and
	 * "carries a German `localizedLabel`" are the same set, and the conjoined `entityLocaleEquals(GERMAN)` intersects
	 * the whole answer away. Note also that the `LocaleFormula` is *not* elided here: `EntityLocaleEqualsTranslator`
	 * only wraps it in a `SelectionFormula` when a prefetch is possible, and `PREFER_INDEX_SCAN` denies the prefetch.
	 */
	@DisplayName("Should resolve the null filter against the index of the requested locale")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldResolveTheNullFilterAgainstTheRequestedLocaleIndex(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream()
				.allMatch(it -> it.getAttribute(ATTR_LOCALIZED_LABEL, Locale.ENGLISH) != null),
			"Fixture guard: every live category must carry an English `" + ATTR_LOCALIZED_LABEL + "`!"
		);
		assertTrue(
			originalCategories.stream().anyMatch(it -> it.getScope() == Scope.LIVE && !hasGermanLabel(it)),
			"Fixture guard: some live category must lack a German `" + ATTR_LOCALIZED_LABEL +
				"` or a wrongly resolved locale would be invisible!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> germanCompanion = queryEntities(
					session, Entities.CATEGORY,
					filterBy(
						entityLocaleEquals(Locale.GERMAN),
						attributeIsNotNull(ATTR_LOCALIZED_LABEL)
					)
				);
				assertResultIs(
					"The German and English label indexes must be different populations",
					originalCategories,
					it -> it.getScope() == Scope.LIVE && hasGermanLabel(it),
					germanCompanion.getRecordData()
				);

				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(
						entityLocaleEquals(Locale.ENGLISH),
						attributeIsNull(ATTR_LOCALIZED_LABEL)
					)
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"Every live category carries an English `" + ATTR_LOCALIZED_LABEL +
						"`, so the answer must be empty but was " + primaryKeysOf(result) + "!"
				);
				return null;
			}
		);
	}

	/**
	 * The per-index loop must visit the indexes of the *requested* scope and no others. Asked in the archived scope,
	 * the reduced indexes are those of the archived owners, whose rows are a different population than the live
	 * ones — so a loop that silently iterated the live family would answer with live primary keys and fail here.
	 *
	 * This row replaces the design's `shouldSkipEmptyIndexesEntirely`: the `BIDI_REWRITE` fixture contains no entity
	 * index with zero records, so the `getAllPrimaryKeysFormula() instanceof EmptyFormula` guard is unreachable from
	 * a functional test against it.
	 *
	 * **Measured, not inferred.** This row fails identically on the pre-optimisation `AttributeIsTranslator`
	 * (A/B swap of the committed translator, this session), so the `attributeIs(NULL)` planning-time skip is not
	 * its cause — the defect predates it. Keep the row red and keep the expectation derived from the entity
	 * bodies; do not weaken it to the observed empty answer.
	 *
	 * **Classification: ENGINE DEFECT, pre-existing** — the archived-scope face of the same defect. The diagnosis,
	 * the probe cardinalities and the one observation that contradicts them are recorded on
	 * {@link #shouldReturnNullBearingReferenceRowsWhenSomeRowsLackTheAttribute}; this row adds only that the defect
	 * is not scope-specific, since the live sibling fails identically.
	 */
	@Disabled(
		"Pins the correct behaviour of a pre-existing engine defect: `attributeIsNull` on a reference attribute " +
		"resolved through reduced indexes returns empty. Measured red with the planning-time skip reverted too. " +
		"Re-enable when issue #1584 is fixed."
	)
	@DisplayName("Should resolve the null filter against the indexes of the requested scope only")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldResolveTheNullFilterAgainstTheIndexesOfTheRequestedScopeOnly(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		assertTrue(
			originalProducts.stream()
				.anyMatch(it -> it.getScope() == Scope.ARCHIVED && lacksReferenceAttribute(it, REF_ATTR_SOMETIMES_SET)),
			"Fixture guard: some archived product must have a `" + REF_PRODUCT_CATEGORIES + "` row without `" +
				REF_ATTR_SOMETIMES_SET + "`!"
		);
		assertTrue(
			originalProducts.stream()
				.anyMatch(it -> it.getScope() == Scope.ARCHIVED && !lacksReferenceAttribute(it, REF_ATTR_SOMETIMES_SET)
				),
			"Fixture guard: some archived product must carry `" + REF_ATTR_SOMETIMES_SET + "` on every row!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.PRODUCT,
					filterBy(
						scope(Scope.ARCHIVED),
						referenceHaving(
							REF_PRODUCT_CATEGORIES,
							attributeIsNull(REF_ATTR_SOMETIMES_SET)
						)
					)
				);
				assertResultIs(
					"Only the archived reduced indexes may contribute to an archived-scope query",
					originalProducts,
					it -> it.getScope() == Scope.ARCHIVED && lacksReferenceAttribute(it, REF_ATTR_SOMETIMES_SET),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * The oracle that cannot pass vacuously, and the companion
	 * {@link #shouldReturnEmptyWhenEveryReferenceRowCarriesTheAttribute} needs in order to be worth its colour.
	 *
	 * `NULL` and `NOT_NULL` must between them account for every owner that has a `categories` row at all: an owner
	 * whose row carries the attribute lands in the second set, one whose row does not lands in the first, and there is
	 * no third place for it to go. The assertion therefore commits to **no** expectation about which side any
	 * particular owner falls on — it only says the two sides cover the collection.
	 *
	 * That is what makes it immune to the thing that devalues a plain "returns empty" row: if the planning-time skip
	 * over-fires and drops every subtraction, the `NULL` side collapses to nothing, the union shrinks to just the
	 * `NOT_NULL` side, and this row goes red without anyone having to know what the right answer was.
	 */
	@Disabled(
		"Pins the correct behaviour of a pre-existing engine defect: `attributeIsNull` on a reference attribute " +
		"resolved through reduced indexes returns empty, so the NULL side of the partition collapses. Measured red with " +
		"the planning-time skip reverted too. Re-enable when issue #1584 is fixed."
	)
	@DisplayName("Should partition the owners between the null and not-null reference filters")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldPartitionOwnersBetweenTheNullAndNotNullReferenceFilters(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> nullSide = queryEntities(
					session, Entities.PRODUCT,
					filterBy(referenceHaving(REF_PRODUCT_CATEGORIES, attributeIsNull(REF_ATTR_SOMETIMES_SET)))
				);
				final EvitaResponse<EntityReference> notNullSide = queryEntities(
					session, Entities.PRODUCT,
					filterBy(referenceHaving(REF_PRODUCT_CATEGORIES, attributeIsNotNull(REF_ATTR_SOMETIMES_SET)))
				);

				final Set<Integer> union = new TreeSet<>(primaryKeysOf(nullSide));
				union.addAll(primaryKeysOf(notNullSide));

				final Set<Integer> ownersWithARow = live(originalProducts).stream()
					.filter(it -> !it.getReferences(REF_PRODUCT_CATEGORIES).isEmpty())
					.map(SealedEntity::getPrimaryKeyOrThrowException)
					.collect(Collectors.toCollection(TreeSet::new));

				assertEquals(
					ownersWithARow, union,
					"`NULL` and `NOT_NULL` must together account for every owner carrying a `" +
						REF_PRODUCT_CATEGORIES + "` row - the null side returned " + nullSide.getRecordData().size() +
						" owners and the not-null side " + notNullSide.getRecordData().size() + ". A null side that " +
						"collapsed to nothing is the signature of the planning-time skip over-firing."
				);
				return null;
			}
		);
	}

	/* ---------------------------------------------------------------------------------------------------------- */
	/*  B-negative — the skip must not fire                                                                         */
	/* ---------------------------------------------------------------------------------------------------------- */

	/**
	 * The inversion guard for the entity-attribute path. Some categories genuinely lack `sometimesSet`, so the subset
	 * test must report "not provably empty" and the subtraction must survive planning. Were the `contains` arguments
	 * swapped — the one thing the whole optimisation turns on — this row returns the empty set.
	 */
	@DisplayName("Should return the null-bearing records when some records lack the attribute")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnNullBearingRecordsWhenSomeRecordsLackTheAttribute(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeIsNull(ATTR_SOMETIMES_SET))
				);
				assertResultIs(
					"The subtraction must survive planning when some record lacks the attribute",
					originalCategories,
					it -> it.getScope() == Scope.LIVE && it.getAttribute(ATTR_SOMETIMES_SET) == null,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * The inversion guard for the reference-attribute path. `refSometimesSet` is written on every row of a product or
	 * on none of them, so "the product has a row without the attribute" is a clean partition of the live collection.
	 *
	 * **Measured, not inferred.** This row fails identically on the pre-optimisation `AttributeIsTranslator`
	 * (A/B swap of the committed translator, this session), so the `attributeIs(NULL)` planning-time skip is not
	 * its cause — the defect predates it. Keep the row red and keep the expectation derived from the entity
	 * bodies; do not weaken it to the observed empty answer.
	 *
	 * **Classification: ENGINE DEFECT, pre-existing.** Not caused by either optimisation under test, out of scope for
	 * the work that wrote this class, and needing its own issue. What follows is where the investigation stopped, so
	 * whoever picks it up starts here rather than from scratch.
	 *
	 * Probed against this fixture and these reduced indexes:
	 *
	 * - `referenceHaving(categories)` bare -> 230 owners
	 * - `attributeIsNotNull(refAlwaysSet)` -> 230 and `attributeIsNotNull(refSometimesSet)` -> 76, both correct
	 * - `attributeIsNull(refSometimesSet)` -> **0**, where the correct answer is 154
	 * - the same `attributeIsNull` against the **global** entity index -> 3, correct
	 *
	 * So `NOT_NULL` resolves correctly through the very indexes from which `NULL` answers zero, and 76 + 0 does not
	 * close the 230 partition. The asymmetry points at the superset: `NOT_NULL` needs only
	 * `FilterIndex#getAllRecordsFormula`, while `NULL` also needs `EntityIndex#getAllPrimaryKeysFormula`, which
	 * returns `EmptyFormula` when its `entityIds` are empty (`EntityIndex.java:364-366`). With an empty superset the
	 * pre-optimisation translator built `NotFormula(rowsCarryingTheAttribute, EMPTY)` and computed nothing, while the
	 * current one proves the subtraction empty at its first branch and emits nothing — the same answer by two routes,
	 * which is why the A/B found no difference.
	 *
	 * **That reading is unconfirmed, and one observation contradicts it.**
	 * `EntityByDuplicateReferencesFunctionalTest:1411` asserts `attributeIsNull` returns one owner through a reduced
	 * index, and it is green on both translator versions — so the superset is not universally empty there. That query
	 * differs in three ways and one of them matters: it narrows to a single reduced index with
	 * `entityPrimaryKeyInSet`, its attribute is `representative`, and its reference is `ZERO_OR_MORE_WITH_DUPLICATES`.
	 * Start by finding which.
	 */
	@Disabled(
		"Pins the correct behaviour of a pre-existing engine defect: `attributeIsNull` on a reference attribute " +
		"resolved through reduced indexes returns empty. Measured red with the planning-time skip reverted too. " +
		"Re-enable when issue #1584 is fixed."
	)
	@DisplayName("Should return the null-bearing reference rows when some rows lack the attribute")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReturnNullBearingReferenceRowsWhenSomeRowsLackTheAttribute(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.PRODUCT,
					filterBy(
						referenceHaving(
							REF_PRODUCT_CATEGORIES,
							attributeIsNull(REF_ATTR_SOMETIMES_SET)
						)
					)
				);
				assertResultIs(
					"The subtraction must survive for every reduced index holding a row without the attribute",
					originalProducts,
					it -> it.getScope() == Scope.LIVE && lacksReferenceAttribute(it, REF_ATTR_SOMETIMES_SET),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * The unique-index inversion guard. `uniqueSometimes` is `unique()` and written on only some categories, so the
	 * unique subtraction must survive — the path in which `OwnerUniqueIndex.getRecordIdsFormula()` hands the subset
	 * test a `ConstantFormula` that may legitimately wrap an empty bitmap.
	 */
	@DisplayName("Should keep the unique subtraction when the unique index is incomplete")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldKeepUniqueSubtractionWhenTheUniqueIndexIsIncomplete(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeIsNull(ATTR_UNIQUE_SOMETIMES))
				);
				assertResultIs(
					"The unique subtraction must survive planning when the unique index does not cover every record",
					originalCategories,
					it -> it.getScope() == Scope.LIVE && it.getAttribute(ATTR_UNIQUE_SOMETIMES) == null,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/* ---------------------------------------------------------------------------------------------------------- */
	/*  B-regression — the boolean algebra around a planning-time EmptyFormula                                      */
	/* ---------------------------------------------------------------------------------------------------------- */

	/**
	 * `EmptyFormula` is the absorbing element of a conjunction. The companion query proves the *other* branch of the
	 * conjunction is non-empty, so a row that widened to "all active categories" — the shape an "an empty array means
	 * no filter" inversion produces — cannot pass here.
	 */
	@DisplayName("Should still return nothing when the collapsed filter is conjuncted")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldStillReturnEmptyWhenTheCollapsedFilterIsConjuncted(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or the filter never collapses!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> companion = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeEquals(ATTR_ACTIVE, true))
				);
				assertFalse(
					companion.getRecordData().isEmpty(),
					"The other branch of the conjunction must be non-empty or the emptiness below proves nothing!"
				);

				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(
						and(
							attributeEquals(ATTR_ACTIVE, true),
							attributeIsNull(ATTR_ALWAYS_SET)
						)
					)
				);
				assertTrue(
					result.getRecordData().isEmpty(),
					"An `EmptyFormula` must absorb the conjunction, but the query returned " +
						primaryKeysOf(result) + "!"
				);
				return null;
			}
		);
	}

	/**
	 * `EmptyFormula` is the identity element of a disjunction. A mis-handled empty operand returns either nothing or
	 * the whole collection; the correct answer is the single pinned category and nothing else.
	 */
	@DisplayName("Should not swallow the other branch when the collapsed filter is disjuncted")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNotSwallowTheOtherBranchWhenTheCollapsedFilterIsDisjuncted(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final SealedEntity pinnedCategory = originalCategories.stream()
			.filter(it -> it.getScope() == Scope.LIVE)
			.findFirst()
			.orElseThrow(() -> new GenericEvitaInternalError("The fixture must contain at least one live category!"));
		final String pinnedCode = pinnedCategory.getAttribute(ATTR_CODE);
		assertNotNull(pinnedCode, "Fixture guard: the pinned category must carry `" + ATTR_CODE + "`!");
		assertNotNull(
			pinnedCategory.getAttribute(ATTR_ALWAYS_SET),
			"Fixture guard: the pinned category must carry `" + ATTR_ALWAYS_SET + "` so the null branch stays empty!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(
						or(
							attributeEquals(ATTR_CODE, pinnedCode),
							attributeIsNull(ATTR_ALWAYS_SET)
						)
					)
				);
				assertResultEquals(
					"An `EmptyFormula` operand must leave its disjunctive sibling untouched",
					result.getRecordData(),
					pinnedCategory.getPrimaryKeyOrThrowException()
				);
				return null;
			}
		);
	}

	/**
	 * The `FutureNotFormula` resolution path with an `EmptyFormula` inside: negating a filter that collapsed at
	 * planning time must widen back to the whole live collection, because the subtracted set is empty and the superset
	 * is every primary key of the index.
	 */
	@DisplayName("Should negate the collapsed filter to the whole collection")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldNegateTheCollapsedFilterToTheWholeCollection(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(not(attributeIsNull(ATTR_ALWAYS_SET)))
				);
				assertResultIs(
					"Negating a collapsed filter must return the entire live collection",
					originalCategories,
					it -> it.getScope() == Scope.LIVE,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	/**
	 * Extra-result producers walk the planned filter tree. An `EmptyFormula` standing where an `AttributeFormula`
	 * used to stand is a shape they may never have been handed before, so this row asserts the query survives it:
	 * empty record data, and every requested extra result produced rather than a null or an exception.
	 */
	@DisplayName("Should still produce extra results when the filter collapses to empty")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldStillProduceExtraResultsWhenTheFilterCollapsesToEmpty(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or the filter never collapses!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeIsNull(ATTR_ALWAYS_SET)),
					referenceSummary(),
					attributeHistogram(HISTOGRAM_BUCKET_COUNT, ATTR_SOMETIMES_SET),
					queryTelemetry()
				);

				assertTrue(
					result.getRecordData().isEmpty(),
					"The collapsed filter must still answer with the empty set but returned " +
						primaryKeysOf(result) + "!"
				);
				assertNotNull(
					result.getExtraResult(ReferenceSummary.class),
					"The reference summary must be produced even when the filter collapsed at planning time!"
				);
				assertNotNull(
					result.getExtraResult(AttributeHistogram.class),
					"The attribute histogram must be produced even when the filter collapsed at planning time!"
				);
				assertNotNull(
					result.getExtraResult(QueryTelemetry.class),
					"The query telemetry must be produced even when the filter collapsed at planning time!"
				);
				return null;
			}
		);
	}

	/**
	 * Histogram production locates `AttributeFormula` nodes by attribute name in order to relax the user filter, and
	 * the translator now hands it a bare `EmptyFormula` in a case that used to be rare and is now common. This row
	 * pins what it does with it: the query does not throw, the record set is empty, and the histogram extra result
	 * is still produced rather than omitted.
	 *
	 * Its *content* is deliberately not asserted here, and that is a design statement rather than a gap. A histogram
	 * is computed over the **relaxed** baseline — `UserFilterRelaxer` drops a user filter it has emptied, and
	 * `ReferenceSummaryProducer:429` maps that sentinel to `null` so the accumulator spans the catalog-wide superset.
	 * A histogram that still spans the catalog while the record set is empty is therefore the intended answer, the
	 * same way facet counts are computed against `getFilteringFormulaWithoutUserFilter()`: both exist so the user can
	 * see what releasing their own refinement would give them.
	 *
	 * The histogram is requested for `sometimesSet` rather than for the collapsing attribute itself because
	 * `alwaysSet` is a `String` and an attribute histogram requires a numeric attribute; `sometimesSet` is the only
	 * numeric filterable attribute on `CATEGORY`.
	 */
	@DisplayName("Should compute the attribute histogram when the null filter collapses inside userFilter")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComputeTheAttributeHistogramWhenTheNullFilterCollapsesInsideUserFilter(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or the filter never collapses!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(userFilter(attributeIs(ATTR_ALWAYS_SET, AttributeSpecialValue.NULL))),
					attributeHistogram(HISTOGRAM_BUCKET_COUNT, ATTR_SOMETIMES_SET),
					queryTelemetry()
				);

				assertTrue(
					result.getRecordData().isEmpty(),
					"The collapsed user filter must still answer with the empty set but returned " +
						primaryKeysOf(result) + "!"
				);

				final AttributeHistogram histogram = result.getExtraResult(AttributeHistogram.class);
				assertNotNull(
					histogram,
					"The attribute histogram must be produced even when the user filter collapsed at planning time!"
				);
				return null;
			}
		);
	}

	/**
	 * Pins the one carrier the `NonCollapsibleFormula` marker does **not** cover, because it turned out not to need
	 * covering. `AttributeHistogramProducer:360-364` harvests the per-bucket `requested` predicate by walking
	 * `FormulaFinder.find(userFilter, AttributeFormula.class, LookUp.DEEP)` and reading
	 * `AttributeFormula#getRequestedPredicate()`, and that predicate is non-null on two unrelated shapes: on
	 * `BetweenAttributeFormula` (which is an `AttributeRangeCarrierFormula` and therefore marked), and on a
	 * **plain** `AttributeFormula` built by `AbstractAttributeComparisonTranslator:110` for
	 * `attributeLessThan(Equals)` / `attributeGreaterThan(Equals)` over a numeric attribute — which carries no
	 * marker at all.
	 *
	 * The unmarked shape is what this row exercises, and it survives. **Why it survives was not established**, and
	 * the obvious explanation has been ruled out: the histogram producer does *not* read a different tree from the
	 * facet path — `QueryPlanner:575` hands `builder.getFilterFormula()` to `ExtraResultPlanningVisitor` and
	 * `AttributeHistogramTranslator:90` forwards it on, so the `FilterFormulaAttributeOptimizeVisitor` pass at
	 * `AttributeHistogramProducer:353` sits on top of the optimiser's output rather than replacing it. What remains
	 * untested is the planned shape of this particular `userFilter`. The row therefore pins the behaviour and
	 * deliberately claims no mechanism; if it ever turns red, establish that shape before reaching for the marker.
	 *
	 * The record set is empty under every variant, so nothing about the returned entities can see this — the flag
	 * is the only observable, exactly as for the facet `requested` flag.
	 */
	@DisplayName("Should keep the histogram requested flag when a user-filter sibling collapses at planning time")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldKeepTheHistogramRequestedFlagWhenAUserFilterSiblingCollapsesAtPlanningTime(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or the sibling never collapses!"
		);
		assertTrue(
			live(originalCategories).stream()
				.map(it -> (Long) it.getAttribute(ATTR_SOMETIMES_SET))
				.filter(Objects::nonNull)
				.anyMatch(it -> it > HISTOGRAM_THRESHOLD),
			"Fixture guard: some live category must hold `" + ATTR_SOMETIMES_SET + "` above " + HISTOGRAM_THRESHOLD +
				" or every bucket would be requested and the assertion could not fail!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(
						userFilter(
							attributeLessThanEquals(ATTR_SOMETIMES_SET, HISTOGRAM_THRESHOLD),
							attributeIs(ATTR_ALWAYS_SET, AttributeSpecialValue.NULL)
						)
					),
					attributeHistogram(HISTOGRAM_BUCKET_COUNT, ATTR_SOMETIMES_SET)
				);

				assertTrue(
					result.getRecordData().isEmpty(),
					"The collapsing sibling must still empty the conjunction but the query returned " +
						primaryKeysOf(result) + "!"
				);

				final AttributeHistogram histogram = result.getExtraResult(AttributeHistogram.class);
				assertNotNull(
					histogram,
					"The attribute histogram must be produced even when the user filter collapsed at planning time!"
				);
				final HistogramContract sometimesSet = histogram.getHistogram(ATTR_SOMETIMES_SET);
				assertNotNull(
					sometimesSet,
					"The histogram for `" + ATTR_SOMETIMES_SET + "` is missing entirely - the producer found no " +
						"baseline to compute it over. The summary held " + histogram.getHistograms().keySet() + "."
				);
				assertTrue(
					Arrays.stream(sometimesSet.getBuckets()).anyMatch(Bucket::requested),
					"No bucket reports `requested`, so the `attributeLessThanEquals` predicate never reached the " +
						"producer. Its plain `AttributeFormula` was destroyed together with the conjunction that " +
						"held the collapsing sibling. Buckets were " +
						Arrays.toString(sometimesSet.getBuckets()) + "."
				);
				return null;
			}
		);
	}

	/**
	 * The one row that asserts the optimisation *fired* end to end rather than that the results are right: an
	 * `EmptyFormula` costs nothing, so the `PLANNING_FILTER_ALTERNATIVE` telemetry step reports an estimated cost of
	 * zero.
	 *
	 * It is fragile on purpose and kept last — the printed cost is the cost of the *whole* filter, so it only means
	 * anything for a query whose only constraint is the collapsing one. Add a second constraint to this query and the
	 * row stops proving what it claims.
	 */
	@DisplayName("Should report zero estimated cost when the null filter collapses")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldReportZeroEstimatedCostWhenTheNullFilterCollapses(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or the filter never collapses!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(attributeIsNull(ATTR_ALWAYS_SET)),
					queryTelemetry()
				);

				final QueryTelemetry telemetry = result.getExtraResult(QueryTelemetry.class);
				assertNotNull(telemetry, "Query telemetry must be present - it is the observable this row reads!");

				final List<String> arguments = collectStepArguments(telemetry, QueryPhase.PLANNING_FILTER_ALTERNATIVE);
				assertFalse(
					arguments.isEmpty(),
					"The planner must have registered at least one filter alternative to report a cost for!"
				);
				assertTrue(
					arguments.stream().anyMatch(it -> it.contains(", estimated costs 0")),
					"A filter that collapsed to `EmptyFormula` must be priced at zero, but the planner reported " +
						arguments + "!"
				);
				return null;
			}
		);
	}

	/* ---------------------------------------------------------------------------------------------------------- */
	/*  R3 — the confirmed regression                                                                               */
	/* ---------------------------------------------------------------------------------------------------------- */

	/**
	 * **Expected to fail on today's code.** The reference summary is computed against the filter with every
	 * `UserFilterFormula` sub-tree stripped (`ExtraResultPlanningVisitor.getFilteringFormulaWithoutUserFilter`), so a
	 * `userFilter` — whatever it contains — must never change it. Three queries make that claim falsifiable:
	 *
	 * - **A** — `userFilter(attributeIs(alwaysSet, NULL))`, which collapses at planning time *because of the
	 *   optimisation this class pins*. `UserFilterFormula` is a conjunctive formula, so `FormulaOptimizer` replaces
	 *   the whole carrier with `EmptyFormula` and there is no longer anything for the producer to strip.
	 * - **B** — no `filterBy` at all: the baseline every summary must match.
	 * - **C** — `userFilter(attributeEquals(active, true))`, true of every category. It collapses to "everything" at
	 *   *execution*, but keeps a populated `UserFilterFormula`, so the carrier survives. This is the control that
	 *   separates "the carrier was destroyed" from "the reference summary happens to be empty here".
	 *
	 * Summaries are compared through a projection built by this class rather than by `equals()` on the extra-result
	 * object, so a failure names the reference, group and facet that differ. Both the baseline **and** the control
	 * are guarded as non-empty before anything is compared: an empty control would agree with an empty A and turn
	 * the three-way comparison into an agreement about nothing.
	 */
	@DisplayName("Should keep the baseline reference summary when the user filter collapses at planning time")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComputeFacetSummaryAgainstTheBaselineWhenTheUserFilterCollapsesAtPlanningTime(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or query A does not collapse!"
		);
		assertTrue(
			live(originalCategories).stream().allMatch(it -> Boolean.TRUE.equals(it.getAttribute(ATTR_ACTIVE))),
			"Fixture guard: every live category must be active or query C is not a control!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> collapsing = queryEntities(
					session, Entities.CATEGORY,
					filterBy(userFilter(attributeIs(ATTR_ALWAYS_SET, AttributeSpecialValue.NULL))),
					referenceSummary()
				);
				final EvitaResponse<EntityReference> baseline = queryEntities(
					session, Entities.CATEGORY,
					null,
					referenceSummary()
				);
				final EvitaResponse<EntityReference> control = queryEntities(
					session, Entities.CATEGORY,
					filterBy(userFilter(attributeEquals(ATTR_ACTIVE, true))),
					referenceSummary()
				);

				final Map<String, Map<Integer, GroupProjection>> baselineProjection =
					referenceSummaryProjectionOf(baseline);
				assertFalse(
					baselineProjection.isEmpty(),
					"The baseline reference summary must be non-empty or every comparison below passes vacuously!"
				);
				assertTrue(
					baselineProjection.containsKey(REF_CATEGORY_BRAND),
					"The baseline reference summary must contain the faceted `" + REF_CATEGORY_BRAND +
						"` reference but held " + baselineProjection.keySet() + "!"
				);

				// the control carries the falsifiability of the whole three-way comparison: were its own summary empty
				// for some unrelated reason it would agree with an empty A, and all three would agree on nothing
				final Map<String, Map<Integer, GroupProjection>> controlProjection =
					referenceSummaryProjectionOf(control);
				assertFalse(
					controlProjection.isEmpty(),
					"The control reference summary must be non-empty - an empty control would agree with an empty " +
						"query A and collapse the comparison into a false agreement!"
				);
				assertTrue(
					controlProjection.containsKey(REF_CATEGORY_BRAND),
					"The control reference summary must contain the faceted `" + REF_CATEGORY_BRAND +
						"` reference but held " + controlProjection.keySet() + "!"
				);

				assertTrue(
					collapsing.getRecordData().isEmpty(),
					"Query A must answer with the empty set but returned " + primaryKeysOf(collapsing) + "!"
				);
				assertEquals(
					baselineProjection, controlProjection,
					"CONTROL: a user filter that collapses at execution time must not change the reference summary!"
				);
				assertEquals(
					baselineProjection, referenceSummaryProjectionOf(collapsing),
					"R3-A: the user filter collapsed at planning time through the `attributeIs(NULL)` SKIP - " +
						"`wrapFormula` returned a bare `EmptyFormula`, which `FormulaOptimizer` propagated through " +
						"the conjunctive `UserFilterFormula`, leaving `getFilteringFormulaWithoutUserFilter()` no " +
						"carrier to strip. Read this row together with its D sibling " +
						"(shouldComputeFacetSummaryAgainstTheBaseline" +
						"WhenAnUnrelatedUserFilterCollapsesAtPlanningTime): " +
						"MEASURED: this row PASSES on the pre-optimisation translator, so the skip INTRODUCED this " +
						"defect. It must go green together with its D sibling once `FormulaOptimizer` preserves the " +
						"collapsing `UserFilterFormula` carrier."
				);
				return null;
			}
		);
	}

	/**
	 * The second half of R3, split out deliberately: query **D** is
	 * `userFilter(attributeEquals(code, <no such code>))`, which is empty at *planning* time for a reason that has
	 * nothing to do with the `attributeIs(NULL)` skip — `AttributeEqualsTranslator.createUniqueAttributeFormula`
	 * wraps an `EmptyFormula` when the unique index has no match.
	 *
	 * **Measured.** An A/B against the committed translator settled this: D fails on **both** versions, while its
	 * A sibling passes on the pre-optimisation one. So this row is a genuinely pre-existing defect, and A is one
	 * the `attributeIs(NULL)` skip introduced. A prediction that the two must always agree was recorded here and
	 * is now known to be wrong: it computed A's tree under the optimised translator, where the skip makes the two
	 * trees identical, and then used that identity to argue the skip was not the cause.
	 *
	 * One fix serves both — preserving a collapsing `UserFilterFormula` as `UserFilterFormula(EmptyFormula)` in
	 * `FormulaOptimizer` rather than replacing it with a bare `EmptyFormula`. **Both rows must go green together
	 * when it lands**, and because they currently differ that is a real acceptance test rather than a restatement.
	 */
	@DisplayName("Should keep the baseline reference summary when an unrelated user filter collapses at planning time")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldComputeFacetSummaryAgainstTheBaselineWhenAnUnrelatedUserFilterCollapsesAtPlanningTime(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		final String missingCode = "no-such-category-code";
		assertTrue(
			originalCategories.stream().noneMatch(it -> missingCode.equals(it.getAttribute(ATTR_CODE))),
			"Fixture guard: no category may carry `" + missingCode + "` or query D does not collapse!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> collapsing = queryEntities(
					session, Entities.CATEGORY,
					filterBy(userFilter(attributeEquals(ATTR_CODE, missingCode))),
					referenceSummary()
				);
				final EvitaResponse<EntityReference> baseline = queryEntities(
					session, Entities.CATEGORY,
					null,
					referenceSummary()
				);

				final Map<String, Map<Integer, GroupProjection>> baselineProjection =
					referenceSummaryProjectionOf(baseline);
				assertFalse(
					baselineProjection.isEmpty(),
					"The baseline reference summary must be non-empty or the comparison below passes vacuously!"
				);
				assertTrue(
					baselineProjection.containsKey(REF_CATEGORY_BRAND),
					"The baseline reference summary must contain the faceted `" + REF_CATEGORY_BRAND +
						"` reference but held " + baselineProjection.keySet() + "!"
				);

				assertTrue(
					collapsing.getRecordData().isEmpty(),
					"Query D must answer with the empty set but returned " + primaryKeysOf(collapsing) + "!"
				);
				assertEquals(
					baselineProjection, referenceSummaryProjectionOf(collapsing),
					"R3-D: the user filter collapsed at planning time through an UNMATCHED UNIQUE LOOKUP - " +
						"`createUniqueAttributeFormula` wrapped an `EmptyFormula` in an `AttributeFormula`, which is " +
						"itself conjunctive, so `FormulaOptimizer` collapsed it and then the enclosing " +
						"`UserFilterFormula`. Nothing here involves the `attributeIs(NULL)` skip. Read this row " +
						"together with its A sibling " +
						"(shouldComputeFacetSummaryAgainstTheBaselineWhenTheUserFilterCollapsesAtPlanningTime): " +
						"MEASURED: this row fails on the pre-optimisation translator too, so it is PRE-EXISTING and " +
						"not caused by the skip. It must go green with its A sibling once `FormulaOptimizer` " +
						"preserves the collapsing `UserFilterFormula` carrier."
				);
				return null;
			}
		);
	}

	/**
	 * The behavioural pin for the `NonCollapsibleFormula` marker
	 * (`documentation/adr/2026-09-15-non-collapsible-formula-marker.md`).
	 *
	 * A `userFilter` that collapses at planning time must keep its carriers, or extra-result planning loses the
	 * nodes it locates to compute the mandatory baseline. The record set is identical whether they are kept or
	 * not — a collapsed conjunction and a conjunction containing an `EmptyFormula` both compute empty — so no
	 * assertion on the returned entities can tell the two apart. The `requested` flag is the only observable that
	 * moves, and it moves quietly: a dropped facet selection reads as a facet nobody asked for, not as an error.
	 *
	 * **Why the flag tracks the formula tree** (checked, not assumed —
	 * `ReferenceSummaryOfReferenceTranslator#findOrCreateProducer:306-312`): the producer derives `requested` by
	 * walking for `FacetGroupFormula` nodes with `FormulaFinder` over `getUserFilteringFormula()`, falling back to
	 * the whole filtering formula when that set is empty. Destroy the `facetHaving` subtree and the walk finds
	 * nothing to report as requested; destroy the `userFilter` itself and the scope falls back to a formula that is
	 * `EmptyFormula`, so no groups are reported at all. The two are asserted separately below for that reason.
	 *
	 * Note `LookUp.SHALLOW` means "stop descending a branch once it matches", not "do not descend" — non-matching
	 * nodes are always traversed, which is why a `FacetGroupFormula` nested under the `facetHaving` is still
	 * reached (`FormulaFinder#visit`).
	 *
	 * **Two routes, one symptom.** `FacetHavingFormula implements ChildrenDependentFormula`, and `FormulaOptimizer`
	 * drops a `ChildrenDependentFormula` outright once its children are optimised away. So the facet subtree can
	 * disappear two independent ways — the enclosing conjunction collapsing, or the carrier being removed as an
	 * emptied container — and either alone yields the same `requested == false`. A fix addressing only one of them
	 * leaves this row red and looks like a failed fix rather than a partial one, so check both before concluding
	 * the marker did not take.
	 */
	@DisplayName("Should keep the facet selection when a sibling user-filter constraint collapses at planning time")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldKeepTheFacetSelectionWhenASiblingUserFilterConstraintCollapsesAtPlanningTime(
		Evita evita,
		List<SealedEntity> originalCategories
	) {
		assertTrue(
			live(originalCategories).stream().allMatch(it -> it.getAttribute(ATTR_ALWAYS_SET) != null),
			"Fixture guard: every live category must carry `" + ATTR_ALWAYS_SET + "` or the sibling never collapses!"
		);
		assertTrue(
			live(originalCategories).stream()
				.anyMatch(it -> it.getReferences(REF_CATEGORY_BRAND).stream()
					.anyMatch(ref -> ref.getReferencedPrimaryKey() == SHARED_BRAND_PK)),
			"Fixture guard: some live category must reference brand `" + SHARED_BRAND_PK +
				"` or its facet never appears in the summary!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = queryEntities(
					session, Entities.CATEGORY,
					filterBy(
						userFilter(
							facetHaving(REF_CATEGORY_BRAND, entityPrimaryKeyInSet(SHARED_BRAND_PK)),
							attributeIs(ATTR_ALWAYS_SET, AttributeSpecialValue.NULL)
						)
					),
					referenceSummary()
				);

				assertTrue(
					result.getRecordData().isEmpty(),
					"The collapsing sibling must still empty the conjunction but the query returned " +
						primaryKeysOf(result) + "!"
				);

				final Map<String, Map<Integer, GroupProjection>> projection = referenceSummaryProjectionOf(result);
				final Map<Integer, GroupProjection> brandGroups = projection.get(REF_CATEGORY_BRAND);
				assertNotNull(
					brandGroups,
					"The `" + REF_CATEGORY_BRAND + "` summary is missing entirely, so the `userFilter` carrier was " +
						"destroyed rather than emptied - that is the unfixed `FormulaOptimizer`, not the rebuild. " +
						"summary held " + projection.keySet() + "."
				);
				final GroupProjection nonGrouped = brandGroups.get(NON_GROUPED_KEY);
				assertNotNull(
					nonGrouped,
					"The `" + REF_CATEGORY_BRAND + "` reference declares no group, so its statistics must be filed " +
						"as non-grouped but the summary held groups " + brandGroups.keySet() + "."
				);
				final FacetProjection facet = nonGrouped.facets().get(SHARED_BRAND_PK);
				assertNotNull(
					facet,
					"Facet `" + SHARED_BRAND_PK + "` must appear in the `" + REF_CATEGORY_BRAND +
						"` summary but it held " + nonGrouped.facets().keySet() + "."
				);
				assertTrue(
					facet.requested(),
					"Facet `" + SHARED_BRAND_PK + "` was selected by the `userFilter` and must still report " +
						"`requested`. A false flag with the summary otherwise present means the `userFilter` node " +
						"survived but the `facetHaving` beneath it did not - the conjunction holding the carrier " +
						"was collapsed, so `FormulaOptimizer#holdsNonCollapsibleFormula` did not see the marker."
				);
				return null;
			}
		);
	}

	/* ---------------------------------------------------------------------------------------------------------- */
	/*  helpers                                                                                                     */
	/* ---------------------------------------------------------------------------------------------------------- */

	/**
	 * Runs a query against `entityType`, always adding the two requirements every row in this class depends on:
	 * `PREFER_INDEX_SCAN` (without which a 12-row collection is answered from prefetched bodies and the translator
	 * under test never runs) together with `VERIFY_POSSIBLE_CACHING_TREES`, and an unbounded page.
	 *
	 * @param session           the session to query through
	 * @param entityType        the collection to query
	 * @param filterBy          the filter to apply, or `null` for an unfiltered query
	 * @param extraRequirements requirements appended after the two mandatory ones
	 * @return the full response including any requested extra results
	 */
	@Nonnull
	private static EvitaResponse<EntityReference> queryEntities(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nullable FilterBy filterBy,
		@Nonnull RequireConstraint... extraRequirements
	) {
		final RequireConstraint[] requirements = new RequireConstraint[extraRequirements.length + 2];
		requirements[0] = debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.PREFER_INDEX_SCAN);
		requirements[1] = page(1, Integer.MAX_VALUE);
		System.arraycopy(extraRequirements, 0, requirements, 2, extraRequirements.length);

		return session.query(
			Query.query(
				collection(entityType),
				filterBy,
				require(requirements)
			),
			EntityReference.class
		);
	}

	/**
	 * Narrows the injected originals to the population the queries in this class can actually reach.
	 *
	 * Every row here queries without a `scope(...)` constraint unless it writes one explicitly, so what a query sees is
	 * the live entities and nothing else. A fixture guard stated over the whole injected list would therefore go red
	 * when the fixture gains an archived entity that no row of this class can reach — the guard would be describing a
	 * population the assertion below it never touches.
	 *
	 * Expectations are unaffected either way: they are computed as predicates carrying their own `Scope.LIVE` test.
	 *
	 * @param entities the injected originals, which carry both scopes
	 * @return the live ones, in the order they were injected
	 */
	@Nonnull
	private static List<SealedEntity> live(@Nonnull List<SealedEntity> entities) {
		final List<SealedEntity> liveEntities = entities.stream()
			.filter(it -> it.getScope() == Scope.LIVE)
			.toList();
		if (liveEntities.isEmpty()) {
			// an all-archived collection would make every `allMatch` guard vacuously true and silently disarm the row
			throw new GenericEvitaInternalError(
				"The fixture handed this class " + entities.size() + " entities and not one of them is live - every " +
					"guard built on this population would pass vacuously."
			);
		}
		return liveEntities;
	}

	/**
	 * Tells whether the category carries a German value for the localized label.
	 *
	 * @param category the category to inspect
	 * @return TRUE when a German `localizedLabel` is present
	 */
	private static boolean hasGermanLabel(@Nonnull SealedEntity category) {
		return category.getAttribute(ATTR_LOCALIZED_LABEL, Locale.GERMAN) != null;
	}

	/**
	 * Tells whether the entity has at least one `categories` row that carries no value for `attributeName`.
	 *
	 * @param entity        the owner entity to inspect
	 * @param attributeName the reference attribute to look for
	 * @return TRUE when some row lacks the attribute
	 */
	private static boolean lacksReferenceAttribute(@Nonnull SealedEntity entity, @Nonnull String attributeName) {
		return entity.getReferences(REF_PRODUCT_CATEGORIES)
			.stream()
			.anyMatch(it -> it.getAttribute(attributeName) == null);
	}

	/**
	 * Reads the reference summary out of a response and converts it to the comparison projection.
	 *
	 * @param response the response to read
	 * @return the projection of the response's reference summary
	 */
	@Nonnull
	private static Map<String, Map<Integer, GroupProjection>> referenceSummaryProjectionOf(
		@Nonnull EvitaResponse<EntityReference> response
	) {
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, "The reference summary must be present in every response this comparison reads!");
		return projectReferenceSummary(summary);
	}

	/**
	 * Projects a reference summary onto `reference name -> group id -> facet id -> (count, requested)`.
	 *
	 * The comparison is deliberately not `ReferenceSummary#equals`: that answers only "same or not", while a
	 * failure here must name the reference, the group and the facet whose count or `requested` flag moved. Sorted
	 * maps are used so the rendering of a failed comparison is stable.
	 *
	 * @param summary the summary to project
	 * @return the projection, ordered by reference name and then by group id
	 */
	@Nonnull
	private static Map<String, Map<Integer, GroupProjection>> projectReferenceSummary(
		@Nonnull ReferenceSummary summary
	) {
		final Map<String, Map<Integer, GroupProjection>> projection = new TreeMap<>();
		for (final ReferenceGroupStatistics groupStatistics : summary.getReferenceStatistics()) {
			final EntityClassifier groupEntity = groupStatistics.getGroupEntity();
			final int groupKey = groupEntity == null ? NON_GROUPED_KEY : groupEntity.getPrimaryKeyOrThrowException();

			final Map<Integer, FacetProjection> facets = new TreeMap<>();
			for (final FacetStatistics facetStatistics : groupStatistics.getFacetStatistics()) {
				facets.put(
					facetStatistics.getFacetEntity().getPrimaryKeyOrThrowException(),
					new FacetProjection(facetStatistics.getCount(), facetStatistics.isRequested())
				);
			}

			final GroupProjection previous = projection
				.computeIfAbsent(groupStatistics.getReferenceName(), it -> new TreeMap<>())
				.put(groupKey, new GroupProjection(groupStatistics.getCount(), facets));
			if (previous != null) {
				throw new GenericEvitaInternalError(
					"Reference `" + groupStatistics.getReferenceName() + "` reported group `" + groupKey +
						"` twice - the summary cannot be projected unambiguously."
				);
			}
		}
		return projection;
	}

	/**
	 * Collects the arguments of every telemetry step of the requested phase anywhere in the tree.
	 *
	 * @param telemetry the telemetry tree to walk
	 * @param phase     the phase whose arguments are wanted
	 * @return the arguments in traversal order
	 */
	@Nonnull
	private static List<String> collectStepArguments(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		final List<String> arguments = new ArrayList<>(8);
		collectStepArguments(telemetry, phase, arguments);
		return arguments;
	}

	/**
	 * Recursive worker of {@link #collectStepArguments(QueryTelemetry, QueryPhase)}.
	 *
	 * @param telemetry the telemetry node to inspect
	 * @param phase     the phase whose arguments are wanted
	 * @param output    the accumulator the arguments are appended to
	 */
	private static void collectStepArguments(
		@Nonnull QueryTelemetry telemetry,
		@Nonnull QueryPhase phase,
		@Nonnull List<String> output
	) {
		if (telemetry.getOperation() == phase) {
			output.addAll(Arrays.asList(telemetry.getArguments()));
		}
		for (final QueryTelemetry step : telemetry.getSteps()) {
			collectStepArguments(step, phase, output);
		}
	}

	/**
	 * Renders the primary keys of a response, used in assertion messages so a failure says *which* entities came back.
	 *
	 * @param response the response to render
	 * @return the returned primary keys in ascending order
	 */
	@Nonnull
	private static List<Integer> primaryKeysOf(@Nonnull EvitaResponse<EntityReference> response) {
		return response.getRecordData()
			.stream()
			.map(EntityReference::getPrimaryKey)
			.sorted()
			.toList();
	}

	/**
	 * One facet row of the projection built by {@link #projectReferenceSummary(ReferenceSummary)}.
	 *
	 * @param count     number of entities in the response that possess this facet
	 * @param requested whether the facet was part of the query's filtering constraints
	 */
	private record FacetProjection(int count, boolean requested) {
	}

	/**
	 * One group row of the projection built by {@link #projectReferenceSummary(ReferenceSummary)}.
	 *
	 * @param count  number of entities in the response that possess any facet of this group
	 * @param facets the group's facets, keyed by facet primary key
	 */
	private record GroupProjection(int count, @Nonnull Map<Integer, FacetProjection> facets) {
	}

}
