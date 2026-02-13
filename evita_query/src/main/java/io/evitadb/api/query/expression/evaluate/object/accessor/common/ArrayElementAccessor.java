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

package io.evitadb.api.query.expression.evaluate.object.accessor.common;

import io.evitadb.api.query.expression.evaluate.object.accessor.ObjectElementAccessor;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Element accessor implementation for `Object[]` arrays (and all object array subtypes like `String[]`,
 * `BigDecimal[]`, etc.). Provides indexed access to array elements.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ArrayElementAccessor implements ObjectElementAccessor {

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
	public Serializable get(@Nonnull Serializable object, int elementIndex) throws ExpressionEvaluationException {
		try {
			// Primitive arrays (must check before Object[])
			if (object instanceof boolean[] array) {
				return array[elementIndex];
			} else if (object instanceof byte[] array) {
				return array[elementIndex];
			} else if (object instanceof char[] array) {
				return array[elementIndex];
			} else if (object instanceof double[] array) {
				return array[elementIndex];
			} else if (object instanceof float[] array) {
				return array[elementIndex];
			} else if (object instanceof int[] array) {
				return array[elementIndex];
			} else if (object instanceof long[] array) {
				return array[elementIndex];
			} else if (object instanceof short[] array) {
				return array[elementIndex];
			} else if (object instanceof Object[] array) {
				// Handles all object arrays (String[], Integer[], BigDecimal[], etc.)
				final Object element = array[elementIndex];
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
			} else {
				throw new ExpressionEvaluationException(
					"Cannot access element by index on object of type `" + object.getClass().getName() + "`. Expected array type.",
					"Cannot access element by index. Expected array type."
				);
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			final int length = java.lang.reflect.Array.getLength(object);
			throw new ExpressionEvaluationException(
				"Index `" + elementIndex + "` is out of bounds for array of length `" + length + "`: " + e.getMessage(),
				"Index `" + elementIndex + "` is out of bounds for array of length `" + length + "`.",
				e
			);
		}
	}
}
