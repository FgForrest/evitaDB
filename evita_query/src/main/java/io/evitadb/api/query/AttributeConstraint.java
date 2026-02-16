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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;

/**
 * Marker interface for constraints that operate on entity or reference attributes. This interface is part of evitaDB's
 * property type system, which categorizes constraints based on the type of data they target. `AttributeConstraint`
 * identifies constraints that filter by attributes, order by attributes, compute attribute statistics, or fetch
 * attribute data into query results.
 *
 * **Purpose**
 *
 * Attributes are user-defined key-value pairs stored on entities or references. They represent the primary mechanism
 * for custom entity data in evitaDB, analogous to columns in traditional SQL databases. `AttributeConstraint` marks
 * constraints that can query, manipulate, or retrieve these attributes.
 *
 * Unlike core entity properties (primary key, locale, scope — see {@link EntityConstraint}), attributes are
 * schema-defined but user-extensible. Each entity type can have a different set of attributes configured in its
 * schema.
 *
 * **Attribute Data Model**
 *
 * Attributes in evitaDB have rich semantics:
 * - **Value types**: String, Number, Date, Boolean, Enum, or arrays of these types
 * - **Localization**: Attributes can be localized (different values per locale) or global
 * - **Indexing**: Attributes can be marked as filterable, sortable, unique, or just storable
 * - **Nullability**: Attributes can be nullable or required
 * - **Schema constraints**: Default values, uniqueness requirements, numeric ranges
 *
 * References (entity relationships) can also have attributes — properties stored on the relationship itself rather
 * than on the referenced entity. For example, a product-category reference might have a `priority` attribute.
 *
 * **Property Type System**
 *
 * This interface represents the `ATTRIBUTE` property type in evitaDB's constraint classification system. Along with
 * other property-type-defining interfaces ({@link GenericConstraint}, {@link EntityConstraint},
 * {@link AssociatedDataConstraint}, {@link PriceConstraint}, {@link ReferenceConstraint}, {@link HierarchyConstraint},
 * {@link FacetConstraint}), it enables:
 * - Type-safe query construction and validation
 * - API schema generation for external APIs (GraphQL, REST, gRPC)
 * - Constraint grouping and documentation organization
 * - Runtime dispatch to attribute-specific query execution logic
 *
 * **Constraint Domains**
 *
 * Attribute constraints can be used in multiple domains:
 * - `ENTITY` domain: Operates on entity attributes (e.g., `product.name`, `product.visible`)
 * - `REFERENCE` domain: Operates on reference attributes (e.g., priority attribute on a product-category reference)
 *
 * The same constraint class (e.g., `AttributeEquals`) can work in both domains, but the target attribute set differs
 * based on the domain context.
 *
 * **Typical Implementations**
 *
 * Constraints implementing this interface include:
 * - **Filtering**: `AttributeEquals`, `AttributeBetween`, `AttributeInSet`, `AttributeContains`, `AttributeStartsWith`,
 *   `AttributeEndsWith`, `AttributeInRange`, `AttributeIs` (null checks), `AttributeIsNull`, `AttributeIsNotNull`
 * - **Ordering**: `AttributeNatural` (ascending/descending sort), `AttributeSetExact`, `AttributeSetInFilter`
 * - **Requirements**: `AttributeContent` (fetch attributes into results), `AttributeHistogram` (compute attribute
 *   value distribution)
 *
 * **Attribute Name Access Methods**
 *
 * `AttributeConstraint` is unique among property-type interfaces in that it declares methods for accessing attribute
 * names. This enables uniform attribute name extraction in constraint visitors and query processors without
 * reflection or casting:
 *
 * - {@link #getAttributeName()}: Returns a single attribute name (throws exception if constraint targets multiple
 *   attributes)
 * - {@link #getAttributeNames()}: Returns all attribute names targeted by this constraint
 *
 * Most attribute constraints target a single attribute and override `getAttributeName()`. Constraints targeting
 * multiple attributes (e.g., `AttributeContent`) override `getAttributeNames()`. The two methods have default
 * implementations that delegate to each other, so implementations need only override one.
 *
 * **Type Parameter**
 *
 * The generic type parameter `T extends TypeDefiningConstraint<T>` ensures type safety when combining constraints.
 * It represents the constraint type classification (e.g., `FilterConstraint`, `OrderConstraint`, `RequireConstraint`)
 * that defines the constraint's purpose within a query.
 *
 * **Example Usage**
 *
 * ```java
 * // Filter by entity attribute
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("name", "iPhone 15")
 *     )
 * )
 *
 * // Filter by reference attribute
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "category",
 *             attributeEquals("priority", 1)  // priority is an attribute on the reference
 *         )
 *     )
 * )
 *
 * // Order by attribute
 * query(
 *     collection("Product"),
 *     orderBy(
 *         attributeNatural("name", ASC)
 *     )
 * )
 *
 * // Fetch specific attributes
 * query(
 *     collection("Product"),
 *     require(
 *         entityFetch(
 *             attributeContent("code", "name", "description")
 *         )
 *     )
 * )
 *
 * // Compute attribute histogram
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeBetween("width", 10, 100)
 *     ),
 *     require(
 *         attributeHistogram(20, "width", "height")
 *     )
 * )
 * ```
 *
 * **Design Rationale**
 *
 * Attributes are a first-class property type (rather than being treated as generic entity properties) because:
 * 1. **Schema definition**: Attributes are defined in the entity schema with type information, indexing rules, and
 *    constraints
 * 2. **Flexible indexing**: Not all attributes are indexed the same way — some are filterable, some sortable, some
 *    unique, some just storable
 * 3. **Localization**: Attributes can have different values per locale, requiring special query handling
 * 4. **API generation**: External APIs need to generate typed accessors for attributes based on schema
 * 5. **Validation**: Attribute queries must be validated against the schema (attribute exists, is filterable/sortable,
 *    has compatible type)
 *
 * **Relationship with EntityConstraint**
 *
 * The distinction between `AttributeConstraint` and {@link EntityConstraint} is:
 * - {@link EntityConstraint}: Operates on built-in entity properties (primary key, type, locale, scope) — present on
 *   every entity
 * - `AttributeConstraint`: Operates on user-defined attributes — schema-configured, entity-type-specific
 *
 * For example:
 * - `entityPrimaryKeyInSet(1, 2, 3)` is an {@link EntityConstraint}
 * - `attributeEquals("name", "iPhone")` is an `AttributeConstraint`
 *
 * **Schema Validation**
 *
 * At application startup, {@link io.evitadb.api.query.descriptor.ConstraintProcessor} validates that each constraint
 * class implements exactly one property-type-defining interface. Constraints implementing `AttributeConstraint` are
 * registered with the `ATTRIBUTE` property type in the constraint descriptor registry.
 *
 * At query execution time, evitaDB validates:
 * - The attribute exists in the entity schema
 * - The attribute has the required indexing (filterable for filters, sortable for ordering)
 * - The attribute type is compatible with the constraint's value type
 * - For localized attributes, a locale is specified via `entityLocaleEquals`
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. The attribute name accessor methods have no side
 * effects and can be safely called from multiple threads concurrently.
 *
 * @param <T> the constraint type classification (FilterConstraint, OrderConstraint, or RequireConstraint)
 * @see PropertyTypeDefiningConstraint parent interface for all property-type-defining interfaces
 * @see io.evitadb.api.query.descriptor.ConstraintPropertyType enum defining all property types
 * @see io.evitadb.api.query.descriptor.ConstraintProcessor constraint metadata processor
 * @see EntityConstraint for core entity property constraints
 * @see AssociatedDataConstraint for complex structured data constraints
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface AttributeConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {

	/**
	 * Returns the single attribute name targeted by this constraint. This method provides uniform access to attribute
	 * names for constraint visitors and query processors without requiring type-specific casting or reflection.
	 *
	 * **Usage Context:**
	 *
	 * This method is primarily used by:
	 * - Constraint visitors that need to extract attribute names from constraint trees
	 * - Query planners that map attribute constraints to attribute indexes
	 * - Schema validators that verify attribute existence and indexing
	 * - External API generators that create typed attribute accessors
	 *
	 * **Error Conditions:**
	 *
	 * This method throws an exception if the constraint targets multiple attributes (e.g., `AttributeContent` with
	 * multiple attribute names or `AttributeHistogram` with multiple attributes). For constraints that may target
	 * multiple attributes, use {@link #getAttributeNames()} instead.
	 *
	 * **Default Implementation:**
	 *
	 * The default implementation extracts the first (and only) element from {@link #getAttributeNames()}. Most
	 * attribute constraints override this method directly since they target a single attribute. Constraints targeting
	 * multiple attributes override {@link #getAttributeNames()} instead and rely on the default implementation of
	 * this method to throw an exception.
	 *
	 * **Thread Safety:**
	 *
	 * This method is thread-safe and has no side effects. It can be safely called concurrently from multiple threads.
	 *
	 * @return the attribute name (e.g., "name", "price", "visible")
	 * @throws EvitaInvalidUsageException if the constraint does not define any attribute name (empty array from
	 *                                    {@link #getAttributeNames()})
	 * @throws EvitaInvalidUsageException if the constraint defines more than one attribute name (use
	 *                                    {@link #getAttributeNames()} instead)
	 */
	@Nonnull
	default String getAttributeName() throws IllegalArgumentException {
		final String[] attributeNames = getAttributeNames();
		if (attributeNames.length == 0) {
			throw new EvitaInvalidUsageException("Constraint does not define any attribute name!");
		} else if (attributeNames.length > 1) {
			throw new EvitaInvalidUsageException("Constraint defines more than one attribute name!");
		} else {
			return attributeNames[0];
		}
	}

	/**
	 * Returns all attribute names targeted by this constraint. This method provides uniform access to attribute names
	 * for constraints that may operate on multiple attributes simultaneously (e.g., `AttributeContent`,
	 * `AttributeHistogram`).
	 *
	 * **Usage Context:**
	 *
	 * This method is primarily used by:
	 * - Constraint visitors that enumerate all attributes referenced in a query
	 * - Query planners that determine which attribute indexes need to be accessed
	 * - Schema validators that verify all referenced attributes exist
	 * - External API generators that create bulk attribute accessors
	 *
	 * **Default Implementation:**
	 *
	 * The default implementation returns a single-element array containing the result of {@link #getAttributeName()}.
	 * This works for constraints targeting a single attribute. Constraints targeting multiple attributes override
	 * this method directly and extract attribute names from their arguments.
	 *
	 * **Implementation Note:**
	 *
	 * Constraint implementations should override **either** `getAttributeName()` **or** `getAttributeNames()`, not
	 * both. The two methods have default implementations that delegate to each other, creating a circular dependency
	 * that is resolved by overriding one method in the concrete constraint class:
	 * - Single-attribute constraints: Override `getAttributeName()` → default `getAttributeNames()` wraps it in array
	 * - Multi-attribute constraints: Override `getAttributeNames()` → default `getAttributeName()` extracts first
	 *   element or throws exception
	 *
	 * **Thread Safety:**
	 *
	 * This method is thread-safe and has no side effects. It can be safely called concurrently from multiple threads.
	 * The returned array should be treated as read-only.
	 *
	 * @return array of attribute names targeted by this constraint (may be empty, never null)
	 */
	@Nonnull
	default String[] getAttributeNames() {
		return new String[] {getAttributeName()};
	}

}
