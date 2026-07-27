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
import java.util.List;

/**
 * Method accessor implementation for arrays (both object and primitive types).
 * Provides method invocation support for the following methods:
 *
 * - `size()` - returns the length of the array
 * - `any(predicate)` - returns `true` if any element matches the predicate
 * - `all(predicate)` - returns `true` if all elements match the predicate
 * - `none(predicate)` - returns `true` if no element matches the predicate
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ArrayMethodAccessor implements ObjectMethodAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] {
			Object[].class,
			boolean[].class,
			byte[].class,
			char[].class,
			double[].class,
			float[].class,
			short[].class,
			int[].class,
			long[].class
		};
	}

	@Nullable
	@Override
	public Serializable invoke(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Serializable object,
		@Nonnull String methodIdentifier,
		@Nonnull List<ExpressionNode> args
	) throws ExpressionEvaluationException {
		return switch (methodIdentifier) {
			case "size" -> invokeSizeMethod(object, args);
			case "any" -> matchArray(context, object, args, MatchMode.ANY);
			case "all" -> matchArray(context, object, args, MatchMode.ALL);
			case "none" -> matchArray(context, object, args, MatchMode.NONE);
			default ->
				throw new ExpressionEvaluationException(
					"Cannot invoke method `" + methodIdentifier + "` on object of type `" + object.getClass().getName() + "`, not supported.",
					"Cannot invoke method `" + methodIdentifier + "`. Not supported on array."
				);
		};
	}

	/**
	 * Returns the length of the given array.
	 */
	private static int invokeSizeMethod(
		@Nonnull Serializable object,
		@Nonnull List<ExpressionNode> args
	) {
		validateRequiredNumberOfArgs("size", args, 0);
		if (object instanceof Object[] a) return a.length;
		else if (object instanceof boolean[] a) return a.length;
		else if (object instanceof byte[] a) return a.length;
		else if (object instanceof char[] a) return a.length;
		else if (object instanceof double[] a) return a.length;
		else if (object instanceof float[] a) return a.length;
		else if (object instanceof short[] a) return a.length;
		else if (object instanceof int[] a) return a.length;
		else if (object instanceof long[] a) return a.length;
		else {
			throw new ExpressionEvaluationException(
				"Cannot invoke method `size` on object of type `" + object.getClass().getName() + "`. Expected array.",
				"Cannot invoke method `size`. Expected array."
			);
		}
	}

	/**
	 * Match mode for predicate-based array methods.
	 */
	private enum MatchMode {
		ANY, ALL, NONE
	}

	/**
	 * Iterates over the array elements and evaluates the predicate for each element,
	 * returning the result based on the match mode.
	 */
	private static boolean matchArray(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Serializable object,
		@Nonnull List<ExpressionNode> args,
		@Nonnull MatchMode mode
	) {
		final String methodName = mode.name();
		validateRequiredNumberOfArgs(methodName, args, 1);
		final ExpressionNode predicate = args.get(0);

		if (object instanceof Object[] array) {
			for (final Object element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof boolean[] array) {
			for (final boolean element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof byte[] array) {
			for (final byte element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof char[] array) {
			for (final char element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof double[] array) {
			for (final double element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof float[] array) {
			for (final float element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof short[] array) {
			for (final short element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof int[] array) {
			for (final int element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else if (object instanceof long[] array) {
			for (final long element : array) {
				final boolean result = computePredicateArgument(context, element, predicate);
				if (mode == MatchMode.ANY && result) return true;
				if (mode == MatchMode.ALL && !result) return false;
				if (mode == MatchMode.NONE && result) return false;
			}
		} else {
			throw new ExpressionEvaluationException(
				"Cannot invoke method `" + methodName + "` on object of type `" + object.getClass().getName() + "`. Expected array.",
				"Cannot invoke method `" + methodName + "`. Expected array."
			);
		}

		// default result when no early return was triggered
		return mode != MatchMode.ANY;
	}

	/**
	 * Evaluates the predicate expression with the given item as the `this` context
	 * and verifies the result is a boolean.
	 */
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

	/**
	 * Validates that the method was called with the expected number of arguments.
	 */
	private static void validateRequiredNumberOfArgs(
		@Nonnull String methodName,
		@Nonnull List<ExpressionNode> args,
		int expectedNumberOfArgs
	) {
		if (args.size() != expectedNumberOfArgs) {
			throw new ExpressionEvaluationException(
				"Method `" + methodName + "` requires exactly " + expectedNumberOfArgs + " argument(s), but got " + args.size() + "."
			);
		}
	}
}
