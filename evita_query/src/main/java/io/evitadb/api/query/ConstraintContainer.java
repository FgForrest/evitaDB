/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
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
	@Serial private static final long serialVersionUID = -446936362470832956L;
	protected static final Serializable[] NO_ARGS = new Serializable[0];
	protected static final RequireConstraint[] NO_CHILDREN = new RequireConstraint[0];
	protected static final Constraint<?>[] NO_ADDITIONAL_CHILDREN = new Constraint<?>[0];
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
		return children;
	}

	/**
	 * Returns count of query children.
	 */
	public int getChildrenCount() {
		return children.length;
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
			.stream(children)
			.iterator();
	}

	/**
	 * Returns all additional children (of different types).
	 */
	@Nonnull
	public Constraint<?>[] getAdditionalChildren() {
		return additionalChildren;
	}

	/**
	 * Returns count of all additional children.
	 */
	public int getAdditionalChildrenCount() {
		return additionalChildren.length;
	}

	/**
	 * Returns true if there is more than one child - if not this container is probably useless (in most cases).
	 */
	public boolean isNecessary() {
		return children.length > 1 || additionalChildren.length > 1;
	}

	/**
	 * Returns true if query has enough data to be used in query.
	 * False in case query has no sense - because it couldn't be processed in current state (for
	 * example significant arguments are missing or are invalid).
	 */
	@Override
	public boolean isApplicable() {
		return children.length > 0;
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
					Arrays.stream(getArguments()).map(BaseConstraint::convertToString),
					Arrays.stream(additionalChildren).map(Constraint::toString),
					Arrays.stream(children).map(Constraint::toString)
				)
				.flatMap(it -> it)
				.collect(Collectors.joining(",")) +
			ARG_CLOSING;
	}

	/**
	 * Returns additional child whose type is same or subclass of passed type. Cannot be same type as this container.
	 * This method should be used only internally to provide access to concrete additional children in implementations.
	 */
	@Nullable
	protected <C extends Constraint<?>> C getAdditionalChild(@Nonnull Class<C> additionalChildType) {
		if (getType().isAssignableFrom(additionalChildType) ||
			additionalChildType.isAssignableFrom(getType())) {
			throw new IllegalArgumentException("Type of additional child must be different from type of children of the main container.");
		}
		//noinspection unchecked
		return (C) Arrays.stream(additionalChildren)
			.filter(additionalChildType::isInstance)
			.findFirst()
			.orElse(null);
	}

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
		for (int i = 0; i < newAdditionalChildren.length; i++) {
			final Class<?> additionalChildType = newAdditionalChildren[i].getType();

			Assert.isTrue(
				!getType().isAssignableFrom(additionalChildType),
				"Type of additional child must be different from type of children of the main container."
			);

			for (int j = i + 1; j < newAdditionalChildren.length; j++) {
				if (newAdditionalChildren[i].getClass().equals(newAdditionalChildren[j].getClass())) {
					throw new EvitaInvalidUsageException(
						"There are multiple additional children of same type: " + additionalChildren[i].getClass() + " and " + additionalChildren[j].getClass()
					);
				}
			}
		}

		return newAdditionalChildren;
	}
}
