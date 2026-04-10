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
import java.util.Map;

/**
 * Method accessor implementation for maps.
 * Provides method invocation support for the following methods:
 *
 * - `size()` - returns the number of entries in the map
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class MapMethodAccessor implements ObjectMethodAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { Map.class };
	}

	@Nullable
	@Override
	public Serializable invoke(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Serializable object,
		@Nonnull String methodIdentifier,
		@Nonnull List<ExpressionNode> args
	) throws ExpressionEvaluationException {
		if (!(object instanceof Map<?, ?> map)) {
			throw new ExpressionEvaluationException(
				"Cannot invoke method on object of type `" + object.getClass().getName() + "`. Expected Map.",
				"Cannot invoke method. Expected Map."
			);
		}

		return switch (methodIdentifier) {
			case "size" -> invokeSizeMethod(map, args);
			default ->
				throw new ExpressionEvaluationException(
					"Cannot invoke method `" + methodIdentifier + "` on object of type `" + object.getClass().getName() + "`, not supported.",
					"Cannot invoke method `" + methodIdentifier + "`. Not supported on map."
				);
		};
	}

	/**
	 * Returns the number of entries in the given map.
	 */
	private static int invokeSizeMethod(
		@Nonnull Map<?, ?> map,
		@Nonnull List<ExpressionNode> args
	) {
		validateRequiredNumberOfArgs("size", args, 0);
		return map.size();
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
