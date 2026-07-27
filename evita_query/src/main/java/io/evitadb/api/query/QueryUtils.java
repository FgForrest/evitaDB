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

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.query.visitor.FinderVisitor.PredicateWithDescription;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Utility class providing methods for searching, extracting, and analyzing constraints within {@link Query}
 * and {@link Constraint} trees.
 *
 * **Purpose**
 *
 * `QueryUtils` serves as the primary API for navigating constraint hierarchies. It provides methods to:
 * - Find single constraints by type or predicate
 * - Find all constraints matching a type or predicate
 * - Search within specific query sections (filter, order, require)
 * - Control search depth with stopper predicates
 * - Compare constraint argument values
 *
 * **Common Usage Patterns**
 *
 * Finding a specific constraint type:
 * ```java
 * AttributeEquals constraint = QueryUtils.findConstraint(filterBy, AttributeEquals.class);
 * ```
 *
 * Finding all constraints of a type:
 * ```java
 * List<AttributeEquals> allEquals = QueryUtils.findConstraints(filterBy, AttributeEquals.class);
 * ```
 *
 * Finding in specific query sections:
 * ```java
 * Page page = QueryUtils.findRequire(query, Page.class);
 * EntityPrimaryKeyInSet pkFilter = QueryUtils.findFilter(query, EntityPrimaryKeyInSet.class);
 * ```
 *
 * Stopping search at container boundaries:
 * ```java
 * // Find AttributeEquals but don't search inside Or containers
 * AttributeEquals found = QueryUtils.findConstraint(filterBy, AttributeEquals.class, Or.class);
 * ```
 *
 * **Implementation Details**
 *
 * All search methods delegate to {@link io.evitadb.api.query.visitor.FinderVisitor}, which traverses the
 * constraint tree using the visitor pattern. The visitor:
 * - Recursively walks the constraint tree
 * - Applies matcher predicates to find matching constraints
 * - Respects stopper predicates to limit search depth
 * - Collects results into lists (for `findConstraints`) or returns first match (for `findConstraint`)
 *
 * **Type Safety**
 *
 * Methods use generics to return correctly-typed constraints without requiring explicit casts:
 * ```java
 * // Type inference ensures 'page' is of type Page
 * Page page = QueryUtils.findRequire(query, Page.class);
 * ```
 *
 * **Performance Considerations**
 *
 * - Searches are linear in the size of the constraint tree
 * - Use stopper predicates to limit search depth when possible
 * - Use type-specific search methods (findFilter, findOrder, findRequire) when the section is known
 * - Finding a single constraint is more efficient than finding all and taking the first
 *
 * **Thread Safety**
 *
 * All methods are stateless and thread-safe. The FinderVisitor instances created internally are not shared
 * across calls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class QueryUtils {

	/** Private constructor to prevent instantiation of utility class. */
	private QueryUtils() {
	}

	/**
	 * Finds the first constraint of the specified type within the constraint tree.
	 *
	 * This method recursively searches the constraint tree starting from the given root constraint.
	 * It returns the first constraint whose runtime type matches the specified class.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraint to find
	 * @param <T> the type of the constraint to find
	 * @return the first matching constraint, or null if no match is found
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType) {
		return FinderVisitor.findConstraint(constraint, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first constraint matching the specified predicate within the constraint tree.
	 *
	 * This method recursively searches the constraint tree, applying the predicate to each constraint
	 * until a match is found.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param predicate the predicate to test each constraint against
	 * @param <T> the type of the constraint to find
	 * @return the first matching constraint, or null if no match is found
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate) {
		return FinderVisitor.findConstraint(constraint, predicate);
	}

	/**
	 * Finds the first constraint of the specified type, stopping search at specified container boundaries.
	 *
	 * This method is useful when you want to search within a specific scope and avoid descending into
	 * certain container types. For example, finding an `AttributeEquals` but not searching inside `Or`
	 * containers.
	 *
	 * The search stops descending when it encounters a container of the stop type, but the stop container
	 * itself is still checked against the constraint type (unless it's the root).
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraint to find
	 * @param stopContainerType containers of this type will not be descended into
	 * @param <T> the type of the constraint to find
	 * @return the first matching constraint, or null if no match is found
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType, @Nonnull Class<? extends Constraint<?>> stopContainerType) {
		return FinderVisitor.findConstraint(constraint, new ConstraintTypeMatcher(constraintType), cnt -> cnt != constraint && stopContainerType.isInstance(cnt));
	}

	/**
	 * Finds all constraints of the specified type within the constraint tree.
	 *
	 * This method recursively searches the entire constraint tree and returns all constraints whose
	 * runtime type matches the specified class.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraints to find
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(Constraint<?> constraint, @Nonnull Class<T> constraintType) {
		return FinderVisitor.findConstraints(constraint, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds all constraints of the specified type, stopping search at specified container boundaries.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param constraintType the class of the constraints to find
	 * @param stopContainerType containers of this type will not be descended into
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType, @Nonnull Class<? extends Constraint<?>> stopContainerType) {
		return FinderVisitor.findConstraints(constraint, new ConstraintTypeMatcher(constraintType), cnt -> cnt != constraint && stopContainerType.isInstance(cnt));
	}

	/**
	 * Finds all constraints matching the specified predicate within the constraint tree.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param predicate the predicate to test each constraint against
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate) {
		return FinderVisitor.findConstraints(constraint, predicate);
	}

	/**
	 * Finds all constraints matching the specified predicate, with custom stopping logic.
	 *
	 * @param constraint the root constraint to start searching from
	 * @param predicate the predicate to test each constraint against
	 * @param stopper predicate that determines which containers should stop descent (if returns true, children are not searched)
	 * @param <T> the type of the constraints to find
	 * @return list of all matching constraints (empty list if none found)
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate, @Nonnull Predicate<Constraint<?>> stopper) {
		return FinderVisitor.findConstraints(constraint, predicate, stopper);
	}

	/**
	 * Finds the first filter constraint of the specified type within the query's filter section.
	 *
	 * This is a convenience method that searches only within the {@link Query#getFilterBy()} section.
	 * If the query has no filter section, null is returned.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the filter constraint to find
	 * @param <T> the type of the filter constraint to find
	 * @return the first matching filter constraint, or null if none found or query has no filter section
	 */
	@Nullable
	public static <T extends FilterConstraint> T findFilter(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy == null) {
			return null;
		}
		//noinspection unchecked
		return (T) FinderVisitor.findConstraint(filterBy, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first filter constraint of the specified type, stopping at specified container boundaries.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the filter constraint to find
	 * @param stopContainerType filter containers of this type will not be descended into
	 * @param <T> the type of the filter constraint to find
	 * @return the first matching filter constraint, or null if none found or query has no filter section
	 */
	@Nullable
	public static <T extends FilterConstraint> T findFilter(@Nonnull Query query, @Nonnull Class<T> constraintType, @Nonnull Class<? extends FilterConstraint> stopContainerType) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy == null) {
			return null;
		}
		//noinspection unchecked
		return (T) FinderVisitor.findConstraint(filterBy, new ConstraintTypeMatcher(constraintType), stopContainerType::isInstance);
	}

	/**
	 * Finds all filter constraints of the specified type within the query's filter section.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the filter constraints to find
	 * @param <T> the type of the filter constraints to find
	 * @return list of all matching filter constraints (empty list if none found or query has no filter section)
	 */
	@Nonnull
	public static <T extends FilterConstraint> List<T> findFilters(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final FilterBy filterBy = query.getFilterBy();
		if (filterBy == null) {
			return Collections.emptyList();
		}
		//noinspection unchecked
		return (List<T>) (List<?>) FinderVisitor.findConstraints(filterBy, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first order constraint of the specified type within the query's order section.
	 *
	 * This is a convenience method that searches only within the {@link Query#getOrderBy()} section.
	 * If the query has no order section, null is returned.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the order constraint to find
	 * @param <T> the type of the order constraint to find
	 * @return the first matching order constraint, or null if none found or query has no order section
	 */
	@Nullable
	public static <T extends OrderConstraint> T findOrder(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final OrderBy orderBy = query.getOrderBy();
		if (orderBy == null) {
			return null;
		}
		//noinspection unchecked
		return (T) FinderVisitor.findConstraint(orderBy, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first require constraint of the specified type within the query's require section.
	 *
	 * This is a convenience method that searches only within the {@link Query#getRequire()} section.
	 * If the query has no require section, null is returned.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraint to find
	 * @param <T> the type of the require constraint to find
	 * @return the first matching require constraint, or null if none found or query has no require section
	 */
	@Nullable
	public static <T extends RequireConstraint> T findRequire(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final Require require = query.getRequire();
		if (require == null) {
			return null;
		}
		//noinspection unchecked
		return (T) FinderVisitor.findConstraint(require, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds the first require constraint of the specified type, stopping at specified container boundaries.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraint to find
	 * @param stopContainerType require containers of this type will not be descended into
	 * @param <T> the type of the require constraint to find
	 * @return the first matching require constraint, or null if none found or query has no require section
	 */
	@Nullable
	public static <T extends RequireConstraint> T findRequire(@Nonnull Query query, @Nonnull Class<T> constraintType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		final Require require = query.getRequire();
		if (require == null) {
			return null;
		}
		//noinspection unchecked
		return (T) FinderVisitor.findConstraint(require, new ConstraintTypeMatcher(constraintType), stopContainerType::isInstance);
	}

	/**
	 * Finds all require constraints of the specified type within the query's require section.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraints to find
	 * @param <T> the type of the require constraints to find
	 * @return list of all matching require constraints (empty list if none found or query has no require section)
	 */
	@Nonnull
	public static <T extends RequireConstraint> List<T> findRequires(@Nonnull Query query, @Nonnull Class<T> constraintType) {
		final Require require = query.getRequire();
		if (require == null) {
			return Collections.emptyList();
		}
		//noinspection unchecked
		return (List<T>) (List<?>) FinderVisitor.findConstraints(require, new ConstraintTypeMatcher(constraintType));
	}

	/**
	 * Finds all require constraints of the specified type, stopping at specified container boundaries.
	 *
	 * @param query the query to search within
	 * @param constraintType the class of the require constraints to find
	 * @param stopContainerType require containers of this type will not be descended into
	 * @param <T> the type of the require constraints to find
	 * @return list of all matching require constraints (empty list if none found or query has no require section)
	 */
	@Nonnull
	public static <T extends RequireConstraint> List<T> findRequires(@Nonnull Query query, @Nonnull Class<T> constraintType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		final Require require = query.getRequire();
		if (require == null) {
			return Collections.emptyList();
		}
		//noinspection unchecked
		return (List<T>) (List<?>) FinderVisitor.findConstraints(require, new ConstraintTypeMatcher(constraintType), stopContainerType::isInstance);
	}

	/**
	 * Compares two serializable values for inequality, using `compareTo` for comparable types.
	 *
	 * This method is used for constraint argument comparison. It handles special cases:
	 * - **Comparable types**: Uses `compareTo()` instead of `equals()` to correctly compare types like
	 *   {@link java.math.BigDecimal} where `equals()` considers scale (e.g., `1.0 != 1.00` via equals,
	 *   but `1.0 == 1.00` via compareTo).
	 * - **Arrays**: Recursively compares array elements
	 * - **Null values**: Null is considered different from any non-null value
	 *
	 * @param thisValue the first value to compare; may be null
	 * @param otherValue the second value to compare; may be null
	 * @return true if the values differ, false if they are equal or both are null
	 */
	public static boolean valueDiffers(@Nullable Serializable thisValue, @Nullable Serializable otherValue) {
		// handle array comparison recursively
		if (thisValue instanceof final Object[] thisValueArray) {
			if (!(otherValue instanceof final Object[] otherValueArray)) {
				return true;
			}
			if (thisValueArray.length != otherValueArray.length) {
				return true;
			}
			// compare each element
			for (int i = 0; i < thisValueArray.length; i++) {
				if (valueDiffersInternal((Serializable) thisValueArray[i], (Serializable) otherValueArray[i])) {
					return true;
				}
			}
			return false;
		} else {
			return valueDiffersInternal(thisValue, otherValue);
		}
	}

	/**
	 * Internal comparison logic for non-array values.
	 *
	 * Uses `compareTo()` for Comparable types (like BigDecimal) to ensure numeric equality is based on value,
	 * not representation. For non-comparable types, uses standard `equals()`.
	 *
	 * @param thisValue the first value to compare; may be null
	 * @param otherValue the second value to compare; may be null
	 * @return true if the values differ, false if they are equal or both are null
	 */
	private static boolean valueDiffersInternal(@Nullable Serializable thisValue, @Nullable Serializable otherValue) {
		// for comparable types (e.g., BigDecimal), use compareTo to handle value-based equality correctly
		if (thisValue instanceof Comparable) {
			if (otherValue == null) {
				return true;
			} else {
				//noinspection unchecked,rawtypes
				return !thisValue.getClass().isInstance(otherValue) || ((Comparable) thisValue).compareTo(otherValue) != 0;
			}
		} else {
			return !Objects.equals(thisValue, otherValue);
		}
	}

	/**
	 * Predicate implementation that matches constraints by their runtime type using {@code isInstance} check.
	 *
	 * This internal helper class is used by all type-based finder methods to create predicates that match
	 * constraints of a specific class. It implements {@link PredicateWithDescription} to provide readable
	 * error messages when constraints are not found.
	 *
	 * The `toString()` method is used by {@link io.evitadb.api.query.visitor.FinderVisitor} to generate
	 * descriptive exception messages.
	 */
	@RequiredArgsConstructor
	private static class ConstraintTypeMatcher implements PredicateWithDescription<Constraint<?>> {
		/** The constraint class to match against. */
		private final Class<? extends Constraint<?>> type;

		/**
		 * Tests whether the constraint is an instance of the target type.
		 *
		 * @param constraint the constraint to test
		 * @return true if the constraint is an instance of the target type
		 */
		@Override
		public boolean test(Constraint<?> constraint) {
			return this.type.isInstance(constraint);
		}

		/**
		 * Returns a human-readable description of this matcher for error messages.
		 *
		 * @return description string like "constraint of type `AttributeEquals`"
		 */
		@Nonnull
		@Override
		public String toString() {
			return "constraint of type `" + this.type.getSimpleName() + "`";
		}

	}

}
