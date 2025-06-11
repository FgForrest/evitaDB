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
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Constraint is a query that allows nesting additional constraints. Actually there is no query container
 * that would allow to combine inner constraints with arguments, but we don't want to close the gate to this, so
 * therefore arguments are defined in the base class this one extends.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true, of = {"children", "additionalChildren"})
public abstract class ConstraintContainer<T extends Constraint<T>> extends BaseConstraint<T> implements Iterable<T> {
	protected static final Serializable[] NO_ARGS = new Serializable[0];
	protected static final RequireConstraint[] NO_CHILDREN = new RequireConstraint[0];
	protected static final Constraint<?>[] NO_ADDITIONAL_CHILDREN = new Constraint<?>[0];
	@Serial private static final long serialVersionUID = -446936362470832956L;
	private final T[] children;
	private final Constraint<?>[] additionalChildren;

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

	@SafeVarargs
	protected ConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull T... children
	) {
		this(name, arguments, children, NO_ADDITIONAL_CHILDREN);
	}

	protected ConstraintContainer(
		@Nonnull Serializable[] arguments,
		@Nonnull T[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments);
		this.children = validateAndFilterChildren(children);
		this.additionalChildren = validateAndFilterAdditionalChildren(additionalChildren);
	}

	@SafeVarargs
	protected ConstraintContainer(@Nonnull Serializable[] arguments, @Nonnull T... children) {
		this(arguments, children, NO_ADDITIONAL_CHILDREN);
	}

	@SafeVarargs
	protected ConstraintContainer(@Nonnull T... children) {
		this(NO_ARGS, children);
	}

	protected ConstraintContainer(@Nonnull T[] children, @Nonnull Constraint<?>... additionalChildren) {
		this(NO_ARGS, children, additionalChildren);
	}

	/**
	 * Returns array of query children.
	 */
	@Nonnull
	public T[] getChildren() {
		return this.children;
	}

	/**
	 * Returns array of query children without implicit children.
	 */
	@Nonnull
	public T[] getExplicitChildren() {
		//noinspection unchecked
		return Arrays.stream(getChildren())
			.filter(it -> !(this instanceof ConstraintContainerWithSuffix ccws) || !ccws.isChildImplicitForSuffix(it))
			.toArray(size -> (T[]) Array.newInstance(getType(), size));
	}

	/**
	 * Returns count of query children.
	 */
	public int getChildrenCount() {
		return this.children.length;
	}

	/**
	 * Returns an iterator over a set of elements of type T.
	 *
	 * @return an Iterator.
	 */
	@Nonnull
	@Override
	public Iterator<T> iterator() {
		return Arrays
			.stream(this.children)
			.iterator();
	}

	/**
	 * Returns all additional children (of different types).
	 */
	@Nonnull
	public Constraint<?>[] getAdditionalChildren() {
		return this.additionalChildren;
	}

	/**
	 * Returns array of query children, possibly without implicit children.
	 */
	@Nonnull
	public Constraint<?>[] getExplicitAdditionalChildren() {
		return Arrays.stream(getAdditionalChildren())
			.filter(it -> !(this instanceof ConstraintContainerWithSuffix ccws) || !ccws.isAdditionalChildImplicitForSuffix(it))
			.toArray(Constraint[]::new);
	}

	/**
	 * Returns count of all additional children.
	 */
	public int getAdditionalChildrenCount() {
		return this.additionalChildren.length;
	}

	/**
	 * Returns true if there is more than one child - if not this container is probably useless (in most cases).
	 */
	public boolean isNecessary() {
		return this.children.length > 1 || this.additionalChildren.length > 1;
	}

	/**
	 * Returns true if constraint has enough data to be used in query.
	 * False in case constraint has no sense - because it couldn't be processed in current state (for
	 * example significant arguments are missing or are invalid).
	 */
	@Override
	public boolean isApplicable() {
		return this.children.length > 0 || this.additionalChildren.length > 0;
	}

	/**
	 * Returns copy of this container type with new children and new additional children.
	 */
	@Nonnull
	public abstract T getCopyWithNewChildren(@Nonnull T[] children, @Nonnull Constraint<?>[] additionalChildren);

	@Nonnull
	@Override
	public String toString() {
		return getName() +
			ARG_OPENING +
			Stream.of(
					getSerializedArguments().stream(),
					Arrays.stream(this.additionalChildren)
						.filter(it -> !(this instanceof ConstraintContainerWithSuffix ccws) || !ccws.isAdditionalChildImplicitForSuffix(it))
						.map(Constraint::toString),
					Arrays.stream(this.children)
						.filter(it -> !(this instanceof ConstraintContainerWithSuffix ccws) || !ccws.isChildImplicitForSuffix(it))
						.map(Constraint::toString)
				)
				.flatMap(it -> it)
				.collect(Collectors.joining(",")) +
			ARG_CLOSING;
	}

	/**
	 * Returns additional child whose type is same or subclass of passed type. Cannot be same type as this container.
	 * This method should be used only internally to provide access to concrete additional children in implementations.
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
	 * Validates and filters the input array of children elements. Ensures that all elements are non-null
	 * and that the array's component type is assignable from the container type. If invalid elements or null values
	 * are found, appropriate adjustments or exceptions will be handled.
	 *
	 * @param children an array of children elements to be validated and filtered
	 * @return a filtered array of children elements with all null values removed
	 * @throws EvitaInvalidUsageException if the component type of the input array is not assignable from the expected container type
	 */
	@Nonnull
	private T[] validateAndFilterChildren(@Nonnull T[] children) {
		if (children.length > 0 && !getType().isAssignableFrom(children.getClass().getComponentType())) {
			throw new EvitaInvalidUsageException(
				children.getClass().getComponentType() + " is not of expected type " + getType()
			);
		}

		// filter out null values, but avoid creating new array if not necessary
		if (Arrays.stream(children).anyMatch(Objects::isNull)) {
			//noinspection unchecked
			return Arrays.stream(children)
				.filter(Objects::nonNull)
				.toArray(size -> (T[]) Array.newInstance(getType(), size));
		} else {
			return children;
		}
	}

	/**
	 * Validates and filters the input array of additional children constraints.
	 * Ensures that the array does not contain null values and validates that the types of the additional children
	 * do not match or extend the type of the parent container's children.
	 *
	 * @param additionalChildren an array of additional children constraints to be validated and filtered
	 * @return an array of additional children constraints after validation and filtering
	 */
	@Nonnull
	private Constraint<?>[] validateAndFilterAdditionalChildren(@Nonnull Constraint<?>[] additionalChildren) {
		if (additionalChildren.length == 0) {
			return additionalChildren;
		}

		// filter out null values, but avoid creating new array if not necessary
		final Constraint<?>[] newAdditionalChildren;
		if (Arrays.stream(additionalChildren).anyMatch(Objects::isNull)) {
			newAdditionalChildren = Arrays.stream(additionalChildren)
				.filter(Objects::nonNull)
				.toArray(size -> new Constraint<?>[size]);
		} else {
			newAdditionalChildren = additionalChildren;
		}

		// validate additional child is not of same type as container and validate that there are distinct children
		for (Constraint<?> newAdditionalChild : newAdditionalChildren) {
			final Class<?> additionalChildType = newAdditionalChild.getType();

			Assert.isTrue(
				!getType().isAssignableFrom(additionalChildType),
				"Type of additional child must be different from type of children of the main container."
			);
		}

		return newAdditionalChildren;
	}
}
