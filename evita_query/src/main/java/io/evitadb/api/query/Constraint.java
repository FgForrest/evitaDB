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

package io.evitadb.api.query;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * The root interface of evitaDB's constraint system, representing a single unit of EvitaQL (evita Query Language).
 * Constraints are the building blocks of evitaDB queries, expressing filtering conditions, ordering specifications,
 * data requirements, and query metadata. Every element of an evitaDB query — from a simple `equals` comparison to
 * a complex faceted navigation setup — is represented as a constraint implementing this interface.
 *
 * **Design Context**
 *
 * evitaDB queries are structured as trees of constraints, where:
 * - **Leaf constraints** carry specific logic (e.g., `attributeEquals("name", "iPhone")`)
 * - **Container constraints** organize other constraints (e.g., `and(...)`, `or(...)`, `filterBy(...)`)
 * - **Type-defining constraints** mark the purpose of a constraint ({@link TypeDefiningConstraint} — filter, order,
 *   require, head)
 * - **Property-defining constraints** mark the data type targeted ({@link PropertyTypeDefiningConstraint} — attribute,
 *   price, reference, etc.)
 *
 * This hierarchical design enables composition of complex queries from simple, reusable components. All constraints
 * are immutable by design — modifications return new constraint instances rather than changing existing ones.
 *
 * **Two-Dimensional Classification System**
 *
 * Every concrete constraint in evitaDB implements this interface through a combination of two classification
 * dimensions:
 *
 * 1. **Purpose (Type)** — what the constraint does in the query:
 *    - {@link io.evitadb.api.query.HeadConstraint}: Specifies target collection and query metadata
 *    - {@link io.evitadb.api.query.FilterConstraint}: Defines which entities match the query (WHERE clause)
 *    - {@link io.evitadb.api.query.OrderConstraint}: Defines result ordering (ORDER BY clause)
 *    - {@link io.evitadb.api.query.RequireConstraint}: Specifies data fetching and extra computations
 *
 * 2. **Property Type** — what kind of data the constraint operates on:
 *    - {@link io.evitadb.api.query.GenericConstraint}: Structural operations (and, or, not, pagination)
 *    - {@link io.evitadb.api.query.EntityConstraint}: Core entity properties (primary key, locale, scope)
 *    - {@link io.evitadb.api.query.AttributeConstraint}: Entity/reference attributes
 *    - {@link io.evitadb.api.query.AssociatedDataConstraint}: Complex structured data
 *    - {@link io.evitadb.api.query.PriceConstraint}: Price lists with currency and validity
 *    - {@link io.evitadb.api.query.ReferenceConstraint}: Entity references and relationships
 *    - {@link io.evitadb.api.query.HierarchyConstraint}: Hierarchical tree structures
 *    - {@link io.evitadb.api.query.FacetConstraint}: Faceted navigation and statistics
 *
 * **Key Behavioral Contracts**
 *
 * All constraint implementations must satisfy:
 * - **Immutability**: Constraints are immutable value objects. State changes produce new instances.
 * - **Serializability**: Constraints must be serializable for cross-network communication (gRPC, REST APIs).
 * - **String representation**: Constraints must support round-trip conversion to/from EvitaQL string syntax.
 * - **Visitor pattern**: Constraints accept visitors via {@link #accept(ConstraintVisitor)} for traversal and
 *   transformation.
 * - **Applicability**: Constraints validate themselves via {@link #isApplicable()} to detect incomplete or invalid
 *   states.
 * - **Cloneability**: Constraints support argument replacement via {@link #cloneWithArguments(Serializable[])} for
 *   query manipulation.
 *
 * **Visitor Pattern for Traversal**
 *
 * Constraints implement the GOF Visitor pattern for tree traversal. The {@link #accept(ConstraintVisitor)} method
 * calls `visitor.visit(this)` but does NOT recurse into child constraints — the visitor implementation controls
 * traversal order and depth. This design allows different traversal strategies (depth-first, breadth-first, selective
 * pruning) without modifying constraint classes.
 *
 * Common visitor implementations:
 * - {@link io.evitadb.api.query.visitor.FinderVisitor}: Searches for constraints matching a predicate
 * - {@link io.evitadb.api.query.visitor.PrettyPrintingVisitor}: Formats constraints as human-readable EvitaQL
 * - {@link io.evitadb.api.query.visitor.ConstraintCloneVisitor}: Creates modified copies of constraint trees
 * - {@link io.evitadb.api.query.visitor.QueryPurifierVisitor}: Removes invalid or redundant constraints
 *
 * **String Representation and Parsing**
 *
 * Constraints have a canonical string form following EvitaQL syntax: `constraintName(arg1, arg2, ...)`.
 * The {@link #toString()} method produces this representation, and the evitaQL parser can reconstruct constraints
 * from strings. This bidirectional conversion enables query logging, debugging, and API interoperability.
 *
 * Arguments are automatically converted to supported types via {@link io.evitadb.dataType.EvitaDataTypes}.
 *
 * **Type Parameter**
 *
 * The generic type parameter `T` enables self-referential typing for type-safe constraint manipulation:
 * ```java
 * public interface FilterConstraint extends Constraint<FilterConstraint> { }
 * ```
 * This allows methods like `cloneWithArguments` to return the specific constraint type rather than the generic
 * `Constraint` interface, maintaining type information through transformations.
 *
 * **Example Usage**
 *
 * ```java
 * // Leaf constraint
 * FilterConstraint priceFilter = attributeEquals("visible", true);
 * priceFilter.getName();        // "attributeEquals"
 * priceFilter.getArguments();   // ["visible", true]
 * priceFilter.toString();       // "attributeEquals('visible',true)"
 * priceFilter.isApplicable();   // true (all required arguments present)
 *
 * // Container constraint
 * FilterConstraint combined = and(
 *     attributeEquals("visible", true),
 *     priceBetween(100, 1000)
 * );
 * combined.getName();           // "and"
 * combined.getType();           // FilterConstraint.class
 *
 * // Visitor pattern for traversal
 * List<AttributeConstraint> attrs = FinderVisitor.findConstraints(
 *     combined,
 *     constraint -> constraint instanceof AttributeConstraint
 * );
 * ```
 *
 * **Implementation Guidelines**
 *
 * Most constraint implementations extend {@link io.evitadb.api.query.BaseConstraint} which provides:
 * - Automatic argument storage and type conversion
 * - Default `toString()` implementation
 * - Argument validation helpers
 * - Name derivation from class name
 *
 * Container constraints extend {@link io.evitadb.api.query.ConstraintContainer} for child constraint management.
 *
 * **Thread Safety**
 *
 * All constraint implementations must be immutable and thread-safe. Constraints are frequently shared across multiple
 * concurrent query executions without synchronization.
 *
 * @param <T> the specific constraint type, enabling self-referential typing for type-safe operations
 * @see TypeDefiningConstraint
 * @see PropertyTypeDefiningConstraint
 * @see io.evitadb.api.query.BaseConstraint
 * @see io.evitadb.api.query.ConstraintContainer
 * @see ConstraintVisitor
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface Constraint<T extends Constraint<T>> extends Serializable {
	/**
	 * Opening parenthesis used in EvitaQL string representation.
	 */
	String ARG_OPENING = "(";

	/**
	 * Closing parenthesis used in EvitaQL string representation.
	 */
	String ARG_CLOSING = ")";

	/**
	 * Returns the constraint's name as it appears in EvitaQL syntax. The name is typically derived from the
	 * constraint's class name by uncapitalizing it (e.g., `AttributeEquals` becomes `attributeEquals`).
	 *
	 * For constraints implementing {@link io.evitadb.api.query.ConstraintWithSuffix}, the name includes the
	 * applicable suffix (e.g., `facetSummary` + `OfReference` = `facetSummaryOfReference`).
	 *
	 * This name is used for:
	 * - String representation via {@link #toString()}
	 * - Parsing EvitaQL strings into constraint objects
	 * - API schema generation for external APIs (GraphQL, REST, gRPC)
	 * - Documentation and error messages
	 *
	 * @return the constraint name in EvitaQL syntax (e.g., "attributeEquals", "and", "priceInCurrency")
	 */
	@Nonnull
	String getName();

	/**
	 * Returns the runtime type of this constraint, used for type checking and generic programming. The returned
	 * class represents the constraint's type classification (e.g., `FilterConstraint.class`, `OrderConstraint.class`).
	 *
	 * This method enables:
	 * - Type-safe downcasting without `instanceof` checks
	 * - Generic constraint processing where the constraint type parameter is not statically known
	 * - Validation that constraints are used in appropriate contexts
	 *
	 * Implementation note: Most implementations simply return the interface type from their type hierarchy (e.g.,
	 * a filter constraint returns `FilterConstraint.class`).
	 *
	 * @return the class representing this constraint's type in the type hierarchy
	 */
	@Nonnull
	Class<T> getType();

	/**
	 * Returns the arguments passed to this constraint. Arguments are the primitive values, strings, enums, or arrays
	 * that parameterize the constraint's behavior (e.g., attribute name, comparison value, price range bounds).
	 *
	 * Arguments are automatically converted to evitaDB-supported types via
	 * {@link io.evitadb.dataType.EvitaDataTypes#toSupportedType} during constraint construction. Unsupported types
	 * are normalized to their closest supported representation.
	 *
	 * The returned array should not be modified. Changes require creating a new constraint via
	 * {@link #cloneWithArguments(Serializable[])}.
	 *
	 * For container constraints, arguments typically include configuration parameters but not child constraints
	 * (which are accessed via {@link io.evitadb.api.query.ConstraintContainer#getChildren()} instead).
	 *
	 * @return array of constraint arguments (may be empty for constraints without parameters)
	 */
	@Nonnull
	Serializable[] getArguments();

	/**
	 * Determines whether this constraint contains sufficient information to be processed by the query engine.
	 * A constraint is applicable if all required arguments are present and valid.
	 *
	 * Constraints may be constructed in invalid states during query building or manipulation. This method allows
	 * the query engine to detect and skip incomplete constraints before execution.
	 *
	 * Common reasons for non-applicability:
	 * - Required arguments are `null`
	 * - Argument values are invalid or out of range
	 * - Container has no children when children are required
	 * - Conflicting arguments that make the constraint logically meaningless
	 *
	 * Example: An `attributeEquals` constraint without an attribute name would return `false` here.
	 *
	 * @return `true` if this constraint is complete and ready for query processing, `false` otherwise
	 */
	boolean isApplicable();

	/**
	 * Implements the GOF <a href="https://en.wikipedia.org/wiki/Visitor_pattern">Visitor pattern</a> for constraint
	 * tree traversal and transformation. This method enables external operations on constraint trees without
	 * modifying constraint classes.
	 *
	 * **Critical Implementation Contract:**
	 * - The constraint MUST call `visitor.visit(this)` to notify the visitor of its presence
	 * - The constraint MUST NOT recurse into child constraints — the visitor controls traversal
	 * - Container constraints should NOT call `accept()` on their children
	 *
	 * The visitor implementation decides:
	 * - Whether to traverse child constraints (depth-first, breadth-first, selective)
	 * - What operations to perform on each constraint (search, transform, validate, format)
	 * - When to stop traversal early (pruning)
	 *
	 * This separation of concerns allows multiple traversal strategies without modifying constraint implementations.
	 *
	 * **Common Visitor Implementations:**
	 * - {@link io.evitadb.api.query.visitor.FinderVisitor}: Finds constraints matching a predicate
	 * - {@link io.evitadb.api.query.visitor.PrettyPrintingVisitor}: Formats constraints as readable EvitaQL
	 * - {@link io.evitadb.api.query.visitor.ConstraintCloneVisitor}: Creates modified constraint tree copies
	 * - {@link io.evitadb.api.query.visitor.QueryPurifierVisitor}: Removes invalid constraints
	 *
	 * Example visitor usage:
	 * ```java
	 * FinderVisitor visitor = new FinderVisitor(c -> c instanceof AttributeConstraint);
	 * constraint.accept(visitor);
	 * List<AttributeConstraint> found = visitor.getResults();
	 * ```
	 *
	 * @param visitor the visitor that will process this constraint
	 */
	void accept(@Nonnull ConstraintVisitor visitor);

	/**
	 * Produces the canonical string representation of this constraint in EvitaQL syntax. The format follows the
	 * pattern: `constraintName(arg1, arg2, ..., argN)`.
	 *
	 * The string representation:
	 * - Matches the syntax accepted by the evitaQL parser (round-trip conversion)
	 * - Uses {@link io.evitadb.dataType.EvitaDataTypes#formatValue} for argument formatting
	 * - Includes child constraints for containers (nested format)
	 * - Respects {@link io.evitadb.api.query.ConstraintWithDefaults} to hide default argument values
	 * - Respects {@link io.evitadb.api.query.ConstraintWithSuffix} to hide implicit arguments
	 *
	 * The output is designed for:
	 * - Query logging and debugging
	 * - API request/response serialization
	 * - Human-readable query display
	 * - Round-trip parsing (parser can reconstruct the constraint from this string)
	 *
	 * For formatted multi-line output, use {@link io.evitadb.api.query.visitor.PrettyPrintingVisitor} instead.
	 *
	 * @return EvitaQL string representation of this constraint (e.g., "attributeEquals('name','iPhone')")
	 */
	@Nonnull
	String toString();

	/**
	 * Creates a new constraint instance identical to this one but with different arguments. This method enables
	 * immutable constraint modification — instead of changing arguments in place, a new constraint is returned.
	 *
	 * The new constraint:
	 * - Has the same type and structure as the original
	 * - Uses the provided arguments instead of the original arguments
	 * - For containers, preserves child constraints (only arguments are replaced)
	 * - Goes through the same validation and type conversion as normal construction
	 *
	 * This method is used by:
	 * - Query manipulation and optimization
	 * - Parameter binding in prepared queries
	 * - Constraint transformation visitors
	 * - Query builder APIs that need to modify existing constraints
	 *
	 * The new arguments array must be compatible with the constraint's expected argument types and count, otherwise
	 * the behavior depends on the specific constraint implementation (typically results in an exception or
	 * non-applicable constraint).
	 *
	 * Example:
	 * ```java
	 * FilterConstraint original = attributeEquals("name", "iPhone");
	 * FilterConstraint modified = original.cloneWithArguments(new Serializable[]{"name", "Samsung"});
	 * // modified: attributeEquals('name','Samsung')
	 * ```
	 *
	 * @param newArguments the new arguments to use for the cloned constraint
	 * @return a new constraint instance with the specified arguments
	 */
	@Nonnull
	T cloneWithArguments(@Nonnull Serializable[] newArguments);

}
