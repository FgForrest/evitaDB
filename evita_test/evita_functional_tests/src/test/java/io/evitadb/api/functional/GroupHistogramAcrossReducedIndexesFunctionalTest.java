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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the histogram of a group whose owners spread over several reduced indexes of the group.
 *
 * The reference to a brand allows duplicates, is grouped, and its representative attribute `rank` decides which
 * reduced index of the group a reference sits in. The anchor product references two brands of the group with the
 * shares 10 and 20, the product references one brand of the group with the share 5. Every upsert is followed by a
 * check of the histogram of the group and of the shares the stored references carry, and the last state is checked
 * again after a restart.
 *
 * The scenarios check the referenced entities anchoring the smallest and the largest value of the histogram of a
 * group, which are the references carrying those values wherever their owners sit in the group (scenarios
 * `BOUNDARY_*`). The control reaches the same final state with all owners at one rank, so the group has a single
 * reduced index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The histogram of a group split into several reduced indexes")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class GroupHistogramAcrossReducedIndexesFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "brandGroup";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_SHARE = "share";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String HISTOGRAM_SHARE = "shareHistogram";
	/**
	 * The histogram condition reading the representative attribute, satisfied by every rank the scenarios use.
	 */
	private static final String CONDITION_RANKED = "($reference.attributes['" + ATTRIBUTE_RANK + "'] ?? 0) > 0";
	private static final int PRODUCT_PK = 1;
	private static final int ANCHOR_PK = 100;
	private static final int GROUP_PK = 1;
	private static final int BRAND_PK = 10;
	private static final int FIRST_ANCHOR_BRAND_PK = 12;
	private static final int SECOND_ANCHOR_BRAND_PK = 13;
	/**
	 * Another brand of the group, referenced by no owner of the scenarios.
	 */
	private static final int OTHER_BRAND_PK = 11;
	private static final int ANCHOR_VALUE = 10;
	private static final int SECOND_ANCHOR_VALUE = 20;
	private static final int PRODUCT_SHARE = 5;
	private static final int INSIDE_ANCHOR_RANGE = 15;
	private static final int FIRST_RANK = 1;
	private static final int SECOND_RANK = 2;
	private static final int BUCKET_COUNT = 10;
	/**
	 * The description of a histogram that does not exist.
	 */
	private static final String NO_HISTOGRAM = "no histogram";

	/**
	 * One upsert of a scenario with the state it must leave behind.
	 *
	 * @param name              the name of the upsert
	 * @param upsert            the upsert
	 * @param expectedHistogram the expected description of the histogram of the group, see
	 *                          {@link #describeHistogram}
	 * @param expectedShares    the expected description of the shares the stored references carry, see
	 *                          {@link #describeStoredShares}
	 */
	private record Step(
		@Nonnull String name,
		@Nonnull Consumer<EvitaSessionContract> upsert,
		@Nonnull String expectedHistogram,
		@Nonnull String expectedShares
	) {
	}

	/**
	 * A catalog set up in warm-up and a sequence of upserts applied to it in the catalog state under test.
	 *
	 * @param name                 the name of the scenario
	 * @param rankReadByCondition  TRUE when the histogram condition reads the representative attribute
	 * @param setUp                the upserts storing the owners in warm-up, in the order they are applied
	 * @param initialHistogram     the expected description of the histogram of the group after the set-up
	 * @param initialShares        the expected description of the stored shares after the set-up
	 * @param steps                the upserts under test, in the order they are applied
	 */
	private record Scenario(
		@Nonnull String name,
		boolean rankReadByCondition,
		@Nonnull List<Consumer<EvitaSessionContract>> setUp,
		@Nonnull String initialHistogram,
		@Nonnull String initialShares,
		@Nonnull List<Step> steps
	) {

		/**
		 * Returns the name of the scenario, which the parameterized test shows as the name of its case.
		 *
		 * @return the name of the scenario
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * The scenarios checking the referenced entities that anchor the smallest and the largest value of the histogram of
	 * the group, see {@link #shouldAnchorBoundariesAtReferencesOfWholeGroup}.
	 *
	 * In the first one the anchor product creates the reduced index of the first rank after the product created the
	 * one of the second rank, so both reduced indexes hold every value - the anchor product's because they were
	 * written after both indexes existed, the product's because it gets its share only then. The smallest value
	 * belongs to the reference of the product, which sits in the other reduced index than the anchor product's. The
	 * control has every owner at one rank.
	 *
	 * @return arguments of {@link #shouldAnchorBoundariesAtReferencesOfWholeGroup}
	 */
	@Nonnull
	static Stream<Arguments> boundaryScenarios() {
		final List<Scenario> scenarios = List.of(
			new Scenario(
				"BOUNDARY_HELD_BY_OWNER_OF_OTHER_INDEX",
				false,
				List.of(createProduct(SECOND_RANK, null), createAnchor(FIRST_RANK)),
				anchoredRange(10, FIRST_ANCHOR_BRAND_PK, 20, SECOND_ANCHOR_BRAND_PK), "[10, 20]",
				List.of(
					productShare(
						PRODUCT_SHARE, anchoredRange(5, BRAND_PK, 20, SECOND_ANCHOR_BRAND_PK), "[5, 10, 20]"
					)
				)
			),
			new Scenario(
				"BOUNDARY_CONTROL_EVERYONE_AT_ONE_RANK",
				false,
				List.of(createAnchor(FIRST_RANK)),
				anchoredRange(10, FIRST_ANCHOR_BRAND_PK, 20, SECOND_ANCHOR_BRAND_PK), "[10, 20]",
				List.of(
					productStored(
						FIRST_RANK, PRODUCT_SHARE,
						anchoredRange(5, BRAND_PK, 20, SECOND_ANCHOR_BRAND_PK), "[5, 10, 20]"
					),
					productShare(
						INSIDE_ANCHOR_RANGE,
						anchoredRange(10, FIRST_ANCHOR_BRAND_PK, 20, SECOND_ANCHOR_BRAND_PK), "[10, 15, 20]"
					)
				)
			)
		);
		final List<Arguments> result = new ArrayList<>(scenarios.size() * 2);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Scenario scenario : scenarios) {
				result.add(Arguments.of(state, scenario));
			}
		}
		return result.stream();
	}

	/**
	 * Applies the upserts of the scenario and checks, after each of them and after a restart, that the smallest and
	 * the largest value of the histogram of the group are anchored at the references carrying them, wherever their
	 * owners sit in the group.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("boundaryScenarios")
	@DisplayName("the boundaries of the histogram of the group are anchored at the references carrying them")
	void shouldAnchorBoundariesAtReferencesOfWholeGroup(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario, false, true);
	}

	/**
	 * Runs one scenario: sets the catalog up and checks the starting state, applies each upsert and checks the state
	 * after it - and after a restart, where `restartAfterEveryStep` says so - and checks the last state after a
	 * restart. All failed checks are reported together.
	 *
	 * @param state                 the state the catalog is in when the upserts are applied
	 * @param scenario              the scenario
	 * @param restartAfterEveryStep TRUE to restart the engine after every upsert
	 * @param withBoundaries        TRUE to describe the histogram with its boundary entities, see
	 *                              {@link #describeHistogram}
	 */
	private void runScenario(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario,
		boolean restartAfterEveryStep,
		boolean withBoundaries
	) {
		final TestPaths paths = createTestPaths(
			"GroupHistogramAcrossReducedIndexes_" + state + "_" + scenario.name() + "_" + restartAfterEveryStep +
				"_" + withBoundaries
		);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(64);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, scenario);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			recordState(
				evita, "after the set-up", withBoundaries,
				scenario.initialHistogram(), scenario.initialShares(), checks
			);

			Step lastStep = null;
			int stepNumber = 0;
			for (Step step : scenario.steps()) {
				stepNumber++;
				final String stage = "after step " + stepNumber + " " + step.name();
				try {
					evita.updateCatalog(TEST_CATALOG, step.upsert());
				} catch (RuntimeException ex) {
					checks.add(
						() -> fail(
							stage + ": the upsert threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex
						)
					);
				}
				recordState(evita, stage, withBoundaries, step.expectedHistogram(), step.expectedShares(), checks);
				if (restartAfterEveryStep) {
					evita = restart(evita, configuration);
					recordState(
						evita, stage + " and restart", withBoundaries,
						step.expectedHistogram(), step.expectedShares(), checks
					);
				}
				lastStep = step;
			}

			if (!restartAfterEveryStep && lastStep != null) {
				evita = restart(evita, configuration);
				recordState(
					evita, "after the last upsert and restart", withBoundaries,
					lastStep.expectedHistogram(), lastStep.expectedShares(), checks
				);
			}
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Creates the step storing the product with one reference to the brand 10 in the group 1 at `rank`.
	 *
	 * @param rank              the rank of the reference
	 * @param share             the share of the reference, or `null` for none
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step productStored(
		int rank,
		@Nullable Integer share,
		@Nonnull String expectedHistogram,
		@Nonnull String expectedShares
	) {
		return new Step(
			"PRODUCT_STORED_AT_RANK_" + rank + (share == null ? "_WITHOUT_SHARE" : "_WITH_SHARE_" + share),
			createProduct(rank, share), expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the step setting the share of the product's reference to `share`.
	 *
	 * @param share             the new share
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step productShare(int share, @Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step(
			"PRODUCT_SHARE_" + share,
			session -> {
				final Collection<ReferenceContract> references = storedReferences(session, PRODUCT_PK);
				final List<LocalMutation<?, ?>> mutations = new ArrayList<>(references.size());
				for (ReferenceContract reference : references) {
					mutations.add(
						new ReferenceAttributeMutation(
							reference.getReferenceKey(), new UpsertAttributeMutation(ATTRIBUTE_SHARE, share)
						)
					);
				}
				upsert(session, PRODUCT_PK, mutations);
			},
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the upsert storing the product with one reference to the brand 10 in the group 1 at `rank`, carrying
	 * `share` when it is not `null`.
	 *
	 * @param rank  the rank of the reference
	 * @param share the share of the reference, or `null` for none
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProduct(int rank, @Nullable Integer share) {
		return session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			// the predicate matches no stored reference, so a new one is created - the only form a reference that
			// allows duplicates accepts
			.setOrUpdateReference(
				REFERENCE_BRAND, BRAND_PK, reference -> false,
				whichIs -> {
					whichIs.setGroup(ENTITY_BRAND_GROUP, GROUP_PK).setAttribute(ATTRIBUTE_RANK, rank);
					if (share != null) {
						whichIs.setAttribute(ATTRIBUTE_SHARE, share);
					}
				}
			)
			.upsertVia(session);
	}

	/**
	 * Creates the upsert storing the anchor product with its references to the brands 12 and 13 in the group 1 at
	 * `rank`, with the shares 10 and 20.
	 *
	 * @param rank the rank of the references
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchor(int rank) {
		return createAnchor(rank, true);
	}

	/**
	 * Creates the upsert storing the anchor product with its references to the brands 12 and 13 in the group 1 at
	 * `rank`, with the shares 10 and 20 where `withShares` says so.
	 *
	 * @param rank       the rank of the references
	 * @param withShares TRUE to give the references their shares
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchor(int rank, boolean withShares) {
		return session -> {
			final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
			for (int brandPk : new int[]{FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_BRAND_PK}) {
				final int share = brandPk == FIRST_ANCHOR_BRAND_PK ? ANCHOR_VALUE : SECOND_ANCHOR_VALUE;
				// the predicate matches no stored reference, so a new one is created - the only form a reference that
				// allows duplicates accepts
				anchor.setOrUpdateReference(
					REFERENCE_BRAND, brandPk, reference -> false,
					whichIs -> {
						whichIs.setGroup(ENTITY_BRAND_GROUP, GROUP_PK).setAttribute(ATTRIBUTE_RANK, rank);
						if (withShares) {
							whichIs.setAttribute(ATTRIBUTE_SHARE, share);
						}
					}
				);
			}
			anchor.upsertVia(session);
		};
	}

	/**
	 * Returns the stored references of the owner `ownerPk` to a brand.
	 *
	 * @param session the session to read from
	 * @param ownerPk the primary key of the owner
	 * @return the stored references
	 */
	@Nonnull
	private static Collection<ReferenceContract> storedReferences(
		@Nonnull EvitaSessionContract session,
		int ownerPk
	) {
		return session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent())
			.orElseThrow()
			.getReferences(REFERENCE_BRAND);
	}

	/**
	 * Applies one upsert of the existing owner `ownerPk` made of `mutations`.
	 *
	 * @param session   the session to write in
	 * @param ownerPk   the primary key of the owner
	 * @param mutations the local mutations of the upsert, in the order they are applied
	 */
	private static void upsert(
		@Nonnull EvitaSessionContract session,
		int ownerPk,
		@Nonnull List<LocalMutation<?, ?>> mutations
	) {
		session.upsertEntity(
			new EntityUpsertMutation(
				ENTITY_PRODUCT, ownerPk, EntityExistence.MUST_EXIST, mutations.toArray(LocalMutation<?, ?>[]::new)
			)
		);
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
	 * Describes a histogram spanning the values `min` to `max`, anchored at the brands `minBrandPk` and `maxBrandPk`.
	 *
	 * @param min        the smallest value
	 * @param minBrandPk the primary key of the brand whose reference carries the smallest value
	 * @param max        the largest value
	 * @param maxBrandPk the primary key of the brand whose reference carries the largest value
	 * @return the description {@link #describeHistogram} produces for such a histogram with its boundaries
	 */
	@Nonnull
	private static String anchoredRange(int min, int minBrandPk, int max, int maxBrandPk) {
		return "min=" + min + "@" + minBrandPk + " max=" + max + "@" + maxBrandPk;
	}

	/**
	 * Records the checks that the histogram of the group and the stored shares are described as expected at the
	 * moment `stage` names.
	 *
	 * @param evita             the engine
	 * @param stage             names the moment of the check
	 * @param withBoundaries    TRUE to describe the histogram with its boundary entities
	 * @param expectedHistogram the expected description of the histogram of the group
	 * @param expectedShares    the expected description of the stored shares
	 * @param checks            the collected checks
	 */
	private static void recordState(
		@Nonnull Evita evita,
		@Nonnull String stage,
		boolean withBoundaries,
		@Nonnull String expectedHistogram,
		@Nonnull String expectedShares,
		@Nonnull List<Executable> checks
	) {
		try {
			final String actualHistogram = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return describeHistogram(session, withBoundaries);
				}
			);
			checks.add(() -> assertEquals(expectedHistogram, actualHistogram, stage + ": " + HISTOGRAM_SHARE));
			final String actualShares = evita.queryCatalog(
				TEST_CATALOG,
				GroupHistogramAcrossReducedIndexesFunctionalTest::describeStoredShares
			);
			checks.add(() -> assertEquals(expectedShares, actualShares, stage + ": stored shares"));
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": a query threw " + ex, ex));
		}
	}

	/**
	 * Describes the share histogram of the group the reference summary of the live scope computes: its smallest and
	 * largest value, or the absence of the histogram. Where `withBoundaries` says so, each value is followed by the
	 * primary key of the brand anchoring it, see {@link #anchoredRange}.
	 *
	 * @param session        the session to query
	 * @param withBoundaries TRUE to request and describe the boundary entities
	 * @return the description
	 */
	@Nonnull
	private static String describeHistogram(@Nonnull EvitaSessionContract session, boolean withBoundaries) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(Scope.LIVE)),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null,
						withBoundaries ?
							histogramStatistics(BUCKET_COUNT, entityFetch(), HISTOGRAM_SHARE) :
							histogramStatistics(BUCKET_COUNT, HISTOGRAM_SHARE)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics = summary == null ?
			null : summary.getReferenceGroupStatistics(REFERENCE_BRAND, GROUP_PK);
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(HISTOGRAM_SHARE);
		if (histogram == null) {
			return NO_HISTOGRAM;
		} else if (withBoundaries) {
			return "min=" + plain(histogram.getMin()) + "@" +
				describeBoundary(histogram.getMinReferencedEntity().orElse(null)) +
				" max=" + plain(histogram.getMax()) + "@" +
				describeBoundary(histogram.getMaxReferencedEntity().orElse(null));
		} else {
			return "min=" + plain(histogram.getMin()) + " max=" + plain(histogram.getMax());
		}
	}

	/**
	 * Describes a boundary entity of a histogram by its primary key.
	 *
	 * @param boundary the boundary entity, or `null` when the histogram has none
	 * @return the primary key of the entity, or `none`
	 */
	@Nonnull
	private static String describeBoundary(@Nullable SealedEntity boundary) {
		return boundary == null ? "none" : String.valueOf(boundary.getPrimaryKey());
	}

	/**
	 * Describes the shares the stored references of the product and of the anchor product carry, in ascending order.
	 * An owner not stored yet contributes nothing.
	 *
	 * @param session the session to read from
	 * @return the description, for instance `[5, 10, 20]`
	 */
	@Nonnull
	private static String describeStoredShares(@Nonnull EvitaSessionContract session) {
		final List<Integer> shares = new ArrayList<>(4);
		for (int ownerPk : new int[]{PRODUCT_PK, ANCHOR_PK}) {
			final Optional<SealedEntity> owner = session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent());
			if (owner.isPresent()) {
				for (ReferenceContract reference : owner.get().getReferences(REFERENCE_BRAND)) {
					final Integer share = reference.getAttribute(ATTRIBUTE_SHARE);
					if (share != null) {
						shares.add(share);
					}
				}
			}
		}
		shares.sort(null);
		return shares.toString();
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
	 * Defines the schemas, stores the group and the brands, and applies the set-up upserts of the scenario.
	 *
	 * The reference of a product to a brand allows duplicates, is grouped by a brand group, indexed for filtering and
	 * faceted, indexed with both its entity and its group component, and carries the `share` attribute with the share
	 * histogram over it and the representative `rank` attribute. Where the scenario says so, the histogram admits
	 * only references whose rank is positive - which every rank of the scenarios is.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param scenario the scenario
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, @Nonnull Scenario scenario) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> {
					whichIs
						.withGroupTypeRelatedToEntity(ENTITY_BRAND_GROUP)
						.indexedForFiltering()
						.faceted()
						.indexedWithComponents(
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						)
						.withAttribute(ATTRIBUTE_SHARE, Integer.class, thatIs -> thatIs.filterable().nullable())
						.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.filterable().representative())
						.bucketed(
							HISTOGRAM_SHARE, ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_SHARE + "']")
						);
					if (scenario.rankReadByCondition()) {
						whichIs.bucketedPartially(ExpressionFactory.parse(CONDITION_RANKED));
					}
				}
			)
			.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK));
		for (int brandPk : new int[]{BRAND_PK, OTHER_BRAND_PK, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_BRAND_PK}) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND, brandPk));
		}
		for (Consumer<EvitaSessionContract> upsert : scenario.setUp()) {
			upsert.accept(session);
		}
	}

}
