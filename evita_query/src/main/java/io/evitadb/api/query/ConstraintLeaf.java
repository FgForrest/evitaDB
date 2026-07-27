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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for leaf constraints — terminal nodes in the constraint tree that cannot contain child
 * constraints. Leaf constraints represent atomic operations like comparisons, filters, and data fetch specifications.
 *
 * **Design Purpose**
 *
 * `ConstraintLeaf` represents the terminal nodes of the constraint tree, as opposed to {@link ConstraintContainer}
 * which represents structural/organizational nodes. Leaf constraints carry the actual query logic:
 * - Attribute comparisons: `attributeEquals("code", "PHONE-123")`, `attributeGreaterThan("price", 100)`
 * - Entity filters: `entityPrimaryKeyInSet(1, 2, 3)`, `entityLocaleEquals(Locale.ENGLISH)`
 * - Price filters: `priceInPriceLists("basic", "vip")`, `priceInCurrency("USD")`
 * - Data fetch requirements: `entityFetch()`, `attributeContent("code", "name")`
 * - Pagination: `page(1, 20)`, `strip(0, 10)`
 *
 * **Structural Constraint**
 *
 * The defining characteristic of leaf constraints is that they **cannot contain child constraints**. Any attempt
 * to pass a `Constraint` instance as an argument will trigger an {@link io.evitadb.exception.EvitaInvalidUsageException}
 * during construction. This enforcement happens in {@link #rejectConstraintArguments(Serializable[])} which is
 * called by all constructors.
 *
 * Leaf constraints can only have scalar arguments (strings, numbers, dates, enums, etc.) — the actual data values
 * that parameterize the constraint's behavior.
 *
 * **Immutability**
 *
 * Like all constraints, leaf instances are immutable. All fields are final, and the arguments array (inherited from
 * {@link BaseConstraint}) is defensively handled to prevent modifications.
 *
 * **Usage Examples**
 *
 * Most concrete leaf constraints extend one of the intermediate abstract classes:
 * - {@link io.evitadb.api.query.filter.AbstractFilterConstraintLeaf} for filter leaves
 * - {@link io.evitadb.api.query.order.AbstractOrderConstraintLeaf} for order leaves
 * - {@link io.evitadb.api.query.require.AbstractRequireConstraintLeaf} for require leaves
 * - {@link io.evitadb.api.query.head.AbstractHeadConstraintLeaf} for head leaves
 *
 * Typical usage in queries:
 * ```java
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("code", "PHONE-123"),      // leaf constraint
 *             attributeGreaterThan("price", 100),        // leaf constraint
 *             entityPrimaryKeyInSet(1, 2, 3)             // leaf constraint
 *         )
 *     ),
 *     orderBy(
 *         attributeNatural("name", ASC)                  // leaf constraint
 *     ),
 *     require(
 *         page(1, 20),                                   // leaf constraint
 *         entityFetch(attributeContent("code", "name"))  // container with leaf inside
 *     )
 * )
 * ```
 *
 * **Thread Safety**
 *
 * Instances are immutable and thread-safe. The same leaf constraint instance can be safely shared across
 * multiple threads and reused in multiple queries.
 *
 * @param <T> the type of constraint this leaf implements (FilterConstraint, OrderConstraint, etc.)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
public abstract class ConstraintLeaf<T extends Constraint<T>> extends BaseConstraint<T> {
	@Serial private static final long serialVersionUID = 3842640572690004094L;

	/**
	 * Constructs a leaf constraint with an explicit name and the specified arguments.
	 *
	 * @param name the explicit constraint name; if null, the default name (derived from class name) is used
	 * @param arguments the constraint arguments (must be scalar values, not constraints)
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if any argument is a Constraint instance
	 */
	protected ConstraintLeaf(@Nonnull String name, @Nonnull Serializable... arguments) {
		super(name, arguments);
		rejectConstraintArguments(arguments);
	}

	/**
	 * Constructs a leaf constraint with the default name (derived from the class name) and the specified arguments.
	 *
	 * @param arguments the constraint arguments (must be scalar values, not constraints)
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if any argument is a Constraint instance
	 */
	protected ConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
		rejectConstraintArguments(arguments);
	}

	/**
	 * Validates that none of the arguments are {@link Constraint} instances.
	 *
	 * Leaf constraints represent atomic operations and cannot contain nested constraints. This validation
	 * enforces the structural integrity of the constraint tree by ensuring that all constraint nesting
	 * happens through {@link ConstraintContainer} instances, not through leaf arguments.
	 *
	 * This check catches programming errors where a developer might accidentally try to pass a constraint
	 * as an argument to a leaf constraint, which would violate the tree structure.
	 *
	 * @param arguments the arguments to validate
	 * @throws EvitaInvalidUsageException if any argument is a Constraint instance
	 */
	private void rejectConstraintArguments(@Nonnull Serializable[] arguments) {
		for (final Serializable argument : arguments) {
			if (argument instanceof Constraint) {
				throw new EvitaInvalidUsageException(
					"Constraint argument is not allowed for leaf query (" + getName() + ")."
				);
			}
		}
	}

}
