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
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Abstract base class for constraints that can contain child constraints, forming tree structures in evitaDB queries.
 * Container constraints enable composition of complex query logic by nesting filtering conditions, ordering rules,
 * and data requirements.
 *
 * **Design Purpose**
 *
 * `ConstraintContainer` represents the structural nodes of the constraint tree, as opposed to {@link ConstraintLeaf}
 * which represents terminal nodes. Examples of container constraints include:
 * - Logical operators: `and(...)`, `or(...)`, `not(...)`
 * - Grouping containers: `filterBy(...)`, `orderBy(...)`, `require(...)`
 * - Scoped operations: `entityHaving(...)`, `facetHaving(...)`, `hierarchyWithin(...)`
 *
 * Containers can hold:
 * 1. **Primary children**: Constraints of a specific type (e.g., `FilterBy` contains `FilterConstraint` children)
 * 2. **Additional children**: Constraints of different types (e.g., `EntityHaving` contains both `FilterConstraint`
 *    and `OrderConstraint` as additional children)
 * 3. **Arguments**: Scalar values (inherited from {@link BaseConstraint})
 *
 * **Child Management System**
 *
 * The class maintains two separate child collections:
 * - `children[]`: Primary children of type `T` (the constraint type parameter)
 * - `additionalChildren[]`: Children of other types (different from `T`)
 *
 * This separation enables type-safe container design while still allowing cross-constraint-type composition when
 * needed. For example, `ReferenceHaving` (a filter constraint) can contain both filter constraints (primary children)
 * and entity constraints like `EntityPrimaryKeyInSet` (additional children).
 *
 * **Suffix Support**
 *
 * Containers implementing {@link ConstraintContainerWithSuffix} can hide implicit children from their string
 * representation. The `getExplicitChildren()` and `getExplicitAdditionalChildren()` methods filter out children
 * marked as implicit for the current suffix. For example, `facetSummaryOfReference("brand")` may have an implicit
 * `FacetStatisticsDepth.COUNTS` requirement that is not shown in EvitaQL syntax.
 *
 * **Validation and Filtering**
 *
 * During construction, the container:
 * 1. Validates that primary children are of the correct type (matching `getType()`)
 * 2. Validates that additional children are of a different type than primary children
 * 3. Filters out null values from both child arrays (defensive programming)
 * 4. Ensures type consistency via {@link #validateAndFilterChildren(Constraint[])} and
 *    {@link #validateAndFilterAdditionalChildren(Constraint[])}
 *
 * **Immutability and Cloning**
 *
 * Like all constraints, containers are immutable. The `getCopyWithNewChildren()` method enables creating modified
 * copies with different child sets, which is used by:
 * - {@link io.evitadb.api.query.visitor.QueryPurifierVisitor} to remove inapplicable constraints
 * - {@link io.evitadb.api.query.visitor.ConstraintCloneVisitor} to transform constraint trees
 * - Query optimization and normalization logic
 *
 * **Applicability**
 *
 * A container is considered applicable ({@link #isApplicable()}) if it has at least one primary child or at least
 * one additional child. Empty containers are typically removed during query normalization.
 *
 * **Necessity**
 *
 * A container is considered necessary ({@link #isNecessary()}) if it has more than one primary child or more than
 * one additional child. Single-child containers are often unnecessary wrappers that can be flattened during query
 * normalization (e.g., `and(attributeEquals("x", 1))` can be simplified to `attributeEquals("x", 1)`).
 *
 * **Iteration Support**
 *
 * The class implements `Iterable<T>` to allow convenient traversal of primary children:
 * ```java
 * for (FilterConstraint child : filterByContainer) {
 *     // process each filter child
 * }
 * ```
 *
 * **Usage Examples**
 *
 * ```java
 * // Simple logical container
 * FilterConstraint filter = and(
 *     attributeEquals("code", "PHONE-123"),
 *     attributeGreaterThan("price", 100)
 * );
 *
 * // Container with additional children (different types)
 * FilterConstraint entityHaving = entityHaving(
 *     referenceHaving("BRAND", entityPrimaryKeyInSet(1, 2, 3))  // FilterConstraint child
 * );
 * ```
 *
 * **Thread Safety**
 *
 * Instances are immutable and thread-safe. Child arrays are defensively copied during construction and never
 * modified after creation.
 *
 * @param <T> the type of primary child constraints this container accepts (FilterConstraint, OrderConstraint, etc.)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@EqualsAndHashCode(callSuper = true, of = {"children", "additionalChildren"})
public abstract class ConstraintContainer<T extends Constraint<T>> extends BaseConstraint<T> implements Iterable<T> {
	/** Empty arguments array shared by constructors that don't require arguments. */
	protected static final Serializable[] NO_ARGS = new Serializable[0];
	/** Empty children array for require constraints, kept for backward compatibility. */
	protected static final RequireConstraint[] NO_CHILDREN = new RequireConstraint[0];
	/** Empty additional children array shared by constructors that don't have additional children. */
	protected static final Constraint<?>[] NO_ADDITIONAL_CHILDREN = new Constraint<?>[0];
	@Serial private static final long serialVersionUID = -446936362470832956L;
	/**
	 * Primary children of this container. All elements are of type `T` (the constraint type parameter).
	 * Null values are filtered out during construction. The array is immutable after creation.
	 */
	private final T[] children;
	/**
	 * Additional children of types different from `T`. Used when a container needs to hold constraints
	 * of multiple types (e.g., `EntityHaving` contains both filter and order constraints).
	 * Null values are filtered out during construction. The array is immutable after creation.
	 */
	private final Constraint<?>[] additionalChildren;

	/**
	 * Constructs a container with an explicit name, arguments, primary children, and additional children.
	 * This is the most general constructor, used when all components need to be specified.
	 *
	 * @param name the explicit constraint name; if null, the default name (derived from class name) is used
	 * @param arguments the constraint arguments (may be empty)
	 * @param children the primary children of type T (validated and filtered for nulls)
	 * @param additionalChildren children of types different from T (validated and filtered for nulls)
	 */
	protected ConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull T[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(name, arguments);
		this.children = validateAndFilterChildren(children);
		this.additionalChildren = validateAndFilterAdditionalChildren(additionalChildren);
	}

	/**
	 * Constructs a container with an explicit name, arguments, and primary children (no additional children).
	 *
	 * @param name the explicit constraint name; if null, the default name is used
	 * @param arguments the constraint arguments (may be empty)
	 * @param children the primary children of type T
	 */
	@SafeVarargs
	protected ConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull T... children
	) {
		this(name, arguments, children, NO_ADDITIONAL_CHILDREN);
	}

	/**
	 * Constructs a container with arguments, primary children, and additional children (using default name).
	 *
	 * @param arguments the constraint arguments (may be empty)
	 * @param children the primary children of type T
	 * @param additionalChildren children of types different from T
	 */
	protected ConstraintContainer(
		@Nonnull Serializable[] arguments,
		@Nonnull T[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments);
		this.children = validateAndFilterChildren(children);
		this.additionalChildren = validateAndFilterAdditionalChildren(additionalChildren);
	}

	/**
	 * Constructs a container with arguments and primary children (no additional children, using default name).
	 *
	 * @param arguments the constraint arguments (may be empty)
	 * @param children the primary children of type T
	 */
	@SafeVarargs
	protected ConstraintContainer(@Nonnull Serializable[] arguments, @Nonnull T... children) {
		this(arguments, children, NO_ADDITIONAL_CHILDREN);
	}

	/**
	 * Constructs a container with only primary children (no arguments, no additional children, using default name).
	 *
	 * @param children the primary children of type T
	 */
	@SafeVarargs
	protected ConstraintContainer(@Nonnull T... children) {
		this(NO_ARGS, children);
	}

	/**
	 * Constructs a container with primary children and additional children (no arguments, using default name).
	 *
	 * @param children the primary children of type T
	 * @param additionalChildren children of types different from T
	 */
	protected ConstraintContainer(@Nonnull T[] children, @Nonnull Constraint<?>... additionalChildren) {
		this(NO_ARGS, children, additionalChildren);
	}

	/**
	 * Returns all primary children of this container.
	 *
	 * The returned array contains all children of type `T`, including any children that may be implicit for
	 * suffixed container variants. To get only explicitly specified children (excluding those marked as implicit
	 * for the current suffix), use {@link #getExplicitChildren()}.
	 *
	 * @return array of all primary children (never null, but may be empty)
	 */
	@Nonnull
	public T[] getChildren() {
		return this.children;
	}

	/**
	 * Returns primary children excluding those that are implicit for the current suffix.
	 *
	 * If this container implements {@link ConstraintContainerWithSuffix}, children marked as implicit via
	 * `isChildImplicitForSuffix()` are filtered out. For non-suffixed containers, this method returns the same
	 * array as {@link #getChildren()}.
	 *
	 * This is used to generate clean EvitaQL string representations where implicit children are omitted.
	 * For example, `facetSummaryOfReference("brand")` may have implicit depth requirements that should not
	 * appear in the query string.
	 *
	 * @return array of explicit (non-implicit) primary children
	 */
	@Nonnull
	public T[] getExplicitChildren() {
		final T[] allChildren = getChildren();
		if (!(this instanceof ConstraintContainerWithSuffix ccws)) {
			return allChildren;
		}
		final List<T> explicit = new ArrayList<>(allChildren.length);
		for (final T child : allChildren) {
			if (!ccws.isChildImplicitForSuffix(child)) {
				explicit.add(child);
			}
		}
		//noinspection unchecked
		return explicit.toArray((T[]) Array.newInstance(getType(), explicit.size()));
	}

	/**
	 * Returns the number of primary children in this container.
	 *
	 * @return count of primary children (0 or more)
	 */
	public int getChildrenCount() {
		return this.children.length;
	}

	/**
	 * Returns an iterator over the primary children of this container.
	 *
	 * This enables convenient iteration using enhanced for loops:
	 * ```java
	 * for (FilterConstraint child : filterContainer) {
	 *     // process each child
	 * }
	 * ```
	 *
	 * @return an iterator over primary children of type T
	 */
	@Nonnull
	@Override
	public Iterator<T> iterator() {
		return Arrays.asList(this.children).iterator();
	}

	/**
	 * Returns all additional children of this container.
	 *
	 * Additional children are constraints of types different from the primary child type `T`. They enable
	 * cross-constraint-type composition when needed (e.g., a filter constraint containing both filter and
	 * order constraints).
	 *
	 * @return array of all additional children (never null, but may be empty)
	 */
	@Nonnull
	public Constraint<?>[] getAdditionalChildren() {
		return this.additionalChildren;
	}

	/**
	 * Returns additional children excluding those that are implicit for the current suffix.
	 *
	 * If this container implements {@link ConstraintContainerWithSuffix}, additional children marked as implicit
	 * via `isAdditionalChildImplicitForSuffix()` are filtered out. For non-suffixed containers, this method
	 * returns the same array as {@link #getAdditionalChildren()}.
	 *
	 * @return array of explicit (non-implicit) additional children
	 */
	@Nonnull
	public Constraint<?>[] getExplicitAdditionalChildren() {
		final Constraint<?>[] allAdditional = getAdditionalChildren();
		if (!(this instanceof ConstraintContainerWithSuffix ccws)) {
			return allAdditional;
		}
		final List<Constraint<?>> explicit = new ArrayList<>(allAdditional.length);
		for (final Constraint<?> child : allAdditional) {
			if (!ccws.isAdditionalChildImplicitForSuffix(child)) {
				explicit.add(child);
			}
		}
		return explicit.toArray(new Constraint<?>[0]);
	}

	/**
	 * Returns the number of additional children in this container.
	 *
	 * @return count of additional children (0 or more)
	 */
	public int getAdditionalChildrenCount() {
		return this.additionalChildren.length;
	}

	/**
	 * Returns true if this container serves a meaningful purpose in the query structure.
	 *
	 * A container is considered necessary if it has more than one primary child OR more than one additional child.
	 * Single-child containers are often unnecessary wrappers that can be simplified during query normalization.
	 * For example, `and(attributeEquals("x", 1))` can be flattened to just `attributeEquals("x", 1)`.
	 *
	 * This is used by {@link io.evitadb.api.query.visitor.QueryPurifierVisitor} to remove redundant container
	 * layers.
	 *
	 * @return true if the container has multiple children and is therefore necessary
	 */
	public boolean isNecessary() {
		return this.children.length > 1 || this.additionalChildren.length > 1;
	}

	/**
	 * Returns true if this container has sufficient data to be used in a query.
	 *
	 * A container is applicable if it has at least one primary child OR at least one additional child.
	 * Empty containers are inapplicable and are typically removed during query normalization.
	 *
	 * @return true if the container has at least one child of any kind
	 */
	@Override
	public boolean isApplicable() {
		return this.children.length > 0 || this.additionalChildren.length > 0;
	}

	/**
	 * Creates a copy of this container with different children and additional children.
	 *
	 * This method is essential for maintaining immutability while enabling constraint tree transformations.
	 * It is used extensively by:
	 * - {@link io.evitadb.api.query.visitor.QueryPurifierVisitor}: To remove inapplicable or unnecessary constraints
	 * - {@link io.evitadb.api.query.visitor.ConstraintCloneVisitor}: To apply transformations to constraint trees
	 * - Query optimization and normalization logic
	 *
	 * Implementations must create a new instance of the same concrete type with the provided children arrays,
	 * preserving all other properties (name, arguments, etc.) from the current instance.
	 *
	 * @param children the new primary children array
	 * @param additionalChildren the new additional children array
	 * @return a new instance of this container type with the specified children
	 */
	@Nonnull
	public abstract T getCopyWithNewChildren(@Nonnull T[] children, @Nonnull Constraint<?>[] additionalChildren);

	/**
	 * Returns the EvitaQL string representation of this container constraint.
	 *
	 * The format is: `constraintName(arg1,arg2,...,child1,child2,...)` where:
	 * - Arguments are serialized via {@link #getSerializedArguments()} (excludes defaults and implicit arguments)
	 * - Additional children are included (unless marked as implicit for suffixed containers)
	 * - Primary children are included (unless marked as implicit for suffixed containers)
	 * - All components are comma-separated
	 *
	 * The string builder is pre-allocated with an estimated capacity to minimize reallocations during
	 * string construction for performance in hot paths (query logging, debugging).
	 *
	 * @return EvitaQL string representation of this container and all its children
	 */
	@Nonnull
	@Override
	public String toString() {
		final List<String> serializedArgs = getSerializedArguments();
		final boolean isSuffixed = this instanceof ConstraintContainerWithSuffix;
		final ConstraintContainerWithSuffix ccws = isSuffixed ? (ConstraintContainerWithSuffix) this : null;

		// estimate capacity based on number of components to minimize reallocations
		final int estimatedParts = serializedArgs.size() + this.additionalChildren.length + this.children.length;
		final StringBuilder sb = new StringBuilder(estimatedParts << 5);
		sb.append(getName()).append(ARG_OPENING);

		boolean first = true;
		for (final String arg : serializedArgs) {
			if (!first) {
				sb.append(',');
			}
			sb.append(arg);
			first = false;
		}
		for (final Constraint<?> additionalChild : this.additionalChildren) {
			if (ccws != null && ccws.isAdditionalChildImplicitForSuffix(additionalChild)) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			sb.append(additionalChild.toString());
			first = false;
		}
		for (final T child : this.children) {
			if (ccws != null && ccws.isChildImplicitForSuffix(child)) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			sb.append(child.toString());
			first = false;
		}

		sb.append(ARG_CLOSING);
		return sb.toString();
	}

	/**
	 * Finds and returns the first additional child that matches the specified type.
	 *
	 * This is a convenience method for concrete constraint implementations to access their known additional
	 * children in a type-safe manner. For example, `EntityHaving` can use this to retrieve its `OrderBy` child.
	 *
	 * The method enforces that the requested type must be different from the container's primary child type `T`
	 * to prevent confusion between primary and additional children.
	 *
	 * @param additionalChildType the class of the additional child to find
	 * @param <C> the type of the additional child constraint
	 * @return Optional containing the first matching additional child, or empty if none found
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the requested type matches the primary child type
	 */
	@Nonnull
	protected <C extends Constraint<?>> Optional<C> getAdditionalChild(@Nonnull Class<C> additionalChildType) {
		if (getType().isAssignableFrom(additionalChildType) ||
			additionalChildType.isAssignableFrom(getType())) {
			throw new EvitaInvalidUsageException("Type of additional child must be different from type of children of the main container.");
		}
		//noinspection unchecked
		return Arrays.stream(this.additionalChildren)
			.filter(additionalChildType::isInstance)
			.findFirst()
			.map(it -> (C) it);
	}

	/**
	 * Validates and filters the primary children array during construction.
	 *
	 * This method performs two critical validation steps:
	 * 1. **Type validation**: Ensures the array component type matches the container's expected child type
	 *    (obtained via `getType()`). This prevents type mismatches at runtime.
	 * 2. **Null filtering**: Removes null elements from the array (defensive programming for varargs constructors).
	 *
	 * The filtering is optimized to avoid array allocation in the common case where no nulls are present.
	 *
	 * @param children the raw children array from the constructor
	 * @return validated and null-filtered array of primary children
	 * @throws EvitaInvalidUsageException if the array component type doesn't match the expected child type
	 */
	@Nonnull
	private T[] validateAndFilterChildren(@Nonnull T[] children) {
		// validate that array component type matches expected child type
		if (children.length > 0 && !getType().isAssignableFrom(children.getClass().getComponentType())) {
			throw new EvitaInvalidUsageException(
				children.getClass().getComponentType() + " is not of expected type " + getType()
			);
		}

		// filter out null values in a single pass, avoiding new array allocation if all elements are non-null
		int nullCount = 0;
		for (final T child : children) {
			if (child == null) {
				nullCount++;
			}
		}
		if (nullCount == 0) {
			return children;
		}
		//noinspection unchecked
		final T[] filtered = (T[]) Array.newInstance(getType(), children.length - nullCount);
		int idx = 0;
		for (final T child : children) {
			if (child != null) {
				filtered[idx++] = child;
			}
		}
		return filtered;
	}

	/**
	 * Validates and filters the additional children array during construction.
	 *
	 * This method performs:
	 * 1. **Null filtering**: Removes null elements from the array (defensive programming)
	 * 2. **Type separation validation**: Ensures no additional child is of the same type as the primary children
	 *    (additional children must be of different constraint types)
	 *
	 * The type separation requirement prevents ambiguity about whether a child should be in the `children` or
	 * `additionalChildren` array. Each child type has a clear home.
	 *
	 * @param additionalChildren the raw additional children array from the constructor
	 * @return validated and null-filtered array of additional children
	 */
	@Nonnull
	private Constraint<?>[] validateAndFilterAdditionalChildren(@Nonnull Constraint<?>[] additionalChildren) {
		// short-circuit for empty arrays
		if (additionalChildren.length == 0) {
			return additionalChildren;
		}

		// filter out null values in a single pass, avoiding array allocation if all elements are non-null
		int nullCount = 0;
		for (final Constraint<?> child : additionalChildren) {
			if (child == null) {
				nullCount++;
			}
		}

		final Constraint<?>[] newAdditionalChildren;
		if (nullCount > 0) {
			newAdditionalChildren = new Constraint<?>[additionalChildren.length - nullCount];
			int idx = 0;
			for (final Constraint<?> child : additionalChildren) {
				if (child != null) {
					newAdditionalChildren[idx++] = child;
				}
			}
		} else {
			newAdditionalChildren = additionalChildren;
		}

		// validate that additional children are of different types than primary children
		// this ensures clear separation between the two child collections
		for (final Constraint<?> newAdditionalChild : newAdditionalChildren) {
			final Class<?> additionalChildType = newAdditionalChild.getType();

			Assert.isTrue(
				!getType().isAssignableFrom(additionalChildType),
				"Type of additional child must be different from type of children of the main container."
			);
		}

		return newAdditionalChildren;
	}
}
