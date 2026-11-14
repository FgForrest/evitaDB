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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.parser.Value;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.utils.Assert;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Base visitor for all constraint visitor implementations. Provides common helper methods.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class EvitaQLBaseConstraintVisitor<T extends Constraint<?>> extends EvitaQLBaseVisitor<T> {
	/**
	 * Return types of the public `QueryConstraints` factories by name. Every EvitaQL constraint keyword is also a
	 * factory name, so a child that its factory omitted (parsed to null) can still be type-checked by its keyword.
	 */
	private static final Map<String, List<Class<?>>> CONSTRAINT_TYPES =
		Arrays.stream(QueryConstraints.class.getMethods())
			.filter(method -> Constraint.class.isAssignableFrom(method.getReturnType()))
			.collect(Collectors.groupingBy(Method::getName,
				Collectors.mapping(Method::getReturnType, Collectors.toList())));

	/**
	 * Validates already parsed constraint against concrete constraint class.
	 *
	 * @param ctx parsing context
	 * @param constraint parsed constraint
	 * @param constraintClass target constraint type
	 * @return parsed constraint in target constraint type, or null when the constraint was omitted
	 * @param <C> target constraint type
	 */
	@Nullable
	protected <C> C visitChildConstraint(
		@Nonnull ParserRuleContext ctx,
		@Nullable Constraint<?> constraint,
		@Nonnull Class<C> constraintClass
	) {
		if (constraint == null) {
			return null;
		}
		Assert.isTrue(
			constraintClass.isAssignableFrom(constraint.getClass()),
			() -> new EvitaSyntaxException(ctx, "Invalid child constraint `" + constraint.getName() + "`.")
		);
		//noinspection unchecked
		return (C) constraint;
	}

	/**
	 * Parses child constraints and validates it against expected constraint class. Uses same visitor as parent constraint.
	 *
	 * @param arg argument containing raw constraint value
	 * @param constraintClass type of expected child constraint
	 * @return parsed child constraint, or null when its factory omitted it for undefined arguments; the type of an
	 *         omitted child is still checked
	 * @param <C> type of expected child constraint
	 */
	@Nullable
	protected <C> C visitChildConstraint(
		@Nonnull ParserRuleContext arg,
		@Nonnull Class<C> constraintClass
	) {

		return visitChildConstraint(this, arg, constraintClass);
	}

	/**
	 * Parses child constraints and validates it against expected constraint class.
	 *
	 * @param visitor visitor responsible for parsing child constraint value
	 * @param arg argument containing raw constraint value
	 * @param constraintClass type of expected child constraint
	 * @return parsed child constraint, or null when its factory omitted it for undefined arguments; the type of an
	 *         omitted child is still checked
	 * @param <C> type of expected child constraint
	 */
	@Nullable
	protected <C> C visitChildConstraint(
		@Nonnull EvitaQLBaseVisitor<? extends Constraint<?>> visitor,
		@Nonnull ParserRuleContext arg,
		@Nonnull Class<C> constraintClass
	) {
		final Constraint<?> constraint = arg.accept(visitor);
		if (constraint == null) {
			assertOmittedChildType(arg, constraintClass);
			return null;
		}
		Assert.isTrue(
			constraintClass.isAssignableFrom(constraint.getClass()),
			() -> new EvitaSyntaxException(arg, "Invalid child constraint `" + constraint.getName() + "`.")
		);
		//noinspection unchecked
		return (C) constraint;
	}

	/**
	 * Parses child constraints and validates it against expected constraint class. Uses same visitor as parent constraint.
	 *
	 * @param arg argument containing raw constraint value
	 * @param constraintClasses type of expected child constraint
	 * @return parsed child constraint, or null when its factory omitted it for undefined arguments; the type of an
	 *         omitted child is still checked
	 * @param <C> type of expected child constraint
	 */
	@Nullable
	protected <C> C visitChildConstraint(
		@Nonnull ParserRuleContext arg,
		@Nonnull Class<?>... constraintClasses
	) {

		return visitChildConstraint(this, arg, constraintClasses);
	}

	/**
	 * Parses child constraints and validates it against expected constraint class.
	 *
	 * @param visitor visitor responsible for parsing child constraint value
	 * @param arg argument containing raw constraint value
	 * @param constraintClasses types of expected child constraint, at least one class must match the parsed constraint
	 * @return parsed child constraint, or null when its factory omitted it for undefined arguments; the type of an
	 *         omitted child is still checked
	 * @param <C> type of expected child constraint
	 */
	@Nullable
	protected <C> C visitChildConstraint(
		@Nonnull EvitaQLBaseVisitor<? extends Constraint<?>> visitor,
		@Nonnull ParserRuleContext arg,
		@Nonnull Class<?>... constraintClasses
	) {
		final Constraint<?> constraint = arg.accept(visitor);
		if (constraint == null) {
			assertOmittedChildType(arg, constraintClasses);
			return null;
		}
		Assert.isTrue(
			Arrays.stream(constraintClasses)
				.anyMatch(c -> c.isAssignableFrom(constraint.getClass())),
			() -> new EvitaSyntaxException(arg, "Invalid child constraint `" + constraint.getName() + "`.")
		);
		//noinspection unchecked
		return (C) constraint;
	}

	/**
	 * Checks the declared child type even when its undefined arguments made the factory omit it.
	 *
	 * @param arg original child syntax, retaining its constraint name
	 * @param constraintClasses accepted child types
	 */
	private static void assertOmittedChildType(@Nonnull ParserRuleContext arg, @Nonnull Class<?>... constraintClasses) {
		final String name = arg.getStart().getText();
		Assert.isTrue(
			CONSTRAINT_TYPES.getOrDefault(name, List.of()).stream()
				.anyMatch(actual -> Arrays.stream(constraintClasses).anyMatch(type -> type.isAssignableFrom(actual))),
			() -> new EvitaSyntaxException(arg, "Invalid child constraint `" + name + "`.")
		);
	}

	/**
	 * Resolves a variadic tail after an optional enum prefix has been consumed. A tail of a single argument is used
	 * as the whole list when it is an iterable or an array. A lone null is treated the same way when a prefix
	 * occupied its own parameter, and is then refused as ambiguous; without a prefix it is a one-item list.
	 *
	 * @param ctx enclosing constraint
	 * @param values remaining arguments
	 * @param visitor expected item types
	 * @param hasPrefix whether a preceding optional setting occupied a separate parameter
	 * @return the tail as a convertible array or list wrapper
	 */
	@Nonnull
	protected Value parseVariadicTail(
		@Nonnull ParserRuleContext ctx,
		@Nonnull List<?> values,
		@Nonnull EvitaQLValueTokenVisitor visitor,
		boolean hasPrefix
	) {
		if (values.size() == 1) {
			final Object first = values.get(0);
			if ((first == null && hasPrefix) || isListArgument(first)) {
				return visitor.parseVariadicArguments(ctx, first);
			}
		}
		return visitor.parseVariadicArguments(ctx, values);
	}

	/**
	 * Tells whether a bound argument is a whole list (an iterable or an array) rather than a single list item.
	 *
	 * @param argument bound argument, possibly undefined
	 * @return true for an iterable or an array
	 */
	protected static boolean isListArgument(@Nullable Object argument) {
		return argument instanceof Iterable<?> || (argument != null && argument.getClass().isArray());
	}

}
