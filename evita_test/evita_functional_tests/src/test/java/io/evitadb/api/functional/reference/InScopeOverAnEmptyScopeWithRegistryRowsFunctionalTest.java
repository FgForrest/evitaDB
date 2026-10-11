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
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Every product is live; no entity is archived, so the archived scope has no index at all.
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
		assertEquals(
			List.of(1, 2, 3),
			queryProducts(
				evita,
				hierarchyWithin(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(1)),
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1, 2, 3, 4))
			)
		);
	}

	@UseDataSet(DUPLICATE_REFERENCE_ROWS)
	@Test
	@DisplayName("hierarchyWithinRoot over registry rows of a reference allowing duplicates finds every product")
	void shouldFindEveryProductWithAnInScopeContainerOverAnEmptyScope(@Nonnull Evita evita) {
		assertEquals(
			List.of(1, 2, 3, 4),
			queryProducts(
				evita,
				hierarchyWithinRoot(REFERENCE_CATEGORIES),
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1, 2, 3, 4))
			)
		);
	}

	@UseDataSet(DUPLICATE_REFERENCE_ROWS)
	@Test
	@DisplayName("referenceHaving matching registry rows finds the owner of the rows")
	void shouldFindTheOwnerOfTheRowsWithAnInScopeContainerOverAnEmptyScope(@Nonnull Evita evita) {
		assertEquals(
			List.of(1),
			queryProducts(
				evita,
				referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(2)),
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1, 2, 3, 4))
			)
		);
	}

	/**
	 * Returns the products matching the constraints in both scopes, in primary key order.
	 *
	 * @param evita       the engine instance
	 * @param constraints the filter constraints following the scope
	 * @return the primary keys of the matching products
	 */
	@Nonnull
	private static List<Integer> queryProducts(@Nonnull Evita evita, @Nonnull FilterConstraint... constraints) {
		final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
		filter[0] = scope(Scope.LIVE, Scope.ARCHIVED);
		System.arraycopy(constraints, 0, filter, 1, constraints.length);
		final List<EntityReference> products = evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, List<EntityReference>>) session -> session.queryList(
				query(collection(ENTITY_PRODUCT), filterBy(filter), require(page(1, 100))),
				EntityReference.class
			)
		);
		return products.stream().map(EntityReference::getPrimaryKey).sorted().toList();
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
