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
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.utils.Assert;
import org.antlr.v4.runtime.ParserRuleContext;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Base visitor for all constraint visitor implementations. Provides common helper methods.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class EvitaQLBaseConstraintVisitor<T extends Constraint<?>> extends EvitaQLBaseVisitor<T> {

	/**
	 * Validates already parsed constraint against concrete constraint class.
	 *
	 * @param ctx parsing context
	 * @param constraint parsed constraint
	 * @param constraintClass target constraint type
	 * @return parsed constraint in target constraint type
	 * @param <C> target constraint type
	 */
	@Nonnull
	protected <C> C visitChildConstraint(@Nonnull ParserRuleContext ctx,
										 @Nonnull Constraint<?> constraint,
	                                     @Nonnull Class<C> constraintClass) {
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
	 * @return parsed child constraint
	 * @param <C> type of expected child constraint
	 */
	@Nonnull
	protected <C> C visitChildConstraint(@Nonnull ParserRuleContext arg,
	                                     @Nonnull Class<C> constraintClass) {

		return visitChildConstraint(this, arg, constraintClass);
	}

	/**
	 * Parses child constraints and validates it against expected constraint class.
	 *
	 * @param visitor visitor responsible for parsing child constraint value
	 * @param arg argument containing raw constraint value
	 * @param constraintClass type of expected child constraint
	 * @return parsed child constraint
	 * @param <C> type of expected child constraint
	 */
	@Nonnull
	protected <C> C visitChildConstraint(@Nonnull EvitaQLBaseVisitor<? extends Constraint<?>> visitor,
	                                     @Nonnull ParserRuleContext arg,
	                                     @Nonnull Class<C> constraintClass) {
		final Constraint<?> constraint = arg.accept(visitor);
		Assert.notNull(constraint, () -> new EvitaSyntaxException(arg, "Child constraint is required."));
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
	 * @return parsed child constraint
	 * @param <C> type of expected child constraint
	 */
	@Nonnull
	protected <C> C visitChildConstraint(@Nonnull ParserRuleContext arg,
	                                     @Nonnull Class<?>... constraintClasses) {

		return visitChildConstraint(this, arg, constraintClasses);
	}

	/**
	 * Parses child constraints and validates it against expected constraint class.
	 *
	 * @param visitor visitor responsible for parsing child constraint value
	 * @param arg argument containing raw constraint value
	 * @param constraintClasses types of expected child constraint, at least one class must match the parsed constraint
	 * @return parsed child constraint
	 * @param <C> type of expected child constraint
	 */
	@Nonnull
	protected <C> C visitChildConstraint(@Nonnull EvitaQLBaseVisitor<? extends Constraint<?>> visitor,
	                                     @Nonnull ParserRuleContext arg,
	                                     @Nonnull Class<?>... constraintClasses) {
		final Constraint<?> constraint = arg.accept(visitor);
		Assert.notNull(constraint, () -> new EvitaSyntaxException(arg, "Child constraint is required."));
		Assert.isTrue(
			Arrays.stream(constraintClasses)
				.anyMatch(c -> c.isAssignableFrom(constraint.getClass())),
			() -> new EvitaSyntaxException(arg, "Invalid child constraint `" + constraint.getName() + "`.")
		);
		//noinspection unchecked
		return (C) constraint;
	}
}
