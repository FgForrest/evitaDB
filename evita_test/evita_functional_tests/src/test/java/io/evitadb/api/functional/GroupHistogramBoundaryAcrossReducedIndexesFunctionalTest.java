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
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.core.Evita;
import io.evitadb.core.expression.trigger.HistogramValueSource;
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
import java.util.ArrayList;
import java.util.List;
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
 * Verifies which referenced entities anchor the smallest and the largest value of the histogram of a group whose
 * owners spread over several reduced indexes of the group.
 *
 * The reference to a brand allows duplicates, is grouped, and its representative attribute `rank` decides which
 * reduced index of the group a reference sits in. The product references the brand 10, the anchor product the brands
 * 12 and 13, all in the group 1; each owner sits at one rank, so the group has one reduced index when the owners
 * share the rank and one per rank when they do not. The histogram of a scenario reads either the `share` attribute of
 * the reference or the `weight` attribute of the referenced brand.
 *
 * Every value of the histogram is written while every reduced index of the group already exists. The shares are
 * written by the steps, after the set-up stored every owner. Over the weight of the brand, the anchor product, whose
 * references bring the weights 10 and 20, is the last owner the set-up stores, and the reference of the product to
 * the brand 10 with the smallest weight is added by a step. The histogram of the group therefore holds every value
 * whatever order the reduced indexes were created in, and the boundaries alone are under test. They are checked after
 * the last upsert and again after a restart, without an order of the reference summary - the lowest primary key wins
 * a tie - and with the descending order of primary keys - the highest one wins.
 *
 * Two families of scenarios:
 *
 * - the histogram over the attribute of the referenced brand has its smallest value on a reference of another reduced
 *   index than its largest one, while the brand 9 carrying the same value is referenced only in another group
 *   (scenarios `REFERENCED_ENTITY_*`);
 * - two brands referenced from different reduced indexes carry the same boundary value of the histogram over the
 *   attribute of the reference, for both orders in which the reduced indexes are created, once with the smallest
 *   value equal to the largest one, and once across three reduced indexes with a third owner; next to them, no
 *   reference sitting in one of the two reduced indexes carries a share (scenarios `TIE_*`, `MIN_EQUALS_MAX_*`,
 *   `SHARES_IN_ONE_OF_TWO_INDEXES`).
 *
 * Every family has its control with every owner at one rank, so the candidates sit in one reduced index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The boundaries of the histogram of a group split into several reduced indexes")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class GroupHistogramBoundaryAcrossReducedIndexesFunctionalTest implements EvitaTestSupport, GroupHistogramTestSupport {

	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	/**
	 * The owner referencing the brand {@link #SECOND_ANCHOR_BRAND_PK} at the rank {@link #THIRD_RANK}, so that the
	 * group has a third reduced index.
	 */
	private static final int THIRD_OWNER_PK = 200;
	/**
	 * The group the brand {@link #OUTSIDE_BRAND_PK} is referenced in.
	 */
	private static final int OTHER_GROUP_PK = 2;
	/**
	 * The brand carrying the smallest weight like the brand {@link #BRAND_PK}, with a smaller primary key, referenced
	 * only in the group {@link #OTHER_GROUP_PK}.
	 */
	private static final int OUTSIDE_BRAND_PK = 9;
	/**
	 * The brand without a weight, whose reference gives the product its place in the group without a value of the
	 * weight histogram.
	 */
	private static final int PLACEHOLDER_BRAND_PK = 11;
	private static final int SMALLEST_VALUE = 5;
	private static final int MIDDLE_VALUE = 10;
	private static final int LARGEST_VALUE = 20;

	/**
	 * A catalog set up in warm-up, a sequence of upserts applied to it in the catalog state under test, and the
	 * boundaries the histogram of the group must have afterwards.
	 *
	 * @param name                      the name of the scenario
	 * @param source                    the source of the values of the histogram of the scenario
	 * @param setUp                     the upserts storing the owners in warm-up, in the order they are applied
	 * @param steps                     the upserts writing the values of the histogram, in the order they are applied
	 * @param expectedWithoutOrder      the expected description of the histogram of the group when the reference
	 *                                  summary has no order, see {@link #describeHistogram}
	 * @param expectedDescendingByKey   the expected description of the histogram of the group when the reference
	 *                                  summary orders the brands by their primary keys in descending order
	 */
	private record Scenario(
		@Nonnull String name,
		@Nonnull HistogramValueSource source,
		@Nonnull List<Consumer<EvitaSessionContract>> setUp,
		@Nonnull List<Consumer<EvitaSessionContract>> steps,
		@Nonnull String expectedWithoutOrder,
		@Nonnull String expectedDescendingByKey
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
	 * The scenarios of the histogram over the weight of the referenced brand, see
	 * {@link #shouldAnchorBoundariesInWholeGroupWhenHistogramReadsReferencedEntity}.
	 *
	 * The product takes its place in the group by a reference to the brand without a weight and references the brand
	 * 9 in the other group; the anchor product references the brands 12 and 13 with the weights 10 and 20. Only then
	 * the product references the brand 10 with the weight 5. In the first scenario the product sits at another rank
	 * than the anchor product and creates its reduced index first, so the smallest value belongs to a reference of
	 * another reduced index than the references of the anchor product. The control has every owner at one rank.
	 *
	 * @return arguments of {@link #shouldAnchorBoundariesInWholeGroupWhenHistogramReadsReferencedEntity}
	 */
	@Nonnull
	static Stream<Arguments> referencedEntityScenarios() {
		final String expected = anchoredRange(SMALLEST_VALUE, BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK);
		return withBothCatalogStates(
			List.of(
				new Scenario(
					"REFERENCED_ENTITY_BOUNDARY_HELD_BY_OWNER_OF_OTHER_INDEX",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE,
					List.of(createProductWithoutWeight(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					List.of(productReferencesBrand(SECOND_RANK)),
					expected, expected
				),
				new Scenario(
					"REFERENCED_ENTITY_CONTROL_EVERYONE_AT_ONE_RANK",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE,
					List.of(createProductWithoutWeight(FIRST_RANK), createAnchor(FIRST_RANK, false)),
					List.of(productReferencesBrand(FIRST_RANK)),
					expected, expected
				)
			)
		);
	}

	/**
	 * The scenarios of a boundary value of the histogram over the share of the reference carried by the references of
	 * two brands, see {@link #shouldBreakBoundaryTieOverWholeGroupWhenReferencesOfSeveralIndexesCarryIt}.
	 *
	 * Both owners are stored without a share. Then the references of the anchor product to the brands 12 and 13 get
	 * their shares and the reference of the product to the brand 10 gets the share 10, so the brands 10 and 12 tie on
	 * the smallest value. The product sits at the second rank and creates its reduced index first, or after the anchor
	 * product created the reduced index of the first rank; the control has every owner at the first rank. In the
	 * scenario `MIN_EQUALS_MAX_*` all three references carry the share 10, so the smallest value is the largest one and
	 * all three brands tie on it.
	 *
	 * Two more scenarios vary the number of reduced indexes whose references carry a share. In one the product never
	 * gets a share, so no reference of the reduced index of the second rank carries one and the boundaries come from
	 * the anchor product's references alone. In the other a third owner at the third rank references the brand 13
	 * with the share 10 as well, so the tie on the smallest value spans three reduced indexes: the brand 10 of the
	 * second rank wins it without an order, the brand 13 of the third rank with the descending one.
	 *
	 * @return arguments of {@link #shouldBreakBoundaryTieOverWholeGroupWhenReferencesOfSeveralIndexesCarryIt}
	 */
	@Nonnull
	static Stream<Arguments> tieScenarios() {
		final List<Consumer<EvitaSessionContract>> tiedShares = List.of(
			anchorShares(MIDDLE_VALUE, LARGEST_VALUE), productShare(MIDDLE_VALUE)
		);
		final String tieWithoutOrder = anchoredRange(MIDDLE_VALUE, BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK);
		final String tieDescendingByKey = anchoredRange(
			MIDDLE_VALUE, FIRST_ANCHOR_BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK
		);
		return withBothCatalogStates(
			List.of(
				new Scenario(
					"TIE_ACROSS_INDEXES_PRODUCT_INDEX_CREATED_FIRST",
					HistogramValueSource.REFERENCE_ATTRIBUTE,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					tiedShares,
					tieWithoutOrder, tieDescendingByKey
				),
				new Scenario(
					"TIE_ACROSS_INDEXES_ANCHOR_INDEX_CREATED_FIRST",
					HistogramValueSource.REFERENCE_ATTRIBUTE,
					List.of(createAnchor(FIRST_RANK, false), createProductWithoutShare(SECOND_RANK)),
					tiedShares,
					tieWithoutOrder, tieDescendingByKey
				),
				new Scenario(
					"TIE_CONTROL_EVERYONE_AT_ONE_RANK",
					HistogramValueSource.REFERENCE_ATTRIBUTE,
					List.of(createProductWithoutShare(FIRST_RANK), createAnchor(FIRST_RANK, false)),
					tiedShares,
					tieWithoutOrder, tieDescendingByKey
				),
				new Scenario(
					"MIN_EQUALS_MAX_ACROSS_INDEXES",
					HistogramValueSource.REFERENCE_ATTRIBUTE,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					List.of(anchorShares(MIDDLE_VALUE, MIDDLE_VALUE), productShare(MIDDLE_VALUE)),
					anchoredRange(MIDDLE_VALUE, BRAND_PK, MIDDLE_VALUE, BRAND_PK),
					anchoredRange(MIDDLE_VALUE, SECOND_ANCHOR_BRAND_PK, MIDDLE_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"SHARES_IN_ONE_OF_TWO_INDEXES",
					HistogramValueSource.REFERENCE_ATTRIBUTE,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					List.of(anchorShares(MIDDLE_VALUE, LARGEST_VALUE)),
					anchoredRange(MIDDLE_VALUE, FIRST_ANCHOR_BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK),
					anchoredRange(MIDDLE_VALUE, FIRST_ANCHOR_BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"TIE_ACROSS_THREE_INDEXES",
					HistogramValueSource.REFERENCE_ATTRIBUTE,
					List.of(
						createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false),
						createThirdOwnerWithoutShare(THIRD_RANK)
					),
					List.of(
						anchorShares(MIDDLE_VALUE, LARGEST_VALUE), productShare(MIDDLE_VALUE),
						everyReferenceAttribute(THIRD_OWNER_PK, ATTRIBUTE_SHARE, MIDDLE_VALUE)
					),
					anchoredRange(MIDDLE_VALUE, BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK),
					anchoredRange(MIDDLE_VALUE, SECOND_ANCHOR_BRAND_PK, LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK)
				)
			)
		);
	}

	/**
	 * Applies the upserts of the scenario and checks, after the last of them and after a restart, that the smallest
	 * and the largest value of the histogram over the weight of the referenced brand are anchored at the brands
	 * carrying them in the group, wherever their owners sit in the group, and never at a brand of another group.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("referencedEntityScenarios")
	@DisplayName("the boundaries of a histogram over the referenced entity are anchored in the whole group")
	void shouldAnchorBoundariesInWholeGroupWhenHistogramReadsReferencedEntity(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Applies the upserts of the scenario and checks, after the last of them and after a restart, that a boundary
	 * value carried by the references of several brands is anchored at the brand the order of the reference summary
	 * picks among all of them, whichever reduced index of the group their owners sit in.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("tieScenarios")
	@DisplayName("a boundary value carried in several reduced indexes is anchored by the order over all of them")
	void shouldBreakBoundaryTieOverWholeGroupWhenReferencesOfSeveralIndexesCarryIt(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Runs one scenario: sets the catalog up, applies each upsert, checks the histogram of the group with both orders
	 * of the reference summary, restarts the engine and checks it again. All failed checks are reported together.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	private void runScenario(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		final TestPaths paths = createTestPaths("GroupHistogramBoundary_" + state + "_" + scenario.name());
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(8);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, scenario);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});

			int stepNumber = 0;
			for (Consumer<EvitaSessionContract> step : scenario.steps()) {
				stepNumber++;
				final String stage = "upsert " + stepNumber;
				try {
					evita.updateCatalog(TEST_CATALOG, step);
				} catch (RuntimeException ex) {
					checks.add(
						() -> fail(
							stage + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex
						)
					);
				}
			}
			recordState(evita, "after the last upsert", scenario, checks);
			evita = restart(evita, configuration);
			recordState(evita, "after a restart", scenario, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Records the checks that the histogram of the group is described as the scenario expects, without an order of the
	 * reference summary and with the descending order of primary keys, at the moment `stage` names.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param scenario the scenario
	 * @param checks   the collected checks
	 */
	private static void recordState(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scenario scenario,
		@Nonnull List<Executable> checks
	) {
		final String histogramName = histogramName(scenario.source());
		try {
			final String withoutOrder = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return describeHistogram(session, histogramName, null);
				}
			);
			checks.add(
				() -> assertEquals(scenario.expectedWithoutOrder(), withoutOrder, stage + ", without order")
			);
			final String descendingByKey = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return describeHistogram(
						session, histogramName, orderBy(entityPrimaryKeyNatural(OrderDirection.DESC))
					);
				}
			);
			checks.add(
				() -> assertEquals(
					scenario.expectedDescendingByKey(), descendingByKey, stage + ", descending by primary key"
				)
			);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": a query threw " + ex, ex));
		}
	}

	/**
	 * Describes the histogram `histogramName` of the group the reference summary of the live scope computes: its
	 * smallest and largest value, each followed by the primary key of the brand anchoring it, or the absence of the
	 * histogram.
	 *
	 * @param session       the session to query
	 * @param histogramName the name of the histogram
	 * @param order         the order of the brands in the reference summary, or `null` for none
	 * @return the description, see {@link #anchoredRange}
	 */
	@Nonnull
	private static String describeHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull String histogramName,
		@Nullable OrderBy order
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(Scope.LIVE)),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null, order, null, null, null,
						histogramStatistics(BUCKET_COUNT, entityFetch(), histogramName)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics = summary == null ?
			null : summary.getReferenceGroupStatistics(REFERENCE_BRAND, GROUP_PK);
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(histogramName);
		if (histogram == null) {
			return NO_HISTOGRAM;
		}
		return "min=" + plain(histogram.getMin()) + "@" +
			describeBoundary(histogram.getMinReferencedEntity().orElse(null)) +
			" max=" + plain(histogram.getMax()) + "@" +
			describeBoundary(histogram.getMaxReferencedEntity().orElse(null));
	}

	/**
	 * Creates the upsert storing the product with one reference to the brand 10 in the group 1 at `rank`, without a
	 * share.
	 *
	 * @param rank the rank of the reference
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProductWithoutShare(int rank) {
		return session -> {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
			addReference(product, BRAND_PK, GROUP_PK, rank, null);
			product.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the product with a reference to the brand 11, which has no weight, in the group 1 and
	 * a reference to the brand 9 in the group 2, both at `rank`.
	 *
	 * @param rank the rank of the references
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProductWithoutWeight(int rank) {
		return session -> {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
			addReference(product, PLACEHOLDER_BRAND_PK, GROUP_PK, rank, null);
			addReference(product, OUTSIDE_BRAND_PK, OTHER_GROUP_PK, rank, null);
			product.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert giving the stored product a reference to the brand 10 in the group 1 at `rank`, without a
	 * share.
	 *
	 * @param rank the rank of the reference
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> productReferencesBrand(int rank) {
		return session -> {
			final EntityBuilder product = session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			addReference(product, BRAND_PK, GROUP_PK, rank, null);
			product.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the third owner with one reference to the brand 13 in the group 1 at `rank`, without
	 * a share.
	 *
	 * @param rank the rank of the reference
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createThirdOwnerWithoutShare(int rank) {
		return session -> {
			final EntityBuilder owner = session.createNewEntity(ENTITY_PRODUCT, THIRD_OWNER_PK);
			addReference(owner, SECOND_ANCHOR_BRAND_PK, GROUP_PK, rank, null);
			owner.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert setting the share of every reference of the stored product to `share`.
	 *
	 * @param share the share
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> productShare(int share) {
		return everyReferenceAttribute(PRODUCT_PK, ATTRIBUTE_SHARE, share);
	}

	/**
	 * Returns the name of the histogram reading values of `source`.
	 *
	 * @param source the source of the values
	 * @return the name of the histogram
	 */
	@Nonnull
	private static String histogramName(@Nonnull HistogramValueSource source) {
		return switch (source) {
			case REFERENCE_ATTRIBUTE -> HISTOGRAM_SHARE;
			case REFERENCED_ENTITY_ATTRIBUTE -> HISTOGRAM_WEIGHT;
		};
	}

	/**
	 * Returns the expression of the values of the histogram reading `source`.
	 *
	 * @param source the source of the values
	 * @return the expression of the values
	 */
	@Nonnull
	private static String histogramValue(@Nonnull HistogramValueSource source) {
		return switch (source) {
			case REFERENCE_ATTRIBUTE -> "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
			case REFERENCED_ENTITY_ATTRIBUTE -> "$reference.referencedEntity?.attributes['" + ATTRIBUTE_WEIGHT + "']";
		};
	}

	/**
	 * Returns the weight of the brand `brandPk`: the smallest value for the brands 9 and 10, the middle and the largest
	 * one for the brands 12 and 13, none for the brand 11.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the weight, or `null` for none
	 */
	@Nullable
	private static Integer weightOf(int brandPk) {
		return switch (brandPk) {
			case OUTSIDE_BRAND_PK, BRAND_PK -> SMALLEST_VALUE;
			case FIRST_ANCHOR_BRAND_PK -> MIDDLE_VALUE;
			case SECOND_ANCHOR_BRAND_PK -> LARGEST_VALUE;
			case PLACEHOLDER_BRAND_PK -> null;
			default -> throw new IllegalArgumentException("Unknown brand " + brandPk + ".");
		};
	}

	/**
	 * Defines the schemas, stores the groups and the brands with their weights, and applies the set-up upserts of the
	 * scenario.
	 *
	 * The brand carries the filterable `weight` attribute. The reference of a product to a brand is the one of
	 * {@link GroupHistogramTestSupport#defineProduct}, with the one histogram reading the source of the scenario.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param scenario the scenario
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, @Nonnull Scenario scenario) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		defineProduct(
			session,
			whichIs -> whichIs.bucketed(
				histogramName(scenario.source()), ExpressionFactory.parse(histogramValue(scenario.source()))
			)
		);

		for (int groupPk : new int[]{GROUP_PK, OTHER_GROUP_PK}) {
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, groupPk));
		}
		for (int brandPk : new int[]{
			OUTSIDE_BRAND_PK, BRAND_PK, PLACEHOLDER_BRAND_PK, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_BRAND_PK
		}) {
			final EntityBuilder brand = session.createNewEntity(ENTITY_BRAND, brandPk);
			final Integer weight = weightOf(brandPk);
			if (weight != null) {
				brand.setAttribute(ATTRIBUTE_WEIGHT, weight);
			}
			session.upsertEntity(brand);
		}
		for (Consumer<EvitaSessionContract> upsert : scenario.setUp()) {
			upsert.accept(session);
		}
	}

}
