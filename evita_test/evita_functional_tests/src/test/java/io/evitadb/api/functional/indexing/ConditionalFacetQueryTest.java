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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end query-side verification tests for the conditional facet indexing feature.
 * Verifies that facet summary counts, facet-based filtering, and reference-based filtering
 * behave correctly when `facetedPartially` expressions conditionally exclude facets.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conditional facet query operations")
class ConditionalFacetQueryTest implements EvitaTestSupport {
	private static final String DIR_CONDITIONAL_FACET_QUERY_TEST = "conditionalFacetQueryTest";
	private static final String DIR_CONDITIONAL_FACET_QUERY_TEST_EXPORT = "conditionalFacetQueryTest_export";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_GROUP = "parameterGroup";

	private static final String REF_PARAM_BY_GROUP_ATTR = "paramByGroupAttr";
	private static final String ATTR_WIDGET_TYPE = "widgetType";

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_CONDITIONAL_FACET_QUERY_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_CONDITIONAL_FACET_QUERY_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_CONDITIONAL_FACET_QUERY_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_CONDITIONAL_FACET_QUERY_TEST_EXPORT);
	}

	/**
	 * Builds the standard Evita configuration pointing to the test directories.
	 *
	 * @return the Evita configuration for the test
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(
						getTestDirectory().resolve(DIR_CONDITIONAL_FACET_QUERY_TEST)
					)
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(
						getTestDirectory().resolve(DIR_CONDITIONAL_FACET_QUERY_TEST_EXPORT)
					)
					.build()
			)
			.build();
	}

	/**
	 * Executes a test in the specified catalog state. The fixture setup always runs in
	 * WARMING_UP (bulk mode), then the catalog optionally transitions to ALIVE before
	 * the test logic executes.
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
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			fixtureSetup.accept(session);
			if (targetState == CatalogState.ALIVE) {
				session.goLiveAndClose();
			}
		});
		testLogic.run();
	}

	/**
	 * Defines the query test schema with a single `paramByGroupAttr` reference type.
	 *
	 * @param session the active evitaDB session
	 */
	private static void defineQueryTestSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER_GROUP)
			.withAttribute(ATTR_WIDGET_TYPE, String.class, AttributeSchemaEditor::filterable)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'"
						)
					)
			)
			.updateVia(session);
	}

	/**
	 * Creates the base fixture used by most query tests: three products referencing
	 * two parameters via a matching group entity.
	 *
	 * @param session the active evitaDB session
	 */
	private static void createBaseFixture(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
			.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
			.upsertVia(session);

		session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER, 2).upsertVia(session);

		// Products 1, 2 reference Parameter 1 via matching group
		session.createNewEntity(ENTITY_PRODUCT, 1)
			.setReference(
				REF_PARAM_BY_GROUP_ATTR, 1,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
			)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 2)
			.setReference(
				REF_PARAM_BY_GROUP_ATTR, 1,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
			)
			.upsertVia(session);

		// Product 3 references Parameter 2 via matching group
		session.createNewEntity(ENTITY_PRODUCT, 3)
			.setReference(
				REF_PARAM_BY_GROUP_ATTR, 2,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
			)
			.upsertVia(session);
	}

	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Should return correct facet summary excluding conditionally excluded facets")
	void shouldReturnCorrectFacetSummaryExcludingConditionallyExcludedFacets(CatalogState state) {
		withCatalogInState(
			state,
			session -> {
				defineQueryTestSchema(session);
				createBaseFixture(session);

				// add non-matching group and a product using it
				session.createNewEntity(ENTITY_PARAMETER_GROUP, 2)
					.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
					.upsertVia(session);

				session.createNewEntity(ENTITY_PRODUCT, 4)
					.setReference(
						REF_PARAM_BY_GROUP_ATTR, 1,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 2)
					)
					.upsertVia(session);
			},
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(facetSummaryOfReference(REF_PARAM_BY_GROUP_ATTR))
						),
						EntityReferenceContract.class
					);

					final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
					assertNotNull(facetSummary, "Facet summary must be present");

					// facet group statistics for group PK=1 (CHECKBOX — matching)
					final FacetGroupStatistics groupStats =
						facetSummary.getFacetGroupStatistics(REF_PARAM_BY_GROUP_ATTR, 1);
					assertNotNull(groupStats, "Group statistics for PG PK=1 must exist");

					// Parameter PK=1: Products 1, 2 (NOT Product 4 — in non-matching group)
					final FacetStatistics param1Stats = groupStats.getFacetStatistics(1);
					assertNotNull(param1Stats, "Facet statistics for Parameter PK=1 must exist");
					assertEquals(
						2, param1Stats.getCount(),
						"Parameter 1 should have count=2 (Products 1, 2)"
					);

					// Parameter PK=2: Product 3
					final FacetStatistics param2Stats = groupStats.getFacetStatistics(2);
					assertNotNull(param2Stats, "Facet statistics for Parameter PK=2 must exist");
					assertEquals(
						1, param2Stats.getCount(),
						"Parameter 2 should have count=1 (Product 3)"
					);
				}
			)
		);
	}

	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Should filter by facet correctly when some facets conditionally excluded")
	void shouldFilterByFacetCorrectlyWhenSomeFacetsConditionallyExcluded(CatalogState state) {
		withCatalogInState(
			state,
			session -> {
				defineQueryTestSchema(session);
				createBaseFixture(session);

				// add non-matching group and a product using it
				session.createNewEntity(ENTITY_PARAMETER_GROUP, 2)
					.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
					.upsertVia(session);

				session.createNewEntity(ENTITY_PRODUCT, 4)
					.setReference(
						REF_PARAM_BY_GROUP_ATTR, 1,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 2)
					)
					.upsertVia(session);
			},
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									facetHaving(REF_PARAM_BY_GROUP_ATTR, entityPrimaryKeyInSet(1))
								)
							)
						),
						EntityReferenceContract.class
					);

					final List<EntityReferenceContract> entities = result.getRecordData();
					// Product 1 and 2 are faceted for Parameter 1 in matching group
					assertTrue(
						entities.stream().anyMatch(e -> e.getPrimaryKey() == 1),
						"Product PK=1 should be in result"
					);
					assertTrue(
						entities.stream().anyMatch(e -> e.getPrimaryKey() == 2),
						"Product PK=2 should be in result"
					);
					// Product 4 has Parameter 1 but in non-matching group → not faceted
					assertTrue(
						entities.stream().noneMatch(e -> e.getPrimaryKey() == 4),
						"Product PK=4 should NOT be in result (not faceted)"
					);
				}
			)
		);
	}

	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Should return updated facet summary after cross-entity trigger changes index state")
	void shouldReturnUpdatedFacetSummaryAfterCrossEntityTriggerChangesIndexState(CatalogState state) {
		withCatalogInState(
			state,
			session -> {
				defineQueryTestSchema(session);
				createBaseFixture(session);
			},
			() -> {
				// verify initial state: facets are present
				this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final EvitaResponse<EntityReferenceContract> result = session.query(
							query(
								collection(ENTITY_PRODUCT),
								require(facetSummaryOfReference(REF_PARAM_BY_GROUP_ATTR))
							),
							EntityReferenceContract.class
						);

						final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
						assertNotNull(facetSummary, "Facet summary must be present initially");

						final Collection<FacetGroupStatistics> stats =
							facetSummary.getReferenceStatistics();
						assertFalse(
							stats.isEmpty(),
							"Initial facet summary should have reference statistics"
						);
					}
				);

				// cross-entity mutation: break the expression for all products
				this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
							.upsertVia(session);
					}
				);

				// verify facets are gone
				this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final EvitaResponse<EntityReferenceContract> result = session.query(
							query(
								collection(ENTITY_PRODUCT),
								require(facetSummaryOfReference(REF_PARAM_BY_GROUP_ATTR))
							),
							EntityReferenceContract.class
						);

						final FacetSummary facetSummary = result.getExtraResult(FacetSummary.class);
						if (facetSummary != null) {
							// either null or empty — no facets should remain
							final FacetGroupStatistics groupStats =
								facetSummary.getFacetGroupStatistics(REF_PARAM_BY_GROUP_ATTR, 1);
							if (groupStats != null) {
								assertEquals(
									0, groupStats.getFacetStatistics().size(),
									"No facet statistics should remain after expression became false"
								);
							}
						}
					}
				);
			}
		);
	}

	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Should still filter by reference when facet conditionally excluded")
	void shouldStillFilterByReferenceWhenFacetConditionallyExcluded(CatalogState state) {
		withCatalogInState(
			state,
			session -> {
				defineQueryTestSchema(session);
				createBaseFixture(session);

				// break expression → all facets removed
				session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
					.upsertVia(session);
			},
			() -> this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// query with referenceHaving (NOT facet-based)
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								referenceHaving(
									REF_PARAM_BY_GROUP_ATTR,
									entityPrimaryKeyInSet(1)
								)
							)
						),
						EntityReferenceContract.class
					);

					// all products with this reference should still be returned
					assertEquals(
						2, result.getRecordData().size(),
						"All products referencing Parameter 1 should be returned via "
							+ "referenceHaving (reference indexing is independent of facet indexing)"
					);
				}
			)
		);
	}

	@ParameterizedTest(name = "catalog state: {0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("Should return correct facet counts after fan-out group entity change")
	void shouldReturnCorrectFacetCountsAfterFanOutGroupEntityChange(CatalogState state) {
		withCatalogInState(
			state,
			session -> {
				defineQueryTestSchema(session);

				session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
					.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
					.upsertVia(session);
				session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

				// create 5 products all referencing Parameter 1 via matching group
				for (int i = 1; i <= 5; i++) {
					session.createNewEntity(ENTITY_PRODUCT, i)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				}
			},
			() -> {
				// verify initial count = 5
				this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final FacetSummary facetSummary = session.query(
							query(
								collection(ENTITY_PRODUCT),
								require(facetSummaryOfReference(REF_PARAM_BY_GROUP_ATTR))
							),
							EntityReferenceContract.class
						).getExtraResult(FacetSummary.class);

						assertNotNull(facetSummary, "Facet summary must be present");
						final FacetGroupStatistics groupStats =
							facetSummary.getFacetGroupStatistics(REF_PARAM_BY_GROUP_ATTR, 1);
						assertNotNull(groupStats, "Group statistics for PG PK=1 must exist");
						final FacetStatistics param1Stats = groupStats.getFacetStatistics(1);
						assertNotNull(param1Stats, "Facet statistics for Parameter 1 must exist");
						assertEquals(
							5, param1Stats.getCount(),
							"Parameter 1 should initially have count=5"
						);
					}
				);

				// fan-out: break expression for all products
				this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
							.upsertVia(session);
					}
				);

				// verify count = 0 or absent
				this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final FacetSummary facetSummary = session.query(
							query(
								collection(ENTITY_PRODUCT),
								require(facetSummaryOfReference(REF_PARAM_BY_GROUP_ATTR))
							),
							EntityReferenceContract.class
						).getExtraResult(FacetSummary.class);

						if (facetSummary != null) {
							final FacetGroupStatistics groupStats =
								facetSummary.getFacetGroupStatistics(REF_PARAM_BY_GROUP_ATTR, 1);
							if (groupStats != null) {
								final FacetStatistics param1Stats =
									groupStats.getFacetStatistics(1);
								if (param1Stats != null) {
									assertEquals(
										0, param1Stats.getCount(),
										"Parameter 1 should have count=0 after fan-out removal"
									);
								}
							}
						}
					}
				);

				// restore: set back to CHECKBOX
				this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
							.upsertVia(session);
					}
				);

				// verify count = 5 again
				this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final FacetSummary facetSummary = session.query(
							query(
								collection(ENTITY_PRODUCT),
								require(facetSummaryOfReference(REF_PARAM_BY_GROUP_ATTR))
							),
							EntityReferenceContract.class
						).getExtraResult(FacetSummary.class);

						assertNotNull(
							facetSummary,
							"Facet summary must be present after restoration"
						);
						final FacetGroupStatistics groupStats =
							facetSummary.getFacetGroupStatistics(REF_PARAM_BY_GROUP_ATTR, 1);
						assertNotNull(
							groupStats,
							"Group statistics for PG PK=1 must exist after restoration"
						);
						final FacetStatistics param1Stats = groupStats.getFacetStatistics(1);
						assertNotNull(
							param1Stats,
							"Facet statistics for Parameter 1 must exist after restoration"
						);
						assertEquals(
							5, param1Stats.getCount(),
							"Parameter 1 should have count=5 after restoration"
						);
					}
				);
			}
		);
	}
}
