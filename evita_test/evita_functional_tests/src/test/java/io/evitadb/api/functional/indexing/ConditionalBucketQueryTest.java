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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReference;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;

/**
 * End-to-end query-side verification tests for the conditional bucketed histogram indexing feature.
 * Verifies that facet-based queries respect the mutual exclusivity between `facetedPartially`
 * and `bucketedPartially` conditions, and that reference-based filtering remains independent
 * of histogram indexing state.
 *
 * Mirrors the structure of {@link ConditionalFacetQueryTest} but exercises the histogram
 * side of the dual facet/histogram model.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conditional bucket query operations")
@Tag(CONTRACT)
@Tag(INDEXING)
class ConditionalBucketQueryTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	private static final String ENTITY_PARAMETER = "parameter";

	private static final String REF_DUAL = "paramDualFacetHistogram";

	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";

	private static final String HISTOGRAM_DUAL = "dualHistogram";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ConditionalBucketQueryTest");
		ConditionalBucketQueryTest.this.evita = new Evita(
			getEvitaConfiguration()
		);
		ConditionalBucketQueryTest.this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		ConditionalBucketQueryTest.this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Builds the standard Evita configuration pointing to the test directories.
	 *
	 * @return the Evita configuration for the test
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	/**
	 * Defines the schema with a dual facet/histogram reference for query tests.
	 *
	 * @param session the active evitaDB session
	 */
	private static void defineQueryTestSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REF_DUAL, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'CHECKBOX'"
						)
					)
					.bucketed(
						HISTOGRAM_DUAL,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
						)
					)
			)
			.updateVia(session);
	}

	/**
	 * Executes a test in the specified catalog state.
	 *
	 * @param targetState  the catalog state in which the test logic should execute
	 * @param fixtureSetup schema definition and entity creation (always runs in WARMING_UP)
	 * @param testLogic    assertions and mutations that exercise the scenario under test
	 */
	private void withCatalogInState(
		@Nonnull CatalogState targetState,
		@Nonnull Consumer<EvitaSessionContract> fixtureSetup,
		@Nonnull Runnable testLogic
	) {
		ConditionalBucketQueryTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
			fixtureSetup.accept(session);
			if (targetState == CatalogState.ALIVE) {
				session.goLiveAndClose();
			}
		});
		testLogic.run();
	}

	/**
	 * Tests verifying facet summary correctness in the presence of dual facet/histogram indexing.
	 */
	@Nested
	@DisplayName("Facet summary in dual facet/histogram setup")
	class DualFacetHistogramFacetSummaryTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should return facet summary only for CHECKBOX groups, not INTERVAL groups")
		void shouldReturnFacetSummaryOnlyForCheckboxGroups(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineQueryTestSchema(session);

					// CHECKBOX group → faceted
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					// INTERVAL group → bucketed (not faceted)
					session.createNewEntity(ENTITY_PARAMETER, 2)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					// Products 1, 2 in CHECKBOX group → should appear in facet summary
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);

					// Product 3 in INTERVAL group → should NOT appear in facet summary
					session.createNewEntity(ENTITY_PRODUCT, 3)
						.setReference(
							REF_DUAL, 2,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 2)
						)
						.upsertVia(session);
				},
				() -> ConditionalBucketQueryTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final EvitaResponse<EntityReferenceContract> result = session.query(
							query(
								collection(ENTITY_PRODUCT),
								require(referenceSummaryOfReference(REF_DUAL))
							),
							EntityReferenceContract.class
						);

						final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
						assertNotNull(referenceSummary, "Facet summary must be present");

						// CHECKBOX group (PK=1) should have facet statistics
						final ReferenceGroupStatistics checkboxGroupStats =
							referenceSummary.getReferenceGroupStatistics(REF_DUAL, 1);
						assertNotNull(
							checkboxGroupStats,
							"Facet group stats for CHECKBOX group (PK=1) must exist"
						);

						// PV#1 in CHECKBOX group: Products 1, 2
						final FacetStatistics pv1Stats = checkboxGroupStats.getFacetStatistics(1);
						assertNotNull(pv1Stats, "Facet stats for PV PK=1 must exist");
						assertEquals(
							2, pv1Stats.getCount(),
							"PV#1 in CHECKBOX group should have count=2 (Products 1, 2)"
						);

						// INTERVAL group (PK=2) should NOT have facet statistics
						// (PV#2 is only in the INTERVAL group, which is bucketed not faceted)
						final ReferenceGroupStatistics intervalGroupStats =
							referenceSummary.getReferenceGroupStatistics(REF_DUAL, 2);
						if (intervalGroupStats != null) {
							assertEquals(
								0, intervalGroupStats.getFacetStatistics().size(),
								"INTERVAL group should have no facet statistics"
							);
						}
					}
				)
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update facet summary when group switches from CHECKBOX to INTERVAL")
		void shouldUpdateFacetSummaryWhenGroupSwitchesFromCheckboxToInterval(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineQueryTestSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
				},
				() -> {
					// verify initial facet summary: CHECKBOX → faceted
					ConditionalBucketQueryTest.this.evita.queryCatalog(
						TEST_CATALOG,
						session -> {
							final ReferenceSummary referenceSummary = session.query(
								query(
									collection(ENTITY_PRODUCT),
									require(referenceSummaryOfReference(REF_DUAL))
								),
								EntityReferenceContract.class
							).getExtraResult(ReferenceSummary.class);

							assertNotNull(referenceSummary, "Facet summary must be present initially");
							final ReferenceGroupStatistics groupStats =
								referenceSummary.getReferenceGroupStatistics(REF_DUAL, 1);
							assertNotNull(groupStats, "Group stats must exist initially");
							final FacetStatistics pv1Stats = groupStats.getFacetStatistics(1);
							assertNotNull(pv1Stats, "PV#1 facet stats must exist initially");
							assertEquals(
								2, pv1Stats.getCount(),
								"PV#1 should have count=2 initially"
							);
						}
					);

					// switch group to INTERVAL → facets removed, histograms added
					ConditionalBucketQueryTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
								.upsertVia(session);
						}
					);

					// verify facet summary is now empty for this group
					ConditionalBucketQueryTest.this.evita.queryCatalog(
						TEST_CATALOG,
						session -> {
							final ReferenceSummary referenceSummary = session.query(
								query(
									collection(ENTITY_PRODUCT),
									require(referenceSummaryOfReference(REF_DUAL))
								),
								EntityReferenceContract.class
							).getExtraResult(ReferenceSummary.class);

							if (referenceSummary != null) {
								final ReferenceGroupStatistics groupStats =
									referenceSummary.getReferenceGroupStatistics(REF_DUAL, 1);
								if (groupStats != null) {
									assertEquals(
										0, groupStats.getFacetStatistics().size(),
										"No facet stats should remain after switching to INTERVAL"
									);
								}
							}
						}
					);

					// restore to CHECKBOX → facets restored
					ConditionalBucketQueryTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
								.upsertVia(session);
						}
					);

					// verify facet summary restored
					ConditionalBucketQueryTest.this.evita.queryCatalog(
						TEST_CATALOG,
						session -> {
							final ReferenceSummary referenceSummary = session.query(
								query(
									collection(ENTITY_PRODUCT),
									require(referenceSummaryOfReference(REF_DUAL))
								),
								EntityReferenceContract.class
							).getExtraResult(ReferenceSummary.class);

							assertNotNull(referenceSummary, "Facet summary must be present after restore");
							final ReferenceGroupStatistics groupStats =
								referenceSummary.getReferenceGroupStatistics(REF_DUAL, 1);
							assertNotNull(groupStats, "Group stats must exist after restore");
							final FacetStatistics pv1Stats = groupStats.getFacetStatistics(1);
							assertNotNull(pv1Stats, "PV#1 facet stats must exist after restore");
							assertEquals(
								2, pv1Stats.getCount(),
								"PV#1 should have count=2 after restore"
							);
						}
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that reference-based filtering remains independent of histogram indexing.
	 */
	@Nested
	@DisplayName("Reference filtering independence")
	class ReferenceFilteringIndependenceTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should still filter by reference when histogram conditionally excluded")
		void shouldStillFilterByReferenceWhenHistogramConditionallyExcluded(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineQueryTestSchema(session);

					// INTERVAL group → bucketed only, not faceted
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// two products referencing PV#1 via INTERVAL group
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
				},
				() -> ConditionalBucketQueryTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						// query with referenceHaving (NOT facet-based)
						final EvitaResponse<EntityReferenceContract> result = session.query(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(
									referenceHaving(
										REF_DUAL,
										entityPrimaryKeyInSet(1)
									)
								)
							),
							EntityReferenceContract.class
						);

						// both products should be returned — reference indexing is independent
						final List<EntityReferenceContract> entities = result.getRecordData();
						assertEquals(
							2, entities.size(),
							"All products referencing PV#1 should be returned via referenceHaving "
								+ "(reference indexing is independent of histogram/facet indexing)"
						);
						assertTrue(
							entities.stream().anyMatch(e -> e.getPrimaryKey() == 1),
							"Product PK=1 should be in result"
						);
						assertTrue(
							entities.stream().anyMatch(e -> e.getPrimaryKey() == 2),
							"Product PK=2 should be in result"
						);
					}
				)
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should return all products via referenceHaving after group switches from faceted to bucketed")
		void shouldReturnAllProductsViaReferenceHavingAfterGroupSwitches(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineQueryTestSchema(session);

					// start as CHECKBOX → faceted
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_DUAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
						)
						.upsertVia(session);
				},
				() -> {
					// switch to INTERVAL → facets removed, histograms added
					ConditionalBucketQueryTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
								.upsertVia(session);
						}
					);

					// referenceHaving should still return both products
					ConditionalBucketQueryTest.this.evita.queryCatalog(
						TEST_CATALOG,
						session -> {
							final EvitaResponse<EntityReferenceContract> result = session.query(
								query(
									collection(ENTITY_PRODUCT),
									filterBy(
										referenceHaving(
											REF_DUAL,
											entityPrimaryKeyInSet(1)
										)
									)
								),
								EntityReferenceContract.class
							);

							assertEquals(
								2, result.getRecordData().size(),
								"Both products should be returned via referenceHaving even after "
									+ "group switched from faceted to bucketed"
							);
						}
					);
				}
			);
		}
	}
}
