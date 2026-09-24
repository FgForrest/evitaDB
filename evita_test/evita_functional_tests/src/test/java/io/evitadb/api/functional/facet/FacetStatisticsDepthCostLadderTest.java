/*
 *
 *                         _     _        ____  ____
 *               _____   _(_)___| |_ __ _|  _ \| __ )
 *              / _ \ \ / / / __| __/ _` | | | |  _ \
 *             |  __/\ V /| | |_ | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__|\__\__,_|____/|____/
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

package io.evitadb.api.functional.facet;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.referenceSummary;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cost ladder {@link FacetStatisticsDepth} documents for its three values: `NONE` computes the least,
 * `IMPACT` the most, and `COUNTS` sits between them. The engine has a single place where the depth switches work
 * off — the impact calculator the group accumulator is handed — and the ladder is only honoured if that switch
 * names the depth that *wants* the work rather than one of the depths that does not.
 *
 * The observable consequence of the switch is the nullity of {@link FacetStatistics#getImpact()}: the null
 * object handed in for the cheaper depths returns `null` for every facet, while the real calculator returns a
 * populated impact. Asserting nullity therefore tests the routing decision itself and not an incidental
 * property of the returned numbers.
 *
 * The `IMPACT` case is a deliberate positive control rather than extra coverage. Without it a fixture that
 * produced no facets at all, or a summary whose facet statistics were never inspected, would satisfy both
 * null-assertions while proving nothing — the assertions would hold because nothing was examined rather than
 * because the routing is right.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Facet statistics depth honours the cost ladder it documents")
@Tag(CONTRACT)
@Tag(FACET)
@Tag(REFERENCE)
class FacetStatisticsDepthCostLadderTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";

	private static final String REF_BRANDS = "brands";

	private static final int[] BRAND_PKS = {1, 2, 3};
	private static final int PRODUCT_COUNT = 9;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FacetStatisticsDepthCostLadderTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session);
			seedData(session);
		});
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * A product referencing a single faceted brand — the minimum shape that produces facet statistics.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REF_BRANDS, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.facetedInScope(Scope.values())
			)
			.updateVia(session);
	}

	/**
	 * Spreads nine products across three brands so every facet is held by more than one product — a facet
	 * matching a single product could make an impact computation degenerate.
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		for (final int brandPk : BRAND_PKS) {
			session.createNewEntity(ENTITY_BRAND, brandPk).upsertVia(session);
		}
		for (int i = 0; i < PRODUCT_COUNT; i++) {
			session.createNewEntity(ENTITY_PRODUCT, 100 + i)
				.setReference(REF_BRANDS, BRAND_PKS[i % BRAND_PKS.length])
				.upsertVia(session);
		}
	}

	/**
	 * Runs a product query asking for the reference summary at the given depth and returns every facet
	 * statistic it reports, flattened across groups.
	 *
	 * @param depth the facet statistics depth to request
	 * @return all facet statistics carried by the resulting summary
	 */
	@Nonnull
	private List<FacetStatistics> facetStatisticsAtDepth(@Nonnull FacetStatisticsDepth depth) {
		final List<FacetStatistics> collected = new ArrayList<>();
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(referenceSummary(depth))
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary, "reference summary must be present at depth " + depth);

				for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
					if (REF_BRANDS.equals(group.getReferenceName())) {
						collected.addAll(group.getFacetStatistics());
					}
				}
			}
		);
		assertFalse(
			collected.isEmpty(),
			"fixture produced no facet statistics at depth " + depth + ", so nothing was actually asserted"
		);
		return collected;
	}

	@Nested
	@DisplayName("Impact routing across the three depths")
	class ImpactRouting {

		@Test
		@DisplayName("should not compute impact at depth NONE, the depth documented as the cheapest")
		void shouldNotComputeImpactAtDepthNone() {
			for (final FacetStatistics facet : facetStatisticsAtDepth(FacetStatisticsDepth.NONE)) {
				assertNull(
					facet.getImpact(),
					"facet " + facet.getFacetEntity().getPrimaryKey() + " carries an impact at depth NONE, which "
						+ "documents that neither counts nor impact are computed and is the cheapest depth"
				);
			}
		}

		@Test
		@DisplayName("should not compute impact at depth COUNTS")
		void shouldNotComputeImpactAtDepthCounts() {
			for (final FacetStatistics facet : facetStatisticsAtDepth(FacetStatisticsDepth.COUNTS)) {
				assertNull(
					facet.getImpact(),
					"facet " + facet.getFacetEntity().getPrimaryKey() + " carries an impact at depth COUNTS"
				);
			}
		}

		@Test
		@DisplayName("should compute impact at depth IMPACT, proving the cheaper depths are not vacuously null")
		void shouldComputeImpactAtDepthImpact() {
			boolean anyImpact = false;
			for (final FacetStatistics facet : facetStatisticsAtDepth(FacetStatisticsDepth.IMPACT)) {
				anyImpact |= facet.getImpact() != null;
			}
			assertTrue(
				anyImpact,
				"no facet carried an impact at depth IMPACT - the fixture cannot tell a routed impact calculator "
					+ "from a suppressed one, so the assertions for the cheaper depths prove nothing"
			);
		}
	}
}
