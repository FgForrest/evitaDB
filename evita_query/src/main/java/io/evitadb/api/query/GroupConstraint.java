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

package io.evitadb.api.query;

/**
 * Marker interface for constraints that operate on the **group entity** of a faceted reference. The group entity is
 * the entity referenced via {@link io.evitadb.api.requestResponse.schema.ReferenceSchemaContract#getReferencedGroupType()}
 * on a reference schema — for example, a `parameterValue` reference may be grouped by a `parameter` entity, and the
 * `parameter` entity is then the *group* of that reference.
 *
 * **Purpose**
 *
 * `GroupConstraint` identifies constraints that shift the filtering or ordering scope inside `referenceHaving` or
 * `facetHaving` from the reference relation itself (or the directly referenced entity) to the *group* entity behind
 * that reference. This allows queries to express conditions such as "match products that have at least one facet
 * whose group entity satisfies these conditions" without having to pivot the query around the group collection
 * directly.
 *
 * The only current implementor is `GroupHaving`, which wraps an inner filter constraint and re-targets it at the
 * group entity of the enclosing `referenceHaving` / `facetHaving` block. Without `GroupHaving`, the inner constraints
 * are evaluated against the reference relation or the referenced entity (via `entityHaving`); with `GroupHaving`,
 * they are evaluated against the group entity instead.
 *
 * **Property Type System**
 *
 * This interface represents the `GROUP` property type in evitaDB's constraint classification system, corresponding
 * to {@link io.evitadb.api.query.descriptor.ConstraintPropertyType#GROUP}. Along with the other
 * property-type-defining interfaces ({@link GenericConstraint}, {@link EntityConstraint},
 * {@link AttributeConstraint}, {@link AssociatedDataConstraint}, {@link PriceConstraint},
 * {@link ReferenceConstraint}, {@link HierarchyConstraint}, {@link FacetConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Runtime dispatch to group-entity-specific query execution logic
 *
 * **Constraint Domains**
 *
 * Group constraints are used in the `GROUP_ENTITY` domain
 * ({@link io.evitadb.api.query.descriptor.ConstraintDomain#GROUP_ENTITY}), which scopes inner constraints to the
 * group entity attached to a faceted reference. The domain switch is performed by the constraint container itself
 * (e.g., `GroupHaving`), so any constraint nested inside is resolved against the group entity's schema rather than
 * the parent entity's or the referenced entity's schema.
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `GroupHaving` — shifts the filtering scope of `referenceHaving` / `facetHaving` from the
 *   reference relation (or referenced entity) to the group entity of that reference.
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (e.g., `FilterConstraint`, `OrderConstraint`) that defines the
 * constraint's purpose within a query.
 *
 * **Example Usage**
 *
 * ```java
 * // Filter products that have at least one "parameterValue" reference whose group entity ("parameter")
 * // matches a given attribute condition
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "parameterValue",
 *             groupHaving(
 *                 attributeEquals("code", "color")  // attribute on the group entity ("parameter")
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements **exactly one** property-type-defining interface. Constraints implementing `GroupConstraint`
 * must therefore not also implement {@link EntityConstraint}, {@link AttributeConstraint},
 * {@link ReferenceConstraint}, or any other sibling marker — they are registered with the `GROUP` property type in
 * the constraint descriptor registry.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across
 * multiple concurrent query executions.
 *
 * @param <T> the constraint type classification (typically FilterConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintDomain enum defining all constraint domains
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see ReferenceConstraint for constraints operating on the reference relation itself
 * @see EntityConstraint for constraints operating on the directly referenced entity
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface GroupConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {
}
