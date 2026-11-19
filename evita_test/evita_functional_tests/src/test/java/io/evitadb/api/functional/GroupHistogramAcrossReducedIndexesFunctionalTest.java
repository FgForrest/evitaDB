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
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.functional.GroupHistogramTestSupport.addReference;
import static io.evitadb.api.functional.GroupHistogramTestSupport.anchorShares;
import static io.evitadb.api.functional.GroupHistogramTestSupport.anchoredRange;
import static io.evitadb.api.functional.GroupHistogramTestSupport.createAnchor;
import static io.evitadb.api.functional.GroupHistogramTestSupport.defineProduct;
import static io.evitadb.api.functional.GroupHistogramTestSupport.describeBoundary;
import static io.evitadb.api.functional.GroupHistogramTestSupport.everyReferenceAttribute;
import static io.evitadb.api.functional.GroupHistogramTestSupport.plain;
import static io.evitadb.api.functional.GroupHistogramTestSupport.restart;
import static io.evitadb.api.functional.GroupHistogramTestSupport.storedReferences;
import static io.evitadb.api.functional.GroupHistogramTestSupport.upsert;
import static io.evitadb.api.functional.GroupHistogramTestSupport.withBothCatalogStates;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the histogram of a group holds exactly the values its stored references carry, however the owners
 * spread over the reduced indexes of the group.
 *
 * The reference to a brand allows duplicates, is grouped, and its representative attribute `rank` decides which
 * reduced index of the group a reference sits in. The anchor product references two brands of the group with the
 * shares 10 and 20, the product references one brand of the group with the share 5. Every upsert is followed by a
 * check of the histogram of the group - its smallest and largest value, and the owners each of its buckets counts -
 * and of the shares the stored references carry, and the last state is checked again after a restart.
 *
 * Two families of scenarios split the owners of the group over several reduced indexes in different ways:
 *
 * - an owner is stored with a rank no owner of the group had, or its reference changes to such a rank - the
 *   histogram of the group still holds the values of the owners already in the group (scenarios `MISSING_*`); the
 *   scenarios `READ_SIDE_*` build the same stored data in the opposite order, or with a reduced index none of whose
 *   references carries a value, so the answer must not depend on the order the reduced indexes were created in;
 * - every owner leaves a rank while a value of the group changes, and owners return to the rank later - the
 *   histogram of the group holds none of the values replaced in the meantime (scenarios `STALE_*`).
 *
 * Every family has its control reaching the same final state with all owners at one rank, or without the time in
 * which a rank has no owner.
 *
 * Separate scenarios check the referenced entities anchoring the smallest and the largest value of the histogram of a
 * group, which are the references carrying those values wherever their owners sit in the group (scenarios
 * `BOUNDARY_*`), and a value one owner carries on two references of the group at two ranks, which stays in the
 * histogram of the group while one of the references still carries it, and leaves it with the removal of the last
 * reference carrying it (scenarios `DUPLICATE_*`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The histogram of a group split into several reduced indexes")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class GroupHistogramAcrossReducedIndexesFunctionalTest implements EvitaTestSupport, GroupHistogramTestSupport {

	/**
	 * The histogram condition reading the representative attribute, satisfied by every rank the scenarios use.
	 */
	private static final String CONDITION_RANKED = "($reference.attributes['" + ATTRIBUTE_RANK + "'] ?? 0) > 0";
	/**
	 * The brand of the group the second reference of the product points to, when it does not duplicate the first.
	 */
	private static final int OTHER_BRAND_PK = 11;
	private static final int PRODUCT_SHARE = 5;
	private static final int CHANGED_PRODUCT_SHARE = 7;
	private static final int INSIDE_ANCHOR_RANGE = 15;

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
	 * The non-empty buckets of the share histogram of the group, described as the histogram counts them and as the
	 * stored references fill them, see {@link #describeBucketContents}.
	 *
	 * @param histogram the occurrences the histogram counts in each bucket
	 * @param stored    the occurrences the stored references give each bucket, followed by the shares no bucket holds
	 */
	private record BucketContents(@Nonnull String histogram, @Nonnull String stored) {
	}

	/**
	 * The scenarios of both families, see the class description.
	 *
	 * The first family creates a reduced index of the group for a rank no owner had - by storing the product with it,
	 * and by changing the rank of the product's reference to it, without and with a histogram condition reading the
	 * rank - and adds the two scenarios whose answer must not depend on the order the reduced indexes were created in,
	 * and the control in which every owner shares one rank.
	 *
	 * The second family leaves the first reduced index of the group without owners while the share of the product
	 * changes, or without such a change, and adds two controls reaching the same final state without such a time: one
	 * in which nobody leaves the first rank, and one in which only the product makes the round trip. Its anchor product
	 * carries its shares only once the product has created the second reduced index of the group, so no value of the
	 * family is written before every reduced index it ever has exists.
	 *
	 * @return arguments of {@link #shouldHoldExactlyStoredValuesWhenGroupSpansSeveralReducedIndexes}
	 */
	@Nonnull
	static Stream<Arguments> scenarios() {
		final List<Consumer<EvitaSessionContract>> staleSetUp = List.of(
			createAnchor(FIRST_RANK, false), createProduct(FIRST_RANK, PRODUCT_SHARE)
		);
		final List<Scenario> scenarios = List.of(
			// the product is the first owner at the second rank; no owner changes its rank
			new Scenario(
				"MISSING_PRODUCT_STORED_AT_RANK_NO_OWNER_HAS",
				false,
				List.of(createAnchor(FIRST_RANK, true)),
				range(10, 20), "[10, 20]",
				List.of(
					productStored(SECOND_RANK, PRODUCT_SHARE, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			// the product leaves the reduced index it shares with the anchor for a rank no owner has
			new Scenario(
				"MISSING_PRODUCT_MOVES_TO_RANK_NO_OWNER_HAS",
				false,
				List.of(createAnchor(FIRST_RANK, true), createProduct(FIRST_RANK, PRODUCT_SHARE)),
				range(5, 20), "[5, 10, 20]",
				List.of(
					productToRank(SECOND_RANK, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			// the same move while the histogram condition reads the rank, and stays satisfied
			new Scenario(
				"MISSING_PRODUCT_MOVES_TO_RANK_NO_OWNER_HAS_RANK_READ_BY_CONDITION",
				true,
				List.of(createAnchor(FIRST_RANK, true), createProduct(FIRST_RANK, PRODUCT_SHARE)),
				range(5, 20), "[5, 10, 20]",
				List.of(
					productToRank(SECOND_RANK, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			// the stored data of the first scenario built in the opposite order: the anchor creates the reduced index
			// of the first rank after the product created the one of the second rank
			new Scenario(
				"READ_SIDE_ANCHOR_STORED_AFTER_PRODUCT_AT_OTHER_RANK",
				false,
				List.of(createProduct(SECOND_RANK, PRODUCT_SHARE)),
				range(5, 5), "[5]",
				List.of(
					anchorStored(FIRST_RANK, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			// no reference of the newest reduced index of the group carries a value until the product gets its share
			new Scenario(
				"READ_SIDE_NEWEST_INDEX_WITHOUT_VALUE",
				false,
				List.of(createAnchor(FIRST_RANK, true)),
				range(10, 20), "[10, 20]",
				List.of(
					productStored(SECOND_RANK, null, range(10, 20), "[10, 20]"),
					productShare(PRODUCT_SHARE, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			// every owner at one rank: the group has a single reduced index
			new Scenario(
				"CONTROL_EVERYONE_AT_ONE_RANK",
				false,
				List.of(createAnchor(FIRST_RANK, true)),
				range(10, 20), "[10, 20]",
				List.of(
					productStored(FIRST_RANK, PRODUCT_SHARE, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			new Scenario(
				"STALE_COPY_SHARE_CHANGED_WHILE_FIRST_INDEX_HAS_NO_OWNER",
				false,
				staleSetUp,
				range(5, 5), "[5]",
				List.of(
					productToRank(SECOND_RANK, range(5, 5), "[5]"),
					anchorSharesSet(range(5, 20), "[5, 10, 20]"),
					anchorToRank(SECOND_RANK, range(5, 20), "[5, 10, 20]"),
					productShare(CHANGED_PRODUCT_SHARE, range(7, 20), "[7, 10, 20]"),
					productToRank(FIRST_RANK, range(7, 20), "[7, 10, 20]"),
					anchorToRank(FIRST_RANK, range(7, 20), "[7, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			new Scenario(
				"STALE_COPY_SHARE_UNCHANGED_WHILE_FIRST_INDEX_HAS_NO_OWNER",
				false,
				staleSetUp,
				range(5, 5), "[5]",
				List.of(
					productToRank(SECOND_RANK, range(5, 5), "[5]"),
					anchorSharesSet(range(5, 20), "[5, 10, 20]"),
					anchorToRank(SECOND_RANK, range(5, 20), "[5, 10, 20]"),
					productToRank(FIRST_RANK, range(5, 20), "[5, 10, 20]"),
					anchorToRank(FIRST_RANK, range(5, 20), "[5, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			new Scenario(
				"CONTROL_EVERYONE_STAYS_AT_FIRST_RANK",
				false,
				staleSetUp,
				range(5, 5), "[5]",
				List.of(
					anchorSharesSet(range(5, 20), "[5, 10, 20]"),
					productShare(CHANGED_PRODUCT_SHARE, range(7, 20), "[7, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			),
			new Scenario(
				"CONTROL_PRODUCT_ROUND_TRIP_WHILE_ANCHOR_STAYS_AT_FIRST_RANK",
				false,
				staleSetUp,
				range(5, 5), "[5]",
				List.of(
					productToRank(SECOND_RANK, range(5, 5), "[5]"),
					anchorSharesSet(range(5, 20), "[5, 10, 20]"),
					productShare(CHANGED_PRODUCT_SHARE, range(7, 20), "[7, 10, 20]"),
					productToRank(FIRST_RANK, range(7, 20), "[7, 10, 20]"),
					productShare(INSIDE_ANCHOR_RANGE, range(10, 20), "[10, 15, 20]")
				)
			)
		);
		return withBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios of {@link #scenarios()} creating a reduced index for a new rank without a histogram condition, and
	 * the scenario leaving the first reduced index without owners while the share of the product changes, run with a
	 * restart after every upsert.
	 *
	 * @return arguments of {@link #shouldHoldExactlyStoredValuesWhenRestartedAfterEveryUpsert}
	 */
	@Nonnull
	static Stream<Arguments> restartedScenarios() {
		return scenarios().filter(
			it -> {
				final String name = ((Scenario) it.get()[1]).name();
				return name.equals("MISSING_PRODUCT_STORED_AT_RANK_NO_OWNER_HAS") ||
					name.equals("MISSING_PRODUCT_MOVES_TO_RANK_NO_OWNER_HAS") ||
					name.equals("STALE_COPY_SHARE_CHANGED_WHILE_FIRST_INDEX_HAS_NO_OWNER");
			}
		);
	}

	/**
	 * The scenarios of one owner holding two references of the group at two ranks, both with the share 5, and losing
	 * the first of them - by its removal, or by its move to a third rank - see
	 * {@link #shouldKeepValueOfRemainingReferenceWhenOwnerHoldsItTwice}.
	 *
	 * The second reference points to the brand of the first one, or to another brand of the group. The anchor product
	 * sits at the rank of the first reference and is stored before it, or sits at the rank of the second reference and
	 * is stored between the two references, or is absent from the group. Where it is absent, the owner then loses every
	 * reference it still has, so the group has no histogram, and the anchor product stored afterwards brings the
	 * histogram back with its own shares only: a share the owner contributed must not outlive its last reference.
	 *
	 * @return arguments of {@link #shouldKeepValueOfRemainingReferenceWhenOwnerHoldsItTwice}
	 */
	@Nonnull
	static Stream<Arguments> duplicateScenarios() {
		final List<Scenario> scenarios = new ArrayList<>(12);
		for (int secondBrandPk : new int[]{BRAND_PK, OTHER_BRAND_PK}) {
			final String target = secondBrandPk == BRAND_PK ? "SAME_BRAND" : "OTHER_BRAND";
			for (boolean removed : new boolean[]{true, false}) {
				final String variant = removed ? "FIRST_REMOVED" : "FIRST_MOVED_TO_THIRD_RANK";
				final Step lastStep = removed ?
					firstReferenceRemoved(range(5, 20), "[5, 10, 20]") :
					firstReferenceToRank(THIRD_RANK, range(5, 20), "[5, 5, 10, 20]");
				final Step lastStepWithoutAnchor = removed ?
					firstReferenceRemoved(range(5, 5), "[5]") :
					firstReferenceToRank(THIRD_RANK, range(5, 5), "[5, 5]");
				scenarios.add(new Scenario(
					"DUPLICATE_" + target + "_" + variant + "_ANCHOR_AT_FIRST_RANK",
					false,
					List.of(createAnchor(FIRST_RANK, true)),
					range(10, 20), "[10, 20]",
					List.of(
						productStored(FIRST_RANK, PRODUCT_SHARE, range(5, 20), "[5, 10, 20]"),
						secondReferenceAdded(secondBrandPk, range(5, 20), "[5, 5, 10, 20]"),
						lastStep
					)
				));
				scenarios.add(new Scenario(
					"DUPLICATE_" + target + "_" + variant + "_ANCHOR_AT_SECOND_RANK_STORED_IN_BETWEEN",
					false,
					List.of(createProduct(FIRST_RANK, PRODUCT_SHARE)),
					range(5, 5), "[5]",
					List.of(
						anchorStored(SECOND_RANK, range(5, 20), "[5, 10, 20]"),
						secondReferenceAdded(secondBrandPk, range(5, 20), "[5, 5, 10, 20]"),
						lastStep
					)
				));
				scenarios.add(new Scenario(
					"DUPLICATE_" + target + "_" + variant + "_OWNER_ALONE_IN_GROUP",
					false,
					List.of(createProduct(FIRST_RANK, PRODUCT_SHARE)),
					range(5, 5), "[5]",
					List.of(
						secondReferenceAdded(secondBrandPk, range(5, 5), "[5, 5]"),
						lastStepWithoutAnchor,
						remainingReferencesRemoved(NO_HISTOGRAM, "[]"),
						anchorStored(FIRST_RANK, range(10, 20), "[10, 20]")
					)
				));
			}
		}
		return withBothCatalogStates(scenarios);
	}

	/**
	 * Applies the upserts of the scenario and checks, after each of them and after a restart, that the share one
	 * owner carries on two references of the group stays in the histogram of the group while one of the references
	 * still carries it, and leaves it with the removal of the last reference carrying it.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("duplicateScenarios")
	@DisplayName("a value one owner carries twice stays while one of its references carries it, then leaves")
	void shouldKeepValueOfRemainingReferenceWhenOwnerHoldsItTwice(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario, false, false);
	}

	/**
	 * The scenarios checking the referenced entities that anchor the smallest and the largest value of the histogram of
	 * the group, see {@link #shouldAnchorBoundariesAtReferencesOfWholeGroup}.
	 *
	 * In the first one the anchor product creates the reduced index of the first rank after the product created the
	 * one of the second rank, and the product gets its share only then, so every value is written after both reduced
	 * indexes exist. The smallest value belongs to the reference of the product, which sits in the other reduced index
	 * than the anchor product's. The control has every owner at one rank.
	 *
	 * @return arguments of {@link #shouldAnchorBoundariesAtReferencesOfWholeGroup}
	 */
	@Nonnull
	static Stream<Arguments> boundaryScenarios() {
		final List<Scenario> scenarios = List.of(
			new Scenario(
				"BOUNDARY_HELD_BY_OWNER_OF_OTHER_INDEX",
				false,
				List.of(createProduct(SECOND_RANK, null), createAnchor(FIRST_RANK, true)),
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
				List.of(createAnchor(FIRST_RANK, true)),
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
		return withBothCatalogStates(scenarios);
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
	 * Applies the upserts of the scenario, checks the histogram of the group and the stored shares after each of them,
	 * and checks the last state again after a restart.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("scenarios")
	@DisplayName("the histogram of the group holds exactly the stored values")
	void shouldHoldExactlyStoredValuesWhenGroupSpansSeveralReducedIndexes(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario, false, false);
	}

	/**
	 * Applies the upserts of the scenario with a restart after each of them, and checks the histogram of the group
	 * and the stored shares after each upsert and after each restart.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("restartedScenarios")
	@DisplayName("a restart after every upsert leaves exactly the stored values in the histogram of the group")
	void shouldHoldExactlyStoredValuesWhenRestartedAfterEveryUpsert(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario, true, false);
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
	 * Creates the step giving the product a second reference of the group, to the brand `brandPk` at the second rank
	 * with the share of its first reference.
	 *
	 * @param brandPk           the primary key of the referenced brand
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step secondReferenceAdded(
		int brandPk,
		@Nonnull String expectedHistogram,
		@Nonnull String expectedShares
	) {
		return new Step(
			"SECOND_REFERENCE_TO_BRAND_" + brandPk + "_AT_RANK_" + SECOND_RANK,
			session -> {
				final EntityBuilder product = session
					.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
					.orElseThrow()
					.openForWrite();
				// a new reference is created next to the first one
				addReference(product, brandPk, GROUP_PK, SECOND_RANK, PRODUCT_SHARE);
				product.upsertVia(session);
			},
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the step removing the reference of the product at the first rank.
	 *
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step firstReferenceRemoved(@Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step(
			"FIRST_REFERENCE_REMOVED",
			session -> upsert(
				session, PRODUCT_PK,
				List.of(new RemoveReferenceMutation(storedReferenceAtFirstRank(session).getReferenceKey()))
			),
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the step removing every reference the product still has, in one upsert.
	 *
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step remainingReferencesRemoved(@Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step(
			"REMAINING_REFERENCES_REMOVED",
			session -> {
				final Collection<ReferenceContract> references = storedReferences(session, PRODUCT_PK);
				final List<LocalMutation<?, ?>> mutations = new ArrayList<>(references.size());
				for (final ReferenceContract reference : references) {
					mutations.add(new RemoveReferenceMutation(reference.getReferenceKey()));
				}
				upsert(session, PRODUCT_PK, mutations);
			},
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the step moving the reference of the product at the first rank to `rank`.
	 *
	 * @param rank              the new rank
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step firstReferenceToRank(
		int rank,
		@Nonnull String expectedHistogram,
		@Nonnull String expectedShares
	) {
		return new Step(
			"FIRST_REFERENCE_TO_RANK_" + rank,
			session -> upsert(
				session, PRODUCT_PK,
				List.of(
					new ReferenceAttributeMutation(
						storedReferenceAtFirstRank(session).getReferenceKey(),
						new UpsertAttributeMutation(ATTRIBUTE_RANK, rank)
					)
				)
			),
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Returns the stored reference of the product at the first rank.
	 *
	 * @param session the session to read from
	 * @return the reference
	 */
	@Nonnull
	private static ReferenceContract storedReferenceAtFirstRank(@Nonnull EvitaSessionContract session) {
		for (ReferenceContract reference : storedReferences(session, PRODUCT_PK)) {
			final Integer rank = reference.getAttribute(ATTRIBUTE_RANK);
			if (rank != null && rank == FIRST_RANK) {
				return reference;
			}
		}
		throw new IllegalStateException("The product has no reference at the first rank.");
	}

	/**
	 * Creates the step storing the anchor product with its two references at `rank`, see {@link #createAnchor}.
	 *
	 * @param rank              the rank of the references
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step anchorStored(int rank, @Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step("ANCHOR_STORED_AT_RANK_" + rank, createAnchor(rank, true), expectedHistogram, expectedShares);
	}

	/**
	 * Creates the step moving every reference of the product to `rank`.
	 *
	 * @param rank              the new rank
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step productToRank(int rank, @Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step(
			"PRODUCT_TO_RANK_" + rank, everyReferenceAttribute(PRODUCT_PK, ATTRIBUTE_RANK, rank),
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the step moving both references of the anchor product to `rank`, in one upsert.
	 *
	 * @param rank              the new rank
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step anchorToRank(int rank, @Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step(
			"ANCHOR_TO_RANK_" + rank, everyReferenceAttribute(ANCHOR_PK, ATTRIBUTE_RANK, rank),
			expectedHistogram, expectedShares
		);
	}

	/**
	 * Creates the step giving the stored anchor product its shares, 10 on its reference to the brand 12 and 20 on its
	 * reference to the brand 13, in one upsert.
	 *
	 * @param expectedHistogram the expected histogram of the group after the step
	 * @param expectedShares    the expected stored shares after the step
	 * @return the step
	 */
	@Nonnull
	private static Step anchorSharesSet(@Nonnull String expectedHistogram, @Nonnull String expectedShares) {
		return new Step(
			"ANCHOR_SHARES_SET", anchorShares(ANCHOR_VALUE, SECOND_ANCHOR_VALUE), expectedHistogram, expectedShares
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
			"PRODUCT_SHARE_" + share, everyReferenceAttribute(PRODUCT_PK, ATTRIBUTE_SHARE, share),
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
		return session -> {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
			addReference(product, BRAND_PK, GROUP_PK, rank, share);
			product.upsertVia(session);
		};
	}

	/**
	 * Describes a histogram spanning the values `min` to `max`.
	 *
	 * @param min the smallest value
	 * @param max the largest value
	 * @return the description {@link #describeHistogram} produces for such a histogram
	 */
	@Nonnull
	private static String range(int min, int max) {
		return "min=" + min + " max=" + max;
	}

	/**
	 * Records the checks that the histogram of the group and the stored shares are described as expected at the
	 * moment `stage` names, and that each bucket of the histogram counts exactly the owners the stored shares put
	 * there, see {@link #describeBucketContents}.
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
			final BucketContents bucketContents = evita.queryCatalog(
				TEST_CATALOG,
				GroupHistogramAcrossReducedIndexesFunctionalTest::describeBucketContents
			);
			checks.add(
				() -> assertEquals(bucketContents.stored(), bucketContents.histogram(), stage + ": bucket contents")
			);
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
		final HistogramContract histogram = queryShareHistogram(session, withBoundaries);
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
	 * Queries the share histogram of the group the reference summary of the live scope computes.
	 *
	 * @param session        the session to query
	 * @param withBoundaries TRUE to request the boundary entities
	 * @return the histogram, or `null` when the summary holds none for the group
	 */
	@Nullable
	private static HistogramContract queryShareHistogram(
		@Nonnull EvitaSessionContract session,
		boolean withBoundaries
	) {
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
		return statistics == null ? null : statistics.getHistogramStatistics(HISTOGRAM_SHARE);
	}

	/**
	 * Describes the non-empty buckets of the share histogram of the group twice: with the occurrences the histogram
	 * counts, and with the occurrences the stored references give them. A bucket counts, for every value inside its
	 * range, the owners carrying that value - an owner carrying a value on two references counts once - so the stored
	 * side counts the distinct shares of each owner into the bucket whose range holds them, and lists apart the shares
	 * no bucket holds. A histogram holding an excess value, missing a value or counting an owner it should not tells
	 * the two apart even where its smallest and largest value are right.
	 *
	 * @param session the session to query
	 * @return both descriptions
	 */
	@Nonnull
	private static BucketContents describeBucketContents(@Nonnull EvitaSessionContract session) {
		final HistogramContract histogram = queryShareHistogram(session, false);
		final Bucket[] buckets = histogram == null ? new Bucket[0] : histogram.getBuckets();
		final int[] histogramOccurrences = new int[buckets.length];
		for (int i = 0; i < buckets.length; i++) {
			histogramOccurrences[i] = buckets[i].occurrences();
		}
		final int[] storedOccurrences = new int[buckets.length];
		final List<Integer> outside = new ArrayList<>(4);
		for (final int ownerPk : new int[]{PRODUCT_PK, ANCHOR_PK}) {
			for (final int share : distinctStoredShares(session, ownerPk)) {
				final int bucketIndex = histogram == null ? -1 : bucketHolding(buckets, histogram.getMax(), share);
				if (bucketIndex < 0) {
					outside.add(share);
				} else {
					storedOccurrences[bucketIndex]++;
				}
			}
		}
		return new BucketContents(
			describeOccurrences(buckets, histogramOccurrences),
			describeOccurrences(buckets, storedOccurrences) + (outside.isEmpty() ? "" : " outside " + outside)
		);
	}

	/**
	 * Returns the index of the bucket whose range holds `value`: a bucket spans from its threshold up to the threshold
	 * of the next one, the last bucket up to the largest value of the histogram inclusive.
	 *
	 * @param buckets the buckets of the histogram
	 * @param max     the largest value of the histogram
	 * @param value   the value
	 * @return the index of the bucket, or `-1` when no bucket holds the value
	 */
	private static int bucketHolding(@Nonnull Bucket[] buckets, @Nonnull BigDecimal max, int value) {
		final BigDecimal number = BigDecimal.valueOf(value);
		if (number.compareTo(max) > 0) {
			return -1;
		}
		for (int i = buckets.length - 1; i >= 0; i--) {
			if (number.compareTo(buckets[i].threshold()) >= 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Describes the buckets with a positive occurrence count as `threshold: occurrences`, in ascending order.
	 *
	 * @param buckets     the buckets of the histogram
	 * @param occurrences the occurrences of each bucket, by its index
	 * @return the description, for instance `[5: 1, 10: 1, 20: 1]`
	 */
	@Nonnull
	private static String describeOccurrences(@Nonnull Bucket[] buckets, @Nonnull int[] occurrences) {
		final List<String> described = new ArrayList<>(buckets.length);
		for (int i = 0; i < buckets.length; i++) {
			if (occurrences[i] > 0) {
				described.add(plain(buckets[i].threshold()) + ": " + occurrences[i]);
			}
		}
		return described.toString();
	}

	/**
	 * Returns the distinct shares the stored references of the owner `ownerPk` carry, in ascending order. An owner
	 * not stored yet carries none.
	 *
	 * @param session the session to read from
	 * @param ownerPk the primary key of the owner
	 * @return the distinct shares
	 */
	@Nonnull
	private static Set<Integer> distinctStoredShares(@Nonnull EvitaSessionContract session, int ownerPk) {
		final Set<Integer> shares = new TreeSet<>();
		final Optional<SealedEntity> owner = session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent());
		if (owner.isPresent()) {
			for (final ReferenceContract reference : owner.get().getReferences(REFERENCE_BRAND)) {
				final Integer share = reference.getAttribute(ATTRIBUTE_SHARE);
				if (share != null) {
					shares.add(share);
				}
			}
		}
		return shares;
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
	 * Defines the schemas, stores the group and the brands, and applies the set-up upserts of the scenario.
	 *
	 * The reference of a product to a brand is the one of {@link GroupHistogramTestSupport#defineProduct}, with the
	 * share histogram over its `share` attribute. Where the scenario says so, the histogram admits only references
	 * whose rank is positive - which every rank of the scenarios is.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param scenario the scenario
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, @Nonnull Scenario scenario) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		defineProduct(
			session,
			whichIs -> {
				whichIs.bucketed(
					HISTOGRAM_SHARE, ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_SHARE + "']")
				);
				if (scenario.rankReadByCondition()) {
					whichIs.bucketedPartially(ExpressionFactory.parse(CONDITION_RANKED));
				}
			}
		);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK));
		for (int brandPk : new int[]{BRAND_PK, OTHER_BRAND_PK, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_BRAND_PK}) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND, brandPk));
		}
		for (Consumer<EvitaSessionContract> upsert : scenario.setUp()) {
			upsert.accept(session);
		}
	}

}
