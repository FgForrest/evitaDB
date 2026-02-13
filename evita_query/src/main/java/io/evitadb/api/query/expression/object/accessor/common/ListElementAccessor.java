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

import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * Element accessor implementation for {@link List} types. Provides indexed access to list elements
 * using integer indices.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ListElementAccessor implements ObjectElementAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		// we want to support serializable lists
		//noinspection unchecked
		return new Class[] { List.class };
	}

	@Nullable
	@Override
	public Serializable get(@Nonnull Serializable object, int elementIndex) throws ExpressionEvaluationException {
		if (!(object instanceof List<?> list)) {
			throw new ExpressionEvaluationException(
				"Cannot access element by index on object of type `" + object.getClass().getName() + "`. Expected List.",
				"Cannot access element by index. Expected List."
			);
		}

		try {
			final Object element = list.get(elementIndex);
			if (element == null) {
				return null;
			}
			if (!(element instanceof Serializable)) {
				throw new ExpressionEvaluationException(
					"Cannot access element `" + elementIndex + "` on object of type `" + object.getClass().getName() + "`. Expected serializable element.",
					"Cannot access element."
				);
			}
			return (Serializable) element;
		} catch (IndexOutOfBoundsException e) {
			throw new ExpressionEvaluationException(
				"Index `" + elementIndex + "` is out of bounds for list of size `" + list.size() + "`: " + e.getMessage(),
				"Index `" + elementIndex + "` is out of bounds.",
				e
			);
		}
	}
}
