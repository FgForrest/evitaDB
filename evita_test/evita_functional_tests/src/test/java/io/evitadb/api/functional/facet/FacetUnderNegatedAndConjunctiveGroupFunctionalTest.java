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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reference summary of a facet referenced under a negated group by some entities and under a conjunctive
 * group by others. The group is a property of the reference, so the facet is listed once for each of its groups:
 *
 * - the count of an entry is the number of entities of the result without the user filter that the facet selected in
 *   the group of the entry alone would return - the entities referencing it under that group, and under a negated
 *   group the inverse, the entities not referencing it under that group
 * - the impact of an entry is the result of selecting the facet, which selects it in every group it is referenced
 *   under, so it is the same for both entries: the entities referencing it under the conjunctive group and not
 *   referencing it under the negated one
 *
 * ## The fixture
 *
 * Six products; the tag `sale` is referenced by products 1 and 2 under the group `Excluded`, which the query negates,
 * and by product 3 under the group `Highlights`, conjunctive by default. Products 4 to 6 reference no tag.
 *
 * | product | sale referenced under |
 * |---------|-----------------------|
 * | 1       | Excluded              |
 * | 2       | Excluded              |
 * | 3       | Highlights            |
 * | 4 - 6   |                       |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Facet referenced under a negated and a conjunctive group")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FACET)
public class FacetUnderNegatedAndConjunctiveGroupFunctionalTest {
	private static final String NEGATED_AND_CONJUNCTIVE_GROUP = "facetUnderNegatedAndConjunctiveGroup";
	private static final String ENTITY_PRODUCT = "groupedTagProduct";
	private static final String ENTITY_TAG = "groupedTag";
	private static final String ENTITY_TAG_GROUP = "externalTagGroup";
	private static final String REF_TAGS = "tags";
	private static final int PRODUCT_COUNT = 6;
	/**
	 * The tag `sale`.
	 */
	private static final int TAG_SALE = 1;
	/**
	 * The group the query negates.
	 */
	private static final int GROUP_EXCLUDED = 1;
	/**
	 * The group left conjunctive.
	 */
	private static final int GROUP_HIGHLIGHTS = 2;

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = NEGATED_AND_CONJUNCTIVE_GROUP, destroyAfterClass = true)
	void setUpNegatedAndConjunctiveGroupDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TAG).withoutGeneratedPrimaryKey().updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, TAG_SALE));
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.faceted()
							.withGroupType(ENTITY_TAG_GROUP)
					)
					.updateVia(session);
				for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
					final int groupPk = productPk <= 2 ? GROUP_EXCLUDED : GROUP_HIGHLIGHTS;
					if (productPk <= 3) {
						session.upsertEntity(
							session.createNewEntity(ENTITY_PRODUCT, productPk)
								.setReference(REF_TAGS, TAG_SALE, whichIs -> whichIs.setGroup(groupPk))
						);
					} else {
						session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, productPk));
					}
				}
			}
		);
	}

	@DisplayName("Should count each entry of the facet by its group and predict the same selection for both")
	@UseDataSet(NEGATED_AND_CONJUNCTIVE_GROUP)
	@Test
	void shouldCountEachEntryByItsGroupAndPredictTheSameSelectionForBoth(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// the oracle of the impact: selecting `sale` returns the product referencing it under `Highlights`
				// and not under `Excluded`
				final EvitaResponse<EntityReference> selected = session.query(
					productQuery(userFilter(facetHaving(REF_TAGS, entityPrimaryKeyInSet(TAG_SALE))), null),
					EntityReference.class
				);
				assertEquals(
					List.of(3),
					selected.getRecordData().stream().map(EntityReference::getPrimaryKey).toList()
				);

				final EvitaResponse<EntityReference> withSummary = session.query(
					productQuery(null, referenceSummaryOfReference(REF_TAGS, FacetStatisticsDepth.IMPACT)),
					EntityReference.class
				);
				assertEquals(PRODUCT_COUNT, withSummary.getTotalRecordCount());
				final ReferenceSummary summary = withSummary.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary, "the reference summary must be computed");

				// the negated entry counts the products not referencing `sale` under `Excluded` - products 3 to 6
				assertEntry(summary, GROUP_EXCLUDED, 4);
				// the conjunctive entry counts the products referencing `sale` under `Highlights` - product 3
				assertEntry(summary, GROUP_HIGHLIGHTS, 1);
				return null;
			}
		);
	}

	/**
	 * Asserts the entry of `sale` in the group: its count, and the impact of selecting `sale` - product 3 alone,
	 * the same for both entries.
	 *
	 * @param summary       the reference summary of the query without a selection
	 * @param groupId       the group of the entry
	 * @param expectedCount the expected count of the entry
	 */
	private static void assertEntry(@Nonnull ReferenceSummary summary, int groupId, int expectedCount) {
		final ReferenceGroupStatistics groupStatistics = summary.getReferenceGroupStatistics(REF_TAGS, groupId);
		assertNotNull(groupStatistics, "the group " + groupId + " must be listed");
		final FacetStatistics statistics = groupStatistics.getFacetStatistics(TAG_SALE);
		assertNotNull(statistics, "the tag `sale` must be listed in the group " + groupId);
		assertEquals(expectedCount, statistics.getCount(), "the count of `sale` in the group " + groupId);
		final RequestImpact impact = statistics.getImpact();
		assertNotNull(impact, "the impact of `sale` in the group " + groupId + " must be predicted");
		assertEquals(1, impact.matchCount(), "the products selecting `sale` returns, entry of the group " + groupId);
		assertEquals(
			1 - PRODUCT_COUNT, impact.difference(),
			"the difference selecting `sale` makes, entry of the group " + groupId
		);
		assertTrue(impact.hasSense(), "selecting `sale` makes sense, entry of the group " + groupId);
	}

	/**
	 * Builds the query of the products negating the group `Excluded` of the tags.
	 *
	 * @param userFilter the user filter of the query, NULL for none
	 * @param summary    the reference summary to compute, NULL for none
	 * @return the query
	 */
	@Nonnull
	private static Query productQuery(@Nullable FilterConstraint userFilter, @Nullable RequireConstraint summary) {
		return query(
			collection(ENTITY_PRODUCT),
			filterBy(userFilter),
			require(
				page(1, PRODUCT_COUNT),
				facetGroupsNegation(REF_TAGS, filterBy(entityPrimaryKeyInSet(GROUP_EXCLUDED))),
				summary
			)
		);
	}

}
