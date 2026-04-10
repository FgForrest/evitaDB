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

package io.evitadb.api.query;

/**
 * Marker interface for constraints that operate on faceted references. This interface is part of evitaDB's property
 * type system, which categorizes constraints based on the type of data they target.
 *
 * **Purpose**
 *
 * `FacetConstraint` identifies constraints that can query, filter, or compute statistics for faceted navigation.
 * Faceted search is a common e-commerce pattern where users progressively refine search results by selecting filters
 * (facets) such as brand, color, size, or price range. evitaDB provides specialized support for faceted navigation,
 * including impact analysis ("if I select brand X, how many products will remain?") and facet statistics.
 *
 * **Facet Data Model**
 *
 * Facets in evitaDB are special references that are marked as "faceted" in the entity schema. A faceted reference is
 * a relationship to another entity that can be used for filtering and whose statistics should be computed. For
 * example:
 * - **Products → Brands**: each product references one brand entity; brands are facets
 * - **Products → Categories**: each product references categories; categories are facets
 * - **Products → Tags**: each product can reference multiple tag entities; tags are facets
 * - **Products → Colors**: each product variant references colors; colors are facets
 *
 * Facets differ from regular references in that:
 * - They are explicitly marked as faceted in the schema
 * - evitaDB computes facet counts and impact predictions for them
 * - They support conjunction/disjunction/negation filtering logic
 * - They can be grouped (e.g., color facets belong to a "Color" facet group)
 *
 * **Property Type System**
 *
 * This interface represents the `FACET` property type in evitaDB's constraint classification system. Along with other
 * property-type-defining interfaces ({@link EntityConstraint}, {@link AttributeConstraint}, {@link PriceConstraint},
 * {@link AssociatedDataConstraint}, {@link ReferenceConstraint}, {@link HierarchyConstraint},
 * {@link GenericConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Faceted navigation patterns specific to e-commerce
 *
 * **Constraint Domains**
 *
 * Facet constraints are used primarily in the `ENTITY` domain, as facets are references owned by entities. They
 * enable filtering entities based on their faceted references and computing facet statistics to power faceted
 * navigation UIs.
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `FacetHaving` - filters entities by facet selection (works like {@link ReferenceConstraint} but
 *   cooperates with facet summary computation), `FacetIncludingChildren` - includes child facets in hierarchical
 *   facet filtering, `FacetIncludingChildrenExcept` - includes child facets with exclusions
 * - **Requirements**: `FacetSummary` - computes facet counts and impact predictions, `FacetSummaryOfReference` -
 *   computes facet summary for specific reference types, `FacetGroupsConjunction` - defines AND logic for facet
 *   groups, `FacetGroupsDisjunction` - defines OR logic for facet groups, `FacetGroupsNegation` - defines NOT logic
 *   for facet groups, `FacetGroupsExclusivity` - defines exclusive facet groups, `FacetCalculationRules` -
 *   customizes facet calculation behavior
 *
 * **Faceted Navigation Patterns**
 *
 * Facet constraints enable several e-commerce navigation patterns:
 * - **Basic facet filtering**: "Show products with brand=Nike OR brand=Adidas"
 * - **Impact prediction**: "If I click brand=Nike, 1,234 products will remain"
 * - **Facet conjunction**: "Show products that have ALL of these tags"
 * - **Facet disjunction**: "Show products that have ANY of these categories"
 * - **Facet negation**: "Show products that do NOT have these brands"
 * - **Hierarchical facets**: "Include subcategories when filtering by category"
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (e.g., `FilterConstraint`, `RequireConstraint`) that defines the
 * constraint's purpose within a query.
 *
 * **Example Usage**
 *
 * ```java
 * // Basic facet filtering with facet summary
 * query(
 *     collection("Product"),
 *     filterBy(
 *         userFilter(
 *             facetHaving("brand", entityPrimaryKeyInSet(1, 2, 3))
 *         )
 *     ),
 *     require(
 *         facetSummary(FacetStatisticsDepth.IMPACT)
 *     )
 * )
 *
 * // Facet filtering with conjunction and disjunction
 * query(
 *     collection("Product"),
 *     filterBy(
 *         userFilter(
 *             and(
 *                 facetHaving("brand", entityPrimaryKeyInSet(100)), // must have brand 100
 *                 facetHaving("category", entityPrimaryKeyInSet(10, 20, 30)) // any of these categories
 *             )
 *         )
 *     ),
 *     require(
 *         facetSummaryOfReference(
 *             "brand",
 *             FacetStatisticsDepth.IMPACT,
 *             facetGroupsConjunction("brand"),
 *             entityFetch(attributeContent("name"))
 *         )
 *     )
 * )
 *
 * // Hierarchical facet filtering (categories with subcategories)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         userFilter(
 *             facetHaving(
 *                 "category",
 *                 entityPrimaryKeyInSet(5),
 *                 facetIncludingChildren() // include subcategories
 *             )
 *         )
 *     ),
 *     require(
 *         facetSummary()
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Facets are a specialized type of reference because:
 * 1. **E-commerce importance**: faceted navigation is a core requirement for modern e-commerce front-ends
 * 2. **Complex statistics**: computing facet counts and impact predictions requires specialized indexes and algorithms
 * 3. **Performance**: evitaDB maintains bitmap indexes optimized for facet filtering and counting
 * 4. **User experience**: impact prediction enables better UX by showing users which filter combinations are viable
 *
 * **Relationship with ReferenceConstraint**
 *
 * `FacetConstraint` is a specialized subset of {@link ReferenceConstraint}. All facets are references, but not all
 * references are facets. Key differences:
 * - {@link ReferenceConstraint}: general entity relationships, no automatic statistics
 * - `FacetConstraint`: references marked as faceted, with automatic count and impact computation
 *
 * The constraint `facetHaving` behaves identically to `referenceHaving` for filtering purposes, but it signals to
 * evitaDB's query planner that facet statistics should be computed and adjusted based on the filter.
 *
 * **UserFilter Context**
 *
 * Facet filtering constraints are typically placed inside a `userFilter` container. This signals to evitaDB that
 * these filters represent user-initiated facet selections and should be excluded from facet impact calculations
 * (otherwise, selecting brand=Nike would show "Nike: 0 products" because the filter already excludes all non-Nike
 * products).
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `FacetConstraint` are
 * registered with the `FACET` property type in the constraint descriptor registry.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe, as they are frequently shared across concurrent
 * query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint or RequireConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see ReferenceConstraint for general (non-faceted) reference constraints
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface FacetConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
