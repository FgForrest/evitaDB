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
 * Marker interface defining the second dimension of evitaDB's constraint classification system:
 * **property type** — the kind of entity data a constraint operates on. This interface is the parent of eight
 * property-type-defining interfaces that categorize constraints by the entity properties they target (attributes,
 * prices, references, etc.) or by their structural role (generic/logical operations).
 *
 * **Purpose in the Constraint System**
 *
 * evitaDB's query language organizes constraints into a two-dimensional classification:
 * 1. **Type (Purpose)** — defined by {@link TypeDefiningConstraint} — **what the constraint does**
 * 2. **Property Type** — defined by this interface and its children — **what data the constraint operates on**
 *
 * The property type dimension answers: "Does this constraint work with attributes, prices, references, hierarchies,
 * facets, associated data, core entity fields, or is it a structural/logical operation?"
 *
 * **The Eight Property Types**
 *
 * This interface has exactly eight direct descendants, each representing a distinct category of entity data or
 * structural operation:
 *
 * **1. {@link GenericConstraint}** — Structural and Logical Operations
 *
 * Generic constraints provide structure, control flow, and coordination without directly querying entity data.
 * They organize other constraints, control pagination, manage localization, and combine constraints with boolean
 * logic.
 *
 * Examples: `and(...)`, `or(...)`, `not(...)`, `filterBy(...)`, `page(1, 20)`, `dataInLocales(...)`, `userFilter(...)`
 *
 * **2. {@link EntityConstraint}** — Core Entity Properties
 *
 * Entity constraints operate on built-in entity metadata: primary key, type, locale, and scope. These are the
 * fundamental identity and context properties present on every entity.
 *
 * Examples: `entityPrimaryKeyInSet(1, 2, 3)`, `entityLocaleEquals(Locale.ENGLISH)`, `entityScope(Scope.LIVE)`
 *
 * **3. {@link AttributeConstraint}** — Entity and Reference Attributes
 *
 * Attribute constraints operate on user-defined key-value pairs stored on entities or references. Attributes can
 * be strings, numbers, dates, booleans, enums, or arrays, and may be localized. They are the primary mechanism for
 * filtering, ordering, and fetching custom entity data.
 *
 * Examples: `attributeEquals("name", "iPhone")`, `attributeBetween("price", 100, 1000)`,
 * `attributeNatural("name", ASC)`, `attributeContent("name", "description")`
 *
 * **4. {@link AssociatedDataConstraint}** — Complex Structured Data
 *
 * Associated data constraints target large, complex structured data blobs (JSON, complex objects) that are not
 * indexed for filtering but need to be fetched with entities. Associated data is typically used for rich content
 * like product descriptions, specifications, or configuration.
 *
 * Examples: `associatedDataContent("description", "specifications")`
 *
 * **5. {@link PriceConstraint}** — Price Lists and Pricing Data
 *
 * Price constraints operate on entity price lists, which include amounts (with/without tax), currency, price list
 * names, and validity timestamps. Prices are a first-class concept in evitaDB due to e-commerce requirements for
 * multi-currency, multi-price-list, and time-based pricing.
 *
 * Examples: `priceInCurrency(Currency.EUR)`, `priceBetween(100, 1000)`, `priceValidIn(timestamp)`,
 * `priceNatural(ASC)`, `priceHistogram(20)`
 *
 * **6. {@link ReferenceConstraint}** — Entity References and Relationships
 *
 * Reference constraints operate on entity references — links to other entities representing relationships like
 * "product → brand", "product → category", "order → customer". References can have their own attributes and
 * optionally belong to group entities.
 *
 * Examples: `referenceHaving("brand", entityPrimaryKeyInSet(1, 2))`, `referenceProperty(...)`,
 * `referenceContent("brand", "category")`
 *
 * **7. {@link HierarchyConstraint}** — Hierarchical Tree Structures
 *
 * Hierarchy constraints operate on parent-child relationships forming tree structures (category trees, organizational
 * charts, geographic regions). They enable subtree filtering, ancestor/descendant queries, and statistics computation
 * per tree node.
 *
 * Examples: `hierarchyWithin("categories", 5)`, `hierarchyOfReference(...)`, `hierarchyStatistics(...)`
 *
 * **8. {@link FacetConstraint}** — Faceted Navigation and Statistics
 *
 * Facet constraints operate on references marked as "faceted" in the schema, enabling faceted navigation with
 * automatic count and impact prediction. Facets are specialized references designed for progressive refinement UI
 * patterns common in e-commerce.
 *
 * Examples: `facetHaving("brand", entityPrimaryKeyInSet(1))`, `facetSummary()`, `facetGroupsConjunction(...)`
 *
 * **Property Type System Design**
 *
 * evitaDB treats certain data types as first-class citizens with dedicated property types because:
 * - **Performance**: Specialized indexes enable efficient queries (price histograms, hierarchy subtrees, facet counts)
 * - **E-commerce semantics**: Multi-currency prices, faceted navigation, and hierarchical categories are core
 *   e-commerce requirements with complex logic
 * - **API clarity**: External APIs (GraphQL, REST, gRPC) benefit from explicit type distinctions
 * - **Rich operations**: Each property type supports domain-specific operations (facet impact prediction, hierarchy
 *   traversal, price validity checks)
 *
 * **Relationship with TypeDefiningConstraint**
 *
 * Property type and constraint type work together to fully categorize each constraint:
 * - A constraint implements one {@link TypeDefiningConstraint} descendant (filter, order, require, head)
 * - A constraint implements one `PropertyTypeDefiningConstraint` descendant (attribute, price, reference, etc.)
 *
 * Example: `attributeEquals` implements both:
 * - `FilterConstraint` (type dimension: this is a filter)
 * - `AttributeConstraint` (property type dimension: this filters by attributes)
 *
 * **Validation and Schema Generation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. This information is stored in
 * {@link io.evitadb.api.query.descriptor.ConstraintDescriptor} and used for:
 * - Query validation (attribute names must exist in schema, prices must be configured, etc.)
 * - API schema generation (GraphQL types, REST endpoints, gRPC services)
 * - Constraint grouping in documentation
 * - Runtime constraint resolution and dispatch
 *
 * The {@link io.evitadb.api.query.descriptor.ConstraintPropertyType} enum maps each property type to its marker
 * interface:
 * - `GENERIC` → {@link GenericConstraint}
 * - `ENTITY` → {@link EntityConstraint}
 * - `ATTRIBUTE` → {@link AttributeConstraint}
 * - `ASSOCIATED_DATA` → {@link AssociatedDataConstraint}
 * - `PRICE` → {@link PriceConstraint}
 * - `REFERENCE` → {@link ReferenceConstraint}
 * - `HIERARCHY` → {@link HierarchyConstraint}
 * - `FACET` → {@link FacetConstraint}
 *
 * **Constraint Domains**
 *
 * Property types can be used in multiple constraint domains (entity, reference, hierarchy, facet contexts) depending
 * on where they make sense. For example:
 * - `AttributeConstraint` can appear in `ENTITY` domain (entity attributes) or `REFERENCE` domain (reference
 *   attributes)
 * - `PriceConstraint` only appears in `ENTITY` domain (entities have prices, references do not)
 *
 * **Design Pattern: Marker Interfaces**
 *
 * This is a marker interface — it declares no methods beyond those inherited from {@link Constraint}. Its sole
 * purpose is type identification. Constraints implement property-type-defining interfaces to declare their data
 * category, and the type system uses these markers for validation and dispatch.
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` links property types to constraint types.
 * It ensures that property-type-defining constraints are also type-defining constraints (filter, order, require,
 * or head). This maintains the two-dimensional classification structure.
 *
 * **Implementation Note**
 *
 * This interface should **only be extended by the eight property-type-defining interfaces** listed above. Concrete
 * constraint classes should implement one of those eight, not this interface directly. The property type system
 * is closed to new types — all constraints must fit into the existing property type taxonomy.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across multiple
 * concurrent query executions.
 *
 * @param <T> the constraint type classification (FilterConstraint, OrderConstraint, RequireConstraint, or
 *           HeadConstraint)
 * @see GenericConstraint
 * @see EntityConstraint
 * @see AttributeConstraint
 * @see AssociatedDataConstraint
 * @see PriceConstraint
 * @see ReferenceConstraint
 * @see HierarchyConstraint
 * @see FacetConstraint
 * @see TypeDefiningConstraint
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface PropertyTypeDefiningConstraint<T extends TypeDefiningConstraint<T>> extends Constraint<T> {
}
