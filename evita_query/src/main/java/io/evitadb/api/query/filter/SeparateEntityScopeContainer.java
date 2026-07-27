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
 * Marker interface identifying {@link FilterConstraint} containers that establish a new, separate entity scope for their nested filtering logic.
 * Constraints implementing this interface indicate that their child filtering constraints target a **different entity type** than the outer query
 * context, creating an isolated filtering context that must not be confused with the global query scope.
 *
 * ## Purpose and Design Intent
 *
 * In evitaDB queries, most filtering constraints operate on the **primary entity type** specified by the query's collection. However, certain
 * constraints need to filter based on properties of **related entities** (references) or **nested entity structures**, requiring a context switch
 * to evaluate filtering logic against a different entity type. The `SeparateEntityScopeContainer` interface marks these context-switching
 * containers, enabling the query engine to correctly interpret scope boundaries and apply filtering logic to the appropriate entity type.
 *
 * ## Implementations
 *
 * The following constraint classes implement this marker interface:
 *
 * - **{@link ReferenceHaving}**: Filters the primary entity based on whether it has references to other entities that match nested filtering
 *   constraints. The nested constraints evaluate properties of the **referenced entity**, not the primary entity. For example, filtering products
 *   by attributes of their associated brands.
 *
 * - **{@link EntityHaving}**: Used within {@link ReferenceHaving} or other reference-related contexts to explicitly denote that nested constraints
 *   should be evaluated against the **referenced entity type**, not the reference relationship itself. This distinguishes entity properties from
 *   reference attributes.
 *
 * - **{@link HierarchyWithin}** and **{@link HierarchyWithinRoot}**: These constraints can include nested filtering logic via
 *   {@link HierarchyHaving} that evaluates properties of **hierarchical parent/ancestor entities**, creating a separate entity scope for hierarchy
 *   traversal.
 *
 * - **{@link io.evitadb.api.query.filter.HierarchyReferenceSpecificationFilterConstraint}**: Base interface for hierarchy-related constraints
 *   that define filtering within a hierarchical reference context, establishing a separate scope for hierarchy navigation.
 *
 * ## Role in Query Processing
 *
 * The query engine uses this marker interface to determine scope boundaries during constraint traversal:
 *
 * **Scope Isolation for {@link EntityScope}**: When searching for the query's effective {@link EntityScope} constraint (which defines whether
 * to search LIVE, ARCHIVED, or both scopes), the engine stops at `SeparateEntityScopeContainer` boundaries. Scope constraints found inside
 * these containers apply to the **nested entity context**, not the outer query. This is implemented in
 * {@link io.evitadb.api.requestResponse.EvitaRequest}, which uses {@link io.evitadb.api.query.QueryUtils#findFilter} with
 * `SeparateEntityScopeContainer.class` as a termination condition.
 *
 * **Scope Isolation for {@link EntityPrimaryKeyInSet}**: Similarly, when extracting primary key filters from the query, the engine stops at
 * `SeparateEntityScopeContainer` boundaries to avoid misinterpreting primary key constraints from nested entity contexts as constraints on the
 * primary entity.
 *
 * **Reference Fetching Decisions**: The {@link io.evitadb.core.query.fetch.ReferencedEntityFetcher} checks whether the filter contains any
 * {@link EntityHaving} constraints outside `SeparateEntityScopeContainer` boundaries to determine if referenced entity bodies need to be fetched
 * for filtering purposes.
 *
 * **Attribute Set Ordering**: The {@link io.evitadb.core.query.sort.attribute.translator.AttributeSetInFilterTranslator} uses this interface
 * to detect when filtering constraints targeting different entity scopes are present, which affects how attribute-based ordering is applied.
 *
 * ## Usage Example
 *
 * Consider the following query structure:
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         and(
 *             entityPrimaryKeyInSet(1, 2, 3), // filters primary Product entities by PK
 *             referenceHaving( // SeparateEntityScopeContainer: enters Brand entity scope
 *                 'brand',
 *                 entityPrimaryKeyInSet(10, 20), // filters Brand entities by PK, NOT Product entities
 *                 entityHaving( // SeparateEntityScopeContainer: reinforces Brand entity scope
 *                     attributeEquals('established', true) // filters Brand entity attributes
 *                 )
 *             ),
 *             scope(LIVE) // defines scope for Product entities (outer context)
 *         )
 *     )
 * )
 * ```
 *
 * In this example:
 * - The outer `entityPrimaryKeyInSet(1, 2, 3)` and `scope(LIVE)` apply to **Product entities** (the primary collection).
 * - The `referenceHaving` container implements `SeparateEntityScopeContainer`, indicating a scope switch to **Brand entities**.
 * - The nested `entityPrimaryKeyInSet(10, 20)` and `attributeEquals('established', true)` apply to **Brand entities**, not Products.
 * - The query engine recognizes the scope boundary and correctly interprets the constraints in their respective contexts.
 *
 * ## Implementation Requirements
 *
 * Constraints implementing this interface must:
 * - Clearly document which entity type their child constraints target
 * - Extend or implement {@link FilterConstraint} (typically via {@link AbstractFilterConstraintContainer})
 * - Ensure their translators correctly handle the entity scope switch during query execution
 * - Validate that nested constraints are appropriate for the target entity type's schema
 *
 * ## Marker Interface Pattern
 *
 * This interface declares no methods and serves purely as a **marker** to communicate semantic intent. Its presence enables polymorphic type
 * checks during query traversal and constraint processing, allowing the engine to handle scope boundaries uniformly without needing to enumerate
 * specific constraint classes.
 *
 * @see ReferenceHaving
 * @see EntityHaving
 * @see HierarchyWithin
 * @see HierarchyWithinRoot
 * @see io.evitadb.api.query.filter.HierarchyReferenceSpecificationFilterConstraint
 * @see EntityScope
 * @see io.evitadb.api.query.QueryUtils#findFilter
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface SeparateEntityScopeContainer extends FilterConstraint {
}
