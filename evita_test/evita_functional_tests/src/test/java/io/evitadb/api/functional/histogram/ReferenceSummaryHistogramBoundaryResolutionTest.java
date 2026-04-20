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

package io.evitadb.api.functional.histogram;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Phase G.1 behavior for `REFERENCE_ATTRIBUTE` histograms: min/max boundary referenced
 * entities are resolved from the reference's own attribute FilterIndex on RGEI (which is keyed on
 * the referenced entity PK via `executeWithDifferentPrimaryKeyToIndex` during insert) and mapped
 * back through
 * {@link io.evitadb.index.ReducedGroupEntityIndex#getReferencedPrimaryKeysForIndexPks}.
 *
 * The selection rule is: when an `orderBy` is configured on the enclosing
 * `referenceSummaryOfReferenceWithHistograms`, the resulting `facetSorter` slices the candidate
 * bitmap and the first PK wins. Without a sorter, the lowest-valued referenced PK is chosen.
 *
 * Also covers the two {@link io.evitadb.index.mutation.local.ReferenceIndexMutator} mutation
 * paths driving RGEI re-keying for REFERENCE_ATTRIBUTE histograms:
 * - `attributeUpdate` — changing an existing reference-attribute value must evict the old
 *   filterIndex entry and re-key on the referenced-PK domain;
 * - `removeAllAttributes` — removing a reference must drop its referenced PK from the
 *   filterIndex so boundary resolution no longer returns it.
 *
 * The scenarios that the shared LARGE fixture cannot reach (multi-group overlap, deliberate ties
 * and mutations) use the {@link OverlapFixture} helper, which provisions its own isolated Evita
 * instance per test.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — REFERENCE_ATTRIBUTE boundary resolution")
public class ReferenceSummaryHistogramBoundaryResolutionTest extends AbstractReferenceSummaryHistogramFunctionalTest {

	@Nested
	@DisplayName("REFERENCE_ATTRIBUTE boundary resolution")
	class ReferenceAttributeBoundaryResolution {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should respect facet sorter when multiple referenced PKs carry the boundary value")
		void shouldRespectFacetSorterForReferenceAttributeBoundaryTie(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					// Query with orderBy(entityPrimaryKeyNatural(DESC)) on the enclosing
					// referenceSummaryOfReferenceWithHistograms — when multiple candidates carry the
					// boundary marketShare, the facetSorter must break the tie picking the *highest*
					// PK, diverging from the default lowest-PK fallback. `ATTR_NAME` on parameterValue
					// isn't sortable, so we use the always-available PK-natural sorter to drive the
					// tie-breaker signal.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES,
									null, null, null,
									orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
									null,
									entityFetch(attributeContent(ATTR_NAME)),
									null,
									histogramStatistics(10, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					// Collect (pvPk, marketShare) tuples per group from the fixture and verify that
					// whichever PK the engine picked as `minReferencedEntity` actually carries the
					// min marketShare value among candidates in that group. When a tie exists, the
					// resolved PK must be the *highest* PK among tied candidates (sorter DESC). When
					// no tie exists the single candidate wins trivially.
					final Map<Integer, List<PvCandidate>> candidatesByGroup =
						collectReferencesWithMarketShare(session);

					boolean atLeastOneGroupWithTie = false;
					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						assertNotNull(group);
						final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
						assertNotNull(histogram);
						assertTrue(histogram.getMinReferencedEntity().isPresent(),
							"minReferencedEntity must be populated for group " + groupPk);

						final BigDecimal histogramMin = histogram.getMin();
						final List<PvCandidate> candidates = candidatesByGroup.get(groupPk);
						assertNotNull(candidates);
						// Compute ground-truth candidates for the min marketShare value
						final List<PvCandidate> minCandidates = new ArrayList<>();
						for (final PvCandidate c : candidates) {
							if (c.marketShare().compareTo(histogramMin) == 0) {
								minCandidates.add(c);
							}
						}
						assertTrue(!minCandidates.isEmpty(),
							"Fixture must contain at least one candidate with the histogram min value for group " + groupPk);

						final int resolvedMinPk = histogram.getMinReferencedEntity().get().getPrimaryKey();
						// The resolved PK must be among the min-value candidates
						boolean found = false;
						for (final PvCandidate c : minCandidates) {
							if (c.pvPk() == resolvedMinPk) {
								found = true;
								break;
							}
						}
						assertTrue(found,
							"Resolved minReferencedEntity PK " + resolvedMinPk
								+ " must be one of the candidates carrying the min marketShare "
								+ histogramMin + " in group " + groupPk + ", got: " + minCandidates);

						// When a genuine tie exists, the facetSorter (entityPrimaryKeyNatural DESC)
						// must have selected the *highest* PK among the tied candidates — this is
						// the signal that differentiates the sorter path from the lowest-PK fallback.
						if (minCandidates.size() > 1) {
							atLeastOneGroupWithTie = true;
							int expectedHighestPk = Integer.MIN_VALUE;
							for (final PvCandidate c : minCandidates) {
								if (c.pvPk() > expectedHighestPk) {
									expectedHighestPk = c.pvPk();
								}
							}
							assertEquals(expectedHighestPk, resolvedMinPk,
								"With orderBy(entityPrimaryKeyNatural(DESC)) and a tie on min marketShare "
									+ histogramMin + " in group " + groupPk + ", the facetSorter "
									+ "must pick the highest PK among tied candidates. "
									+ "Tied candidates: " + minCandidates);
						}
					}
					// Not strictly required — serves as a data-health signal so silent fixture
					// regressions (no ties ever produced) are noticed.
					if (!atLeastOneGroupWithTie) {
						// No group contained a tie on the min value — the test degrades to a
						// single-candidate check which is covered by other tests. Emit a warning via
						// Assumptions rather than failing, so the test still guards against regressions
						// that produce *incorrect* (non-candidate) PKs.
						Assumptions.abort(
							"No tie on min marketShare produced by the deterministic fixture — the "
								+ "facet-sorter branch wasn't exercised for any group. This test still "
								+ "guards the candidate-membership invariant above."
						);
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should fall back to lowest-PK when no facet sorter is configured")
		void shouldFallBackToLowestPkWhenNoFacetSorter(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					// All-references fan-out has no per-reference orderBy wiring so no facetSorter
					// is attached — boundary resolution must fall back to the lowest-PK rule.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryWithHistograms(
									null, null, null,
									histogramStatistics(10, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					final Map<Integer, List<PvCandidate>> candidatesByGroup =
						collectReferencesWithMarketShare(session);

					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						assertNotNull(group);
						final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
						assertNotNull(histogram);
						assertTrue(histogram.getMinReferencedEntity().isPresent(),
							"minReferencedEntity must be populated for group " + groupPk);
						assertTrue(histogram.getMaxReferencedEntity().isPresent(),
							"maxReferencedEntity must be populated for group " + groupPk);

						final BigDecimal histogramMin = histogram.getMin();
						final BigDecimal histogramMax = histogram.getMax();
						final List<PvCandidate> candidates = candidatesByGroup.get(groupPk);
						assertNotNull(candidates);

						// lowest PK among candidates carrying the min marketShare
						final int expectedMinPk = AbstractReferenceSummaryHistogramFunctionalTest
							.lowestPkWithValue(candidates, histogramMin);
						// lowest PK among candidates carrying the max marketShare
						final int expectedMaxPk = AbstractReferenceSummaryHistogramFunctionalTest
							.lowestPkWithValue(candidates, histogramMax);

						assertEquals(
							expectedMinPk,
							histogram.getMinReferencedEntity().get().getPrimaryKey(),
							"Without a facet sorter, minReferencedEntity must be the lowest-numbered "
								+ "candidate carrying the histogram min " + histogramMin
								+ " in group " + groupPk + ". Candidates: " + candidates
						);
						assertEquals(
							expectedMaxPk,
							histogram.getMaxReferencedEntity().get().getPrimaryKey(),
							"Without a facet sorter, maxReferencedEntity must be the lowest-numbered "
								+ "candidate carrying the histogram max " + histogramMax
								+ " in group " + groupPk + ". Candidates: " + candidates
						);
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should set boundary EntityReference type to the referenced entity type, not the group type")
		void shouldReturnReferencedEntityTypeNotGroupTypeForBoundary(@Nonnull Evita evita) {
			// Pins Phase G.1 contract: REFERENCE_ATTRIBUTE boundary resolution must emit the
			// *referenced* entity type (parameterValue) on the boundary EntityReference, NOT the group
			// type (parameter). Previous implementations copy-pasted from REFERENCED_ENTITY_ATTRIBUTE
			// mistakenly emitted the group type here — this test guards against that regression.
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryWithHistograms(
									null, null, null,
									histogramStatistics(10, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					// Assert for every seeded group that min/max boundary EntityReferences carry the
					// *referenced* entity type (parameterValue), never the group type (parameter).
					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						assertNotNull(group);
						final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
						assertNotNull(histogram);
						assertTrue(histogram.getMinReferencedEntity().isPresent(),
							"minReferencedEntity must be populated for group " + groupPk);
						assertTrue(histogram.getMaxReferencedEntity().isPresent(),
							"maxReferencedEntity must be populated for group " + groupPk);

						final String minType = histogram.getMinReferencedEntity().get().getType();
						final String maxType = histogram.getMaxReferencedEntity().get().getType();
						assertEquals(ENTITY_PARAMETER_VALUE, minType,
							"Boundary min EntityReference for group " + groupPk
								+ " must carry the referenced entity type "
								+ ENTITY_PARAMETER_VALUE + ", not the group type " + ENTITY_PARAMETER);
						assertEquals(ENTITY_PARAMETER_VALUE, maxType,
							"Boundary max EntityReference for group " + groupPk
								+ " must carry the referenced entity type "
								+ ENTITY_PARAMETER_VALUE + ", not the group type " + ENTITY_PARAMETER);
					}
				}
			);
		}

		/**
		 * Exercises the multi-group overlap scenario — a single parameter value appears in two
		 * distinct groups with different marketShare values per group. This is the false-positive
		 * scenario Option A is designed to handle correctly: boundary resolution for group A must
		 * return the referenced PK with marketShare matching group A's histogram min/max, not
		 * group B's.
		 *
		 * The LARGE fixture distributes pvPks into disjoint ranges per group (1..10, 11..20,
		 * 21..30) so this scenario can't be exercised there — we spin up a dedicated Evita
		 * instance in {@link OverlapFixture} with an explicit seed that places the same pvPk
		 * into two groups.
		 */
		@Test
		@DisplayName("should resolve min/max correctly for a pvPk that appears in multiple groups with different marketShare values")
		void shouldResolveMinMaxForRefAttributeAgainstMultiGroupOverlap() {
			OverlapFixture.runWithOverlapFixture((session, ctx) -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ctx.entityProduct()),
						require(
							page(1, Integer.MAX_VALUE),
							referenceSummaryOfReferenceWithHistograms(
								ctx.refName(),
								null, null, null,
								histogramStatistics(10, ctx.histogramName())
							)
						)
					),
					EntityReferenceContract.class
				);
				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);

				// Shared pvPk `SHARED_PV` is referenced in both group 1 and group 2 with disjoint
				// marketShare ranges (group 1: 10; group 2: 90). Each group's histogram must resolve
				// min/max against its own group's marketShare, not leak across groups.
				final ReferenceGroupStatistics g1 =
					summary.getReferenceGroupStatistics(ctx.refName(), 1);
				assertNotNull(g1);
				final HistogramContract h1 = g1.getHistogramStatistics(ctx.histogramName());
				assertNotNull(h1);
				assertTrue(h1.getMinReferencedEntity().isPresent());
				// group 1 seeded so that SHARED_PV carries the MIN value (10) — boundary resolution
				// must pick it as minReferencedEntity. If Option A leaked group 2's value for the
				// same pvPk, the cross-group value (90) would *also* match in the filterIndex and
				// could swap the boundary — this assertion proves isolation.
				assertEquals(
					OverlapFixture.SHARED_PV,
					h1.getMinReferencedEntity().get().getPrimaryKey(),
					"Group 1's min boundary must resolve to SHARED_PV carrying marketShare=10 "
						+ "in group 1, not the same pvPk's marketShare=90 in group 2"
				);

				final ReferenceGroupStatistics g2 =
					summary.getReferenceGroupStatistics(ctx.refName(), 2);
				assertNotNull(g2);
				final HistogramContract h2 = g2.getHistogramStatistics(ctx.histogramName());
				assertNotNull(h2);
				assertTrue(h2.getMaxReferencedEntity().isPresent());
				// Symmetric check — SHARED_PV is MAX in group 2 (marketShare=90). Boundary
				// resolution must pick it, not leak group 1's marketShare=10.
				assertEquals(
					OverlapFixture.SHARED_PV,
					h2.getMaxReferencedEntity().get().getPrimaryKey(),
					"Group 2's max boundary must resolve to SHARED_PV carrying marketShare=90 "
						+ "in group 2, not the same pvPk's marketShare=10 in group 1"
				);
			});
		}

		/**
		 * Deliberately forces at least one group to contain a tie on the min marketShare value in
		 * a dedicated fixture, so the facet-sorter tie-breaker path is actually exercised rather
		 * than only covered by the candidate-membership invariant against the LARGE fixture seed.
		 */
		@Test
		@DisplayName("should pick highest-PK via facet sorter when multiple referenced PKs tie on the boundary value")
		void shouldRespectFacetSorterForReferenceAttributeBoundaryTieDedicated() {
			OverlapFixture.runWithTieFixture((session, ctx) -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ctx.entityProduct()),
						require(
							page(1, Integer.MAX_VALUE),
							referenceSummaryOfReferenceWithHistograms(
								ctx.refName(),
								null, null, null,
								orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
								null,
								entityFetch(),
								null,
								histogramStatistics(10, ctx.histogramName())
							)
						)
					),
					EntityReferenceContract.class
				);
				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);

				// TieFixture seeds two carriers of the min marketShare in group 1:
				// pvPk=100 and pvPk=200, both at marketShare=10. With facetSorter=DESC, the
				// engine must pick pvPk=200 as minReferencedEntity (highest among tied carriers).
				final ReferenceGroupStatistics g1 =
					summary.getReferenceGroupStatistics(ctx.refName(), 1);
				assertNotNull(g1);
				final HistogramContract h1 = g1.getHistogramStatistics(ctx.histogramName());
				assertNotNull(h1);
				assertTrue(h1.getMinReferencedEntity().isPresent());
				assertEquals(
					OverlapFixture.TIE_HIGH_PV,
					h1.getMinReferencedEntity().get().getPrimaryKey(),
					"With orderBy(entityPrimaryKeyNatural(DESC)) and two carriers of the min "
						+ "marketShare=10 in group 1 (pvPks 100 and 200), the facet sorter must "
						+ "pick the highest PK (200), not fall back to the lowest (100)"
				);
			});
		}
	}

	// ==========================================================================================
	// mutation paths (attributeUpdate, removeAllAttributes) — OverlapFixture isolates each test
	// ==========================================================================================

	/**
	 * Covers the two {@link io.evitadb.index.mutation.local.ReferenceIndexMutator} mutation paths
	 * that drive RGEI re-keying for REFERENCE_ATTRIBUTE histograms but aren't touched by the
	 * parent read-only fixture:
	 * - `attributeUpdate` — changing an existing reference-attribute value must evict the old
	 *   filterIndex entry and re-key on the referenced-PK domain;
	 * - `removeAllAttributes` — removing a reference must drop its referenced PK from the
	 *   filterIndex so boundary resolution no longer returns it.
	 *
	 * Both use their own isolated Evita instance (see `OverlapFixture#runWithUpdateFixture` and
	 * `OverlapFixture#runWithRemovalFixture`) so no test ordering dependency is introduced.
	 */
	@Nested
	@DisplayName("REFERENCE_ATTRIBUTE mutation paths")
	class ReferenceAttributeMutationPaths {

		@Test
		@DisplayName("should keep REFERENCE_ATTRIBUTE boundary resolution correct after updating an existing reference-attribute value")
		void shouldMaintainReferenceAttributeBoundaryAfterAttributeUpdate() {
			OverlapFixture.runWithUpdateFixture((evita, ctx) -> {
				// Initial query: PV 100 carries marketShare 50.0, PV 200 carries 80.0 — so PV 100
				// is the initial minReferencedEntity.
				final BigDecimal initialMin = new BigDecimal("50.00");
				final int pvPk100 = 100;
				final int pvPk200 = 200;
				assertInitialMin(evita, ctx, pvPk100, initialMin);

				// Mutate: drop PV 100's marketShare to 5.0 in a second session. The new min must be
				// 5.0 and PV 100 must still be the minReferencedEntity since it carries the new
				// boundary value — this is the invariant that the `attributeUpdate` RGEI re-keying
				// must preserve.
				evita.updateCatalog(TEST_CATALOG, writeSession -> {
					final SealedEntity product = writeSession
						.getEntity(ctx.entityProduct(), 1, entityFetchAllContent())
						.orElseThrow();
					product.openForWrite()
						.setReference(ctx.refName(), pvPk100, whichIs ->
							whichIs.setGroup(ENTITY_PARAMETER, 1)
								.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("5.00"))
						)
						.upsertVia(writeSession);
				});

				// Re-query and assert new min is 5.0 with PV 100 still resolved as the boundary.
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> {
						final HistogramContract histogram = queryGroupHistogram(readSession, ctx, 1);
						assertEquals(0, new BigDecimal("5.00").compareTo(histogram.getMin()),
							"After updating PV 100's marketShare to 5.0, the histogram min must "
								+ "reflect the new value, but was: " + histogram.getMin());
						assertTrue(histogram.getMinReferencedEntity().isPresent(),
							"minReferencedEntity must be populated after the reference attribute update");
						assertEquals(pvPk100,
							histogram.getMinReferencedEntity().get().getPrimaryKey(),
							"PV 100 must remain the minReferencedEntity — it now carries the new min "
								+ "marketShare (5.0). If RGEI re-keying failed on attributeUpdate, the "
								+ "FilterIndex would still be keyed on the old (owner) PK and boundary "
								+ "resolution would return a different pvPk or null.");
						// Sanity check — PV 200 at 80.0 must remain the max
						assertEquals(0, new BigDecimal("80.00").compareTo(histogram.getMax()),
							"Max must still be PV 200's unchanged marketShare 80.0");
						assertTrue(histogram.getMaxReferencedEntity().isPresent());
						assertEquals(pvPk200,
							histogram.getMaxReferencedEntity().get().getPrimaryKey(),
							"PV 200 must remain the maxReferencedEntity after the update");
					}
				);
			});
		}

		@Test
		@DisplayName("should evict referenced PK from REFERENCE_ATTRIBUTE boundary after the reference is removed")
		void shouldDropReferencedEntityFromBoundaryAfterReferenceRemoval() {
			OverlapFixture.runWithRemovalFixture((evita, ctx) -> {
				// Initial query: PV 100 carries marketShare 30.0, PV 200 carries 80.0 — so PV 100
				// is the initial minReferencedEntity.
				final BigDecimal initialMin = new BigDecimal("30.00");
				final int pvPk100 = 100;
				final int pvPk200 = 200;
				assertInitialMin(evita, ctx, pvPk100, initialMin);

				// Mutate: remove the reference to PV 100 from product 1 in a second session.
				evita.updateCatalog(TEST_CATALOG, writeSession -> {
					final SealedEntity product = writeSession
						.getEntity(ctx.entityProduct(), 1, entityFetchAllContent())
						.orElseThrow();
					product.openForWrite()
						.removeReference(ctx.refName(), pvPk100)
						.upsertVia(writeSession);
				});

				// Re-query and assert PV 100 is no longer the minReferencedEntity. PV 200 (80.0) is
				// now the sole remaining reference so it must be both the min and max boundary.
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) readSession -> {
						final HistogramContract histogram = queryGroupHistogram(readSession, ctx, 1);
						assertTrue(histogram.getMinReferencedEntity().isPresent(),
							"minReferencedEntity must still be populated after one reference removal");
						assertEquals(pvPk200,
							histogram.getMinReferencedEntity().get().getPrimaryKey(),
							"PV 200 must be the minReferencedEntity after PV 100's reference is "
								+ "removed — PV 100 should have been evicted from the FilterIndex by "
								+ "the `removeAllAttributes` RGEI re-keying path. If the re-key failed, "
								+ "PV 100 would still appear in the boundary lookup.");
						assertEquals(0, new BigDecimal("80.00").compareTo(histogram.getMin()),
							"After removing PV 100's reference, the only remaining marketShare is "
								+ "PV 200's 80.0 — that must be both min and max");
					}
				);
			});
		}

		/**
		 * Runs the baseline assertion for mutation tests: query the histogram in the
		 * freshly-seeded state and confirm both the min value and the PK resolved as
		 * `minReferencedEntity`. Shared between update and removal tests so the
		 * mutation-specific assertions can focus on the post-mutation state.
		 */
		private void assertInitialMin(
			@Nonnull Evita evita,
			@Nonnull OverlapFixture.FixtureCtx ctx,
			int expectedMinPk,
			@Nonnull BigDecimal expectedMinValue
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) readSession -> {
					final HistogramContract histogram = queryGroupHistogram(readSession, ctx, 1);
					assertEquals(0, expectedMinValue.compareTo(histogram.getMin()),
						"Initial histogram min must be " + expectedMinValue + " but was: " + histogram.getMin());
					assertTrue(histogram.getMinReferencedEntity().isPresent(),
						"Initial minReferencedEntity must be populated");
					assertEquals(expectedMinPk,
						histogram.getMinReferencedEntity().get().getPrimaryKey(),
						"Initial minReferencedEntity must be PV " + expectedMinPk);
				}
			);
		}

		/**
		 * Queries the single-group REFERENCE_ATTRIBUTE histogram and returns the contract for the
		 * given group. Centralizes the query shape used by both mutation tests so differences
		 * between pre/post-mutation queries are purely in the assertions.
		 */
		@Nonnull
		private HistogramContract queryGroupHistogram(
			@Nonnull EvitaSessionContract session,
			@Nonnull OverlapFixture.FixtureCtx ctx,
			int groupPk
		) {
			final EvitaResponse<EntityReferenceContract> result = session.query(
				query(
					collection(ctx.entityProduct()),
					require(
						page(1, Integer.MAX_VALUE),
						referenceSummaryOfReferenceWithHistograms(
							ctx.refName(),
							null, null, null,
							histogramStatistics(10, ctx.histogramName())
						)
					)
				),
				EntityReferenceContract.class
			);
			final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group =
				summary.getReferenceGroupStatistics(ctx.refName(), groupPk);
			assertNotNull(group, "ReferenceGroupStatistics must be present for group " + groupPk);
			final HistogramContract histogram = group.getHistogramStatistics(ctx.histogramName());
			assertNotNull(histogram, "Histogram must be present for group " + groupPk);
			return histogram;
		}
	}
}
