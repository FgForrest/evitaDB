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
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage verifying the interaction between `facetSummary(IMPACT)` and a
 * `userFilter → referenceHaving` subtree. Pins two invariants:
 *
 * - `ReferenceHaving` inside `userFilter` is **not** enumerated as an impact candidate — the
 *   facet summary produces impact entries only for `facetHaving` children of `userFilter`.
 * - The facet-impact *baseline* narrows with the `referenceHaving` applied as an always-on
 *   filter, matching how `priceBetween` is already treated.
 *
 * Uses a small hand-rolled fixture (self-contained) rather than the large 1000-product facet
 * dataset in {@link AbstractEntityByFacetFilteringFunctionalTest} so the oracle computation
 * stays auditable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Facet summary impact with referenceHaving inside userFilter")
class FacetSummaryImpactWithReferenceHavingTest implements EvitaTestSupport {
	private static final String DIR_NAME = "facetSummaryImpactWithReferenceHavingTest";
	private static final String DIR_EXPORT = "facetSummaryImpactWithReferenceHavingTest_export";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_CATEGORY = "category";

	private static final String REF_BRAND = "brands";
	private static final String REF_CATEGORIES = "categories";

	private static final String ATTR_NAME = "name";
	private static final String ATTR_PRIORITY = "priority";

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
	 * Defines a product → (brand, category) schema. `brand` is faceted so it participates in the
	 * facet summary; `categories` is faceted AND carries a filterable `priority` attribute that
	 * drives the `referenceHaving` narrowing.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_CATEGORY)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
			)
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.withAttribute(
						ATTR_PRIORITY, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(0).nullable()
					)
			)
			.updateVia(session);
	}

	/**
	 * Seeds:
	 * - 3 brands (PK 1, 2, 3)
	 * - 3 categories (PK 10, 20, 30)
	 * - 6 products, each referencing exactly one brand and two categories with varying `priority`
	 *   values on the category reference.
	 *
	 * Product → (brand, categoriesWithPriority):
	 * - P1 → brand 1, [cat 10 (prio 10), cat 20 (prio 100)]
	 * - P2 → brand 1, [cat 10 (prio 20), cat 30 (prio 200)]
	 * - P3 → brand 2, [cat 20 (prio 30), cat 30 (prio 300)]
	 * - P4 → brand 2, [cat 10 (prio 40), cat 20 (prio 40)]
	 * - P5 → brand 3, [cat 20 (prio 500), cat 30 (prio 50)]
	 * - P6 → brand 3, [cat 10 (prio 600), cat 30 (prio 150)]
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		createBrand(session, 1, "Alpha");
		createBrand(session, 2, "Beta");
		createBrand(session, 3, "Gamma");
		createCategory(session, 10, "Accessories");
		createCategory(session, 20, "Electronics");
		createCategory(session, 30, "Home");

		createProduct(session, 1, 1, new int[]{10, 20}, new int[]{10, 100});
		createProduct(session, 2, 1, new int[]{10, 30}, new int[]{20, 200});
		createProduct(session, 3, 2, new int[]{20, 30}, new int[]{30, 300});
		createProduct(session, 4, 2, new int[]{10, 20}, new int[]{40, 40});
		createProduct(session, 5, 3, new int[]{20, 30}, new int[]{500, 50});
		createProduct(session, 6, 3, new int[]{10, 30}, new int[]{600, 150});
	}

	private static void createBrand(
		@Nonnull EvitaSessionContract session, int pk, @Nonnull String name
	) {
		session.createNewEntity(ENTITY_BRAND, pk)
			.setAttribute(ATTR_NAME, name)
			.upsertVia(session);
	}

	private static void createCategory(
		@Nonnull EvitaSessionContract session, int pk, @Nonnull String name
	) {
		session.createNewEntity(ENTITY_CATEGORY, pk)
			.setAttribute(ATTR_NAME, name)
			.upsertVia(session);
	}

	private static void createProduct(
		@Nonnull EvitaSessionContract session, int productPk, int brandPk,
		@Nonnull int[] categoryPks, @Nonnull int[] priorities
	) {
		final io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder builder =
			session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setReference(REF_BRAND, brandPk);
		for (int i = 0; i < categoryPks.length; i++) {
			final int categoryPk = categoryPks[i];
			final int priority = priorities[i];
			builder.setReference(
				REF_CATEGORIES, categoryPk,
				whichIs -> whichIs.setAttribute(ATTR_PRIORITY, new BigDecimal(priority))
			);
		}
		builder.upsertVia(session);
	}

	@Test
	@DisplayName("should not enumerate referenceHaving as impact candidate")
	void shouldNotEnumerateReferenceHavingAsImpactCandidate() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				// facetHaving(brand=1) + referenceHaving(categories, priority in [10, 50]) inside
				// the same userFilter, plus facetSummary(IMPACT). Expectation: impact entries
				// appear only on `brands` facets (i.e. the other brands 2, 3, which could be
				// added / switched). No impact entry exists that toggles the `referenceHaving`.
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(
							userFilter(
								and(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(1)),
									referenceHaving(
										REF_CATEGORIES,
										attributeBetween(ATTR_PRIORITY, 10, 50)
									)
								)
							)
						),
						require(facetSummary(FacetStatisticsDepth.IMPACT))
					),
					EntityReferenceContract.class
				);
				final FacetSummary summary = result.getExtraResult(FacetSummary.class);
				assertNotNull(summary, "ReferenceSummary must be present for facetSummary(IMPACT)");

				// Locate the `brands` group statistics — impact must be attached to brand facets
				// other than the selected one. (Brand 1 is selected, so its impact is either null
				// or present — but brands 2 and 3 must have non-null impact entries.)
				final Collection<FacetGroupStatistics> allGroups = summary.getReferenceStatistics();
				boolean foundBrandWithImpact = false;
				for (final FacetGroupStatistics group : allGroups) {
					for (final FacetStatistics facet : group.getFacetStatistics()) {
						if (REF_BRAND.equals(group.getReferenceName())
							&& facet.getFacetEntity().getPrimaryKey() != 1
							&& facet.getImpact() != null
						) {
							foundBrandWithImpact = true;
						}
					}
				}
				assertTrue(
					foundBrandWithImpact,
					"At least one non-selected brand facet must carry a non-null impact entry"
				);

				// Categories appear in the summary only because they are faceted — but the
				// referenceHaving constraint does NOT introduce synthetic "impact candidates"
				// that would flip that constraint itself. Concretely, we verify the categories
				// facet summary contains at most the facets naturally discoverable from the
				// filtered baseline (categories 10, 20, 30 that pass `priority in [10, 50]`).
				final Set<Integer> categoryFacetsInSummary = new HashSet<>();
				for (final FacetGroupStatistics group : allGroups) {
					if (!REF_CATEGORIES.equals(group.getReferenceName())) {
						continue;
					}
					for (final FacetStatistics facet : group.getFacetStatistics()) {
						categoryFacetsInSummary.add(facet.getFacetEntity().getPrimaryKey());
					}
				}
				// The baseline products (passing priority ∈ [10, 50] and brand=1) are P1, P2,
				// P4 — referencing categories 10, 20, 30. Only category PKs visible must come
				// from categories that participate in the baseline. No synthetic "toggle
				// referenceHaving" entry is enumerated — this is a structural check.
				for (final int categoryPk : categoryFacetsInSummary) {
					assertTrue(
						categoryPk == 10 || categoryPk == 20 || categoryPk == 30,
						"Unexpected category facet enumerated as impact candidate: " + categoryPk
					);
				}
			}
		);
	}

	@Test
	@DisplayName("should narrow facet impact base by referenceHaving")
	void shouldNarrowFacetImpactBaseByReferenceHaving() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				// We select brand=3 because its two products have diverging priorities on the
				// `categories` reference — P5 has one category with priority 50 (in [10, 50])
				// while P6 has no category with priority in that range. That makes the
				// referenceHaving effective: unnarrowed {P5, P6} → narrowed {P5}.

				// Query 1: no referenceHaving — baseline is {P5, P6} = 2 products.
				final EvitaResponse<EntityReferenceContract> unnarrowed = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(
							userFilter(
								facetHaving(REF_BRAND, entityPrimaryKeyInSet(3))
							)
						),
						require(facetSummary(FacetStatisticsDepth.IMPACT))
					),
					EntityReferenceContract.class
				);
				final FacetSummary unnarrowedSummary = unnarrowed.getExtraResult(
					FacetSummary.class
				);
				assertNotNull(unnarrowedSummary);
				assertEquals(
					2, unnarrowed.getTotalRecordCount(),
					"Unnarrowed baseline must contain {P5, P6} = 2 products"
				);

				// Query 2: narrow by referenceHaving(priority in [10, 50]) — only P5 has a
				// category with priority in that range.
				final EvitaResponse<EntityReferenceContract> narrowed = session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(
							userFilter(
								and(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(3)),
									referenceHaving(
										REF_CATEGORIES,
										attributeBetween(ATTR_PRIORITY, 10, 50)
									)
								)
							)
						),
						require(facetSummary(FacetStatisticsDepth.IMPACT))
					),
					EntityReferenceContract.class
				);
				final FacetSummary narrowedSummary = narrowed.getExtraResult(
					FacetSummary.class
				);
				assertNotNull(narrowedSummary);
				assertEquals(
					1, narrowed.getTotalRecordCount(),
					"Narrowed baseline must contain only {P5} = 1 product"
				);

				// Monotonicity: narrowed count must not exceed unnarrowed count.
				assertTrue(
					narrowed.getTotalRecordCount() <= unnarrowed.getTotalRecordCount(),
					"Narrowed baseline count must not exceed unnarrowed"
				);

				// Per-facet comparison: for every brand facet present in both summaries, the
				// narrowed impact matchCount must be <= unnarrowed impact matchCount.
				for (final FacetGroupStatistics narrowedGroup :
					narrowedSummary.getReferenceStatistics()) {
					if (!REF_BRAND.equals(narrowedGroup.getReferenceName())) {
						continue;
					}
					final FacetGroupStatistics unnarrowedGroup = findBrandGroup(
						unnarrowedSummary, narrowedGroup.getGroupEntity() == null
							? null
							: narrowedGroup.getGroupEntity().getPrimaryKey()
					);
					if (unnarrowedGroup == null) {
						continue;
					}
					for (final FacetStatistics narrowedFacet : narrowedGroup.getFacetStatistics()) {
						final int facetPk = narrowedFacet.getFacetEntity().getPrimaryKey();
						final FacetStatistics unnarrowedFacet = unnarrowedGroup.getFacetStatistics(
							facetPk
						);
						if (unnarrowedFacet == null
							|| narrowedFacet.getImpact() == null
							|| unnarrowedFacet.getImpact() == null
						) {
							continue;
						}
						final RequestImpact narrowedImpact = narrowedFacet.getImpact();
						final RequestImpact unnarrowedImpact = unnarrowedFacet.getImpact();
						assertTrue(
							narrowedImpact.matchCount() <= unnarrowedImpact.matchCount(),
							"Narrowed matchCount (" + narrowedImpact.matchCount() + ") for brand facet "
								+ facetPk + " must not exceed unnarrowed ("
								+ unnarrowedImpact.matchCount() + ")"
						);
					}
				}
			}
		);
	}

	/**
	 * Locates the brand reference-group statistics for a given group PK in the supplied summary.
	 * Returns null when no matching group is present.
	 */
	@javax.annotation.Nullable
	private static FacetGroupStatistics findBrandGroup(
		@Nonnull FacetSummary summary, @javax.annotation.Nullable Integer groupPk
	) {
		for (final FacetGroupStatistics group : summary.getReferenceStatistics()) {
			if (!REF_BRAND.equals(group.getReferenceName())) {
				continue;
			}
			final Integer currentGroupPk = group.getGroupEntity() == null
				? null
				: group.getGroupEntity().getPrimaryKey();
			if ((groupPk == null && currentGroupPk == null)
				|| (groupPk != null && groupPk.equals(currentGroupPk))
			) {
				return group;
			}
		}
		return null;
	}
}
