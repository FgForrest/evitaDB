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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.functional.indexing.IndexingTestSupport;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfReference;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integrity guard for the shared `BIDI_REWRITE` fixture.
 *
 * The fixture class itself carries no test methods, so nothing would ever build it — and none of its `assertEquals`
 * gate guards would ever run — until one of the four suites that depend on it is executed. This class exists to make
 * that cheap and unconditional: it asks for the dataset, checks that the four carrier payloads are the sizes every
 * other suite computes its expected sets from, and asks one query that only answers non-empty when the reflected
 * `CATEGORY.products` rows really were materialised.
 *
 * It deliberately asserts nothing about the *rewrite* — that is what the sibling suites are for. If this class goes
 * red, the fixture is broken and every red row in those suites is uninformative.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("BIDI_REWRITE shared fixture integrity")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public class BidiRewriteFixtureSmokeTest extends AbstractBidirectionalReferenceRewriteFunctionalTest {

	@DisplayName("Should build the shared fixture with the contracted payload sizes and reflected rows")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldBuildTheSharedFixture(
		Evita evita,
		List<SealedEntity> originalCategories,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalBrands,
		List<SealedEntity> originalTaxonomy
	) {
		assertEquals(CATEGORY_COUNT, originalCategories.size(), "The carrier must expose all 12 categories.");
		assertEquals(PRODUCT_COUNT, originalProducts.size(), "The carrier must expose all 240 products.");
		assertEquals(BRAND_COUNT, originalBrands.size(), "The carrier must expose all 8 brands.");
		assertEquals(TAXONOMY_COUNT, originalTaxonomy.size(), "The carrier must expose all 6 taxonomy nodes.");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(referenceHaving(REF_CATEGORY_PRODUCTS)),
						require(entityFetch(), page(1, Integer.MAX_VALUE))
					)
				);
				assertFalse(
					result.getRecordData().isEmpty(),
					"The reflected `" + REF_CATEGORY_PRODUCTS + "` rows were not materialised - no category matches."
				);
				return null;
			}
		);
	}

	/**
	 * Reports, as fact rather than as inference, the index shape the rewrite gate arithmetic is read off.
	 *
	 * `worthRewriting` weighs two O(1) cardinalities against each other: the **candidates** side is
	 * `getAllReferencedPrimaryKeys().size()` of the *counterpart's* `REFERENCED_ENTITY_TYPE` index in the target
	 * collection, and the **buckets** side is `getAllPrimaryKeys().size()` of the *owner's* own type index. Both
	 * are printed here for every pair the fixture is built around, in both scopes, so that a row that claims a gate
	 * verdict can be checked against the catalog instead of against the contract.
	 *
	 * The last block is the one the fixture's whole archived-scope story rests on. Category 12 is `ARCHIVED` and
	 * references live products 1-10, so that relation spans both scopes; the two reduced-index probes say which
	 * scope's index family actually holds each half of it.
	 *
	 * @param evita the evitaDB instance holding the shared dataset
	 */
	@DisplayName("Should expose the index shape the gate arithmetic assumes")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldExposeTheIndexShapeTheGateArithmeticAssumes(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollectionContract products = catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityCollectionContract categories = catalog.getCollectionForEntity(Entities.CATEGORY).orElseThrow();

		for (final Scope scope : Scope.values()) {
			reportTypeIndex(products, scope, REF_PRODUCT_CATEGORIES, true);
			reportTypeIndex(categories, scope, REF_CATEGORY_PRODUCTS, false);
			reportTypeIndex(products, scope, REF_PRODUCT_CURATED_BY, true);
			reportTypeIndex(categories, scope, REF_CATEGORY_CURATED, false);
			reportTypeIndex(products, scope, REF_PRODUCT_WEAK_TAGS, true);
			reportTypeIndex(categories, scope, REF_CATEGORY_WEAK_PRODUCTS, false);
			reportTypeIndex(products, scope, REF_TAXONOMY_STATS, true);
			reportTypeIndex(categories, scope, REF_TAXONOMY_STATS, true);
			reportTypeIndex(products, scope, REF_PRODUCT_SCOPED_CATEGORIES, true);
			reportTypeIndex(categories, scope, REF_CATEGORY_SCOPED_PRODUCTS, false);
		}

		// the candidates side of the two gate rows the family turns on - these are the numbers, measured
		assertEquals(
			11, referencedPrimaryKeyCount(products, Scope.LIVE, REF_PRODUCT_CATEGORIES),
			"LIVE candidates for collection(CATEGORY) + referenceHaving(products) must be 11."
		);
		assertEquals(
			1, referencedPrimaryKeyCount(products, Scope.ARCHIVED, REF_PRODUCT_CATEGORIES),
			"ARCHIVED candidates for collection(CATEGORY) + referenceHaving(products) must be 1."
		);
		assertEquals(
			12, referencedPrimaryKeyCount(products, Scope.LIVE, REF_PRODUCT_CURATED_BY),
			"LIVE candidates for collection(CATEGORY) + curated must be 12."
		);
		assertEquals(
			WEAK_TAG_CATEGORY_COUNT, referencedPrimaryKeyCount(products, Scope.LIVE, REF_PRODUCT_WEAK_TAGS),
			"LIVE candidates for collection(CATEGORY) + weakProducts must be 6."
		);
		// the archived counterpart type index that keeps the ReferenceNotIndexedException row non-vacuous
		assertEquals(
			ROUND_ROBIN_CATEGORY_COUNT,
			referencedPrimaryKeyCount(products, Scope.ARCHIVED, REF_PRODUCT_SCOPED_CATEGORIES),
			"The ARCHIVED `" + REF_PRODUCT_SCOPED_CATEGORIES + "` type index must exist and reach 10 categories."
		);
		// the LIVE-only reflected end must genuinely be absent in the archived family
		assertNull(
			IndexingTestSupport.getReferencedEntityTypeIndex(categories, Scope.ARCHIVED, REF_CATEGORY_SCOPED_PRODUCTS),
			"`" + REF_CATEGORY_SCOPED_PRODUCTS + "` must have no ARCHIVED type index - it is declared LIVE only."
		);

		// the cross-scope relation: category 12 (ARCHIVED) referencing products 1-10 (LIVE). Which scope's index
		// family holds each half?
		for (final Scope scope : Scope.values()) {
			final EntityIndex ownerBucket = IndexingTestSupport.getReferencedEntityIndex(
				categories, scope, REF_CATEGORY_PRODUCTS, 1
			);
			final EntityIndex counterpartBucket = IndexingTestSupport.getReferencedEntityIndex(
				products, scope, REF_PRODUCT_CATEGORIES, ARCHIVED_CATEGORY_PK
			);
			log.info(
				"BIDI_REWRITE reduced [{}] CATEGORY({}, 1)={} PRODUCT({}, {})={}",
				scope, REF_CATEGORY_PRODUCTS,
				ownerBucket == null ? "ABSENT" : String.valueOf(ownerBucket.getAllPrimaryKeys()),
				REF_PRODUCT_CATEGORIES, ARCHIVED_CATEGORY_PK,
				counterpartBucket == null ? "ABSENT" : String.valueOf(counterpartBucket.getAllPrimaryKeys())
			);
		}

		// the counterpart half of the cross-scope relation lives in the LIVE family, because the owning products
		// 1-10 are live - this is what the archived-scope candidate set therefore cannot see
		assertNotNull(
			IndexingTestSupport.getReferencedEntityIndex(
				products, Scope.LIVE, REF_PRODUCT_CATEGORIES, ARCHIVED_CATEGORY_PK
			),
			"The reduced index for (categories, 12) must exist in the LIVE family - products 1-10 are live."
		);
	}

	/**
	 * Proves that the deliberately filtering-only `taxonomyStats` reference is nevertheless a legal target for
	 * `hierarchyOfReference`. The hierarchy-statistics row is only worth writing if the query shape it needs is
	 * accepted at all, and a rejection here would have to be resolved by changing the fixture rather than the row.
	 *
	 * @param evita the evitaDB instance holding the shared dataset
	 */
	@DisplayName("Should accept hierarchyOfReference against the filtering-only statistics reference")
	@UseDataSet(BIDI_REWRITE)
	@Test
	void shouldAcceptHierarchyOfReferenceAgainstTheFilteringOnlyStatisticsReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(referenceHaving(REF_CATEGORY_PRODUCTS)),
						require(
							entityFetch(),
							hierarchyOfReference(
								REF_TAXONOMY_STATS,
								fromRoot("wholeTree", entityFetchAll(), statistics(StatisticsType.QUERIED_ENTITY_COUNT))
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);
				assertNotNull(
					result.getExtraResult(Hierarchy.class),
					"`hierarchyOfReference` must produce a hierarchy extra result for `" + REF_TAXONOMY_STATS + "`."
				);
				return null;
			}
		);
	}

	/**
	 * Prints one `REFERENCED_ENTITY_TYPE` index census line, and the contents of its referenced-primary-key set
	 * when that set is small enough to be readable.
	 *
	 * @param collection    collection owning the index
	 * @param scope         scope to look the index up in
	 * @param referenceName name of the reference
	 * @param printContents whether the referenced primary keys should be printed as well
	 */
	private static void reportTypeIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		boolean printContents
	) {
		final ReferencedTypeEntityIndex index = typeIndex(collection, scope, referenceName);
		if (index == null) {
			log.info("BIDI_REWRITE type [{}] {}.{} ABSENT", scope, collection.getEntityType(), referenceName);
		} else {
			log.info(
				"BIDI_REWRITE type [{}] {}.{} referencedPks={} allPks={}{}",
				scope, collection.getEntityType(), referenceName,
				index.getAllReferencedPrimaryKeys().size(), index.getAllPrimaryKeys().size(),
				printContents ? " contents=" + index.getAllReferencedPrimaryKeys() : ""
			);
		}
	}

	/**
	 * Returns the size of the referenced-primary-key set of a `REFERENCED_ENTITY_TYPE` index, or `-1` when the
	 * index does not exist in the requested scope.
	 *
	 * @param collection    collection owning the index
	 * @param scope         scope to look the index up in
	 * @param referenceName name of the reference
	 * @return the candidate cardinality, or -1 when the index is absent
	 */
	private static int referencedPrimaryKeyCount(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final ReferencedTypeEntityIndex index = typeIndex(collection, scope, referenceName);
		return index == null ? -1 : index.getAllReferencedPrimaryKeys().size();
	}

	/**
	 * Looks a `REFERENCED_ENTITY_TYPE` index up and narrows it to its concrete type.
	 *
	 * @param collection    collection owning the index
	 * @param scope         scope to look the index up in
	 * @param referenceName name of the reference
	 * @return the index, or null when it does not exist in that scope
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndex(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndex index = IndexingTestSupport.getReferencedEntityTypeIndex(collection, scope, referenceName);
		if (index == null) {
			return null;
		}
		if (index instanceof ReferencedTypeEntityIndex typeIndex) {
			return typeIndex;
		}
		throw new GenericEvitaInternalError(
			"A REFERENCED_ENTITY_TYPE index key must always resolve to a ReferencedTypeEntityIndex, got `" +
				index.getClass().getName() + "`!"
		);
	}

}
