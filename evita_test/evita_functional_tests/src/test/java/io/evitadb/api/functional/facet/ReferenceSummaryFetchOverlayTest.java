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
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies how the entity fetch of a reference-specific summary is combined with the entity fetch of the generic
 * summary written beside it. The two are **overlaid**: a requirement of a keyed kind - a `referenceContent` for one
 * reference, an `accompanyingPriceContent` for one price - is taken from the reference-specific fetch alone, while
 * every other kind is united exactly as it always was.
 *
 * The narrowing is confined to the keyed kinds because uniting those is what damages the specific fetch, in both
 * directions:
 *
 * - a nested `referenceContent` filter carried by the **specific** fetch alone would be dropped by the union's
 *   superset rule, widening the graph the client receives
 * - two **different** nested filters would make the union refuse the query outright, although the client wrote only
 *   one of them for the reference the specific summary describes
 *
 * The last two tests pin what the overlay leaves alone: a kind the specific fetch does not mention at all is still
 * inherited from the generic one, and two requirements of an unkeyed kind are still united, so defining common
 * defaults once and specializing a single reference keeps working.
 *
 * The tests drive `referenceSummary` / `referenceSummaryOfReference`; the deprecated `facetSummary` pair reaches the
 * very same code through `FacetSummaryOfReferenceTranslator`, which delegates to
 * `ReferenceSummaryOfReferenceTranslator`.
 *
 * Fixture: a product whose `brands` and `categories` references are both faceted, where each brand and each category
 * carries the same `tags` reference. That shared reference name is what lets one generic summary describe a nested
 * fetch valid for every faceted reference, while the specific summary describes it differently for `brands` alone.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary overlays the specific entity fetch onto the generic one")
@Tag(CONTRACT)
@Tag(FACET)
@Tag(REFERENCE)
@Tag(REQUIRE)
class ReferenceSummaryFetchOverlayTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_TAG = "tag";

	private static final String REF_BRANDS = "brands";
	private static final String REF_CATEGORIES = "categories";
	private static final String REF_TAGS = "tags";

	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";

	private static final int PRODUCT_PK = 100;
	private static final int BRAND_PK = 1;
	private static final int CATEGORY_PK = 10;
	private static final int[] TAG_PKS = {1, 2, 3, 4};

	private TestPaths paths;
	private Evita evita;

	/**
	 * Defines a product whose `brands` and `categories` references are faceted, and gives both the brand and the
	 * category entity the same `tags` reference, so that a single generic summary can describe a nested fetch that
	 * is valid for either of them.
	 *
	 * @param session session to define the schema in
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TAG)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);

		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withAttribute(ATTRIBUTE_NAME, String.class)
			.withReferenceToEntity(
				REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_CATEGORY)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withAttribute(ATTRIBUTE_NAME, String.class)
			.withReferenceToEntity(
				REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REF_BRANDS, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
			)
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
			)
			.updateVia(session);
	}

	/**
	 * Seeds four tags, one brand and one category carrying all of them, and a single product referencing both. Every
	 * facet entity therefore holds the same four `tags` references, so a filter applied to them is visible as the
	 * exact subset that comes back.
	 *
	 * @param session session to seed the data in
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		for (final int tagPk : TAG_PKS) {
			session.createNewEntity(ENTITY_TAG, tagPk).upsertVia(session);
		}

		session.createNewEntity(ENTITY_BRAND, BRAND_PK)
			.setAttribute(ATTRIBUTE_CODE, "brand-" + BRAND_PK)
			.setAttribute(ATTRIBUTE_NAME, "Brand " + BRAND_PK)
			.setReference(REF_TAGS, TAG_PKS[0])
			.setReference(REF_TAGS, TAG_PKS[1])
			.setReference(REF_TAGS, TAG_PKS[2])
			.setReference(REF_TAGS, TAG_PKS[3])
			.upsertVia(session);

		session.createNewEntity(ENTITY_CATEGORY, CATEGORY_PK)
			.setAttribute(ATTRIBUTE_CODE, "category-" + CATEGORY_PK)
			.setAttribute(ATTRIBUTE_NAME, "Category " + CATEGORY_PK)
			.setReference(REF_TAGS, TAG_PKS[0])
			.setReference(REF_TAGS, TAG_PKS[1])
			.setReference(REF_TAGS, TAG_PKS[2])
			.setReference(REF_TAGS, TAG_PKS[3])
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(REF_BRANDS, BRAND_PK)
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.upsertVia(session);
	}

	/**
	 * Returns the primary keys of the `tags` references carried by the single facet entity of the passed reference.
	 * The fixture seeds exactly one facet per reference, so the summary is expected to hold exactly one.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the faceted reference whose only facet entity is examined
	 * @return primary keys of the tags fetched with that facet entity, in ascending order
	 */
	@Nonnull
	private static Set<Integer> tagPksOfSingleFacet(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		final SealedEntity facetEntity = singleFacetEntity(summary, referenceName);
		final Set<Integer> tagPks = new TreeSet<>();
		for (final ReferenceContract tag : facetEntity.getReferences(REF_TAGS)) {
			tagPks.add(tag.getReferencedPrimaryKey());
		}
		return tagPks;
	}

	/**
	 * Returns the body of the only facet entity the summary holds for the passed reference, failing the test when
	 * the summary holds no facet or when the facet body was not fetched at all.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the faceted reference whose only facet entity is returned
	 * @return the fetched body of that facet entity
	 */
	@Nonnull
	private static SealedEntity singleFacetEntity(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			if (!referenceName.equals(group.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facet : group.getFacetStatistics()) {
				return assertInstanceOf(
					SealedEntity.class,
					facet.getFacetEntity(),
					"The body of the `" + referenceName + "` facet entity was not fetched!"
				);
			}
		}
		throw new AssertionError("Summary holds no facet for reference `" + referenceName + "`!");
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceSummaryFetchOverlayTest");
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

	/**
	 * The nested filter written only on the reference-specific summary must survive - uniting it with the generic
	 * fetch, which asks for every tag, would drop it as the superset and hand the client references it excluded.
	 */
	@Test
	@DisplayName("should keep the nested filter of the specific summary beside an unfiltered generic one")
	void shouldKeepNestedFilterOfSpecificSummaryBesideUnfilteredGenericOne() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(referenceContent(REF_TAGS))
							),
							referenceSummaryOfReference(
								REF_BRANDS,
								FacetStatisticsDepth.COUNTS,
								entityFetch(
									referenceContent(
										REF_TAGS,
										filterBy(entityPrimaryKeyInSet(TAG_PKS[2]))
									)
								)
							)
						)
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				assertEquals(
					Set.of(TAG_PKS[2]),
					tagPksOfSingleFacet(summary, REF_BRANDS),
					"The generic summary widened the nested references of the specific one!"
				);
				assertEquals(
					Set.of(TAG_PKS[0], TAG_PKS[1], TAG_PKS[2], TAG_PKS[3]),
					tagPksOfSingleFacet(summary, REF_CATEGORIES),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * Two different nested filters are not a conflict here: the reference-specific summary is the only one that
	 * describes the reference it names, so its filter simply wins and the generic one keeps applying to every other
	 * faceted reference. Uniting the two fetches would refuse the query instead.
	 */
	@Test
	@DisplayName("should prefer the nested filter of the specific summary over the generic one")
	void shouldPreferNestedFilterOfSpecificSummaryOverGenericOne() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(
									referenceContent(
										REF_TAGS,
										filterBy(entityPrimaryKeyInSet(TAG_PKS[0]))
									)
								)
							),
							referenceSummaryOfReference(
								REF_BRANDS,
								FacetStatisticsDepth.COUNTS,
								entityFetch(
									referenceContent(
										REF_TAGS,
										filterBy(entityPrimaryKeyInSet(TAG_PKS[2], TAG_PKS[3]))
									)
								)
							)
						)
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				assertEquals(
					Set.of(TAG_PKS[2], TAG_PKS[3]),
					tagPksOfSingleFacet(summary, REF_BRANDS),
					"The filter of the reference-specific summary did not win!"
				);
				assertEquals(
					Set.of(TAG_PKS[0]),
					tagPksOfSingleFacet(summary, REF_CATEGORIES),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * The overlay is not a replacement: a content requirement of a kind the specific fetch does not mention at all
	 * is still contributed by the generic one, which is what lets a caller define common defaults once and
	 * specialize a single reference.
	 */
	@Test
	@DisplayName("should inherit the generic requirements the specific summary does not mention")
	void shouldInheritGenericRequirementsTheSpecificSummaryDoesNotMention() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(attributeContent(ATTRIBUTE_CODE))
							),
							referenceSummaryOfReference(
								REF_BRANDS,
								FacetStatisticsDepth.COUNTS,
								entityFetch(referenceContent(REF_TAGS))
							)
						)
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				assertEquals(
					Set.of(TAG_PKS[0], TAG_PKS[1], TAG_PKS[2], TAG_PKS[3]),
					tagPksOfSingleFacet(summary, REF_BRANDS),
					"The requirement written on the reference-specific summary was lost!"
				);
				assertEquals(
					"brand-" + BRAND_PK,
					singleFacetEntity(summary, REF_BRANDS).getAttribute(ATTRIBUTE_CODE),
					"The requirement the specific summary does not mention was not inherited from the generic one!"
				);
				return null;
			}
		);
	}

	/**
	 * The overlay narrows nothing outside the keyed kinds: an `attributeContent` written on both summaries is still
	 * united, so the facet entity carries the generic summary's attribute as well as its own, while the nested
	 * `referenceContent` the specific summary filters stays the specific summary's alone.
	 */
	@Test
	@DisplayName("should unite the attributes of both summaries while the keyed requirement stays specific")
	void shouldUniteAttributesOfBothSummariesWhileKeyedRequirementStaysSpecific() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(attributeContent(ATTRIBUTE_CODE))
							),
							referenceSummaryOfReference(
								REF_BRANDS,
								FacetStatisticsDepth.COUNTS,
								entityFetch(
									attributeContent(ATTRIBUTE_NAME),
									referenceContent(
										REF_TAGS,
										filterBy(entityPrimaryKeyInSet(TAG_PKS[2]))
									)
								)
							)
						)
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				final SealedEntity brandFacet = singleFacetEntity(summary, REF_BRANDS);
				assertEquals(
					"Brand " + BRAND_PK,
					brandFacet.getAttribute(ATTRIBUTE_NAME),
					"The attribute the reference-specific summary asked for was lost!"
				);
				assertEquals(
					"brand-" + BRAND_PK,
					brandFacet.getAttribute(ATTRIBUTE_CODE),
					"The attribute of the generic summary is no longer united with the specific one!"
				);
				assertEquals(
					Set.of(TAG_PKS[2]),
					tagPksOfSingleFacet(summary, REF_BRANDS),
					"The generic summary widened the nested references of the specific one!"
				);
				return null;
			}
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

}
