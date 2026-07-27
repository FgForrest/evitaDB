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

import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for all leaf filter constraints — terminal nodes in the filter constraint tree that cannot
 * contain child constraints. Leaf filter constraints represent atomic filtering operations like comparisons, set
 * membership tests, and entity property checks.
 *
 * **Design Purpose**
 *
 * This class specializes {@link ConstraintLeaf} for the filter constraint domain by restricting the constraint type
 * to {@link FilterConstraint}. It serves as the foundation for all terminal filtering conditions in evitaDB queries,
 * analogous to predicates in SQL `WHERE` clauses.
 *
 * **Class Hierarchy**
 *
 * The filter constraint leaf hierarchy is organized by property domains:
 * - **Entity constraints**: {@link EntityPrimaryKeyInSet}, {@link EntityLocaleEquals}, {@link EntityScope} — filter
 *   by entity-level properties (primary keys, locales, scopes)
 * - **Price constraints**: {@link PriceInCurrency}, {@link PriceInPriceLists}, {@link PriceBetween},
 *   {@link PriceValidIn} — filter by price data (currency, price lists, value ranges, temporal validity)
 * - **Attribute constraints**: All subclasses of {@link AbstractAttributeFilterConstraintLeaf} — filter by
 *   user-defined attributes (see that class for the attribute filter hierarchy)
 * - **Hierarchy constraints**: {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot} — filter hierarchical
 *   relationships (parent-child, tree structure)
 *
 * **Helper Methods**
 *
 * This class provides protected `concat()` utility methods for building argument arrays in subclass constructors.
 * These methods simplify varargs handling when constraints need to combine fixed parameters with variable-length
 * argument lists:
 *
 * ```java
 * // In AttributeInSet constructor
 * public AttributeInSet(String attributeName, Serializable... values) {
 *     super(concat(attributeName, values)); // prepend attributeName before values array
 * }
 * ```
 *
 * **Structural Constraints**
 *
 * Like all leaf constraints, instances of this class:
 * - Cannot contain child constraints (enforced by {@link ConstraintLeaf})
 * - Only accept scalar arguments (strings, numbers, dates, enums, etc.)
 * - Are immutable — all fields are final
 * - Are thread-safe due to immutability
 *
 * **Applicability**
 *
 * A filter leaf is considered applicable ({@link #isApplicable()}) if it has at least one non-null argument. Empty
 * filter leaves are removed during query normalization.
 *
 * **Usage Examples**
 *
 * Typical usage in filter expressions:
 * ```java
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityPrimaryKeyInSet(1, 2, 3),         // entity constraint leaf
 *             priceInCurrency("USD"),                  // price constraint leaf
 *             equals("visible", true)                  // attribute constraint leaf
 *         )
 *     )
 * );
 * ```
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 * @see AbstractFilterConstraintContainer
 * @see AbstractAttributeFilterConstraintLeaf
 * @see FilterConstraint
 * @see ConstraintLeaf
 */
abstract class AbstractFilterConstraintLeaf extends ConstraintLeaf<FilterConstraint> implements FilterConstraint {
	@Serial private static final long serialVersionUID = -474943967147232148L;

	public AbstractFilterConstraintLeaf(@Nonnull String name, @Nonnull Serializable... arguments) {
		super(name, arguments);
	}

	protected AbstractFilterConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Nonnull
	@Override
	public Class<FilterConstraint> getType() {
		return FilterConstraint.class;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 0;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

	/**
	 * Creates a serializable array by prepending `firstArg` before all elements of `rest`.
	 *
	 * @param firstArg the first argument to place at index 0
	 * @param rest     the remaining arguments to append after the first
	 * @param <T>      the element type
	 * @return a new array containing `firstArg` followed by all elements of `rest`
	 */
	@Nonnull
	protected static <T extends Serializable> Serializable[] concat(@Nonnull T firstArg, @Nonnull T[] rest) {
		final Serializable[] result = new Serializable[rest.length + 1];
		result[0] = firstArg;
		System.arraycopy(rest, 0, result, 1, rest.length);
		return result;
	}

	/**
	 * Creates a serializable array by prepending `firstArg` and `secondArg` before all elements of `rest`.
	 *
	 * @param firstArg  the first argument to place at index 0
	 * @param secondArg the second argument to place at index 1
	 * @param rest      the remaining arguments to append
	 * @param <T>       the element type
	 * @return a new array containing `firstArg`, `secondArg`, then all elements of `rest`
	 */
	@Nonnull
	protected static <T extends Serializable> Serializable[] concat(
		@Nonnull T firstArg,
		@Nonnull T secondArg,
		@Nonnull T[] rest
	) {
		final Serializable[] result = new Serializable[rest.length + 2];
		result[0] = firstArg;
		result[1] = secondArg;
		System.arraycopy(rest, 0, result, 2, rest.length);
		return result;
	}

}
