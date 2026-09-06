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
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
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
 * Two tests pin what the overlay leaves alone: a kind the specific fetch does not mention at all is still inherited
 * from the generic one, and two requirements of an unkeyed kind are still united, so defining common defaults once
 * and specializing a single reference keeps working.
 *
 * The last three tests pin the granularity: an unnamed `referenceContent` is overlaid **per reference name**, not per
 * whole name set. A generic requirement addressing two references beside a specific one addressing a single one of
 * them keeps the specific fetch's `filterBy` for the shared name and still contributes the other name, a generic
 * requirement is not inherited at all beside a specific `referenceContentAll…()`, and two filters disagreeing on the
 * shared name are reconciled by the specific one winning rather than by refusing the query.
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
	private static final String ENTITY_LABEL = "label";

	private static final String REF_BRANDS = "brands";
	private static final String REF_CATEGORIES = "categories";
	private static final String REF_TAGS = "tags";
	private static final String REF_LABELS = "labels";

	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";

	private static final int PRODUCT_PK = 100;
	private static final int BRAND_PK = 1;
	private static final int CATEGORY_PK = 10;
	private static final int[] TAG_PKS = {1, 2, 3, 4};
	/**
	 * Primary keys of the `label` entities. They deliberately mirror {@link #TAG_PKS}, so that one
	 * `entityPrimaryKeyInSet` filter written for a requirement addressing both `tags` and `labels` selects the
	 * corresponding member of either reference.
	 */
	private static final int[] LABEL_PKS = {1, 2, 3, 4};

	private TestPaths paths;
	private Evita evita;

	/**
	 * Defines a product whose `brands` and `categories` references are faceted, and gives both the brand and the
	 * category entity the same `tags` and `labels` references, so that a single generic summary can describe
	 * a nested fetch that is valid for either of them. Two nested references are what makes a generic requirement
	 * addressing a *set* of names - and therefore only partially overlapping a reference-specific one - expressible.
	 *
	 * @param session session to define the schema in
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TAG)
			.withoutGeneratedPrimaryKey()
			.updateVia(session);

		session.defineEntitySchema(ENTITY_LABEL)
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
			.withReferenceToEntity(
				REF_LABELS, ENTITY_LABEL, Cardinality.ZERO_OR_MORE,
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
			.withReferenceToEntity(
				REF_LABELS, ENTITY_LABEL, Cardinality.ZERO_OR_MORE,
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
	 * Seeds four tags, four labels, one brand and one category carrying all of them, and a single product
	 * referencing both. Every facet entity therefore holds the same four `tags` and four `labels` references, so
	 * a filter applied to them is visible as the exact subset that comes back.
	 *
	 * @param session session to seed the data in
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		for (final int tagPk : TAG_PKS) {
			session.createNewEntity(ENTITY_TAG, tagPk).upsertVia(session);
		}
		for (final int labelPk : LABEL_PKS) {
			session.createNewEntity(ENTITY_LABEL, labelPk).upsertVia(session);
		}

		session.createNewEntity(ENTITY_BRAND, BRAND_PK)
			.setAttribute(ATTRIBUTE_CODE, "brand-" + BRAND_PK)
			.setAttribute(ATTRIBUTE_NAME, "Brand " + BRAND_PK)
			.setReference(REF_TAGS, TAG_PKS[0])
			.setReference(REF_TAGS, TAG_PKS[1])
			.setReference(REF_TAGS, TAG_PKS[2])
			.setReference(REF_TAGS, TAG_PKS[3])
			.setReference(REF_LABELS, LABEL_PKS[0])
			.setReference(REF_LABELS, LABEL_PKS[1])
			.setReference(REF_LABELS, LABEL_PKS[2])
			.setReference(REF_LABELS, LABEL_PKS[3])
			.upsertVia(session);

		session.createNewEntity(ENTITY_CATEGORY, CATEGORY_PK)
			.setAttribute(ATTRIBUTE_CODE, "category-" + CATEGORY_PK)
			.setAttribute(ATTRIBUTE_NAME, "Category " + CATEGORY_PK)
			.setReference(REF_TAGS, TAG_PKS[0])
			.setReference(REF_TAGS, TAG_PKS[1])
			.setReference(REF_TAGS, TAG_PKS[2])
			.setReference(REF_TAGS, TAG_PKS[3])
			.setReference(REF_LABELS, LABEL_PKS[0])
			.setReference(REF_LABELS, LABEL_PKS[1])
			.setReference(REF_LABELS, LABEL_PKS[2])
			.setReference(REF_LABELS, LABEL_PKS[3])
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
		return nestedPksOfSingleFacet(summary, referenceName, REF_TAGS);
	}

	/**
	 * Returns the primary keys of the `labels` references carried by the single facet entity of the passed
	 * reference. The fixture seeds exactly one facet per reference, so the summary is expected to hold exactly one.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the faceted reference whose only facet entity is examined
	 * @return primary keys of the labels fetched with that facet entity, in ascending order
	 */
	@Nonnull
	private static Set<Integer> labelPksOfSingleFacet(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		return nestedPksOfSingleFacet(summary, referenceName, REF_LABELS);
	}

	/**
	 * Returns the primary keys the single facet entity of the passed reference carries under the passed nested
	 * reference name.
	 *
	 * @param summary             summary returned by the query
	 * @param referenceName       name of the faceted reference whose only facet entity is examined
	 * @param nestedReferenceName name of the reference read from that facet entity
	 * @return primary keys of the nested references fetched with that facet entity, in ascending order
	 */
	@Nonnull
	private static Set<Integer> nestedPksOfSingleFacet(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName,
		@Nonnull String nestedReferenceName
	) {
		final SealedEntity facetEntity = singleFacetEntity(summary, referenceName);
		final Set<Integer> referencedPks = new TreeSet<>();
		for (final ReferenceContract nestedReference : facetEntity.getReferences(nestedReferenceName)) {
			referencedPks.add(nestedReference.getReferencedPrimaryKey());
		}
		return referencedPks;
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

	/**
	 * The generic summary addresses two references at once while the reference-specific one addresses a single one
	 * of them: the shared name keeps the specific fetch's filter, and the name the specific fetch never mentions is
	 * still contributed by the generic requirement. Comparing whole reference-name sets would let the generic
	 * requirement through untouched, and the nested request would then fold it onto the shared name and drop the
	 * specific filter as the superset.
	 */
	@Test
	@DisplayName("should keep the specific filter while inheriting the names it does not address")
	void shouldKeepSpecificFilterWhileInheritingNamesItDoesNotAddress() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(referenceContent(REF_TAGS, REF_LABELS))
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
					"The generic requirement widened the nested references of the specific one!"
				);
				assertEquals(
					Set.of(LABEL_PKS[0], LABEL_PKS[1], LABEL_PKS[2], LABEL_PKS[3]),
					labelPksOfSingleFacet(summary, REF_BRANDS),
					"The name the specific fetch does not address was not inherited from the generic one!"
				);
				assertEquals(
					Set.of(TAG_PKS[0], TAG_PKS[1], TAG_PKS[2], TAG_PKS[3]),
					tagPksOfSingleFacet(summary, REF_CATEGORIES),
					"The generic summary stopped applying to the references it was written for!"
				);
				assertEquals(
					Set.of(LABEL_PKS[0], LABEL_PKS[1], LABEL_PKS[2], LABEL_PKS[3]),
					labelPksOfSingleFacet(summary, REF_CATEGORIES),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * A `referenceContentAll…()` on the reference-specific summary describes every reference of the facet entity, so
	 * no `referenceContent` of the generic summary is inherited beside it - not even one addressing a name the
	 * specific fetch does not spell out, because the catch-all already covers that name too.
	 */
	@Test
	@DisplayName("should not inherit a generic requirement beside a specific catch-all")
	void shouldNotInheritGenericRequirementBesideSpecificCatchAll() {
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
								entityFetch(referenceContentAll())
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
					"The generic filter narrowed the references the specific catch-all asked for!"
				);
				assertEquals(
					Set.of(LABEL_PKS[0], LABEL_PKS[1], LABEL_PKS[2], LABEL_PKS[3]),
					labelPksOfSingleFacet(summary, REF_BRANDS),
					"The specific catch-all stopped fetching the references it asked for!"
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
	 * Two filters disagreeing on the reference both requirements address are not a conflict: the reference-specific
	 * summary is the only one written for the reference it names, so its filter wins, while the generic filter keeps
	 * applying to the name only the generic requirement addresses. Letting the generic requirement through whole
	 * would make the nested request refuse the query outright.
	 */
	@Test
	@DisplayName("should prefer the specific filter over a conflicting generic one on the shared name")
	void shouldPreferSpecificFilterOverConflictingGenericOneOnSharedName() {
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
									filteredReferenceContent(
										filterBy(entityPrimaryKeyInSet(TAG_PKS[0])),
										REF_TAGS, REF_LABELS
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
					"The filter of the reference-specific summary did not win on the shared name!"
				);
				assertEquals(
					Set.of(LABEL_PKS[0]),
					labelPksOfSingleFacet(summary, REF_BRANDS),
					"The generic filter was lost on the name only the generic requirement addresses!"
				);
				assertEquals(
					Set.of(TAG_PKS[0]),
					tagPksOfSingleFacet(summary, REF_CATEGORIES),
					"The generic summary stopped applying to the references it was written for!"
				);
				assertEquals(
					Set.of(LABEL_PKS[0]),
					labelPksOfSingleFacet(summary, REF_CATEGORIES),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * Builds a `referenceContent` addressing several references at once and carrying a `filterBy`. The
	 * {@link io.evitadb.api.query.QueryConstraints} factories offer no such combination - there a `filterBy` is only
	 * ever written beside a single reference name - while the constructor the GraphQL API builds its multi-reference
	 * requirements with does, and a filtered requirement addressing a *set* of names is what makes a genuine
	 * disagreement on a partially overlapping name expressible.
	 *
	 * @param filterBy       filter the requirement carries for every reference it addresses
	 * @param referenceNames names of the references the requirement addresses
	 * @return the requirement to write into an entity fetch
	 */
	@Nonnull
	private static ReferenceContent filteredReferenceContent(
		@Nonnull FilterBy filterBy,
		@Nonnull String... referenceNames
	) {
		return new ReferenceContent(
			null,
			ManagedReferencesBehaviour.ANY,
			referenceNames,
			new RequireConstraint[0],
			new Constraint<?>[]{filterBy}
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

}
