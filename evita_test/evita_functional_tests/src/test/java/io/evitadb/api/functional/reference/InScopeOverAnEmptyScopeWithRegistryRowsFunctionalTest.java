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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a query over more scopes than hold any entity, with an `inScope` container next to a `hierarchyWithin` or a
 * `referenceHaving` of a reference whose matching partitions include single-owner ones - registry rows without an index
 * object.
 *
 * The container asks for the entities of every requested scope. A scope that holds no entity has no global index, so
 * the superset of that scope is looked up among every candidate index set of the query (see
 * `FilterByVisitor#getSuperSetFormula(Scope)`) - the hierarchy and the `referenceHaving` options included, each of
 * which resolves the index objects of its partitions on demand. A set holding a registry row has no index object to
 * resolve for it, so the lookup must leave such a set alone.
 *
 * ## The fixture
 *
 * `category` 1 is a root with the child 2, `category` 3 another root. `product.categories` allows duplicates, told
 * apart by the representative attribute `country`, and is indexed for filtering in both scopes:
 *
 * | product | `categories` | partitions |
 * |---|---|---|
 * | 1 | 2 `CZ`, 2 `DE` | two single-owner partitions of category 2 - two registry rows |
 * | 2 | 1 `CZ` | the partition `1 CZ`, shared with product 3 - an index object |
 * | 3 | 1 `CZ` | |
 * | 4 | 3 `CZ` | a single-owner partition outside the tree of category 1 |
 *
 * Every product is live; no entity is archived, so the archived scope has no index at all. Every query adds
 * `inScope(LIVE, entityPrimaryKeyInSet(1, 2, 3))`, and every expected answer is computed from the bodies of the
 * products and of the categories, never from another query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("an inScope container over a scope without entities next to partitions kept as registry rows")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(HIERARCHY)
@Tag(REFERENCE)
@Tag(FILTER)
public class InScopeOverAnEmptyScopeWithRegistryRowsFunctionalTest {
	private static final String DUPLICATE_REFERENCE_ROWS = "inScopeOverAnEmptyScopeWithRegistryRows";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_PRODUCT = "product";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_COUNTRY = "country";
	/**
	 * The live products the `inScope(LIVE, ...)` container of every query admits - product 4 is left out, so the
	 * container restricts the live scope rather than standing for all of it.
	 */
	private static final int[] IN_SCOPE_LIVE_PRODUCTS = {1, 2, 3};
	/**
	 * The only owner of the registry rows of category 2 - every case must answer it, or it would not reach a row.
	 */
	private static final int ROW_OWNER = 1;

	/**
	 * Builds the fixture described in the class documentation.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = DUPLICATE_REFERENCE_ROWS, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchyIndexedInScope(Scope.values())
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.values())
							.withAttribute(
								ATTRIBUTE_COUNTRY, String.class,
								thatIs -> thatIs.filterableInScope(Scope.values()).representative()
							)
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 1));
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 2).setParent(1));
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 3));
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setOrUpdateReference(
							REFERENCE_CATEGORIES, 2, Functions.alwaysFalse(),
							whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "CZ")
						)
						.setOrUpdateReference(
							REFERENCE_CATEGORIES, 2, Functions.alwaysFalse(),
							whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "DE")
						)
				);
				for (int productPk = 2; productPk <= 3; productPk++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, productPk)
							.setOrUpdateReference(
								REFERENCE_CATEGORIES, 1, Functions.alwaysFalse(),
								whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "CZ")
							)
					);
				}
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 4)
						.setOrUpdateReference(
							REFERENCE_CATEGORIES, 3, Functions.alwaysFalse(),
							whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "CZ")
						)
				);
			}
		);
		// fixture guard: the partitions `2 CZ`, `2 DE` and `3 CZ` are registry rows, `1 CZ` is an index object
		assertEquals(
			3, countRegistryRows(evita),
			"Fixture guard: every single-owner partition must be a registry row!"
		);
	}

	@UseDataSet(DUPLICATE_REFERENCE_ROWS)
	@Test
	@DisplayName("hierarchyWithin over a node with registry rows of a reference allowing duplicates finds the subtree")
	void shouldFindTheProductsOfTheSubtreeWithAnInScopeContainerOverAnEmptyScope(@Nonnull Evita evita) {
		final Set<Integer> subtree = categoriesUnder(evita, 1);
		assertAnswer(
			evita, product -> referencesAnyOf(product, subtree),
			hierarchyWithin(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(1))
		);
	}

	@UseDataSet(DUPLICATE_REFERENCE_ROWS)
	@Test
	@DisplayName("hierarchyWithinRoot over registry rows of a reference allowing duplicates finds every product")
	void shouldFindEveryProductWithAnInScopeContainerOverAnEmptyScope(@Nonnull Evita evita) {
		final Set<Integer> everyNode = categoriesUnder(evita, null);
		assertAnswer(
			evita, product -> referencesAnyOf(product, everyNode),
			hierarchyWithinRoot(REFERENCE_CATEGORIES)
		);
	}

	@UseDataSet(DUPLICATE_REFERENCE_ROWS)
	@Test
	@DisplayName("referenceHaving matching registry rows finds the owner of the rows")
	void shouldFindTheOwnerOfTheRowsWithAnInScopeContainerOverAnEmptyScope(@Nonnull Evita evita) {
		assertAnswer(
			evita, product -> referencesAnyOf(product, Set.of(2)),
			referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(2))
		);
	}

	/**
	 * Asserts that the query over both scopes with the constraint and the `inScope(LIVE, ...)` container answers
	 * exactly the products the entity bodies say: the live products the container admits and every archived product,
	 * of those the ones the predicate accepts. The answer must hold {@link #ROW_OWNER}, so the case reaches a row.
	 *
	 * @param evita      the engine instance
	 * @param matches    decides from the body of a product whether the constraint matches it
	 * @param constraint the constraint of the case
	 */
	private static void assertAnswer(
		@Nonnull Evita evita,
		@Nonnull Predicate<SealedEntity> matches,
		@Nonnull FilterConstraint constraint
	) {
		final List<Integer> expected = new ArrayList<>(8);
		for (final SealedEntity product : queryBodies(evita, ENTITY_PRODUCT, entityFetch(referenceContentAll()))) {
			final boolean admitted = product.getScope() != Scope.LIVE ||
				ArrayUtils.contains(IN_SCOPE_LIVE_PRODUCTS, product.getPrimaryKeyOrThrowException());
			if (admitted && matches.test(product)) {
				expected.add(product.getPrimaryKeyOrThrowException());
			}
		}
		expected.sort(Comparator.naturalOrder());
		assertTrue(expected.contains(ROW_OWNER), "Premise: the case must reach the registry rows: " + expected);
		assertEquals(expected, queryProducts(evita, constraint));
	}

	/**
	 * Returns the products matching the constraint and the `inScope(LIVE, ...)` container in both scopes, in primary
	 * key order.
	 *
	 * @param evita      the engine instance
	 * @param constraint the constraint of the case
	 * @return the primary keys of the matching products
	 */
	@Nonnull
	private static List<Integer> queryProducts(@Nonnull Evita evita, @Nonnull FilterConstraint constraint) {
		final List<EntityReference> products = evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, List<EntityReference>>) session -> session.queryList(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						scope(Scope.LIVE, Scope.ARCHIVED),
						constraint,
						inScope(Scope.LIVE, entityPrimaryKeyInSet(IN_SCOPE_LIVE_PRODUCTS))
					),
					require(page(1, 100))
				),
				EntityReference.class
			)
		);
		return products.stream().map(EntityReference::getPrimaryKey).sorted().toList();
	}

	/**
	 * Returns the bodies of every entity of the type in both scopes.
	 *
	 * @param evita       the engine instance
	 * @param entityType  the entity type
	 * @param entityFetch the content to fetch
	 * @return the bodies
	 */
	@Nonnull
	private static List<SealedEntity> queryBodies(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull EntityFetch entityFetch
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.queryListOfSealedEntities(
				query(
					collection(entityType),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
					require(page(1, 100), entityFetch)
				)
			)
		);
	}

	/**
	 * Returns the categories in the subtree of a node, the node included, read from the parents in their bodies.
	 *
	 * @param evita the engine instance
	 * @param root  the primary key of the node, `null` for every node of the hierarchy
	 * @return the primary keys of the categories
	 */
	@Nonnull
	private static Set<Integer> categoriesUnder(@Nonnull Evita evita, @Nullable Integer root) {
		final Map<Integer, Integer> parents = new HashMap<>(8);
		for (final SealedEntity category : queryBodies(evita, ENTITY_CATEGORY, entityFetch(hierarchyContent()))) {
			parents.put(
				category.getPrimaryKeyOrThrowException(),
				category.getParentEntity().map(EntityClassifier::getPrimaryKeyOrThrowException).orElse(null)
			);
		}
		final Set<Integer> result = new TreeSet<>();
		for (final Integer category : parents.keySet()) {
			Integer node = category;
			while (node != null && root != null && !node.equals(root)) {
				node = parents.get(node);
			}
			if (node != null) {
				result.add(category);
			}
		}
		return result;
	}

	/**
	 * Tells whether a product body holds a reference of `categories` to any of the categories.
	 *
	 * @param product    the body of the product
	 * @param categories the primary keys of the categories
	 * @return TRUE when a row of the product points to one of them
	 */
	private static boolean referencesAnyOf(@Nonnull SealedEntity product, @Nonnull Set<Integer> categories) {
		for (final ReferenceContract reference : product.getReferences(REFERENCE_CATEGORIES)) {
			if (categories.contains(reference.getReferencedPrimaryKey())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns how many registry rows the live referenced-type index of `product.categories` holds, as the public index
	 * statistics report it.
	 *
	 * @param evita the engine instance
	 * @return the number of single-owner partitions kept as registry rows
	 */
	private static int countRegistryRows(@Nonnull Evita evita) {
		final CollectionIndexCardinality cardinality = evita.management()
			.getEntityCollectionStatistics(
				TEST_CATALOG, ENTITY_PRODUCT, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY)
			)
			.indexCardinalityIfPresent()
			.orElseThrow();
		for (final IndexCardinality index : cardinality.indexes()) {
			if (index.indexType() == EntityIndexType.REFERENCED_ENTITY_TYPE && index.scope() == Scope.LIVE) {
				return index.virtualPartitionCountIfKnown().orElseThrow();
			}
		}
		throw new AssertionError("No live referenced-type index was described: " + cardinality);
	}

}
