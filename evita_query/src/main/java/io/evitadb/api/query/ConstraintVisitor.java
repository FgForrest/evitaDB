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

import javax.annotation.Nonnull;

/**
 * Defines the visitor interface for traversing and processing evitaDB constraint trees using the GOF Visitor pattern.
 * This interface enables external operations on constraints — searching, transformation, validation, formatting —
 * without modifying constraint classes. Visitors control traversal order, depth, and pruning logic, separating tree
 * navigation from constraint implementation.
 *
 * **Design Rationale**
 *
 * evitaDB queries are hierarchical trees of {@link Constraint} objects. Many operations need to traverse these trees:
 * finding specific constraints, transforming constraints, validating structure, or formatting for output. Without the
 * visitor pattern, these operations would require constraint classes to expose implementation details or duplicate
 * traversal logic across the codebase.
 *
 * The visitor pattern solves this by:
 * - **Separating traversal from constraint logic**: Constraints don't know how to traverse themselves
 * - **Centralizing tree operations**: Each operation (search, transform, validate) is a single visitor class
 * - **Enabling multiple traversal strategies**: Depth-first, breadth-first, selective pruning, etc.
 * - **Type-safe double dispatch**: The constraint type determines which visitor method is called
 *
 * **Visitor Responsibilities**
 *
 * A `ConstraintVisitor` implementation controls:
 * 1. **What to do with each constraint**: Collect it, transform it, validate it, format it, count it, etc.
 * 2. **Whether to traverse children**: For container constraints, decide whether to recurse into child constraints
 * 3. **Traversal order**: Visit children depth-first, breadth-first, or in a custom order
 * 4. **Early termination**: Stop traversal when a condition is met (e.g., first match found)
 *
 * **Constraint Responsibilities**
 *
 * Constraints have a simple contract:
 * - Call `visitor.visit(this)` in their {@link Constraint#accept(ConstraintVisitor)} method
 * - Do NOT recurse into children — the visitor handles that
 *
 * This clear separation allows visitors to implement sophisticated traversal strategies without modifying constraints.
 *
 * **Common Visitor Implementations**
 *
 * evitaDB provides several standard visitors:
 *
 * - **{@link io.evitadb.api.query.visitor.FinderVisitor}**: Searches for constraints matching a predicate.
 *   Recursively visits all constraints and collects those satisfying a condition.
 *
 * - **{@link io.evitadb.api.query.visitor.PrettyPrintingVisitor}**: Formats constraints as human-readable EvitaQL.
 *   Produces indented, multi-line strings with proper parenthesization and argument formatting.
 *
 * - **{@link io.evitadb.api.query.visitor.ConstraintCloneVisitor}**: Creates modified copies of constraint trees.
 *   Clones constraints while optionally transforming or filtering them.
 *
 * - **{@link io.evitadb.api.query.visitor.QueryPurifierVisitor}**: Removes invalid or redundant constraints.
 *   Validates applicability and eliminates constraints that cannot be executed.
 *
 * Additional visitors in the engine module handle query execution:
 * - `FilterByVisitor`: Processes filter constraints into bitmap operations
 * - `OrderByVisitor`: Processes order constraints into sorted result sets
 * - `ExtraResultPlanningVisitor`: Plans histogram and statistics computation
 *
 * **Traversal Pattern**
 *
 * Typical visitor traversal for a {@link io.evitadb.api.query.ConstraintContainer}:
 *
 * ```java
 * @Override
 * public void visit(@Nonnull Constraint<?> constraint) {
 *     // 1. Process the constraint itself (collect, transform, validate, etc.)
 *     processConstraint(constraint);
 *
 *     // 2. If it's a container, decide whether to recurse into children
 *     if (constraint instanceof ConstraintContainer<?> container) {
 *         if (shouldTraverseChildren(container)) {
 *             for (Constraint<?> child : container.getChildren()) {
 *                 child.accept(this);  // Recursively visit children
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * **Type Safety and Double Dispatch**
 *
 * The visitor pattern uses double dispatch to achieve type-safe processing:
 * 1. Client calls `constraint.accept(visitor)` — first dispatch on constraint type
 * 2. Constraint calls `visitor.visit(this)` — second dispatch on visitor type
 *
 * In Java, this is simpler than classic GOF visitors because we have a single `visit(Constraint)` method rather than
 * separate methods per constraint type. Implementations use `instanceof` checks or reflection when type-specific
 * behavior is needed.
 *
 * **Thread Safety**
 *
 * Visitor implementations are typically **not thread-safe**. They maintain mutable state (accumulated results,
 * traversal depth counters, etc.) and should not be shared across threads. Create a new visitor instance for each
 * traversal operation.
 *
 * Many visitor classes are annotated with `@NotThreadSafe` to document this constraint.
 *
 * **Usage Examples**
 *
 * ```java
 * // Example 1: Find all AttributeConstraints in a query
 * Query query = query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("visible", true),
 *             priceBetween(100, 1000)
 *         )
 *     )
 * );
 *
 * List<AttributeConstraint> attributeConstraints = FinderVisitor.findConstraints(
 *     query,
 *     c -> c instanceof AttributeConstraint
 * );
 * // Result: [attributeEquals("visible", true)]
 *
 * // Example 2: Pretty-print a query
 * String formatted = PrettyPrintingVisitor.toString(query, "\t");
 * // Result:
 * // query(
 * //     collection('Product'),
 * //     filterBy(
 * //         and(
 * //             attributeEquals('visible',true),
 * //             priceBetween(100,1000)
 * //         )
 * //     )
 * // )
 *
 * // Example 3: Custom visitor to count constraints
 * class CountingVisitor implements ConstraintVisitor {
 *     private int count = 0;
 *
 *     @Override
 *     public void visit(@Nonnull Constraint<?> constraint) {
 *         count++;
 *         if (constraint instanceof ConstraintContainer<?> container) {
 *             for (Constraint<?> child : container.getChildren()) {
 *                 child.accept(this);
 *             }
 *         }
 *     }
 *
 *     public int getCount() { return count; }
 * }
 *
 * CountingVisitor counter = new CountingVisitor();
 * query.accept(counter);
 * System.out.println("Total constraints: " + counter.getCount());
 * ```
 *
 * **Performance Considerations**
 *
 * Visitor traversal is efficient for typical query trees (depth 3-10, hundreds of constraints). For very large
 * or deeply nested queries, consider:
 * - Early termination when the desired result is found
 * - Pruning subtrees that don't need to be visited (use a stopper predicate in `FinderVisitor`)
 * - Avoiding redundant traversals by caching results
 *
 * **Extending with Custom Visitors**
 *
 * To implement a custom visitor:
 * 1. Implement `ConstraintVisitor` interface
 * 2. Maintain any state needed for the operation (results list, counters, flags)
 * 3. In `visit()`, process the constraint and decide whether to recurse
 * 4. Provide getter methods to retrieve results after traversal
 * 5. Document thread safety (usually not thread-safe)
 *
 * @see Constraint#accept(ConstraintVisitor)
 * @see io.evitadb.api.query.visitor.FinderVisitor
 * @see io.evitadb.api.query.visitor.PrettyPrintingVisitor
 * @see io.evitadb.api.query.visitor.ConstraintCloneVisitor
 * @see io.evitadb.api.query.visitor.QueryPurifierVisitor
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface ConstraintVisitor {

	/**
	 * Visits and processes a single constraint during tree traversal. This method is called by
	 * {@link Constraint#accept(ConstraintVisitor)} as part of the visitor pattern's double-dispatch mechanism.
	 *
	 * **Implementation Responsibilities:**
	 *
	 * 1. **Process the constraint**: Perform the operation this visitor is designed for (collect, transform, validate,
	 *    format, count, etc.)
	 *
	 * 2. **Control recursion**: If the constraint is a {@link io.evitadb.api.query.ConstraintContainer}, decide
	 *    whether to visit its children by calling `child.accept(this)` on each child. The constraint itself does NOT
	 *    recurse — the visitor controls traversal.
	 *
	 * 3. **Maintain state**: Update any internal state (result collections, counters, flags) based on the visited
	 *    constraint.
	 *
	 * **Traversal Control:**
	 *
	 * The visitor decides:
	 * - Whether to visit children (selective traversal, early termination)
	 * - In what order to visit children (depth-first, breadth-first, custom)
	 * - Whether to visit additional children (containers may have both primary and additional children)
	 *
	 * Example implementation:
	 * ```java
	 * @Override
	 * public void visit(@Nonnull Constraint<?> constraint) {
	 *     // Process the constraint
	 *     if (matcher.test(constraint)) {
	 *         results.add(constraint);
	 *     }
	 *
	 *     // Recurse into children if it's a container
	 *     if (constraint instanceof ConstraintContainer<?> container) {
	 *         for (Constraint<?> child : container.getChildren()) {
	 *             child.accept(this);  // Recursive visit
	 *         }
	 *         for (Constraint<?> additionalChild : container.getAdditionalChildren()) {
	 *             additionalChild.accept(this);  // Visit additional children too
	 *         }
	 *     }
	 * }
	 * ```
	 *
	 * **Thread Safety:**
	 *
	 * This method is typically NOT thread-safe because visitors maintain mutable state. Do not call this method
	 * concurrently from multiple threads on the same visitor instance.
	 *
	 * @param constraint the constraint to visit and process
	 */
	void visit(@Nonnull Constraint<?> constraint);

}
