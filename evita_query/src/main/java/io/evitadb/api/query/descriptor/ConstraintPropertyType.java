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

import io.evitadb.api.query.AssociatedDataConstraint;
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.PropertyTypeDefiningConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines the data property type that a constraint operates on, enabling fine-grained categorization beyond just
 * domains. Each property type corresponds to a marker interface that constraints implement, allowing type-safe query
 * construction and accurate API schema generation.
 *
 * **Purpose and Usage**
 *
 * While {@link ConstraintDomain} defines the broad context (entity, reference, hierarchy), `ConstraintPropertyType`
 * specifies the exact data property being targeted:
 * - **Attributes**: Named key-value pairs on entities or references
 * - **Prices**: Price lists with currency, validity, and tax information
 * - **References**: Links to other entities (products → categories, brands)
 * - **Associated Data**: Complex structured data blobs
 * - **Hierarchy**: Parent-child relationships and tree structures
 * - **Facets**: Special references used for faceted navigation
 * - **Entity**: Core entity properties like primary key and locale
 * - **Generic**: Logic containers that don't target specific properties
 *
 * **Type Resolution**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} determines each constraint's
 * property type by checking which marker interface it implements. The `{@link #getRepresentingInterface()}` method
 * returns the interface that defines each property type. Every constraint class must implement exactly one
 * property-type-defining interface.
 *
 * **Relationship with ConstraintDomain**
 *
 * Property types and domains work together to fully specify a constraint's target:
 * - Domain = **where** the constraint executes (entity, reference, hierarchy context)
 * - Property Type = **what** data the constraint operates on (attributes, prices, etc.)
 *
 * Examples:
 * - `attributeEquals` in `{@link ConstraintDomain#ENTITY}`: operates on entity attributes
 * - `attributeEquals` in `{@link ConstraintDomain#REFERENCE}`: operates on reference attributes
 * - `priceInCurrency` in `{@link ConstraintDomain#ENTITY}`: operates on entity prices
 *
 * **API Schema Generation**
 *
 * External API builders use property types to:
 * - Group related constraints in documentation and schemas
 * - Generate type-specific input objects (AttributeFilter, PriceFilter, etc.)
 * - Validate that constraints are applied to the correct property types
 * - Provide auto-completion and IntelliSense based on schema
 *
 * **Example Usage**
 *
 * ```
 * @ConstraintDefinition(
 * name = "equals",
 * supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }
 * )
 * public class AttributeEquals extends AbstractAttributeFilterConstraintLeaf
 * implements FilterConstraint, AttributeConstraint { // PropertyType = ATTRIBUTE
 * // This constraint filters by attribute values
 * }
 * ```
 *
 * **Related Classes**
 *
 * - `{@link PropertyTypeDefiningConstraint}` - Parent marker interface for all property-type-defining interfaces
 * - `{@link GenericConstraint}`, `{@link EntityConstraint}`, `{@link AttributeConstraint}`,
 * `{@link AssociatedDataConstraint}`, `{@link PriceConstraint}`, `{@link ReferenceConstraint}`,
 * `{@link HierarchyConstraint}`, `{@link FacetConstraint}` - Property-type-defining marker interfaces
 * - `{@link io.evitadb.api.query.descriptor.ConstraintProcessor}` - Resolves property type at startup
 * - `{@link io.evitadb.api.query.descriptor.ConstraintDescriptor}` - Runtime descriptor containing the resolved
 * property type
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
@Getter
public enum ConstraintPropertyType {

	/**
	 * Property type for generic logic constraints that don't target specific entity properties.
	 *
	 * **Typical Constraints:**
	 * - Logical combinators: `and`, `or`, `not`
	 * - Container wrappers: `filterBy`, `orderBy`, `require`, `userFilter`
	 * - Pagination: `page`, `strip`
	 * - Localization: `dataInLocales`
	 * - Entity fetch: `entityFetch` (top-level projection container)
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link GenericConstraint}`.
	 *
	 * **Usage Context:**
	 * These constraints provide structure and control flow but don't directly query entity data. They typically
	 * contain or coordinate other constraints.
	 */
	GENERIC(GenericConstraint.class),
	/**
	 * Property type for constraints that operate on core entity metadata.
	 *
	 * **Accessible Properties:**
	 * - Primary key
	 * - Entity type
	 * - Locale
	 * - Entity scope
	 *
	 * **Typical Constraints:**
	 * - `entityPrimaryKeyInSet` - filters by primary key
	 * - `entityLocaleEquals` - filters by locale
	 * - `entityScope` - filters by entity scope (live, archived)
	 * - `entityPrimaryKeyNatural` - orders by primary key
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link EntityConstraint}`.
	 *
	 * **Distinction from Other Types:**
	 * Entity constraints target core identity properties, not user-defined attributes or complex data.
	 */
	ENTITY(EntityConstraint.class),
	/**
	 * Property type for constraints that operate on entity or reference attributes.
	 *
	 * **Accessible Properties:**
	 * - Attribute values (strings, numbers, dates, booleans, enums)
	 * - Attribute nullability
	 * - Localized vs. global attributes
	 * - Array-valued attributes
	 *
	 * **Typical Constraints:**
	 * - Comparison: `attributeEquals`, `attributeBetween`, `attributeInSet`
	 * - String operations: `attributeContains`, `attributeStartsWith`, `attributeEndsWith`
	 * - Null checks: `attributeIs`
	 * - Range checks: `attributeInRange`
	 * - Ordering: `attributeNatural`, `attributeSetExact`
	 * - Requirements: `attributeContent`, `attributeHistogram`
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link AttributeConstraint}`.
	 *
	 * **Context:**
	 * Can be used in `{@link ConstraintDomain#ENTITY}` for entity attributes or
	 * `{@link ConstraintDomain#REFERENCE}` for reference attributes.
	 */
	ATTRIBUTE(AttributeConstraint.class),
	/**
	 * Property type for constraints that operate on entity associated data (complex structured data blobs).
	 *
	 * **Accessible Properties:**
	 * - Associated data keys
	 * - Associated data localization
	 * - Associated data values (JSON, complex objects)
	 *
	 * **Typical Constraints:**
	 * - Requirements: `associatedDataContent` - fetches associated data into the result
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link AssociatedDataConstraint}`.
	 *
	 * **Note:**
	 * Associated data is typically used for rich content like product descriptions, specifications, or configuration
	 * that doesn't need to be indexed for filtering.
	 */
	ASSOCIATED_DATA(AssociatedDataConstraint.class),
	/**
	 * Property type for constraints that operate on entity price lists.
	 *
	 * **Accessible Properties:**
	 * - Price amounts (with and without tax)
	 * - Currency
	 * - Price list names
	 * - Validity ranges (from/to timestamps)
	 *
	 * **Typical Constraints:**
	 * - Filtering: `priceBetween`, `priceInCurrency`, `priceInPriceLists`, `priceValidIn`
	 * - Ordering: `priceNatural`, `priceDiscount`
	 * - Requirements: `priceContent`, `priceHistogram`
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link PriceConstraint}`.
	 *
	 * **Use Case:**
	 * Prices are critical for e-commerce queries, enabling filtering by budget, currency, promotional price lists,
	 * and time-based validity.
	 */
	PRICE(PriceConstraint.class),
	/**
	 * Property type for constraints that operate on entity references (links to other entities).
	 *
	 * **Accessible Properties:**
	 * - Referenced entity primary keys
	 * - Reference names/types
	 * - Reference attributes
	 * - Referenced group entities
	 *
	 * **Typical Constraints:**
	 * - Filtering: `referenceHaving` - filters entities by reference existence or properties
	 * - Ordering: `referenceProperty` - orders by reference attributes
	 * - Requirements: `referenceContent` - fetches referenced entities into the result
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link ReferenceConstraint}`.
	 *
	 * **Use Case:**
	 * References model relationships like products → brands, products → categories, products → stores. This property
	 * type enables querying and ordering based on these relationships.
	 */
	REFERENCE(ReferenceConstraint.class),
	/**
	 * Property type for constraints that operate on hierarchical entity structures and tree traversal.
	 *
	 * **Accessible Properties:**
	 * - Hierarchical parent entity
	 * - Hierarchy depth and distance
	 * - Direct vs. transitive relationships
	 * - Tree paths and subtrees
	 *
	 * **Typical Constraints:**
	 * - Filtering: `hierarchyWithin`, `hierarchyWithinRoot`, `hierarchyDirectRelation`, `hierarchyExcluding`
	 * - Requirements: `hierarchyOfSelf`, `hierarchyOfReference`, `hierarchyContent`, `hierarchyStatistics`,
	 * `hierarchyParents`, `hierarchyChildren`, `hierarchySiblings`
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link HierarchyConstraint}`.
	 *
	 * **Use Case:**
	 * Hierarchies model tree structures like category trees, organizational charts, or geographic regions. This
	 * property type enables filtering by subtree, computing statistics per node, and retrieving related nodes.
	 */
	HIERARCHY(HierarchyConstraint.class),
	/**
	 * Property type for constraints that operate on faceted references (special references used for faceted
	 * navigation).
	 *
	 * **Accessible Properties:**
	 * - Facet reference primary keys
	 * - Facet group entities
	 * - Facet conjunction/disjunction/negation rules
	 * - Facet counts and statistics
	 *
	 * **Typical Constraints:**
	 * - Filtering: `facetHaving`, `facetIncludingChildren`, `facetIncludingChildrenExcept`
	 * - Requirements: `facetSummary`, `facetSummaryOfReference`, `facetGroupsConjunction`, `facetGroupsDisjunction`,
	 * `facetGroupsNegation`, `facetGroupsExclusivity`
	 *
	 * **Marker Interface:**
	 * Conforms to `{@link FacetConstraint}`.
	 *
	 * **Use Case:**
	 * Facets enable faceted search patterns common in e-commerce: "Show me products filtered by brand A, category B,
	 * and color C, and tell me how many products match each brand/category/color combination." Facets are references
	 * marked as faceted in the schema.
	 */
	FACET(FacetConstraint.class);

	@SuppressWarnings("rawtypes")
	private final Class<? extends PropertyTypeDefiningConstraint> representingInterface;
}
