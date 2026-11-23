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
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.functional.GroupHistogramTestSupport.addReference;
import static io.evitadb.api.functional.GroupHistogramTestSupport.defineProduct;
import static io.evitadb.api.functional.GroupHistogramTestSupport.plain;
import static io.evitadb.api.functional.GroupHistogramTestSupport.restart;
import static io.evitadb.api.functional.GroupHistogramTestSupport.withBothCatalogStates;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REQUIRE;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that two histograms of one grouped reference, requested in one reference summary and anchored at the same
 * brands, each receive their boundary entities fetched with their own `entityFetch` requirement: the boundary entity
 * of each histogram carries exactly the content it carries when the histogram is requested alone, and the query does
 * not fail. Histograms asking for their boundary entities alike share one fetch of them.
 *
 * The fixture is the one of {@link GroupHistogramTestSupport} with two histograms on the brand reference, neither with
 * a condition or a default: `shareHistogram` over the reference attribute `share` and `weightHistogram` over the brand
 * attribute `weight`. The brand also carries the attribute `code`, so two different explicit requirements reveal
 * different content. Every reference sits in the group 1 at the first rank. The anchor product references the brand 12
 * with the share 10, the brand 13 with the share 20 and the brand 14 with the share 10; the brands 12 and 14 weigh 30,
 * the brand 13 weighs 40. So both histograms tie at their smallest value between the brands 12 and 14 and are anchored
 * at the same brands: at the brands 12 and 13 without an order of the reference summary - the lowest primary key wins
 * a tie - and at the brands 14 and 13 with the descending order of primary keys. In the narrowed scenarios the product
 * also references the brand 10 with the share 10 and the brand 11 with the share 20, both weighing 0, and the query
 * keeps only the anchor product, so its brands must not anchor anything while a brand of the anchor product can.
 *
 * One more narrowed scenario stores no entity for the brand 11, lets the anchor product reference it with the share
 * 10, and lets the product reference the brand 10 alone. The referenced LIVE brands 12 and 14 have scope priority
 * over the unstored brand, so the anchor is still brand 12 without an order and brand 14 with descending PK order.
 * Each histogram still fetches the boundary with its own requirement.
 *
 * Each scenario is checked after the owners are stored, in both catalog states, and again after a restart; without an
 * order and with the descending order of primary keys; each histogram requested alone and both together, in both orders
 * of their requirements. The histograms requested together must be anchored as expected, with each boundary entity
 * described exactly as when its histogram is requested alone. A histogram carries the boundary entity instances the
 * accumulator fetched, so the instances show how often a brand was fetched: histograms asking alike must share one
 * instance of a brand, histograms asking differently must not.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The boundary entities of sibling group histograms fetched by the requirement of each")
@Tag(CONTRACT)
@Tag(REQUIRE)
@Tag(HISTOGRAM)
class GroupHistogramSiblingBoundaryFetchFunctionalTest implements EvitaTestSupport, GroupHistogramTestSupport {

	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	/**
	 * The names of both histograms, in the order the checks describe them.
	 */
	private static final String[] HISTOGRAM_NAMES = {HISTOGRAM_SHARE, HISTOGRAM_WEIGHT};
	/**
	 * The brand the product references with the share 20 in narrowed scenarios. The missing-candidate scenario
	 * stores no entity for it and the anchor product references it with the share 10.
	 */
	private static final int OTHER_SHARE_BRAND_PK = 11;
	/**
	 * The brand of the anchor product tying with the brand 12 at the smallest share and the smallest weight.
	 */
	private static final int TIED_BRAND_PK = 14;
	/**
	 * The weight of the brands 12 and 14, the smallest weight of the anchor product.
	 */
	private static final int TIED_WEIGHT = 30;
	/**
	 * The weight of the brand 13, the largest weight of the anchor product.
	 */
	private static final int LARGEST_WEIGHT = 40;

	/**
	 * The content a histogram asks for its boundary entities with.
	 */
	private enum BoundaryContent {
		/**
		 * No `entityFetch`: the boundary entity carries no attributes.
		 */
		NONE,
		/**
		 * `entityFetch(attributeContent("weight"))`.
		 */
		WEIGHT,
		/**
		 * `entityFetch(attributeContent("code"))`.
		 */
		CODE;

		/**
		 * Creates a new requirement asking for this content - a new instance on every call.
		 *
		 * @return the requirement, or `null` for {@link #NONE}
		 */
		@Nullable
		EntityFetch createFetch() {
			return switch (this) {
				case NONE -> null;
				case WEIGHT -> entityFetch(attributeContent(ATTRIBUTE_WEIGHT));
				case CODE -> entityFetch(attributeContent(ATTRIBUTE_CODE));
			};
		}

		/**
		 * Describes the brand `brandPk` fetched with this content, as {@link #describeContent} does.
		 *
		 * @param brandPk the primary key of the brand
		 * @return the expected description of the brand
		 */
		@Nonnull
		String describe(int brandPk) {
			return switch (this) {
				case NONE -> brandPk + "[no attributes]";
				case WEIGHT -> brandPk + "[" + ATTRIBUTE_WEIGHT + "=" + weightOf(brandPk) + "]";
				case CODE -> brandPk + "[" + ATTRIBUTE_CODE + "=" + codeOf(brandPk) + "]";
			};
		}
	}

	/**
	 * How the requirements of the two histograms are composed when both are requested together.
	 */
	private enum Composition {
		/**
		 * Each histogram has its own `histogramStatistics`, each with a requirement created for it.
		 */
		SEPARATE_REQUIREMENTS,
		/**
		 * Each histogram has its own `histogramStatistics`, both given one `EntityFetch` object.
		 */
		ONE_FETCH_OBJECT,
		/**
		 * One `histogramStatistics` names both histograms.
		 */
		ONE_REQUIREMENT
	}

	/**
	 * The contents both histograms ask for their boundary entities with, and how their requirements are composed.
	 */
	private enum FetchCase {
		/**
		 * The share histogram without `entityFetch`, the weight histogram asking for the weight.
		 */
		SHARE_WITHOUT_FETCH(BoundaryContent.NONE, BoundaryContent.WEIGHT, Composition.SEPARATE_REQUIREMENTS),
		/**
		 * The share histogram asking for the code, the weight histogram asking for the weight.
		 */
		SHARE_FETCHING_CODE(BoundaryContent.CODE, BoundaryContent.WEIGHT, Composition.SEPARATE_REQUIREMENTS),
		/**
		 * Both histograms asking for the weight with requirements equal in content but created separately.
		 */
		EQUAL_FETCHES(BoundaryContent.WEIGHT, BoundaryContent.WEIGHT, Composition.SEPARATE_REQUIREMENTS),
		/**
		 * Neither histogram with `entityFetch`.
		 */
		NEITHER_FETCHING(BoundaryContent.NONE, BoundaryContent.NONE, Composition.SEPARATE_REQUIREMENTS),
		/**
		 * Both histograms asking for the weight with one `EntityFetch` object passed to both requirements.
		 */
		ONE_FETCH_OBJECT(BoundaryContent.WEIGHT, BoundaryContent.WEIGHT, Composition.ONE_FETCH_OBJECT),
		/**
		 * Both histograms named by one requirement asking for the weight.
		 */
		ONE_REQUIREMENT(BoundaryContent.WEIGHT, BoundaryContent.WEIGHT, Composition.ONE_REQUIREMENT);

		/**
		 * The content the share histogram asks for.
		 */
		private final BoundaryContent shareContent;
		/**
		 * The content the weight histogram asks for.
		 */
		private final BoundaryContent weightContent;
		/**
		 * How the requirements of both histograms are composed; anything but
		 * {@link Composition#SEPARATE_REQUIREMENTS} implies both ask for the same content.
		 */
		private final Composition composition;

		/**
		 * Creates the case.
		 *
		 * @param shareContent  the content the share histogram asks for
		 * @param weightContent the content the weight histogram asks for
		 * @param composition   how the requirements of both histograms are composed
		 */
		FetchCase(
			@Nonnull BoundaryContent shareContent,
			@Nonnull BoundaryContent weightContent,
			@Nonnull Composition composition
		) {
			this.shareContent = shareContent;
			this.weightContent = weightContent;
			this.composition = composition;
		}

		/**
		 * Returns the content the histogram `histogramName` asks for.
		 *
		 * @param histogramName the name of the histogram
		 * @return the content
		 */
		@Nonnull
		BoundaryContent contentOf(@Nonnull String histogramName) {
			return HISTOGRAM_SHARE.equals(histogramName) ? this.shareContent : this.weightContent;
		}

		/**
		 * Returns TRUE when both histograms ask for the same content, so one fetch of a brand serves both.
		 *
		 * @return TRUE when both histograms ask alike
		 */
		boolean asksAlike() {
			return this.shareContent == this.weightContent;
		}

		/**
		 * Returns the requirement of the histogram `histogramName` requested alone.
		 *
		 * @param histogramName the name of the histogram
		 * @return the requirement
		 */
		@Nonnull
		ReferenceHistogramStatistics alone(@Nonnull String histogramName) {
			return histogramStatistics(BUCKET_COUNT, contentOf(histogramName).createFetch(), histogramName);
		}

		/**
		 * Returns the requirements of both histograms requested together.
		 *
		 * @param shareFirst TRUE to name the share histogram first, FALSE to name the weight histogram first
		 * @return the requirements
		 */
		@Nonnull
		ReferenceHistogramStatistics[] together(boolean shareFirst) {
			final String first = shareFirst ? HISTOGRAM_SHARE : HISTOGRAM_WEIGHT;
			final String second = shareFirst ? HISTOGRAM_WEIGHT : HISTOGRAM_SHARE;
			return switch (this.composition) {
				case SEPARATE_REQUIREMENTS -> new ReferenceHistogramStatistics[]{alone(first), alone(second)};
				case ONE_FETCH_OBJECT -> {
					final EntityFetch fetch = this.shareContent.createFetch();
					yield new ReferenceHistogramStatistics[]{
						histogramStatistics(BUCKET_COUNT, fetch, first),
						histogramStatistics(BUCKET_COUNT, fetch, second)
					};
				}
				case ONE_REQUIREMENT -> new ReferenceHistogramStatistics[]{
					histogramStatistics(BUCKET_COUNT, this.shareContent.createFetch(), first, second)
				};
			};
		}
	}

	/**
	 * A case of requirements, whether the query keeps only the anchor product while the product references other
	 * brands, and whether an unstored brand ties at the smallest share.
	 *
	 * @param fetchCase      the requirements of the histograms
	 * @param narrowed       TRUE to store the product too and keep only the anchor product in the query
	 * @param missingCandidate TRUE to leave brand 11 unstored and give it share 10 on the anchor product's reference,
	 *                         while the product references brand 10 alone; only with `narrowed`
	 */
	private record Scenario(@Nonnull FetchCase fetchCase, boolean narrowed, boolean missingCandidate) {

		/**
		 * Creates a scenario whose picks can all be loaded.
		 *
		 * @param fetchCase the requirements of the histograms
		 * @param narrowed  TRUE to store the product too and keep only the anchor product in the query
		 */
		Scenario(@Nonnull FetchCase fetchCase, boolean narrowed) {
			this(fetchCase, narrowed, false);
		}

		/**
		 * Returns the name of the scenario, which the parameterized test shows as the name of its case.
		 *
		 * @return the name of the scenario
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.fetchCase + (this.narrowed ? "_NARROWED" : "_UNNARROWED") +
				(this.missingCandidate ? "_MISSING_CANDIDATE" : "");
		}
	}

	/**
	 * A histogram as the checks see it.
	 *
	 * @param compact its bounds, each followed by the primary key and the attributes of the boundary entity, see
	 *                {@link #describeContent}
	 * @param full    its bounds, each followed by the full description of the boundary entity
	 * @param min     the entity anchoring the smallest value, or `null` for none
	 * @param max     the entity anchoring the largest value, or `null` for none
	 */
	private record HistogramView(
		@Nonnull String compact,
		@Nonnull String full,
		@Nullable SealedEntity min,
		@Nullable SealedEntity max
	) {

		/**
		 * The view of a histogram the reference summary does not contain.
		 */
		static final HistogramView ABSENT = new HistogramView(NO_HISTOGRAM, NO_HISTOGRAM, null, null);

		/**
		 * Creates the view of `histogram`.
		 *
		 * @param histogram the histogram
		 * @return the view
		 */
		@Nonnull
		static HistogramView of(@Nonnull HistogramContract histogram) {
			final SealedEntity min = histogram.getMinReferencedEntity().orElse(null);
			final SealedEntity max = histogram.getMaxReferencedEntity().orElse(null);
			final String minValue = "min=" + plain(histogram.getMin()) + "@";
			final String maxValue = " max=" + plain(histogram.getMax()) + "@";
			return new HistogramView(
				minValue + describeContent(min) + maxValue + describeContent(max),
				minValue + describeFully(min) + maxValue + describeFully(max),
				min,
				max
			);
		}
	}

	/**
	 * The scenarios whose histograms ask for their boundary entities differently, see
	 * {@link #shouldFetchBoundaryEntitiesByRequirementOfEachHistogramWhenRequirementsDiffer}. One narrowed scenario
	 * includes an unstored brand tied at the smallest share.
	 *
	 * @return arguments of the test: the catalog state and the scenario
	 */
	@Nonnull
	static Stream<Arguments> differentRequirementScenarios() {
		final List<Scenario> scenarios = scenarios(FetchCase.SHARE_WITHOUT_FETCH, FetchCase.SHARE_FETCHING_CODE);
		scenarios.add(new Scenario(FetchCase.SHARE_FETCHING_CODE, true, true));
		return withBothCatalogStates(scenarios);
	}

	/**
	 * The scenarios whose histograms ask for their boundary entities alike, see
	 * {@link #shouldFetchSharedBoundaryEntitiesOnceWhenRequirementsAreAlike}.
	 *
	 * @return arguments of the test: the catalog state and the scenario
	 */
	@Nonnull
	static Stream<Arguments> alikeRequirementScenarios() {
		return withBothCatalogStates(
			scenarios(
				FetchCase.EQUAL_FETCHES, FetchCase.NEITHER_FETCHING,
				FetchCase.ONE_FETCH_OBJECT, FetchCase.ONE_REQUIREMENT
			)
		);
	}

	/**
	 * Checks that two histograms anchored at the same brands while asking for different content each receive the
	 * brands with the content they ask for - as when requested alone - and that the query does not fail. Where the
	 * smallest share is picked at a brand the query cannot load, the share histogram receives the brand anchoring it
	 * instead with the content it asks for too.
	 *
	 * @param state    the state the catalog is in when the owners are stored
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("differentRequirementScenarios")
	@DisplayName("each histogram receives the boundary entities fetched by its own requirement")
	void shouldFetchBoundaryEntitiesByRequirementOfEachHistogramWhenRequirementsDiffer(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Checks that two histograms anchored at the same brands while asking for the same content - by equal requirements,
	 * by one requirement object, by one requirement naming both, or by none at all - receive the brands with that
	 * content, as when requested alone, sharing one fetch of each brand.
	 *
	 * @param state    the state the catalog is in when the owners are stored
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("alikeRequirementScenarios")
	@DisplayName("histograms asking alike share one fetch of their boundary entities")
	void shouldFetchSharedBoundaryEntitiesOnceWhenRequirementsAreAlike(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Pairs each of `fetchCases` with an unnarrowed and a narrowed query.
	 *
	 * @param fetchCases the cases of requirements
	 * @return the scenarios, in a list open to more
	 */
	@Nonnull
	private static List<Scenario> scenarios(@Nonnull FetchCase... fetchCases) {
		final List<Scenario> result = new ArrayList<>(fetchCases.length * 2);
		for (final FetchCase fetchCase : fetchCases) {
			result.add(new Scenario(fetchCase, false));
			result.add(new Scenario(fetchCase, true));
		}
		return result;
	}

	/**
	 * Runs one scenario: sets the catalog up, stores the owners in `state`, checks the histograms, restarts the engine
	 * and checks them again. All failed checks are reported together.
	 *
	 * @param state    the state the catalog is in when the owners are stored
	 * @param scenario the scenario
	 */
	private void runScenario(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		final TestPaths paths = createTestPaths("GroupHistogramSiblingBoundaryFetch_" + state + "_" + scenario);
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
			evita.updateCatalog(TEST_CATALOG, session -> {
				storeOwners(session, scenario);
			});
			recordState(evita, "after the owners are stored", scenario, checks);
			evita = restart(evita, configuration);
			recordState(evita, "after a restart", scenario, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Records the checks of both histograms at the moment `stage` names, without an order of the reference summary and
	 * with the descending order of primary keys: each histogram requested alone is anchored as expected, and both
	 * requested together - in both orders of their requirements - are anchored as expected, with each boundary entity
	 * described exactly as when its histogram is requested alone, and with one instance of a brand anchoring both
	 * histograms exactly when they ask for it alike.
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
		final FetchCase fetchCase = scenario.fetchCase();
		for (final boolean descending : new boolean[]{false, true}) {
			final OrderBy order = descending ? orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)) : null;
			final String moment = stage + (descending ? ", descending by primary key" : ", without order");
			final int minBrandPk = descending ? TIED_BRAND_PK : FIRST_ANCHOR_BRAND_PK;

			final Map<String, HistogramView> alone = createHashMap(HISTOGRAM_NAMES.length);
			for (final String name : HISTOGRAM_NAMES) {
				final String what = moment + ", " + name + " alone";
				final Map<String, HistogramView> result = runQuery(
					evita, scenario, order, what, checks, fetchCase.alone(name)
				);
				if (result != null) {
					final HistogramView view = viewOf(result, name);
					alone.put(name, view);
					final String expected = expectedHistogram(
						name, fetchCase.contentOf(name), minBrandPk
					);
					checks.add(() -> assertEquals(expected, view.compact(), what));
				}
			}

			for (final boolean shareFirst : new boolean[]{true, false}) {
				final String what = moment + (shareFirst ? ", share requested first" : ", weight requested first");
				final Map<String, HistogramView> result = runQuery(
					evita, scenario, order, what, checks, fetchCase.together(shareFirst)
				);
				if (result == null) {
					continue;
				}
				for (final String name : HISTOGRAM_NAMES) {
					final HistogramView view = viewOf(result, name);
					final String expected = expectedHistogram(
						name, fetchCase.contentOf(name), minBrandPk
					);
					checks.add(() -> assertEquals(expected, view.compact(), what + ", " + name));
					final HistogramView viewAlone = alone.get(name);
					if (viewAlone != null) {
						checks.add(
							() -> assertEquals(viewAlone.full(), view.full(), what + ", " + name + " as when alone")
						);
					}
				}
				recordInstanceChecks(
					fetchCase, viewOf(result, HISTOGRAM_SHARE), viewOf(result, HISTOGRAM_WEIGHT), what, checks
				);
			}
		}
	}

	/**
	 * Records the checks that the share and the weight histogram requested together share one instance of each brand
	 * anchoring both when they ask for it alike, and hold different instances when they ask differently.
	 *
	 * @param fetchCase the requirements of the histograms
	 * @param share     the share histogram
	 * @param weight    the weight histogram
	 * @param what      names the query
	 * @param checks    the collected checks
	 */
	private static void recordInstanceChecks(
		@Nonnull FetchCase fetchCase,
		@Nonnull HistogramView share,
		@Nonnull HistogramView weight,
		@Nonnull String what,
		@Nonnull List<Executable> checks
	) {
		if (fetchCase.asksAlike()) {
			checks.add(() -> assertSame(share.min(), weight.min(), what + ", one fetch of the smallest boundary"));
			checks.add(() -> assertSame(share.max(), weight.max(), what + ", one fetch of the largest boundary"));
		} else {
			checks.add(
				() -> assertNotSame(share.min(), weight.min(), what + ", own fetch of the smallest boundary")
			);
			checks.add(
				() -> assertNotSame(share.max(), weight.max(), what + ", own fetch of the largest boundary")
			);
		}
	}

	/**
	 * Runs the query of the scenario with `requirements` and returns the histograms of the group 1 by their names. A
	 * failing query is recorded as a failed check.
	 *
	 * @param evita        the engine
	 * @param scenario     the scenario
	 * @param order        the order of the brands in the reference summary, or `null` for none
	 * @param what         names the query
	 * @param checks       the collected checks
	 * @param requirements the histogram requirements
	 * @return the histograms by their names, or `null` when the query failed
	 */
	@Nullable
	private static Map<String, HistogramView> runQuery(
		@Nonnull Evita evita,
		@Nonnull Scenario scenario,
		@Nullable OrderBy order,
		@Nonnull String what,
		@Nonnull List<Executable> checks,
		@Nonnull ReferenceHistogramStatistics... requirements
	) {
		try {
			return evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return queryHistograms(session, scenario.narrowed(), order, requirements);
				}
			);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(what + ": the query threw " + ex, ex));
			return null;
		}
	}

	/**
	 * Queries the reference summary of the brand reference with `requirements` and returns the views of the histograms
	 * of the group 1 by their names; a histogram the summary does not contain is missing from the result.
	 *
	 * @param session      the session to query
	 * @param narrowed     TRUE to keep only the anchor product
	 * @param order        the order of the brands in the reference summary, or `null` for none
	 * @param requirements the histogram requirements
	 * @return the views of the histograms by their names
	 */
	@Nonnull
	private static Map<String, HistogramView> queryHistograms(
		@Nonnull EvitaSessionContract session,
		boolean narrowed,
		@Nullable OrderBy order,
		@Nonnull ReferenceHistogramStatistics... requirements
	) {
		final FilterBy filter = narrowed ?
			filterBy(scope(Scope.LIVE), entityPrimaryKeyInSet(ANCHOR_PK)) : filterBy(scope(Scope.LIVE));
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filter,
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null, order, null, null, null,
						requirements
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics = summary == null ?
			null : summary.getReferenceGroupStatistics(REFERENCE_BRAND, GROUP_PK);
		final Map<String, HistogramView> result = createHashMap(HISTOGRAM_NAMES.length);
		for (final String name : HISTOGRAM_NAMES) {
			final HistogramContract histogram = statistics == null ? null : statistics.getHistogramStatistics(name);
			if (histogram != null) {
				result.put(name, HistogramView.of(histogram));
			}
		}
		return result;
	}

	/**
	 * Returns the view of the histogram `histogramName` in `histograms`.
	 *
	 * @param histograms    the views of the histograms by their names
	 * @param histogramName the name of the histogram
	 * @return the view, {@link HistogramView#ABSENT} when the histogram is missing
	 */
	@Nonnull
	private static HistogramView viewOf(@Nonnull Map<String, HistogramView> histograms, @Nonnull String histogramName) {
		final HistogramView view = histograms.get(histogramName);
		return view == null ? HistogramView.ABSENT : view;
	}

	/**
	 * Describes the histogram `histogramName` as the scenario expects it: anchored at the brand `minBrandPk` at its
	 * smallest value and at the brand 13 at its largest one, each fetched with `content`.
	 *
	 * @param histogramName the name of the histogram
	 * @param content       the content the histogram asks for
	 * @param minBrandPk    the brand anchoring the smallest value under the order of the query
	 * @return the expected compact description, see {@link HistogramView#compact()}
	 */
	@Nonnull
	private static String expectedHistogram(
		@Nonnull String histogramName,
		@Nonnull BoundaryContent content,
		int minBrandPk
	) {
		final boolean share = HISTOGRAM_SHARE.equals(histogramName);
		return "min=" + (share ? ANCHOR_VALUE : TIED_WEIGHT) + "@" + content.describe(minBrandPk) +
			" max=" + (share ? SECOND_ANCHOR_VALUE : LARGEST_WEIGHT) + "@" + content.describe(SECOND_ANCHOR_BRAND_PK);
	}

	/**
	 * Describes a boundary entity by its primary key and the attributes it was fetched with.
	 *
	 * @param boundary the boundary entity, or `null` when the histogram has none
	 * @return `<pk>[no attributes]` when no attributes were fetched, `<pk>[<name>=<value>, ...]` otherwise, or `none`
	 */
	@Nonnull
	private static String describeContent(@Nullable SealedEntity boundary) {
		if (boundary == null) {
			return "none";
		}
		if (!boundary.attributesAvailable()) {
			return boundary.getPrimaryKey() + "[no attributes]";
		}
		final StringBuilder result = new StringBuilder(32).append(boundary.getPrimaryKey()).append('[');
		boolean first = true;
		for (final AttributeValue attribute : boundary.getAttributeValues()) {
			if (!first) {
				result.append(", ");
			}
			first = false;
			result.append(attribute.key().attributeName()).append('=').append(attribute.value());
		}
		return result.append(']').toString();
	}

	/**
	 * Describes a boundary entity by everything it was fetched with, and whether its attributes were fetched at all.
	 *
	 * @param boundary the boundary entity, or `null` when the histogram has none
	 * @return the description, or `none`
	 */
	@Nonnull
	private static String describeFully(@Nullable SealedEntity boundary) {
		if (boundary == null) {
			return "none";
		}
		return boundary.describe() + (boundary.attributesAvailable() ? " (attributes fetched)" : " (no attributes)");
	}

	/**
	 * Returns the weight of the brand `brandPk`.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the weight
	 */
	private static int weightOf(int brandPk) {
		return switch (brandPk) {
			case BRAND_PK, OTHER_SHARE_BRAND_PK -> 0;
			case FIRST_ANCHOR_BRAND_PK, TIED_BRAND_PK -> TIED_WEIGHT;
			case SECOND_ANCHOR_BRAND_PK -> LARGEST_WEIGHT;
			default -> throw new IllegalArgumentException("Unknown brand " + brandPk + ".");
		};
	}

	/**
	 * Returns the code of the brand `brandPk`.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the code
	 */
	@Nonnull
	private static String codeOf(int brandPk) {
		return "brand-" + brandPk;
	}

	/**
	 * Defines the schemas with both histograms on the brand reference, and stores the group and the brands with their
	 * weights and codes. The missing-candidate scenario leaves brand 11 unstored.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param scenario the scenario
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, @Nonnull Scenario scenario) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterable().nullable())
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		defineProduct(
			session,
			whichIs -> whichIs
				.bucketedInScope(
					Scope.LIVE, HISTOGRAM_SHARE,
					ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_SHARE + "']")
				)
				.bucketedInScope(
					Scope.LIVE, HISTOGRAM_WEIGHT,
					ExpressionFactory.parse("$reference.referencedEntity?.attributes['" + ATTRIBUTE_WEIGHT + "']")
				)
		);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK));
		for (final int brandPk : new int[]{
			BRAND_PK, OTHER_SHARE_BRAND_PK, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_BRAND_PK, TIED_BRAND_PK
		}) {
			if (scenario.missingCandidate() && brandPk == OTHER_SHARE_BRAND_PK) {
				continue;
			}
			final EntityBuilder brand = session.createNewEntity(ENTITY_BRAND, brandPk);
			brand.setAttribute(ATTRIBUTE_WEIGHT, weightOf(brandPk));
			brand.setAttribute(ATTRIBUTE_CODE, codeOf(brandPk));
			session.upsertEntity(brand);
		}
	}

	/**
	 * Stores the anchor product with its references to the brands 12, 13 and 14, and in the narrowed scenarios the
	 * product with its references to the brands 10 and 11 - all in the group 1 at the first rank. In the
	 * missing-candidate scenario, the anchor product references brand 11 with share 10 and the other product
	 * references brand 10 alone.
	 *
	 * @param session  the session to write in
	 * @param scenario the scenario
	 */
	private static void storeOwners(@Nonnull EvitaSessionContract session, @Nonnull Scenario scenario) {
		final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
		addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, FIRST_RANK, ANCHOR_VALUE);
		addReference(anchor, SECOND_ANCHOR_BRAND_PK, GROUP_PK, FIRST_RANK, SECOND_ANCHOR_VALUE);
		addReference(anchor, TIED_BRAND_PK, GROUP_PK, FIRST_RANK, ANCHOR_VALUE);
		if (scenario.missingCandidate()) {
			addReference(anchor, OTHER_SHARE_BRAND_PK, GROUP_PK, FIRST_RANK, ANCHOR_VALUE);
		}
		anchor.upsertVia(session);
		if (scenario.narrowed()) {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
			addReference(product, BRAND_PK, GROUP_PK, FIRST_RANK, ANCHOR_VALUE);
			if (!scenario.missingCandidate()) {
				addReference(product, OTHER_SHARE_BRAND_PK, GROUP_PK, FIRST_RANK, SECOND_ANCHOR_VALUE);
			}
			product.upsertVia(session);
		}
	}

}
