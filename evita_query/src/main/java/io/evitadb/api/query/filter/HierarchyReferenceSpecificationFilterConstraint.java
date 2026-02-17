/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
 * Marker interface for filter constraints that can be nested within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} containers to further specify hierarchy queries. This interface is parallel to
 * {@link HierarchySpecificationFilterConstraint} but identifies constraints that operate on referenced entities rather
 * than on the hierarchical structure itself.
 *
 * **Purpose**
 *
 * `HierarchyReferenceSpecificationFilterConstraint` distinguishes constraints that can be used as modifiers within
 * hierarchy queries, typically to filter or constrain the referenced entities in a hierarchy-aware context. While
 * {@link HierarchySpecificationFilterConstraint} focuses on modifying how the hierarchy tree itself is traversed and
 * filtered, this interface marks constraints that target the entities participating in the hierarchical relationship.
 *
 * **Constraint Containment**
 *
 * Like specification constraints, reference specification constraints are only valid as children of hierarchy filter
 * constraints:
 * - **Valid**: `hierarchyWithin("categories", entityPrimaryKeyInSet(5), referenceSpecificationConstraint(...))`
 * - **Invalid**: `and(referenceSpecificationConstraint(...), attributeEquals("name", "test"))` (used standalone)
 *
 * **SeparateEntityScopeContainer Integration**
 *
 * This interface extends {@link SeparateEntityScopeContainer}, which signals to the query engine that constraints
 * implementing this interface establish a new filtering context targeting a different entity type. This is critical for
 * reference-hierarchical queries where the filter logic must distinguish between constraints targeting the queried
 * entity (e.g., `Product`) and constraints targeting the referenced hierarchical entity (e.g., `Category`).
 *
 * **Design Context**
 *
 * The distinction between `HierarchySpecificationFilterConstraint` and `HierarchyReferenceSpecificationFilterConstraint`
 * reflects the dual nature of hierarchy queries:
 * 1. **Hierarchy structure constraints** (HierarchySpecificationFilterConstraint): modify how the tree is traversed
 *    (e.g., `directRelation`, `having`, `excluding`)
 * 2. **Referenced entity constraints** (HierarchyReferenceSpecificationFilterConstraint): filter based on properties of
 *    the entities participating in the hierarchy (less common, used for advanced filtering scenarios)
 *
 * In most evitaDB queries, hierarchy refinements use `HierarchySpecificationFilterConstraint` implementations. This
 * interface exists to support advanced use cases where constraints need to target referenced entities within a
 * hierarchy-aware filtering context.
 *
 * **Comparison with HierarchySpecificationFilterConstraint**
 *
 * | Aspect | HierarchySpecificationFilterConstraint | HierarchyReferenceSpecificationFilterConstraint |
 * |--------|----------------------------------------|--------------------------------------------------|
 * | Purpose | Modify hierarchy tree traversal | Filter referenced entities in hierarchy context |
 * | Target | Hierarchical structure itself | Entities participating in hierarchy |
 * | Typical implementations | `directRelation`, `having`, `excluding`, `excludingRoot` | Advanced filtering constraints |
 * | Extends SeparateEntityScopeContainer | No | Yes |
 * | Frequency of use | Very common | Rare |
 *
 * **Thread Safety**
 *
 * All implementations must be immutable and thread-safe. State changes return new instances.
 *
 * @see HierarchyFilterConstraint parent container constraints that establish hierarchical scope
 * @see HierarchySpecificationFilterConstraint constraints modifying hierarchy tree traversal
 * @see SeparateEntityScopeContainer marker for constraints establishing new entity filtering contexts
 * @see HierarchyWithin hierarchy filter with specified root nodes
 * @see HierarchyWithinRoot hierarchy filter for entire tree
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyReferenceSpecificationFilterConstraint
	extends SeparateEntityScopeContainer, FilterConstraint {
}
