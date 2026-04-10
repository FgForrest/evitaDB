/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression.object.accessor.common;

import io.evitadb.api.query.expression.object.accessor.ObjectMethodAccessor;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Method accessor implementation for lists.
 * Provides method invocation support for the following methods:
 *
 * - `size()` - returns the length of the list
 * - `any(predicate)` - returns `true` if any element matches the predicate
 * - `all(predicate)` - returns `true` if all elements match the predicate
 * - `none(predicate)` - returns `true` if no element matches the predicate
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ListMethodAccessor implements ObjectMethodAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		// we want to support serializable lists
		//noinspection unchecked
		return new Class[] { List.class };
	}

	@Nullable
	@Override
	public Serializable invoke(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Serializable object,
		@Nonnull String methodIdentifier,
		@Nonnull List<ExpressionNode> args
	) throws ExpressionEvaluationException {
		if (!(object instanceof List<?> list)) {
			throw new ExpressionEvaluationException(
				"Cannot invoke method on object of type `" + object.getClass().getName() + "`. Expected List.",
				"Cannot invoke method. Expected List."
			);
		}

		return switch (methodIdentifier) {
			case "size" -> invokeSizeMethod(list, args);
			case "any" -> invokeAnyMethod(context, list, args);
			case "all" -> invokeAllMethod(context, list, args);
			case "none" -> invokeNoneMethod(context, list, args);
			default ->
				throw new ExpressionEvaluationException(
					"Cannot invoke method `" + methodIdentifier + "` on object of type `" + object.getClass().getName() + "`, not supported.",
					"Cannot invoke method `" + methodIdentifier + "`. Not supported on list."
				);
		};
	}

	private static int invokeSizeMethod(
		@Nonnull List<?> list,
		@Nonnull List<ExpressionNode> args
	) {
		validateRequiredNumberOfArgs(args, 0);
		return list.size();
	}

	private static boolean invokeAnyMethod(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull List<?> list,
		@Nonnull List<ExpressionNode> args
	) {
		validateRequiredNumberOfArgs(args, 1);
		return list
			.stream()
			.anyMatch(it -> computePredicateArgument(context, it, args.get(0)));
	}

	private static boolean invokeAllMethod(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull List<?> list,
		@Nonnull List<ExpressionNode> args
	) {
		validateRequiredNumberOfArgs(args, 1);
		return list
			.stream()
			.allMatch(it -> computePredicateArgument(context, it, args.get(0)));
	}

	private static boolean invokeNoneMethod(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull List<?> list,
		@Nonnull List<ExpressionNode> args
	) {
		validateRequiredNumberOfArgs(args, 1);
		return list
			.stream()
			.noneMatch(it -> computePredicateArgument(context, it, args.get(0)));
	}

	@Nonnull
	private static Boolean computePredicateArgument(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Object item,
		@Nonnull ExpressionNode predicateArgument
	) {
		final Serializable computedArg = predicateArgument.compute(context.withThis(item));
		if (!(computedArg instanceof Boolean)) {
			throw new ExpressionEvaluationException(
				"Predicate must evaluate to boolean, but got " + (computedArg != null ? computedArg.getClass().getName() : "null") + ".",
				"Predicate must evaluate to boolean."
			);
		}
		return (Boolean) computedArg;
	}

	private static void validateRequiredNumberOfArgs(@Nonnull List<ExpressionNode> args, int expectedNumberOfArgs) {
		if (args.size() != expectedNumberOfArgs) {
			throw new ExpressionEvaluationException(
				"Method `any` requires exactly " + expectedNumberOfArgs + " argument, but got " + args.size() + "."
			);
		}
	}
}
