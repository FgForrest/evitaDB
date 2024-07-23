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

import io.evitadb.api.query.visitor.FinderVisitor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Class contains various utility method for accessing and manipulating the {@link Query}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class QueryUtils {

	private QueryUtils() {
	}

	/**
	 * Method finds constraint of specified type in the passed constraint container and returns it.
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType) {
		return FinderVisitor.findConstraint(constraint, constraintType::isInstance);
	}

	/**
	 * Method finds constraint by passed predicate.
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate) {
		return FinderVisitor.findConstraint(constraint, predicate);
	}

	/**
	 * Method finds constraint of specified type in the passed constraint container and returns it. Lookup will ignore
	 * all containers that are assignable to `stopContainerType`.
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType, @Nonnull Class<? extends Constraint<?>> stopContainerType) {
		return FinderVisitor.findConstraint(constraint, constraintType::isInstance, cnt -> cnt != constraint && stopContainerType.isInstance(cnt));
	}

	/**
	 * Method finds all constraints of specified type in the passed query and returns them.
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(Constraint<?> constraint, @Nonnull Class<T> filterType) {
		return FinderVisitor.findConstraints(constraint, filterType::isInstance);
	}

	/**
	 * Method finds all constraints in the query by passed predicate.
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Predicate<Constraint<?>> predicate) {
		return FinderVisitor.findConstraints(constraint, predicate);
	}

	/**
	 * Method finds constraint of specified type in the passed constraint container and returns it. Lookup will ignore
	 * all containers that are assignable to `stopContainerType`.
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint, @Nonnull Class<T> constraintType, @Nonnull Class<? extends Constraint<?>> stopContainerType) {
		return FinderVisitor.findConstraints(constraint, constraintType::isInstance, cnt -> cnt != constraint && stopContainerType.isInstance(cnt));
	}

	/**
	 * Method finds filtering constraint of specified type in the passed query and returns it.
	 */
	@Nullable
	public static <T extends FilterConstraint> T findFilter(@Nonnull Query query, @Nonnull Class<T> filterType) {
		//noinspection unchecked
		return Optional.ofNullable(query.getFilterBy())
				.map(it -> (T) FinderVisitor.findConstraint(it, filterType::isInstance))
				.orElse(null);
	}

	/**
	 * Method finds filtering constraint of specified type in the passed query and returns it.
	 */
	@Nullable
	public static <T extends FilterConstraint> T findFilter(@Nonnull Query query, @Nonnull Class<T> filterType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		//noinspection unchecked
		return Optional.ofNullable(query.getFilterBy())
			.map(it -> (T) FinderVisitor.findConstraint(it, filterType::isInstance, stopContainerType::isInstance))
			.orElse(null);
	}

	/**
	 * Method finds all filtering constraints of specified type in the passed query and returns them.
	 */
	@Nonnull
	public static <T extends FilterConstraint> List<T> findFilters(@Nonnull Query query, @Nonnull Class<T> filterType) {
		//noinspection unchecked
		return (List<T>) Optional.ofNullable(query.getFilterBy())
			.map(it -> FinderVisitor.findConstraints(it, filterType::isInstance))
				.orElse(Collections.emptyList())
				.stream()
				.map(i -> (FilterConstraint) i)
				.collect(Collectors.toList());
	}

	/**
	 * Method finds ordering constraint of specified type in the passed query and returns it.
	 */
	@Nullable
	public static <T extends OrderConstraint> T findOrder(@Nonnull Query query, @Nonnull Class<T> orderType) {
		//noinspection unchecked
		return Optional.ofNullable(query.getOrderBy())
			.map(it -> (T) FinderVisitor.findConstraint(it, orderType::isInstance))
			.orElse(null);
	}

	/**
	 * Method finds requirement constraint of specified type in the passed query and returns it.
	 */
	@Nullable
	public static <T extends RequireConstraint> T findRequire(@Nonnull Query query, @Nonnull Class<T> requireType) {
		//noinspection unchecked
		return Optional.ofNullable(query.getRequire())
				.map(it -> (T) FinderVisitor.findConstraint(it, requireType::isInstance))
				.orElse(null);
	}

	/**
	 * Method finds requirement constraint of specified type in the passed query and returns it. Lookup will ignore
	 * all containers that are assignable to `stopContainerType`.
	 */
	@Nullable
	public static <T extends RequireConstraint> T findRequire(@Nonnull Query query, @Nonnull Class<T> requireType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		//noinspection unchecked
		return Optional.ofNullable(query.getRequire())
			.map(it -> (T) FinderVisitor.findConstraint(it, requireType::isInstance, stopContainerType::isInstance))
			.orElse(null);
	}

	/**
	 * Method finds all requirement constraints of specified type in the passed query and returns them.
	 */
	@Nonnull
	public static <T extends RequireConstraint> List<T> findRequires(@Nonnull Query query, @Nonnull Class<T> requireType) {
		//noinspection unchecked
		return (List<T>) Optional.ofNullable(query.getRequire())
			.map(it -> FinderVisitor.findConstraints(it, requireType::isInstance))
			.orElse(Collections.emptyList())
			.stream()
			.map(i -> (RequireConstraint) i)
			.collect(Collectors.toList());
	}

	/**
	 * Method finds requirement constraint of specified type in the passed query and returns it. Lookup will ignore
	 * all containers that are assignable to `stopContainerType`.
	 */
	@Nonnull
	public static <T extends RequireConstraint> List<T> findRequires(@Nonnull Query query, @Nonnull Class<T> requireType, @Nonnull Class<? extends RequireConstraint> stopContainerType) {
		//noinspection unchecked
		return (List<T>) Optional.ofNullable(query.getRequire())
			.map(it -> FinderVisitor.findConstraints(it, requireType::isInstance, stopContainerType::isInstance))
			.orElse(Collections.emptyList())
			.stream()
			.map(i -> (RequireConstraint) i)
			.collect(Collectors.toList());
	}

	/**
	 * Method returns true if passed two values are not equals. When first value is comparable, method compareTo is used
	 * instead of equals (to correctly match {@link java.math.BigDecimal}).
	 */
	public static boolean valueDiffers(@Nullable Serializable thisValue, @Nullable Serializable otherValue) {
		if (thisValue instanceof final Object[] thisValueArray) {
			if (!(otherValue instanceof final Object[] otherValueArray)) {
				return true;
			}
			if (thisValueArray.length != otherValueArray.length) {
				return true;
			}
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

	private static boolean valueDiffersInternal(@Nullable Serializable thisValue, @Nullable Serializable otherValue) {
		// when value is Comparable (such as BigDecimal!) - use compareTo function instead of equals
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
}
