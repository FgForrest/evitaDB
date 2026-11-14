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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the two write paths of a reference histogram over an attribute of the referenced entity build the
 * same histogram from the same data.
 *
 * The histogram of the reference of a product to a brand takes the `size` of the brand and admits a reference only
 * while its `active` attribute is true. The same final data are reached in two catalogs, along two paths:
 *
 * - **the cross-entity path** - the owners already reference the brand, and the brand changes its `size`; the owners
 *   are re-evaluated from the outside,
 * - **the local path** - the brand already has the new `size`, and the owners are created with their references; each
 *   owner indexes its own references.
 *
 * Both catalogs must then hold the same histogram, bucket by bucket, and both must equal what a count of the stored
 * references predicts: the anchor products span the range 10 to 20, the brand moves out of that range, one owner
 * contributes, and two owners - one inactive, one without the flag - must not. A value counted twice is invisible to
 * the minimum, the maximum and the bucket occurrences, so a follow-up applied identically to both catalogs then
 * takes the value of the contributing owner away from below the anchor range - once through the cross-entity path,
 * by moving the size of the brand into the anchor range, once through the local path, by deactivating the
 * reference - and the histogram is checked again, also after a restart.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Parity of the cross-entity and the local path of a reference histogram")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class ReferenceHistogramPathParityFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_SIZE = "size";
	private static final String ATTRIBUTE_ACTIVE = "active";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String HISTOGRAM_SIZE = "sizeHistogram";
	private static final String SIZE_VALUE = "$reference.referencedEntity?.attributes['" + ATTRIBUTE_SIZE + "']";
	private static final String LOCALIZED_SIZE_VALUE =
		"$reference.referencedEntity?.localizedAttributes['" + ATTRIBUTE_SIZE + "']";
	private static final String REFERENCE_ACTIVE =
		"($reference.attributes['" + ATTRIBUTE_ACTIVE + "'] ?? false) == true";
	private static final int GROUP_PK = 1;
	private static final int SUBJECT_BRAND_PK = 10;
	private static final int FIRST_ANCHOR_BRAND_PK = 12;
	private static final int SECOND_ANCHOR_BRAND_PK = 13;
	private static final int ACTIVE_OWNER_PK = 1;
	private static final int INACTIVE_OWNER_PK = 2;
	private static final int UNFLAGGED_OWNER_PK = 3;
	private static final int FIRST_ANCHOR_PK = 100;
	private static final int SECOND_ANCHOR_PK = 101;
	private static final int ANCHOR_VALUE = 10;
	private static final int SECOND_ANCHOR_VALUE = 20;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The owners of the subject brand, in ascending order of their primary keys.
	 */
	private static final List<Integer> SUBJECT_BRAND_OWNERS = List.of(
		ACTIVE_OWNER_PK, INACTIVE_OWNER_PK, UNFLAGGED_OWNER_PK
	);
	/**
	 * The description of a histogram that does not exist.
	 */
	private static final String NO_HISTOGRAM = "no histogram";
	private static final String STAGE_BASELINE = "before the change";
	private static final String STAGE_CHANGE = "after the change";
	private static final String STAGE_CHANGE_RESTART = "after the change and a restart";
	private static final String STAGE_FOLLOW_UP = "after the follow-up";
	private static final String STAGE_FOLLOW_UP_RESTART = "after the follow-up and a restart";
	/**
	 * The stages at which both catalogs hold the same data and must hold the same histogram.
	 */
	private static final String[] COMPARED_STAGES = {
		STAGE_CHANGE, STAGE_CHANGE_RESTART, STAGE_FOLLOW_UP, STAGE_FOLLOW_UP_RESTART
	};
	/**
	 * The only view of a histogram over a value that is not localized.
	 */
	private static final View NOT_LOCALIZED = new View("histogram", null, 30, 5, 15);
	/**
	 * The English view of a histogram over a localized value.
	 */
	private static final View ENGLISH = new View("histogram in en", Locale.ENGLISH, 30, 5, 15);
	/**
	 * The German view of a histogram over a localized value; the brand has other sizes in German than in English.
	 */
	private static final View GERMAN = new View("histogram in de", Locale.GERMAN, 31, 6, 16);

	/**
	 * The shape of the reference and of the histogram.
	 */
	enum Variant {
		/**
		 * The reference is not grouped and the size of the brand is not localized.
		 */
		UNGROUPED(false, false),
		/**
		 * Every reference sits in one group, and the histogram of that group is checked.
		 */
		GROUPED(true, false),
		/**
		 * The size of the brand is localized, and the histogram is checked in English and in German.
		 */
		LOCALIZED(false, true);

		/**
		 * TRUE when the reference is grouped by a brand group.
		 */
		private final boolean grouped;
		/**
		 * TRUE when the size of the brand is localized.
		 */
		private final boolean localized;

		/**
		 * Creates the variant.
		 *
		 * @param grouped   TRUE when the reference is grouped by a brand group
		 * @param localized TRUE when the size of the brand is localized
		 */
		Variant(boolean grouped, boolean localized) {
			this.grouped = grouped;
			this.localized = localized;
		}

		/**
		 * Returns the views of the histogram this variant checks.
		 *
		 * @return the views, one per locale where the size is localized
		 */
		@Nonnull
		View[] views() {
			return this.localized ? new View[]{ENGLISH, GERMAN} : new View[]{NOT_LOCALIZED};
		}
	}

	/**
	 * The path along which a catalog reaches the final data.
	 */
	enum Arm {
		/**
		 * The owners exist with their references, and the brand changes its size.
		 */
		CROSS_ENTITY,
		/**
		 * The brand has the new size, and the owners are created with their references.
		 */
		LOCAL
	}

	/**
	 * The upsert applied identically to both catalogs once they hold the same data, which takes the value of the
	 * contributing owner away from below the anchor range - into the range, or out of the histogram.
	 */
	enum FollowUp {
		/**
		 * The brand moves its size into the anchor range; the owners are re-evaluated from the outside.
		 */
		BRAND_SIZE_INTO_ANCHOR_RANGE,
		/**
		 * The contributing owner deactivates its reference; the owner indexes its own reference.
		 */
		ACTIVE_OWNER_DEACTIVATED
	}

	/**
	 * One histogram the reference summary computes for a variant, with the sizes the subject brand has in it.
	 *
	 * @param label      names the view in failure messages
	 * @param locale     the locale the histogram is read in, or `null` for a histogram over a value that is not
	 *                   localized
	 * @param oldSize    the size of the subject brand before the change of the cross-entity path, above the anchor
	 *                   range
	 * @param newSize    the size of the subject brand after the change, below the anchor range
	 * @param insideSize the size the subject brand moves to in the follow-up, inside the anchor range
	 */
	private record View(
		@Nonnull String label,
		@Nullable Locale locale,
		int oldSize,
		int newSize,
		int insideSize
	) {
	}

	/**
	 * One histogram as the reference summary returned it.
	 *
	 * @param summary the smallest and the largest value, the overall count and the occurrences of the buckets that hold
	 *                anything, see {@link #summary}
	 * @param full    the summary followed by the threshold and the occurrences of every bucket
	 */
	private record Observation(
		@Nonnull String summary,
		@Nonnull String full
	) {
	}

	/**
	 * Combines both catalog states, every variant and both follow-ups.
	 *
	 * @return arguments of {@link #shouldBuildSameHistogramOnBothPathsWhenBrandSizeMeetsOwners}
	 */
	@Nonnull
	static Stream<Arguments> cases() {
		final List<Arguments> result = new ArrayList<>(12);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final Variant variant : Variant.values()) {
				for (final FollowUp followUp : FollowUp.values()) {
					result.add(Arguments.of(state, variant, followUp));
				}
			}
		}
		return result.stream();
	}

	/**
	 * Reaches the same data along the cross-entity and along the local path, in a catalog of its own each, checks
	 * each histogram against the count of the stored references and both against each other, and does the same
	 * after a restart, after the follow-up and after one more restart.
	 *
	 * @param state    the state the catalogs are in when the change and the follow-up are applied
	 * @param variant  the shape of the reference and of the histogram
	 * @param followUp the upsert taking the value of the contributing owner away from below the anchor range
	 */
	@ParameterizedTest(name = "{0} {1} {2}")
	@MethodSource("cases")
	@DisplayName("both paths build the histogram the stored references predict, bucket by bucket")
	void shouldBuildSameHistogramOnBothPathsWhenBrandSizeMeetsOwners(
		@Nonnull CatalogState state,
		@Nonnull Variant variant,
		@Nonnull FollowUp followUp
	) {
		final List<Executable> checks = new ArrayList<>(64);
		final Map<String, Observation> crossEntity = runArm(Arm.CROSS_ENTITY, state, variant, followUp, checks);
		final Map<String, Observation> local = runArm(Arm.LOCAL, state, variant, followUp, checks);
		for (final String stage : COMPARED_STAGES) {
			for (final View view : variant.views()) {
				final String key = key(stage, view);
				final Observation crossEntityObservation = crossEntity.get(key);
				final Observation localObservation = local.get(key);
				checks.add(
					() -> assertEquals(
						crossEntityObservation == null ? null : crossEntityObservation.full(),
						localObservation == null ? null : localObservation.full(),
						key + ": the cross-entity path (expected) and the local path (actual) differ"
					)
				);
			}
		}
		assertAll(checks);
	}

	/**
	 * Runs one path in a catalog of its own: sets the catalog up, checks the histogram before the change, applies the
	 * change and checks the histogram and the owners of the subject brand, restarts and checks again, applies the
	 * follow-up and checks the histogram before and after one more restart. Every histogram is checked against the
	 * count of the stored references, and the change must move the histogram away from where it started.
	 *
	 * @param arm      the path
	 * @param state    the state the catalog is in when the change and the follow-up are applied
	 * @param variant  the shape of the reference and of the histogram
	 * @param followUp the upsert taking the value of the contributing owner away from below the anchor range
	 * @param checks   the collected checks
	 * @return the histograms observed, by {@link #key}
	 */
	@Nonnull
	private Map<String, Observation> runArm(
		@Nonnull Arm arm,
		@Nonnull CatalogState state,
		@Nonnull Variant variant,
		@Nonnull FollowUp followUp,
		@Nonnull List<Executable> checks
	) {
		final TestPaths paths = createTestPaths(
			"ReferenceHistogramPathParity_" + state + "_" + variant + "_" + followUp + "_" + arm
		);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final Map<String, Observation> observations = createLinkedHashMap(16);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, variant, arm);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			observe(evita, arm, variant, followUp, STAGE_BASELINE, observations, checks);

			applyUpdate(
				evita, arm + ": the change", checks,
				arm == Arm.CROSS_ENTITY ?
					session -> upsertSubjectBrandSize(session, variant, View::newSize) :
					session -> createOwners(session, variant)
			);
			observe(evita, arm, variant, followUp, STAGE_CHANGE, observations, checks);
			recordSubjectBrandOwners(evita, arm, checks);
			evita = restart(evita, configuration);
			observe(evita, arm, variant, followUp, STAGE_CHANGE_RESTART, observations, checks);

			applyUpdate(
				evita, arm + ": the follow-up " + followUp, checks,
				followUp == FollowUp.BRAND_SIZE_INTO_ANCHOR_RANGE ?
					session -> upsertSubjectBrandSize(session, variant, View::insideSize) :
					ReferenceHistogramPathParityFunctionalTest::deactivateActiveOwner
			);
			observe(evita, arm, variant, followUp, STAGE_FOLLOW_UP, observations, checks);
			evita = restart(evita, configuration);
			observe(evita, arm, variant, followUp, STAGE_FOLLOW_UP_RESTART, observations, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		// a path that leaves the histogram where it started proves nothing about how it maintains it
		for (final View view : variant.views()) {
			final Observation baseline = observations.get(key(STAGE_BASELINE, view));
			final Observation changed = observations.get(key(STAGE_CHANGE, view));
			if (baseline != null && changed != null) {
				checks.add(
					() -> assertNotEquals(
						baseline.summary(), changed.summary(),
						arm + " " + view.label() + ": the change did not move the histogram"
					)
				);
			}
		}
		return observations;
	}

	/**
	 * Reads every view of the histogram, stores what it returned under {@link #key} and records the check that its
	 * summary matches the count of the stored references at the moment `stage` names.
	 *
	 * @param evita        the engine
	 * @param arm          the path
	 * @param variant      the shape of the reference and of the histogram
	 * @param followUp     the upsert taking the value of the contributing owner away from below the anchor range
	 * @param stage        names the moment of the check
	 * @param observations the histograms observed so far
	 * @param checks       the collected checks
	 */
	private static void observe(
		@Nonnull Evita evita,
		@Nonnull Arm arm,
		@Nonnull Variant variant,
		@Nonnull FollowUp followUp,
		@Nonnull String stage,
		@Nonnull Map<String, Observation> observations,
		@Nonnull List<Executable> checks
	) {
		for (final View view : variant.views()) {
			final String key = key(stage, view);
			final String expected = expectedSummary(arm, followUp, stage, view);
			try {
				final Observation observation = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return describeHistogram(session, variant, view);
					}
				);
				observations.put(key, observation);
				checks.add(() -> assertEquals(expected, observation.summary(), arm + " " + key));
			} catch (RuntimeException ex) {
				checks.add(() -> fail(arm + " " + key + ": the query threw " + ex, ex));
			}
		}
	}

	/**
	 * Returns the summary of the histogram the stored references imply at the moment `stage` names. Every anchor
	 * product contributes one value, 10 and 20, the contributing owner contributes the size the subject brand has at
	 * that moment, and the other two owners never contribute. All values lie far enough apart to fall into buckets
	 * of their own.
	 *
	 * @param arm      the path
	 * @param followUp the upsert taking the value of the contributing owner away from below the anchor range
	 * @param stage    names the moment of the check
	 * @param view     the view of the histogram
	 * @return the expected summary, see {@link #summary}
	 */
	@Nonnull
	private static String expectedSummary(
		@Nonnull Arm arm,
		@Nonnull FollowUp followUp,
		@Nonnull String stage,
		@Nonnull View view
	) {
		return switch (stage) {
			// the cross-entity path starts with the old size of the contributing owner above the anchors, the local
			// path with the anchors alone
			case STAGE_BASELINE -> arm == Arm.CROSS_ENTITY ?
				summary(ANCHOR_VALUE, view.oldSize(), 3, 1, 1, 1) :
				summary(ANCHOR_VALUE, SECOND_ANCHOR_VALUE, 2, 1, 1);
			// the new size of the contributing owner below the anchors
			case STAGE_CHANGE, STAGE_CHANGE_RESTART -> summary(view.newSize(), SECOND_ANCHOR_VALUE, 3, 1, 1, 1);
			// the value of the contributing owner moved among the anchors, or gone with the deactivated reference
			case STAGE_FOLLOW_UP, STAGE_FOLLOW_UP_RESTART -> followUp == FollowUp.BRAND_SIZE_INTO_ANCHOR_RANGE ?
				summary(ANCHOR_VALUE, SECOND_ANCHOR_VALUE, 3, 1, 1, 1) :
				summary(ANCHOR_VALUE, SECOND_ANCHOR_VALUE, 2, 1, 1);
			default -> throw new IllegalArgumentException("Unknown stage: " + stage);
		};
	}

	/**
	 * Returns the key an observation is stored under.
	 *
	 * @param stage names the moment of the observation
	 * @param view  the view of the histogram
	 * @return the key
	 */
	@Nonnull
	private static String key(@Nonnull String stage, @Nonnull View view) {
		return stage + ", " + view.label();
	}

	/**
	 * Formats the summary of a histogram the way {@link #describeHistogram} does.
	 *
	 * @param min          the smallest value
	 * @param max          the largest value
	 * @param overallCount the overall count of the histogram
	 * @param occupied     the occurrences of the buckets that hold anything, in ascending order of their thresholds
	 * @return the summary
	 */
	@Nonnull
	private static String summary(int min, int max, int overallCount, int... occupied) {
		final List<Integer> occurrences = new ArrayList<>(occupied.length);
		for (final int occurrence : occupied) {
			occurrences.add(occurrence);
		}
		return "min=" + min + " max=" + max + " count=" + overallCount + " occupied=" + occurrences;
	}

	/**
	 * Describes the size histogram the reference summary of the live scope computes for `view` - of the group where
	 * the reference is grouped, read in the locale of the view where the size is localized.
	 *
	 * @param session the session to query
	 * @param variant the shape of the reference and of the histogram
	 * @param view    the view of the histogram
	 * @return the observed histogram, or {@link #NO_HISTOGRAM} in both parts when there is none
	 */
	@Nonnull
	private static Observation describeHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull Variant variant,
		@Nonnull View view
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				view.locale() == null ?
					filterBy(scope(Scope.LIVE)) :
					filterBy(scope(Scope.LIVE), entityLocaleEquals(view.locale())),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null,
						histogramStatistics(BUCKET_COUNT, HISTOGRAM_SIZE)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics;
		if (summary == null) {
			statistics = null;
		} else if (variant.grouped) {
			statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND, GROUP_PK);
		} else {
			statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND);
		}
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(HISTOGRAM_SIZE);
		if (histogram == null) {
			return new Observation(NO_HISTOGRAM, NO_HISTOGRAM);
		}
		final Bucket[] buckets = histogram.getBuckets();
		final List<Integer> occupied = new ArrayList<>(buckets.length);
		final StringBuilder bucketList = new StringBuilder(buckets.length * 8);
		for (final Bucket bucket : buckets) {
			if (bucket.occurrences() > 0) {
				occupied.add(bucket.occurrences());
			}
			if (!bucketList.isEmpty()) {
				bucketList.append(", ");
			}
			bucketList.append(plain(bucket.threshold())).append(':').append(bucket.occurrences());
		}
		final String summaryText = "min=" + plain(histogram.getMin()) + " max=" + plain(histogram.getMax()) +
			" count=" + histogram.getOverallCount() + " occupied=" + occupied;
		return new Observation(summaryText, summaryText + " buckets=[" + bucketList + "]");
	}

	/**
	 * Records the check that the products referencing the subject brand are the three owners, so that the two owners
	 * missing from the histogram are missing because of their flag and not because they are not there.
	 *
	 * @param evita  the engine
	 * @param arm    the path
	 * @param checks the collected checks
	 */
	private static void recordSubjectBrandOwners(
		@Nonnull Evita evita,
		@Nonnull Arm arm,
		@Nonnull List<Executable> checks
	) {
		try {
			final List<Integer> owners = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final List<EntityReference> found = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(referenceHaving(REFERENCE_BRAND, entityPrimaryKeyInSet(SUBJECT_BRAND_PK)))
						),
						EntityReference.class
					).getRecordData();
					final List<Integer> primaryKeys = new ArrayList<>(found.size());
					for (final EntityReference reference : found) {
						primaryKeys.add(reference.primaryKey());
					}
					primaryKeys.sort(null);
					return primaryKeys;
				}
			);
			checks.add(
				() -> assertEquals(SUBJECT_BRAND_OWNERS, owners, arm + " " + STAGE_CHANGE + ": owners of the brand")
			);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(arm + " " + STAGE_CHANGE + ": the query of the owners threw " + ex, ex));
		}
	}

	/**
	 * Formats `number` without trailing zeros and without an exponent.
	 *
	 * @param number the number
	 * @return the plain form of the number
	 */
	@Nonnull
	private static String plain(@Nullable BigDecimal number) {
		return number == null ? "null" : number.stripTrailingZeros().toPlainString();
	}

	/**
	 * Applies `update` in its own read-write session and records a failed check when it throws.
	 *
	 * @param evita  the engine
	 * @param label  names the update in the failure message
	 * @param checks the collected checks
	 * @param update the update
	 */
	private static void applyUpdate(
		@Nonnull Evita evita,
		@Nonnull String label,
		@Nonnull List<Executable> checks,
		@Nonnull Consumer<EvitaSessionContract> update
	) {
		try {
			evita.updateCatalog(TEST_CATALOG, update);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(label + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex));
		}
	}

	/**
	 * Closes the engine and starts a new one on the stored data.
	 *
	 * @param evita         the running engine
	 * @param configuration the configuration of the engine
	 * @return the new engine, fully initialized
	 */
	@Nonnull
	private static Evita restart(@Nonnull Evita evita, @Nonnull EvitaConfiguration configuration) {
		evita.close();
		final Evita restarted = new Evita(configuration);
		restarted.waitUntilFullyInitialized();
		return restarted;
	}

	/**
	 * Sets the size of the subject brand, in every locale of the variant, in one upsert.
	 *
	 * @param session the session to write in
	 * @param variant the shape of the reference and of the histogram
	 * @param size    picks the size for a view
	 */
	private static void upsertSubjectBrandSize(
		@Nonnull EvitaSessionContract session,
		@Nonnull Variant variant,
		@Nonnull ToIntFunction<View> size
	) {
		final View[] views = variant.views();
		final LocalMutation<?, ?>[] mutations = new LocalMutation<?, ?>[views.length];
		for (int i = 0; i < views.length; i++) {
			final View view = views[i];
			mutations[i] = view.locale() == null ?
				new UpsertAttributeMutation(ATTRIBUTE_SIZE, size.applyAsInt(view)) :
				new UpsertAttributeMutation(ATTRIBUTE_SIZE, view.locale(), size.applyAsInt(view));
		}
		session.upsertEntity(
			new EntityUpsertMutation(ENTITY_BRAND, SUBJECT_BRAND_PK, EntityExistence.MUST_EXIST, mutations)
		);
	}

	/**
	 * Deactivates the reference of the contributing owner to the subject brand.
	 *
	 * @param session the session to write in
	 */
	private static void deactivateActiveOwner(@Nonnull EvitaSessionContract session) {
		session.upsertEntity(
			new EntityUpsertMutation(
				ENTITY_PRODUCT, ACTIVE_OWNER_PK, EntityExistence.MUST_EXIST,
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE_BRAND, SUBJECT_BRAND_PK),
					new UpsertAttributeMutation(ATTRIBUTE_ACTIVE, false)
				)
			)
		);
	}

	/**
	 * Creates the three owners of the subject brand, each in an upsert of its own: the contributing owner with an
	 * active reference, an owner with an inactive one and an owner whose reference has no flag.
	 *
	 * @param session the session to write in
	 * @param variant the shape of the reference and of the histogram
	 */
	private static void createOwners(@Nonnull EvitaSessionContract session, @Nonnull Variant variant) {
		createProduct(session, variant, ACTIVE_OWNER_PK, SUBJECT_BRAND_PK, Boolean.TRUE);
		createProduct(session, variant, INACTIVE_OWNER_PK, SUBJECT_BRAND_PK, Boolean.FALSE);
		createProduct(session, variant, UNFLAGGED_OWNER_PK, SUBJECT_BRAND_PK, null);
	}

	/**
	 * Creates a product referencing the brand `brandPk` - in the group, where the reference is grouped - with the
	 * title in English and in German, where the size is localized, so that the product has both locales.
	 *
	 * @param session   the session to write in
	 * @param variant   the shape of the reference and of the histogram
	 * @param productPk the primary key of the product
	 * @param brandPk   the primary key of the brand
	 * @param active    the `active` flag of the reference, or `null` to leave it unset
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Variant variant,
		int productPk,
		int brandPk,
		@Nullable Boolean active
	) {
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, productPk);
		if (variant.localized) {
			product
				.setAttribute(ATTRIBUTE_TITLE, Locale.ENGLISH, "product " + productPk)
				.setAttribute(ATTRIBUTE_TITLE, Locale.GERMAN, "Produkt " + productPk);
		}
		product.setReference(
			REFERENCE_BRAND, brandPk,
			whichIs -> {
				if (variant.grouped) {
					whichIs.setGroup(ENTITY_BRAND_GROUP, GROUP_PK);
				}
				if (active != null) {
					whichIs.setAttribute(ATTRIBUTE_ACTIVE, active);
				}
			}
		);
		product.upsertVia(session);
	}

	/**
	 * Creates a brand with the size `size` picks for every view of the variant.
	 *
	 * @param session the session to write in
	 * @param variant the shape of the reference and of the histogram
	 * @param brandPk the primary key of the brand
	 * @param size    picks the size for a view
	 */
	private static void createBrand(
		@Nonnull EvitaSessionContract session,
		@Nonnull Variant variant,
		int brandPk,
		@Nonnull ToIntFunction<View> size
	) {
		final EntityBuilder brand = session.createNewEntity(ENTITY_BRAND, brandPk);
		for (final View view : variant.views()) {
			if (view.locale() == null) {
				brand.setAttribute(ATTRIBUTE_SIZE, size.applyAsInt(view));
			} else {
				brand.setAttribute(ATTRIBUTE_SIZE, view.locale(), size.applyAsInt(view));
			}
		}
		brand.upsertVia(session);
	}

	/**
	 * Defines the schemas and stores the brands, the anchor products and - on the cross-entity path - the owners.
	 *
	 * The brand has the filterable `size`, localized where the variant says, with the locales English and German.
	 * The product has a localized `title` with the same locales where the size is localized. Its reference to a
	 * brand is indexed for filtering and faceted, grouped by a brand group and indexed with both its components where
	 * the variant says, and carries the filterable `active` flag, the size histogram over the size of the brand and
	 * the condition admitting only an active reference. The anchor brands have the sizes 10 and 20 and each is
	 * referenced, actively, by an anchor product of its own. The subject brand has the old size on the cross-entity
	 * path and the new size on the local path; only the cross-entity path stores the owners here.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param variant the shape of the reference and of the histogram
	 * @param arm     the path
	 */
	private static void setUpCatalog(
		@Nonnull EvitaSessionContract session,
		@Nonnull Variant variant,
		@Nonnull Arm arm
	) {
		if (variant.localized) {
			session.defineEntitySchema(ENTITY_BRAND)
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAttribute(ATTRIBUTE_SIZE, Integer.class, whichIs -> whichIs.localized().filterable().nullable())
				.updateVia(session);
		} else {
			session.defineEntitySchema(ENTITY_BRAND)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_SIZE, Integer.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);
		}
		if (variant.grouped) {
			session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		}
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_TITLE, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (variant.grouped) {
						whichIs.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP);
					}
					whichIs
						.indexedForFiltering()
						.faceted();
					if (variant.grouped) {
						whichIs.indexedWithComponents(
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						);
					}
					whichIs
						.withAttribute(ATTRIBUTE_ACTIVE, Boolean.class, thatIs -> thatIs.filterable().nullable())
						.bucketed(
							HISTOGRAM_SIZE,
							ExpressionFactory.parse(variant.localized ? LOCALIZED_SIZE_VALUE : SIZE_VALUE)
						)
						.bucketedPartially(ExpressionFactory.parse(REFERENCE_ACTIVE));
				}
			)
			.updateVia(session);

		if (variant.grouped) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK));
		}
		createBrand(session, variant, FIRST_ANCHOR_BRAND_PK, view -> ANCHOR_VALUE);
		createBrand(session, variant, SECOND_ANCHOR_BRAND_PK, view -> SECOND_ANCHOR_VALUE);
		createBrand(
			session, variant, SUBJECT_BRAND_PK,
			arm == Arm.CROSS_ENTITY ? View::oldSize : View::newSize
		);
		createProduct(session, variant, FIRST_ANCHOR_PK, FIRST_ANCHOR_BRAND_PK, Boolean.TRUE);
		createProduct(session, variant, SECOND_ANCHOR_PK, SECOND_ANCHOR_BRAND_PK, Boolean.TRUE);
		if (arm == Arm.CROSS_ENTITY) {
			createOwners(session, variant);
		}
	}

}
