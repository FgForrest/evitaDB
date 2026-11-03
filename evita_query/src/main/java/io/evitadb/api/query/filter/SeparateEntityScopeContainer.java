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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;

/**
 * Marker interface identifying {@link FilterConstraint} containers that establish a new, separate entity scope for
 * their nested filtering logic. Their child constraints address something other than the queried entity itself - its
 * references, a referenced entity or group, or hierarchy nodes - so lookups of the outer query's own primary keys and
 * scope must not descend into them.
 *
 * ## Purpose and Design Intent
 *
 * In evitaDB queries, most filtering constraints operate on the **primary entity type** specified by the query's
 * collection. However, certain constraints need to filter based on the **references** of the entity, the properties of
 * **related entities** or **nested entity structures**, which requires a context switch. The
 * `SeparateEntityScopeContainer` interface marks these context-switching containers, enabling the query engine to
 * recognize scope boundaries and to interpret the constraints inside them in their own context.
 *
 * ## Scope boundary and locale level
 *
 * This marker is the broad boundary for entity keys and `entityScope` lookups. {@link DifferentEntityFilterContainer}
 * extends it and marks the narrower subset whose children filter a different entity and therefore also form a locale
 * level of their own. {@link ReferenceHaving} and {@link FacetHaving} are scope boundaries only: their direct
 * children keep the owner's locale, including localized reference attributes. A direct `entityLocaleEquals` in either
 * container requires the owner to hold that locale.
 *
 * ## Implementations
 *
 * - **{@link ReferenceHaving} and {@link FacetHaving}**: filter the primary entity by its matching references - their
 *   reference attributes, the keys of the referenced entities and, through a nested {@link EntityHaving} or
 *   {@link GroupHaving}, the referenced entity or group itself.
 * - **{@link DifferentEntityFilterContainer}** implementations - {@link EntityHaving}, {@link GroupHaving},
 *   {@link HierarchyWithin} and {@link HierarchyWithinRoot}: their children filter the referenced entity, the
 *   referenced group or the hierarchy nodes rather than the entity or reference that encloses them.
 * - **{@link HierarchyReferenceSpecificationFilterConstraint}** implementations - {@link FacetIncludingChildren} and
 *   {@link FacetIncludingChildrenExcept}: their children filter the hierarchical nodes of a faceted reference.
 *
 * ## Role in Query Processing
 *
 * The query engine uses this marker interface to determine scope boundaries during constraint traversal:
 *
 * **Scope Isolation for {@link EntityScope}**: When searching for the query's effective {@link EntityScope} constraint
 * (which defines whether to search LIVE, ARCHIVED, or both scopes), the engine stops at `SeparateEntityScopeContainer`
 * boundaries. Scope constraints found inside these containers apply to the **nested entity context**, not the outer
 * query. `EvitaRequest` implements this with {@link io.evitadb.api.query.QueryUtils#findFilter} and
 * `SeparateEntityScopeContainer.class` as the termination condition, and the nested query of an `entityHaving` /
 * `groupHaving` looks its own `entityScope` up the same way.
 *
 * **Scope Isolation for {@link EntityPrimaryKeyInSet}**: Similarly, when `EvitaRequest` extracts the primary keys of
 * the queried entities, it stops at `SeparateEntityScopeContainer` boundaries to avoid misinterpreting primary key
 * constraints from nested entity contexts as constraints on the primary entity.
 *
 * **Reference Fetching Decisions**: When `ReferencedEntityFetcher` derives the request fetching the referenced entity
 * (or group) bodies of a `referenceContent`, it takes the children of the {@link EntityHaving} (or {@link GroupHaving})
 * containers and the {@link EntityPrimaryKeyInSet} constraints of the reference filter only outside nested
 * `SeparateEntityScopeContainer` boundaries.
 *
 * **Attribute Set Ordering**: `AttributeSetInFilterTranslator` looks up the `attributeInSet` constraint whose value
 * order `attributeSetInFilter` follows without descending into these containers - an `attributeInSet` inside one
 * filters an attribute of another context.
 *
 * ## Usage Example
 *
 * Consider the following query structure:
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         entityPrimaryKeyInSet(1, 2, 3), // filters primary Product entities by PK
 *         referenceHaving( // SeparateEntityScopeContainer: filters the `brand` references of the Products
 *             'brand',
 *             entityPrimaryKeyInSet(10, 20), // keys of the referenced Brands, NOT of the Products
 *             entityHaving( // DifferentEntityFilterContainer: its children filter the Brand entity itself
 *                 attributeEquals('established', true) // an attribute of the Brand entity
 *             )
 *         ),
 *         scope(LIVE) // defines scope for Product entities (outer context)
 *     )
 * )
 * ```
 *
 * In this example:
 * - The outer `entityPrimaryKeyInSet(1, 2, 3)` and `scope(LIVE)` apply to **Product entities** (the primary
 *   collection).
 * - The lookups of the queried keys and scope stop at `referenceHaving`, so the nested `entityPrimaryKeyInSet(10, 20)`
 *   is never read as a constraint on Products - it selects the references pointing to those Brands.
 * - It is the `entityHaving` container that moves to the **Brand entity's** own properties:
 *   `attributeEquals('established', true)` reads an attribute of the Brand, not of the Product or of the reference.
 *
 * ## Marker Interface Pattern
 *
 * This interface declares no methods and serves purely as a **marker** to communicate semantic intent. Its presence
 * enables polymorphic type checks during query traversal and constraint processing, allowing the engine to handle
 * scope boundaries uniformly without needing to enumerate specific constraint classes.
 *
 * @see ReferenceHaving
 * @see DifferentEntityFilterContainer
 * @see EntityHaving
 * @see GroupHaving
 * @see HierarchyWithin
 * @see HierarchyWithinRoot
 * @see HierarchyReferenceSpecificationFilterConstraint
 * @see EntityScope
 * @see io.evitadb.api.query.QueryUtils#findFilter
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface SeparateEntityScopeContainer extends FilterConstraint {
}
