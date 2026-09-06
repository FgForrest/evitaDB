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
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
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
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that a reference-specific summary constraint - `referenceSummaryOfReference` /
 * `facetSummaryOfReference` - governs the reference it names **entirely**. It must define all of its own
 * requirements: nothing at all is inherited from a generic `referenceSummary` / `facetSummary` written beside it,
 * neither for the facet entity fetch nor for the group entity fetch. The generic constraint keeps governing every
 * reference that has no specific constraint of its own.
 *
 * That is the rule the class javadoc of `ReferenceSummary` and `FacetSummary` has always stated. The producer used
 * to overlay the two fetches instead, so a reference-specific summary silently picked up the generic summary's
 * attributes, locales and nested references; these tests pin the override the documentation promises.
 *
 * The three groups of tests are:
 *
 * - **nothing is inherited** - an attribute, a locale, a nested reference name or a group fetch written on the
 *   generic summary alone does not reach the reference the specific summary names, while it keeps applying to
 *   every other faceted reference
 * - **the specific fetch's own selectors survive** - the nested `referenceContent` `filterBy`, `orderBy` and page
 *   the specific summary carries reach the fetch untouched, which a union of the two fetches would have dropped as
 *   the superset or refused outright when the two disagreed
 * - **the generic summary still governs the rest** - every assertion checks the un-named reference too
 *
 * The tests drive `referenceSummary` / `referenceSummaryOfReference`; the deprecated `facetSummary` pair reaches the
 * very same code through `FacetSummaryOfReferenceTranslator`, which delegates to
 * `ReferenceSummaryOfReferenceTranslator`.
 *
 * Fixture: a product whose `brands` and `categories` references are both faceted and both grouped, where each brand
 * and each category carries the same `tags` and `labels` references. Those shared reference names are what let one
 * generic summary describe a nested fetch valid for every faceted reference, while the specific summary describes
 * it differently for `brands` alone.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary of a reference overrides the generic summary completely")
@Tag(CONTRACT)
@Tag(FACET)
@Tag(REFERENCE)
@Tag(REQUIRE)
class ReferenceSummaryFetchOverrideTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_GROUP = "group";
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
	private static final int BRAND_GROUP_PK = 1;
	private static final int CATEGORY_GROUP_PK = 2;
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
	 * Defines a product whose `brands` and `categories` references are faceted and grouped by the same `group`
	 * entity type, and gives both the brand and the category entity the same `tags` and `labels` references, so
	 * that a single generic summary can describe a nested fetch that is valid for either of them. Two nested
	 * references are what makes a generic requirement addressing a *set* of names - and therefore only partially
	 * overlapping a reference-specific one - expressible.
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

		session.defineEntitySchema(ENTITY_GROUP)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_CODE, String.class)
			.withAttribute(ATTRIBUTE_NAME, String.class)
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
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_GROUP)
			)
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_GROUP)
			)
			.updateVia(session);
	}

	/**
	 * Seeds four tags, four labels, two groups, one brand and one category carrying all tags and labels, and
	 * a single product referencing both facet entities, each in a group of its own. Every facet entity therefore
	 * holds the same four `tags` and four `labels` references, so a filter applied to them is visible as the exact
	 * subset that comes back.
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

		session.createNewEntity(ENTITY_GROUP, BRAND_GROUP_PK)
			.setAttribute(ATTRIBUTE_CODE, "group-" + BRAND_GROUP_PK)
			.setAttribute(ATTRIBUTE_NAME, "Group " + BRAND_GROUP_PK)
			.upsertVia(session);
		session.createNewEntity(ENTITY_GROUP, CATEGORY_GROUP_PK)
			.setAttribute(ATTRIBUTE_CODE, "group-" + CATEGORY_GROUP_PK)
			.setAttribute(ATTRIBUTE_NAME, "Group " + CATEGORY_GROUP_PK)
			.upsertVia(session);

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
			.setReference(REF_BRANDS, BRAND_PK, whichIs -> whichIs.setGroup(ENTITY_GROUP, BRAND_GROUP_PK))
			.setReference(REF_CATEGORIES, CATEGORY_PK, whichIs -> whichIs.setGroup(ENTITY_GROUP, CATEGORY_GROUP_PK))
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
	 * Returns the primary keys the single facet entity of the passed reference carries under the passed nested
	 * reference name, in the order the fetch produced them. Unlike {@link #nestedPksOfSingleFacet} this keeps the
	 * ordering and the paging the nested requirement asked for observable.
	 *
	 * @param summary             summary returned by the query
	 * @param referenceName       name of the faceted reference whose only facet entity is examined
	 * @param nestedReferenceName name of the reference read from that facet entity
	 * @return primary keys of the nested references fetched with that facet entity, in fetch order
	 */
	@Nonnull
	private static List<Integer> orderedNestedPksOfSingleFacet(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName,
		@Nonnull String nestedReferenceName
	) {
		final SealedEntity facetEntity = singleFacetEntity(summary, referenceName);
		final List<Integer> referencedPks = new ArrayList<>(8);
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
		return assertInstanceOf(
			SealedEntity.class,
			singleFacetClassifier(summary, referenceName),
			"The body of the `" + referenceName + "` facet entity was not fetched!"
		);
	}

	/**
	 * Returns the classifier the summary holds for the only facet of the passed reference - a bare
	 * {@link EntityReference} when no entity fetch governs the reference, the fetched body otherwise.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the faceted reference whose only facet classifier is returned
	 * @return the classifier of that facet
	 */
	@Nonnull
	private static EntityClassifier singleFacetClassifier(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			if (!referenceName.equals(group.getReferenceName())) {
				continue;
			}
			for (final FacetStatistics facet : group.getFacetStatistics()) {
				return facet.getFacetEntity();
			}
		}
		throw new AssertionError("Summary holds no facet for reference `" + referenceName + "`!");
	}

	/**
	 * Returns the group entity the summary holds for the only group of the passed reference.
	 *
	 * @param summary       summary returned by the query
	 * @param referenceName name of the faceted reference whose only group entity is returned
	 * @return the classifier of that group
	 */
	@Nonnull
	private static EntityClassifier singleGroupClassifier(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName
	) {
		for (final ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			if (!referenceName.equals(group.getReferenceName())) {
				continue;
			}
			final EntityClassifier groupEntity = group.getGroupEntity();
			assertNotNull(groupEntity, "Summary holds no group entity for reference `" + referenceName + "`!");
			return groupEntity;
		}
		throw new AssertionError("Summary holds no group for reference `" + referenceName + "`!");
	}

	/**
	 * Asserts that the single facet entity of the passed reference did not fetch the passed nested reference at
	 * all - reading it raises {@link ContextMissingException}, which is how the engine reports a reference the
	 * request never asked for.
	 *
	 * @param summary             summary returned by the query
	 * @param referenceName       name of the faceted reference whose only facet entity is examined
	 * @param nestedReferenceName name of the reference expected not to have been fetched
	 * @param message             assertion message
	 */
	private static void assertNestedReferenceNotFetched(
		@Nonnull ReferenceSummary summary,
		@Nonnull String referenceName,
		@Nonnull String nestedReferenceName,
		@Nonnull String message
	) {
		final SealedEntity facetEntity = singleFacetEntity(summary, referenceName);
		assertThrows(
			ContextMissingException.class,
			() -> facetEntity.getReferences(nestedReferenceName),
			message
		);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceSummaryFetchOverrideTest");
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
	 * The nested filter written on the reference-specific summary is the only requirement governing the reference
	 * it names - the generic summary's unfiltered `referenceContent` does not widen it, and keeps applying to every
	 * other faceted reference.
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
	 * Two different nested filters are not a conflict: the reference-specific summary is the only one that
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
	 * The nested `orderBy` and page written on the reference-specific summary reach the fetch untouched. Uniting
	 * the two fetches has no form that preserves them - a one-sided restriction is dropped as the superset - which
	 * is why the reference-specific fetch has to be the only one governing the reference it names.
	 */
	@Test
	@DisplayName("should keep the nested order and page of the specific summary")
	void shouldKeepNestedOrderAndPageOfSpecificSummary() {
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
										orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
										page(1, 2)
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
					List.of(TAG_PKS[3], TAG_PKS[2]),
					orderedNestedPksOfSingleFacet(summary, REF_BRANDS, REF_TAGS),
					"The order or the page of the reference-specific summary was lost!"
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
	 * The override is total: a content requirement of a kind the specific fetch does not mention at all is **not**
	 * contributed by the generic one either. A reference-specific summary has to define all of its own
	 * requirements.
	 */
	@Test
	@DisplayName("should not inherit the generic requirements the specific summary does not mention")
	void shouldNotInheritGenericRequirementsTheSpecificSummaryDoesNotMention() {
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
				final SealedEntity brandFacet = singleFacetEntity(summary, REF_BRANDS);
				assertThrows(
					ContextMissingException.class,
					brandFacet::getAttributeNames,
					"The requirement the specific summary does not mention was inherited from the generic one!"
				);
				assertEquals(
					"category-" + CATEGORY_PK,
					singleFacetEntity(summary, REF_CATEGORIES).getAttribute(ATTRIBUTE_CODE),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * Two requirements of the very same kind are not united either: the attribute of the generic summary does not
	 * reach the reference the specific summary names, although uniting `attributeContent` can lose nothing. The
	 * override is about the whole constraint rather than about the kinds a union would damage.
	 */
	@Test
	@DisplayName("should not unite the attributes of both summaries")
	void shouldNotUniteAttributesOfBothSummaries() {
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
					Set.of(ATTRIBUTE_NAME),
					brandFacet.getAttributeNames(),
					"The attribute of the generic summary was united with the specific one!"
				);
				assertEquals(
					Set.of(TAG_PKS[2]),
					tagPksOfSingleFacet(summary, REF_BRANDS),
					"The generic summary widened the nested references of the specific one!"
				);
				assertEquals(
					Set.of(ATTRIBUTE_CODE),
					singleFacetEntity(summary, REF_CATEGORIES).getAttributeNames(),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * The generic summary addresses two references at once while the reference-specific one addresses a single one
	 * of them: the shared name keeps the specific fetch's filter and the name the specific fetch never mentions is
	 * not fetched at all, because nothing of the generic summary reaches the reference it names.
	 */
	@Test
	@DisplayName("should not inherit the nested names the specific requirement does not address")
	void shouldNotInheritNestedNamesTheSpecificRequirementDoesNotAddress() {
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
				assertNestedReferenceNotFetched(
					summary, REF_BRANDS, REF_LABELS,
					"The name the specific fetch does not address was inherited from the generic one!"
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
	 * A `referenceContentAll…()` on the reference-specific summary describes every reference of the facet entity,
	 * and no `referenceContent` of the generic summary is inherited beside it - as none is inherited beside any
	 * other specific requirement.
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
	 * summary is the only one written for the reference it names, so its filter wins, and the name only the generic
	 * requirement addresses is not fetched for that reference at all. The generic filter keeps governing every
	 * other faceted reference, both names included.
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
				assertNestedReferenceNotFetched(
					summary, REF_BRANDS, REF_LABELS,
					"The name only the generic requirement addresses was inherited by the specific summary!"
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
	 * A reference-specific summary carrying no entity fetch at all comes back as bare entity references - both for
	 * the facets and for their groups - although the generic summary beside it asks for attributes of each. The
	 * generic summary keeps enriching every other faceted reference.
	 */
	@Test
	@DisplayName("should return bare entity references for a specific summary carrying no fetch")
	void shouldReturnBareEntityReferencesForSpecificSummaryCarryingNoFetch() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(attributeContent(ATTRIBUTE_CODE)),
								entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
							),
							referenceSummaryOfReference(REF_BRANDS, FacetStatisticsDepth.COUNTS)
						)
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				assertInstanceOf(
					EntityReference.class, singleFacetClassifier(summary, REF_BRANDS),
					"The facet entity fetch of the generic summary was inherited by the specific one!"
				);
				assertInstanceOf(
					EntityReference.class, singleGroupClassifier(summary, REF_BRANDS),
					"The group entity fetch of the generic summary was inherited by the specific one!"
				);
				assertEquals(
					"category-" + CATEGORY_PK,
					assertInstanceOf(
						SealedEntity.class, singleFacetClassifier(summary, REF_CATEGORIES),
						"The generic summary stopped applying to the references it was written for!"
					).getAttribute(ATTRIBUTE_CODE),
					"The generic summary stopped applying to the references it was written for!"
				);
				assertEquals(
					"group-" + CATEGORY_GROUP_PK,
					assertInstanceOf(
						SealedEntity.class, singleGroupClassifier(summary, REF_CATEGORIES),
						"The generic summary stopped applying to the references it was written for!"
					).getAttribute(ATTRIBUTE_CODE),
					"The generic summary stopped applying to the references it was written for!"
				);
				return null;
			}
		);
	}

	/**
	 * An empty `entityGroupFetch()` on the reference-specific summary fetches the group body and nothing else - the
	 * attribute the generic summary's own `entityGroupFetch` asks for is not inherited into it.
	 */
	@Test
	@DisplayName("should not inherit the group attributes of the generic summary into an empty group fetch")
	void shouldNotInheritGroupAttributesOfGenericSummaryIntoEmptyGroupFetch() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> result = session.query(
					query(
						collection(ENTITY_PRODUCT),
						require(
							referenceSummary(
								FacetStatisticsDepth.COUNTS,
								entityFetch(attributeContent(ATTRIBUTE_CODE)),
								entityGroupFetch(attributeContent(ATTRIBUTE_CODE))
							),
							referenceSummaryOfReference(
								REF_BRANDS,
								FacetStatisticsDepth.COUNTS,
								entityFetch(attributeContent(ATTRIBUTE_NAME)),
								entityGroupFetch()
							)
						)
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				assertEquals(
					Set.of(ATTRIBUTE_NAME),
					singleFacetEntity(summary, REF_BRANDS).getAttributeNames(),
					"The facet attribute of the generic summary was inherited by the specific one!"
				);
				final SealedEntity brandGroup = assertInstanceOf(
					SealedEntity.class, singleGroupClassifier(summary, REF_BRANDS),
					"The empty group fetch of the specific summary did not fetch the group body!"
				);
				assertThrows(
					ContextMissingException.class,
					brandGroup::getAttributeNames,
					"The group attribute of the generic summary was inherited by the specific one!"
				);
				assertEquals(
					Set.of(ATTRIBUTE_CODE),
					assertInstanceOf(
						SealedEntity.class, singleGroupClassifier(summary, REF_CATEGORIES),
						"The generic summary stopped applying to the references it was written for!"
					).getAttributeNames(),
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
