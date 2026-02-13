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

import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import lombok.Getter;

/**
 * Defines the data domain or execution context in which a query constraint operates within evitaDB's constraint
 * descriptor system. Each constraint declares which domains it supports, enabling validation and proper API schema
 * generation for external query languages (GraphQL, REST).
 *
 * **Purpose and Usage**
 *
 * Domains classify constraints based on what data they target and how they interact with the entity model:
 * - **Entity data**: Primary keys, attributes, prices, hierarchies
 * - **Reference data**: Reference attributes, facets, group entities
 * - **Generic logic**: Containers like `and`, `or`, `not` that work across all domains
 *
 * At application startup, {@link ConstraintProcessor} reads each constraint's
 * `{@link ConstraintDefinition#supportedIn()}` annotation to determine which domains that constraint can operate in.
 * External API builders (GraphQL/REST) use this information to dynamically generate schemas that only expose
 * constraints valid for the current query context.
 *
 * **Domain Resolution and Context Switching**
 *
 * Some domains are "virtual" placeholders that resolve to concrete domains at query execution time:
 * - `{@link #DEFAULT}`: Inherits the domain from the parent constraint in the tree
 * - `{@link #HIERARCHY_TARGET}`: Resolves to either `{@link #ENTITY}` or `{@link #REFERENCE}` depending on whether
 * the hierarchy is self-referential or comes from a referenced entity collection
 *
 * The {@link io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorResolver} class handles domain
 * resolution and context switching when traversing nested constraint trees. For example, when processing a
 * `referenceHaving` constraint in the `{@link #ENTITY}` domain, child constraints inside it switch to the
 * `{@link #REFERENCE}` domain.
 *
 * **Domain Hierarchy and Relationships**
 *
 * - `{@link #GENERIC}`: Top-level logic containers, no specific data target
 * - `{@link #ENTITY}`: Main entity data (attributes, prices, primary keys)
 * - `{@link #REFERENCE}`: Reference data in main query (reference attributes, facets)
 * - `{@link #INLINE_REFERENCE}`: Reference data in nested clauses (e.g., inside `orderBy` or filter properties)
 * - `{@link #GROUP_ENTITY}`: Group entity data for faceted references
 * - `{@link #HIERARCHY}`: Hierarchical placement and traversal rules
 * - `{@link #FACET}`: Facet-specific filtering and summarization
 * - `{@link #SEGMENT}`: Result segmentation for pagination or grouping
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(
 *     name = "equals",
 *     supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }
 * )
 * public class AttributeEquals extends AbstractAttributeFilterConstraintLeaf {
 *     // This constraint can filter attributes on entities or references
 * }
 * ```
 *
 * **Related Classes**
 *
 * - `{@link ConstraintDefinition}` - Annotation where `supportedIn` is declared
 * - `{@link ConstraintDescriptor}` - Runtime descriptor containing the set of supported domains
 * - `{@link ConstraintProcessor}` - Processes annotations at startup to build descriptors
 * - `{@link io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorResolver}` - Resolves domain context
 * switching in constraint trees
 * - `{@link io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator}` - Tracks current domain and entity
 * type during API schema building
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Getter
public enum ConstraintDomain {

	/**
	 * Virtual domain that inherits the domain from the parent constraint in the query tree. This is a placeholder
	 * that avoids explicitly specifying the domain when a constraint should simply operate in the same context as
	 * its parent.
	 *
	 * **Context Inheritance:**
	 * For example, a child constraint of `and` within `referenceHaving` would inherit the `{@link #REFERENCE}` domain
	 * from the `referenceHaving` parent. This simplifies constraint definitions that work universally across domains.
	 *
	 * **Dynamic Resolution:**
	 * Marked as dynamic (`{@link #isDynamic()}` returns `true`) because the actual domain is unknown until runtime
	 * when the constraint tree is traversed.
	 */
	DEFAULT(true),
	/**
	 * Virtual domain that targets a hierarchical entity collection, resolving at runtime to either `{@link #ENTITY}`
	 * (for self-hierarchies) or `{@link #REFERENCE}` (for hierarchies accessed via references).
	 *
	 * **Use Case:**
	 * Hierarchy constraints like `hierarchyWithin` can operate on:
	 * - The queried entity type itself (if it has a self-referential hierarchy placement)
	 * - A referenced entity type that is hierarchical
	 *
	 * The actual target domain is determined by schema inspection at query execution time.
	 *
	 * **Dynamic Resolution:**
	 * Marked as dynamic (`{@link #isDynamic()}` returns `true`) because the domain depends on schema configuration.
	 */
	HIERARCHY_TARGET(true),

	/**
	 * Domain for generic logic containers and constraints that operate independently of any specific data model.
	 *
	 * **Typical Constraints:**
	 * - Logical combinators: `and`, `or`, `not`
	 * - Wrapper constraints: `userFilter`
	 * - Top-level containers: `filterBy`, `orderBy`, `require`
	 *
	 * **Property Type:**
	 * Usually paired with `{@link ConstraintPropertyType#GENERIC}`.
	 */
	GENERIC,
	/**
	 * Domain for constraints that operate on core entity data directly accessible from the queried entity.
	 *
	 * **Accessible Data:**
	 * - Primary keys
	 * - Entity attributes
	 * - Prices
	 * - Locales
	 * - Entity scope
	 * - Self-referential hierarchical placements
	 *
	 * **Typical Constraints:**
	 * - `entityPrimaryKeyInSet`, `entityLocaleEquals`
	 * - `attributeEquals`, `attributeBetween`
	 * - `priceInCurrency`, `priceBetween`
	 * - `entityPrimaryKeyNatural`, `attributeNatural`
	 *
	 * **Property Types:**
	 * Supports `{@link ConstraintPropertyType#ENTITY}`, `{@link ConstraintPropertyType#ATTRIBUTE}`,
	 * `{@link ConstraintPropertyType#PRICE}`.
	 */
	ENTITY,
	/**
	 * Domain for constraints that operate on the group entity of a faceted reference.
	 *
	 * **Context:**
	 * When a reference has a group entity (defined via `{@link io.evitadb.api.requestResponse.schema.ReferenceSchemaContract#getReferencedGroupType()}`),
	 * some constraints need to target that group entity's data rather than the main entity or the reference itself.
	 *
	 * **Typical Use Case:**
	 * Inside `entityGroupFetch` requirement constraints that retrieve and project group entity attributes.
	 *
	 * **Resolution:**
	 * This is a virtual domain that resolves to `{@link #ENTITY}` at runtime but with the context pointing to the
	 * group entity type instead of the main queried entity type.
	 */
	GROUP_ENTITY,
	/**
	 * Domain for constraints that operate on entity reference data in the main query context.
	 *
	 * **Accessible Data:**
	 * - Reference attributes
	 * - Referenced entity primary keys
	 *
	 * **Typical Constraints:**
	 * - `attributeEquals` (on reference attributes)
	 * - `entityPrimaryKeyInSet` (filtering referenced entities)
	 * - `referenceProperty` (ordering by reference attributes)
	 *
	 * **Context:**
	 * Entered when a constraint like `referenceHaving` switches context from `{@link #ENTITY}` to reference data.
	 * This is used for top-level reference filtering/ordering in the main query.
	 *
	 * **Property Type:**
	 * Usually paired with `{@link ConstraintPropertyType#REFERENCE}`.
	 *
	 * **Distinction from {@link #INLINE_REFERENCE}:**
	 * Use `{@link #REFERENCE}` for main query reference operations; use `{@link #INLINE_REFERENCE}` for nested
	 * reference operations inside property-specific clauses.
	 */
	REFERENCE,
	/**
	 * Domain for constraints that operate on reference data within nested property-specific clauses.
	 *
	 * **Accessible Data:**
	 * - Reference attributes
	 * - Referenced entity primary keys
	 *
	 * **Typical Context:**
	 * Used inside property-specific ordering or filtering constraints where references are accessed inline:
	 * - Inside `referenceProperty` for ordering by reference attributes
	 * - Inside `entityProperty` for filtering based on reference properties
	 *
	 * **Property Type:**
	 * Usually paired with `{@link ConstraintPropertyType#REFERENCE}`.
	 *
	 * **Distinction from {@link #REFERENCE}:**
	 * Use `{@link #INLINE_REFERENCE}` for nested reference operations in property clauses; use `{@link #REFERENCE}`
	 * for top-level reference filtering/ordering in the main query.
	 *
	 * **Example:**
	 * ```
	 * orderBy(
	 *     referenceProperty( // Switches to INLINE_REFERENCE domain
	 *         "brand",
	 *         attributeNatural("priority") // Operates in INLINE_REFERENCE domain
	 *     )
	 * )
	 * ```
	 */
	INLINE_REFERENCE,
	/**
	 * Domain for constraints that operate on hierarchical placement and traversal rules.
	 *
	 * **Accessible Data:**
	 * - Hierarchical entity placement (parent-child relationships)
	 * - Hierarchy distance and depth
	 * - Direct vs. transitive relationships
	 *
	 * **Typical Constraints:**
	 * - `hierarchyWithin`, `hierarchyWithinRoot`
	 * - `hierarchyDirectRelation`
	 * - `hierarchyExcluding`, `hierarchyHaving`
	 * - `hierarchyOfSelf`, `hierarchyOfReference`
	 * - `hierarchyDistance`, `hierarchyLevel`
	 *
	 * **Context:**
	 * Entered when processing hierarchy-specific filtering or requirement constraints. Hierarchies can be
	 * self-referential (entity type references itself) or come from referenced entity types.
	 *
	 * **Property Type:**
	 * Usually paired with `{@link ConstraintPropertyType#HIERARCHY}`.
	 */
	HIERARCHY,
	/**
	 * Domain for constraints that operate on faceted references and their group entities.
	 *
	 * **Accessible Data:**
	 * - Facet reference primary keys
	 * - Facet group entity data
	 * - Facet conjunction/disjunction/negation rules
	 *
	 * **Typical Constraints:**
	 * - `facetHaving` (filtering by facet references)
	 * - `facetGroupsConjunction`, `facetGroupsDisjunction`, `facetGroupsNegation`
	 * - `facetSummary`, `facetSummaryOfReference`
	 * - `entityGroupFetch` (fetching group entity data)
	 *
	 * **Context:**
	 * Facets are special references marked as such in the schema
	 * (`{@link io.evitadb.api.requestResponse.schema.ReferenceSchemaContract#isFaceted()}`). This domain enables
	 * faceted search and navigation patterns common in e-commerce filtering.
	 *
	 * **Property Type:**
	 * Usually paired with `{@link ConstraintPropertyType#FACET}`.
	 */
	FACET,
	/**
	 * Domain for result segmentation, enabling division of query results into multiple independent segments with
	 * separate ordering and pagination.
	 *
	 * **Use Case:**
	 * Used for advanced result partitioning where different subsets of results need different sort orders or limits,
	 * such as:
	 * - Promoted vs. organic results
	 * - Different priority tiers
	 * - A/B testing variants
	 *
	 * **Typical Constraints:**
	 * - `segments` (defines multiple result segments)
	 * - `segment` (defines a single segment with its own ordering and limit)
	 *
	 * **Context:**
	 * Segments are containers within the main result set. Each segment has independent ordering logic but shares
	 * the same base filtering.
	 */
	SEGMENT;

	/**
	 * Indicates whether this domain is dynamic (requires runtime resolution to determine the actual domain).
	 *
	 * **Dynamic Domains:**
	 * - `{@link #DEFAULT}`: Inherits parent's domain
	 * - `{@link #HIERARCHY_TARGET}`: Resolves to `{@link #ENTITY}` or `{@link #REFERENCE}` based on schema
	 *
	 * **Usage:**
	 * Dynamic domains serve as placeholders during constraint definition but must be resolved to concrete domains
	 * before execution. The {@link io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocatorResolver} handles
	 * this resolution in external API builders.
	 */
	private final boolean dynamic;

	ConstraintDomain() {
		this(false);
	}

	ConstraintDomain(boolean dynamic) {
		this.dynamic = dynamic;
	}
}
