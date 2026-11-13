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
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the selection of facets whose group terms are merged across the live and the archived scope. A query over
 * both scopes collects the term of a facet group from the index of each scope and merges the terms of one group into
 * one; a facet both scopes reference gets the union of the two scopes' entities as a plain bitmap, no longer the
 * transactional bitmap of a facet index.
 *
 * ## The fixture
 *
 * The primary key of the tag group `1` equals the primary key of the tag `1`, and the same layout of references is
 * repeated in both scopes:
 *
 * | product | scope    | tag 1 referenced under | tag 2 referenced under |
 * |---------|----------|------------------------|------------------------|
 * | 1       | LIVE     | no group               | group 1                |
 * | 2       | LIVE     |                        | group 1                |
 * | 3       | LIVE     |                        | no group               |
 * | 4       | LIVE     | no group               |                        |
 * | 5       | ARCHIVED | no group               | group 1                |
 * | 6       | ARCHIVED |                        | group 1                |
 * | 7       | ARCHIVED |                        | no group               |
 * | 8       | ARCHIVED | no group               |                        |
 *
 * Selecting tags 1 and 2 yields two group terms joined by a conjunction: group 1 holding tag 2, and the facets without
 * a group holding tags 1 and 2. Only products 1 and 5 satisfy both.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Facet group terms merged across scopes")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FACET)
@Tag(FILTER)
public class FacetGroupTermsAcrossScopesFunctionalTest {
	private static final String FACET_GROUP_TERMS_ACROSS_SCOPES = "facetGroupTermsAcrossScopes";
	private static final String ENTITY_PRODUCT = "scopedTermProduct";
	private static final String ENTITY_TAG = "scopedTermTag";
	private static final String ENTITY_TAG_GROUP = "scopedTermTagGroup";
	private static final String REF_TAGS = "tags";
	/**
	 * The tag whose primary key equals the primary key of {@link #GROUP}.
	 */
	private static final int TAG_ONE = 1;
	/**
	 * The tag referenced both under {@link #GROUP} and without a group.
	 */
	private static final int TAG_TWO = 2;
	/**
	 * The only tag group.
	 */
	private static final int GROUP = 1;
	/**
	 * Marks a product that does not reference the tag of the column in {@link #PRODUCT_TAG_GROUPS}.
	 */
	private static final int NOT_REFERENCED = -1;
	/**
	 * Marks a reference without a group in {@link #PRODUCT_TAG_GROUPS}.
	 */
	private static final int NO_GROUP = 0;
	/**
	 * The group under which each product references tag 1 (first column) and tag 2 (second column), indexed by the
	 * product primary key minus one.
	 */
	private static final int[][] PRODUCT_TAG_GROUPS = {
		{NO_GROUP, GROUP}, {NOT_REFERENCED, GROUP}, {NOT_REFERENCED, NO_GROUP}, {NO_GROUP, NOT_REFERENCED},
		{NO_GROUP, GROUP}, {NOT_REFERENCED, GROUP}, {NOT_REFERENCED, NO_GROUP}, {NO_GROUP, NOT_REFERENCED}
	};
	/**
	 * The first product moved to the archived scope - products from this one on are archived.
	 */
	private static final int FIRST_ARCHIVED_PRODUCT = 5;

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = FACET_GROUP_TERMS_ACROSS_SCOPES, destroyAfterClass = true)
	void setUpFacetGroupTermsAcrossScopes(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TAG_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TAG_GROUP, GROUP));
				session.defineEntitySchema(ENTITY_TAG).withoutGeneratedPrimaryKey().updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, TAG_ONE));
				session.upsertEntity(session.createNewEntity(ENTITY_TAG, TAG_TWO));
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_TAGS, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.values())
							.facetedInScope(Scope.values())
							.withGroupTypeRelatedToEntity(ENTITY_TAG_GROUP)
					)
					.updateVia(session);
				final int[] tags = {TAG_ONE, TAG_TWO};
				for (int productPk = 1; productPk <= PRODUCT_TAG_GROUPS.length; productPk++) {
					final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, productPk);
					for (int i = 0; i < tags.length; i++) {
						final int groupId = PRODUCT_TAG_GROUPS[productPk - 1][i];
						if (groupId == NO_GROUP) {
							product.setReference(REF_TAGS, tags[i]);
						} else if (groupId != NOT_REFERENCED) {
							product.setReference(REF_TAGS, tags[i], whichIs -> whichIs.setGroup(groupId));
						}
					}
					session.upsertEntity(product);
					if (productPk >= FIRST_ARCHIVED_PRODUCT) {
						session.archiveEntity(ENTITY_PRODUCT, productPk);
					}
				}
			}
		);
	}

	/**
	 * Selects tags 1 and 2 over both scopes. Each scope's index yields the term of group 1 holding tag 2 and the term
	 * of the facets without a group holding tags 1 and 2, and the terms of one group from the two scopes merge into
	 * one whose entity bitmaps are plain bitmaps. The hash of a facet group formula folds the reference name, the group
	 * id when present, the facet ids and the ids of transactional operand bitmaps into one flat array: plain operand
	 * bitmaps contribute nothing, and nothing marks where the group id ends and the facet ids begin. Both merged terms
	 * then hash `[tags, 1, 2]`, the formula deduplicator, which runs on every query, keeps only one of them, and the
	 * conjunction of the two terms collapses into one term. The expected answer is the union of the answers of the
	 * two scopes queried alone - in one scope the terms keep their transactional bitmaps and stay apart.
	 */
	@DisplayName("Should select products of both scopes matching the term of a group and of the facets without a group")
	@UseDataSet(FACET_GROUP_TERMS_ACROSS_SCOPES)
	@Test
	void shouldSelectProductsOfBothScopesMatchingGroupTermAndUngroupedTerm(@Nonnull Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> liveProducts = queryProductsSelectingBothTags(session, Scope.LIVE);
				final Set<Integer> archivedProducts = queryProductsSelectingBothTags(session, Scope.ARCHIVED);
				final Set<Integer> expectedProducts = new HashSet<>(liveProducts);
				expectedProducts.addAll(archivedProducts);
				assertEquals(Set.of(1, 5), expectedProducts, "each scope alone selects its product referencing both terms");

				assertEquals(
					expectedProducts,
					queryProductsSelectingBothTags(session, Scope.LIVE, Scope.ARCHIVED)
				);
				return null;
			}
		);
	}

	/**
	 * Returns the primary keys of the products selecting tags 1 and 2 in the passed scopes.
	 *
	 * @param session the session to query in
	 * @param scopes  the scopes to query
	 * @return the primary keys of the matching products
	 */
	@Nonnull
	private static Set<Integer> queryProductsSelectingBothTags(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope... scopes
	) {
		return session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						scope(scopes),
						facetHaving(REF_TAGS, entityPrimaryKeyInSet(TAG_ONE, TAG_TWO))
					),
					require(page(1, PRODUCT_TAG_GROUPS.length))
				),
				EntityReference.class
			)
			.getRecordData()
			.stream()
			.map(EntityReference::getPrimaryKey)
			.collect(Collectors.toSet());
	}

}
