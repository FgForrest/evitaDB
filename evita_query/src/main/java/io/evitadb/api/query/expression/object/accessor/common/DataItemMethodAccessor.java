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
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * Implementation of {@link ObjectMethodAccessor} for the nodes of a {@link ComplexDataObject}, offering the collection
 * methods {@link ArrayMethodAccessor} and {@link MapMethodAccessor} offer for plain arrays and maps:
 *
 * - `size()` - the number of elements of an array node, or of properties of a map node
 * - `any(predicate)`, `all(predicate)`, `none(predicate)` - over the (unwrapped) elements of an array node
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DataItemMethodAccessor implements ObjectMethodAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { DataItemMap.class, DataItemArray.class, ComplexDataObject.class };
	}

	@Nullable
	@Override
	public Serializable invoke(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Serializable object,
		@Nonnull String methodIdentifier,
		@Nonnull List<ExpressionNode> args
	) throws ExpressionEvaluationException {
		final Serializable navigable = DataItemSupport.toNavigable(object);
		return switch (methodIdentifier) {
			case "size" -> size(navigable, args);
			case "any" -> match(context, navigable, args, methodIdentifier, MatchMode.ANY);
			case "all" -> match(context, navigable, args, methodIdentifier, MatchMode.ALL);
			case "none" -> match(context, navigable, args, methodIdentifier, MatchMode.NONE);
			default -> throw new ExpressionEvaluationException(
				"Cannot invoke method `" + methodIdentifier + "` on a node of a complex data object, not supported.",
				"Cannot invoke method `" + methodIdentifier + "`. Not supported on a complex data object."
			);
		};
	}

	/**
	 * Returns the number of elements of an array node or of properties of a map node.
	 *
	 * @param object the node
	 * @param args   the arguments of the call, which must be none
	 * @return the size
	 */
	private static int size(@Nullable Serializable object, @Nonnull List<ExpressionNode> args) {
		validateRequiredNumberOfArgs("size", args, 0);
		if (object instanceof DataItemArray array) {
			return array.children().length;
		} else if (object instanceof DataItemMap map) {
			return map.getPropertyCount();
		}
		throw new ExpressionEvaluationException(
			"Cannot invoke method `size` on object of type `" +
				(object == null ? "null" : object.getClass().getName()) +
				"`. Expected an array or an object of a complex data object.",
			"Cannot invoke method `size`."
		);
	}

	/**
	 * Match mode of the predicate methods.
	 */
	private enum MatchMode {
		ANY, ALL, NONE
	}

	/**
	 * Evaluates the predicate for the unwrapped elements of an array node and combines the results by `mode`.
	 *
	 * @param context    the evaluation context
	 * @param object     the node
	 * @param args       the arguments of the call - exactly the predicate
	 * @param methodName the name of the invoked method, for error messages
	 * @param mode       how the results combine
	 * @return the combined result
	 */
	private static boolean match(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable object,
		@Nonnull List<ExpressionNode> args,
		@Nonnull String methodName,
		@Nonnull MatchMode mode
	) {
		validateRequiredNumberOfArgs(methodName, args, 1);
		if (!(object instanceof DataItemArray array)) {
			throw new ExpressionEvaluationException(
				"Cannot invoke method `" + methodName + "` on object of type `" +
					(object == null ? "null" : object.getClass().getName()) + "`. Expected an array of a complex " +
					"data object.",
				"Cannot invoke method `" + methodName + "`. Expected an array."
			);
		}
		final ExpressionNode predicate = args.get(0);
		for (final DataItem element : array.children()) {
			final Serializable result = predicate.compute(context.withThis(DataItemSupport.unwrap(element)));
			if (!(result instanceof Boolean matches)) {
				throw new ExpressionEvaluationException(
					"Predicate must evaluate to boolean, but got " +
						(result != null ? result.getClass().getName() : "null") + ".",
					"Predicate must evaluate to boolean."
				);
			}
			if (mode == MatchMode.ANY && matches) {
				return true;
			} else if (mode == MatchMode.ALL && !matches) {
				return false;
			} else if (mode == MatchMode.NONE && matches) {
				return false;
			}
		}
		// the result when no element decided it early
		return mode != MatchMode.ANY;
	}

	/**
	 * Validates that the method was called with the expected number of arguments.
	 *
	 * @param methodName           the invoked method
	 * @param args                 the arguments of the call
	 * @param expectedNumberOfArgs the number of arguments the method takes
	 */
	private static void validateRequiredNumberOfArgs(
		@Nonnull String methodName,
		@Nonnull List<ExpressionNode> args,
		int expectedNumberOfArgs
	) {
		if (args.size() != expectedNumberOfArgs) {
			throw new ExpressionEvaluationException(
				"Method `" + methodName + "` requires exactly " + expectedNumberOfArgs + " argument(s), but got " +
					args.size() + "."
			);
		}
	}

}
