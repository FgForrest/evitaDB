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

package io.evitadb.api.functional.facet;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral regression guard for BUG-1: the `ReferenceSummaryOfReferenceTranslator` and its sibling
 * `ReferenceSummaryTranslator` must filter the stream of entity indexes by the currently-processed
 * scopes before collecting the faceting entities. Without this guard, facets from ARCHIVED indexes
 * leak into a `scope(LIVE)` summary (and vice versa).
 *
 * This test replaces an earlier source-regex approach that validated the production code by
 * text-matching the stream pipeline against a regular expression. Source-regex tests false-positive
 * on benign refactors (extracted helper methods, for-loops instead of streams) and false-negative
 * on semantic bypasses (a negated predicate still matches the regex). A behavioral test pins the
 * actual invariant — "no ARCHIVED facets in a LIVE-only summary" — and survives refactoring.
 *
 * Coverage:
 *
 * - {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator}
 *   via `referenceSummaryOfReference(refName)` — the translator that produced BUG-1;
 * - {@link io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryTranslator}
 *   (all-references form) via `referenceSummary()` — parity guard;
 * - single-scope queries (LIVE only, ARCHIVED only);
 * - multi-scope queries (both LIVE and ARCHIVED) to confirm filtering is inclusive rather than exclusive.
 *
 * Fixture: two brands referenced by a LIVE product (brand 1) and a separate ARCHIVED product
 * (brand 2). If the scope filter is missing, a `scope(LIVE)` summary would enumerate brand 2 as a
 * facet candidate (it lives in the ARCHIVED reference index) — the assertion verifies that doesn't
 * happen.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary must not leak facets across scopes")
class ReferenceSummaryScopeLeakTest implements EvitaTestSupport {

	private static final String DIR_NAME = "referenceSummaryScopeLeakTest";
	private static final String DIR_EXPORT = "referenceSummaryScopeLeakTest_export";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_CATEGORY = "category";

	private static final String REF_BRAND = "brands";
	private static final String REF_CATEGORIES = "categories";

	private static final int PRODUCT_LIVE_PK = 100;
	private static final int PRODUCT_ARCHIVED_PK = 200;
	private static final int BRAND_LIVE_PK = 1;
	private static final int BRAND_ARCHIVED_PK = 2;
	private static final int CATEGORY_LIVE_PK = 10;
	private static final int CATEGORY_ARCHIVED_PK = 20;

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_NAME);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session);
			seedData(session);
			archiveProducts(session);
		});
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_NAME);
		cleanTestSubDirectoryWithRethrow(DIR_EXPORT);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_NAME))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Defines a minimal product → (brand, category) schema with both references faceted in every
	 * scope — the cross-scope archived product's reference index therefore carries live facet
	 * candidates unless the scope filter is applied.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);

		session.defineEntitySchema(ENTITY_CATEGORY)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.facetedInScope(Scope.values())
			)
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.facetedInScope(Scope.values())
			)
			.updateVia(session);
	}

	/**
	 * Seeds:
	 *
	 * - 2 brands (LIVE PK 1, ARCHIVED PK 2) — both referenced by their respective products;
	 * - 2 categories (LIVE PK 10, ARCHIVED PK 20);
	 * - 2 products: PRODUCT 100 (will stay in LIVE) references brand 1 and category 10;
	 *   PRODUCT 200 (will be archived) references brand 2 and category 20.
	 *
	 * After archiving, the per-scope reference indexes are split so that scope(LIVE) sees only
	 * (brand 1, category 10) and scope(ARCHIVED) sees only (brand 2, category 20). A missing scope
	 * filter would accidentally merge the two.
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_BRAND, BRAND_LIVE_PK).upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, BRAND_ARCHIVED_PK).upsertVia(session);

		session.createNewEntity(ENTITY_CATEGORY, CATEGORY_LIVE_PK).upsertVia(session);
		session.createNewEntity(ENTITY_CATEGORY, CATEGORY_ARCHIVED_PK).upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_LIVE_PK)
			.setReference(REF_BRAND, BRAND_LIVE_PK)
			.setReference(REF_CATEGORIES, CATEGORY_LIVE_PK)
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_ARCHIVED_PK)
			.setReference(REF_BRAND, BRAND_ARCHIVED_PK)
			.setReference(REF_CATEGORIES, CATEGORY_ARCHIVED_PK)
			.upsertVia(session);
	}

	/**
	 * Moves product {@link #PRODUCT_ARCHIVED_PK} from the default LIVE scope into the ARCHIVED
	 * scope so that each scope's reference index holds exactly one product.
	 */
	private static void archiveProducts(@Nonnull EvitaSessionContract session) {
		session.archiveEntity(ENTITY_PRODUCT, PRODUCT_ARCHIVED_PK);
	}

	/**
	 * Collects facet primary keys present in the given summary for the given reference name.
	 * Works for both grouped and non-grouped reference summaries.
	 *
	 * @param summary       the reference-summary extra result
	 * @param referenceName the reference whose facets should be enumerated
	 * @return set of facet primary keys observed in the summary for the named reference
	 */
	@Nonnull
	private static Set<Integer> facetPksFor(
		@Nonnull ReferenceSummary summary, @Nonnull String referenceName
	) {
		final Set<Integer> result = new HashSet<>();
		for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			if (!referenceName.equals(group.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facet : group.getFacetStatistics()) {
				result.add(facet.getFacetEntity().getPrimaryKey());
			}
		}
		return result;
	}

	@Nested
	@DisplayName("ReferenceSummaryOfReferenceTranslator (single-reference form)")
	class SingleReferenceForm {

		@Test
		@DisplayName("should include only LIVE facets when query requests scope(LIVE)")
		void shouldIncludeOnlyLiveFacetsWhenQueryRequestsLiveScope() {
			ReferenceSummaryScopeLeakTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(scope(Scope.LIVE)),
							require(referenceSummaryOfReference(REF_BRAND))
						),
						EntityReferenceContract.class
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary, "ReferenceSummary must be present");

					final Set<Integer> brandFacetPks = facetPksFor(summary, REF_BRAND);
					assertTrue(
						brandFacetPks.contains(BRAND_LIVE_PK),
						"LIVE brand PK " + BRAND_LIVE_PK + " must appear in scope(LIVE) summary"
					);
					assertFalse(
						brandFacetPks.contains(BRAND_ARCHIVED_PK),
						"ARCHIVED brand PK " + BRAND_ARCHIVED_PK + " must NOT leak into scope(LIVE) summary"
					);
				}
			);
		}

		@Test
		@DisplayName("should include only ARCHIVED facets when query requests scope(ARCHIVED)")
		void shouldIncludeOnlyArchivedFacetsWhenQueryRequestsArchivedScope() {
			ReferenceSummaryScopeLeakTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(scope(Scope.ARCHIVED)),
							require(referenceSummaryOfReference(REF_BRAND))
						),
						EntityReferenceContract.class
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					final Set<Integer> brandFacetPks = facetPksFor(summary, REF_BRAND);
					assertTrue(
						brandFacetPks.contains(BRAND_ARCHIVED_PK),
						"ARCHIVED brand PK " + BRAND_ARCHIVED_PK + " must appear in scope(ARCHIVED) summary"
					);
					assertFalse(
						brandFacetPks.contains(BRAND_LIVE_PK),
						"LIVE brand PK " + BRAND_LIVE_PK + " must NOT leak into scope(ARCHIVED) summary"
					);
				}
			);
		}

		@Test
		@DisplayName("should include facets from both scopes when query requests scope(LIVE, ARCHIVED)")
		void shouldIncludeFacetsFromBothScopesWhenQueryRequestsBothScopes() {
			ReferenceSummaryScopeLeakTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
							require(referenceSummaryOfReference(REF_BRAND))
						),
						EntityReferenceContract.class
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					final Set<Integer> brandFacetPks = facetPksFor(summary, REF_BRAND);
					assertEquals(
						Set.of(BRAND_LIVE_PK, BRAND_ARCHIVED_PK), brandFacetPks,
						"Multi-scope query must include facets from every requested scope"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("ReferenceSummaryTranslator (all-references form) — parity guard")
	class AllReferencesForm {

		@Test
		@DisplayName("should include only LIVE facets on every reference when query requests scope(LIVE)")
		void shouldIncludeOnlyLiveFacetsAcrossAllReferencesWhenQueryRequestsLiveScope() {
			ReferenceSummaryScopeLeakTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(scope(Scope.LIVE)),
							require(referenceSummary())
						),
						EntityReferenceContract.class
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					// both brand and category references must be filtered by scope
					final Set<Integer> brandFacetPks = facetPksFor(summary, REF_BRAND);
					final Set<Integer> categoryFacetPks = facetPksFor(summary, REF_CATEGORIES);

					assertTrue(brandFacetPks.contains(BRAND_LIVE_PK));
					assertFalse(
						brandFacetPks.contains(BRAND_ARCHIVED_PK),
						"ARCHIVED brand must not leak into all-references scope(LIVE) summary"
					);

					assertTrue(categoryFacetPks.contains(CATEGORY_LIVE_PK));
					assertFalse(
						categoryFacetPks.contains(CATEGORY_ARCHIVED_PK),
						"ARCHIVED category must not leak into all-references scope(LIVE) summary"
					);
				}
			);
		}

		@Test
		@DisplayName("should include only ARCHIVED facets on every reference when query requests scope(ARCHIVED)")
		void shouldIncludeOnlyArchivedFacetsAcrossAllReferencesWhenQueryRequestsArchivedScope() {
			ReferenceSummaryScopeLeakTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(scope(Scope.ARCHIVED)),
							require(referenceSummary())
						),
						EntityReferenceContract.class
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					final Set<Integer> brandFacetPks = facetPksFor(summary, REF_BRAND);
					final Set<Integer> categoryFacetPks = facetPksFor(summary, REF_CATEGORIES);

					assertTrue(brandFacetPks.contains(BRAND_ARCHIVED_PK));
					assertFalse(
						brandFacetPks.contains(BRAND_LIVE_PK),
						"LIVE brand must not leak into all-references scope(ARCHIVED) summary"
					);

					assertTrue(categoryFacetPks.contains(CATEGORY_ARCHIVED_PK));
					assertFalse(
						categoryFacetPks.contains(CATEGORY_LIVE_PK),
						"LIVE category must not leak into all-references scope(ARCHIVED) summary"
					);
				}
			);
		}
	}
}
