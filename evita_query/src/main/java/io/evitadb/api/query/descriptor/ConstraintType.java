/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.TypeDefiningConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines the high-level category of a query constraint, determining its role in the query execution model. Each
 * type corresponds to a marker interface that all constraints of that type must implement, enabling compile-time
 * type safety and runtime categorization.
 *
 * **Query Execution Model**
 *
 * evitaDB queries are structured into four distinct phases, each represented by a constraint type:
 * 1. **Header** (`{@link #HEAD}`): Metadata about the query itself (collection name, etc.)
 * 2. **Filtering** (`{@link #FILTER}`): Narrows the result set based on predicates
 * 3. **Ordering** (`{@link #ORDER}`): Defines the sort order of results
 * 4. **Requirements** (`{@link #REQUIRE}`): Specifies additional data to fetch and result formatting
 *
 * This separation enables:
 * - Clear query intent and readability
 * - Independent processing pipelines for each phase
 * - Parallel execution where possible (filtering and requirement pre-fetching)
 * - Type-safe query construction via marker interfaces
 *
 * **Type Resolution**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} determines each constraint's
 * type by checking which marker interface it implements. The `{@link #getRepresentingInterface()}` method returns
 * the interface that defines each type. Every constraint class must implement exactly one type-defining interface.
 *
 * **API Schema Generation**
 *
 * External API builders (GraphQL, REST) use constraint types to organize schema namespaces:
 * - GraphQL: Separate input types for `filterBy`, `orderBy`, and `require` arguments
 * - REST: Different URL parameter structures for each type
 * - Documentation: Constraints are grouped by type in user guides
 *
 * **Example Usage**
 *
 * ```
 * query(
 *     collection("Product"),           // HEAD constraint
 *     filterBy(                         // FILTER container
 *         and(
 *             attributeEquals("code", "abc"),
 *             priceBetween(100, 200)
 *         )
 *     ),
 *     orderBy(                          // ORDER container
 *         attributeNatural("priority")
 *     ),
 *     require(                          // REQUIRE container
 *         entityFetch(
 *             attributeContent(),
 *             priceContent()
 *         )
 *     )
 * )
 * ```
 *
 * **Related Classes**
 *
 * - `{@link TypeDefiningConstraint}` - Parent marker interface for all type-defining interfaces
 * - `{@link HeadConstraint}`, `{@link FilterConstraint}`, `{@link OrderConstraint}`, `{@link RequireConstraint}` -
 * Type-defining marker interfaces
 * - `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` - Resolves constraint type at startup
 * - `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor}` - Runtime descriptor containing the resolved type
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
@Getter
public enum ConstraintType {

	/**
	 * Marks constraints that provide query metadata, such as which collection to query.
	 *
	 * **Typical Constraints:**
	 * - `collection` - specifies the target entity collection
	 *
	 * **Execution Phase:**
	 * Processed first, before filtering or ordering, to establish the query context.
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link io.evitadb.api.query.HeadConstraint}`.
	 */
	HEAD(HeadConstraint.class),
	/**
	 * Marks constraints that filter entities, narrowing the result set based on predicates.
	 *
	 * **Typical Constraints:**
	 * - Logical combinators: `and`, `or`, `not`
	 * - Attribute filters: `attributeEquals`, `attributeBetween`, `attributeInSet`
	 * - Price filters: `priceBetween`, `priceInCurrency`, `priceInPriceLists`
	 * - Reference filters: `referenceHaving`, `facetHaving`
	 * - Hierarchy filters: `hierarchyWithin`, `hierarchyWithinRoot`
	 * - Entity filters: `entityPrimaryKeyInSet`, `entityLocaleEquals`
	 *
	 * **Execution Phase:**
	 * Executed early in the query pipeline to reduce the working set of entities before ordering or projection.
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link io.evitadb.api.query.FilterConstraint}`.
	 */
	FILTER(FilterConstraint.class),
	/**
	 * Marks constraints that define the sort order of query results.
	 *
	 * **Typical Constraints:**
	 * - Attribute ordering: `attributeNatural`, `attributeSetExact`, `attributeSetInFilter`
	 * - Entity ordering: `entityPrimaryKeyNatural`, `entityPrimaryKeyExact`, `entityPrimaryKeyInFilter`
	 * - Price ordering: `priceNatural`, `priceDiscount`
	 * - Reference ordering: `referenceProperty`
	 * - Special ordering: `random`, `orderBy` (container)
	 * - Segmentation: `segments`, `segment`
	 *
	 * **Execution Phase:**
	 * Applied after filtering but before pagination and projection. Multiple ordering constraints can be combined
	 * to create multi-level sort keys.
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link io.evitadb.api.query.OrderConstraint}`.
	 */
	ORDER(OrderConstraint.class),
	/**
	 * Marks constraints that specify what data to fetch and how to format the response. These constraints do not
	 * affect which entities are returned or their order, but rather what information each entity contains and how
	 * results are structured.
	 *
	 * **Typical Constraints:**
	 * - Entity body: `entityFetch`, `entityGroupFetch`
	 * - Attributes: `attributeContent`, `attributeHistogram`
	 * - Associated data: `associatedDataContent`
	 * - Prices: `priceContent`, `priceHistogram`
	 * - References: `referenceContent`
	 * - Hierarchy: `hierarchyOfSelf`, `hierarchyOfReference`, `hierarchyContent`, `hierarchyStatistics`
	 * - Facets: `facetSummary`, `facetSummaryOfReference`, `facetGroupsConjunction`
	 * - Pagination: `page`, `strip`
	 * - Localization: `dataInLocales`
	 *
	 * **Execution Phase:**
	 * Applied after filtering and ordering during entity projection and extra computation (statistics, histograms).
	 *
	 * **Performance Note:**
	 * Requirements can significantly impact query cost. Fetching full entity bodies with all references is more
	 * expensive than returning just primary keys. Use requirements judiciously based on client needs.
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link io.evitadb.api.query.RequireConstraint}`.
	 */
	REQUIRE(RequireConstraint.class);

	private final Class<? extends TypeDefiningConstraint<?>> representingInterface;
}
