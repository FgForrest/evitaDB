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

package io.evitadb.api.query.expression.coalesce;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Expression node implementing the spread null coalescing operator (`*?` / `?*?`). Iterates over
 * elements of a collection, array, or map and replaces each null element with the provided default
 * value.
 *
 * The `nullSafe` flag controls behavior when the input object itself is null:
 * - when `true` (`?*?` syntax), returns null without error
 * - when `false` (`*?` syntax), throws an {@link ExpressionEvaluationException}
 *
 * For example, given a list `[1, null, 3]` and default value `0`, the result is `[1, 0, 3]`.
 * For maps, only the values are coalesced while keys are preserved.

 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
@RequiredArgsConstructor
public class SpreadNullCoalesceOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 2760082902212762061L;

	private final boolean nullSafe;

	@Nonnull private final ExpressionNode valueOperator;
	@Nonnull private final ExpressionNode defaultValueOperator;

	@Nullable
	@Override
	public Serializable compute(@Nonnull ExpressionEvaluationContext context) throws ExpressionEvaluationException {
		final Serializable value = this.valueOperator.compute(context);
		if (value == null) {
			if (this.nullSafe) {
				return null;
			} else {
				throw new ExpressionEvaluationException(
					"Cannot coalesce null items, object is null. If this is expected, use " +
						"optional chaining (`?*?`) instead."
				);
			}
		}

		final Serializable defaultValue = this.defaultValueOperator.compute(context);

		if (value instanceof Collection<?> collection) {
			return coalesceCollection(collection, defaultValue);
		} else if (value.getClass().isArray()) {
			return coalesceArray(value, defaultValue);
		} else if (value instanceof Map<?, ?> map) {
			return coalesceMap(map, defaultValue);
		} else {
			throw new ExpressionEvaluationException(
				"Cannot coalesce null items, object not a collection. Object is of type `" + value.getClass().getName() + "`",
				"Cannot coalesce null items, object not a collection."
			);
		}
	}

	@Nonnull
	private static Serializable coalesceCollection(
		@Nonnull Collection<?> collection,
		@Nullable Serializable defaultValue
	) {
		final List<Serializable> mappedCollection = collection.stream()
			.map(item -> coalesceItem(item, defaultValue))
			.toList();

		Assert.isPremiseValid(
			mappedCollection instanceof Serializable,
			() -> new GenericEvitaInternalError(
				"Expected unmodifiable mapped collection to be serializable, but it is not.",
				"Unexpected internal error occurred while accessing collection."
			)
		);
		return (Serializable) mappedCollection;
	}

	@Nonnull
	private static Serializable coalesceArray(
		@Nonnull Object array,
		@Nullable Serializable defaultValue
	) {
		if (array instanceof Object[] typedArray) {
			final Object[] mappedArray = new Object[typedArray.length];
			for (int i = 0; i < typedArray.length; i++) {
				mappedArray[i] = coalesceItem(typedArray[i], defaultValue);
			}
			return mappedArray;
		} else {
			// else it is an array of primitives whose elements cannot be null, thus we don't need to coalesce them
			return (Serializable) array;
		}
	}

	@Nonnull
	private static Serializable coalesceMap(
		@Nonnull Map<?, ?> map,
		@Nullable Serializable defaultValue
	) {
		final Map<Serializable, Serializable> mappedMap = createHashMap(map.size());
		for (final Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getKey() instanceof Serializable)) {
				throw new ExpressionEvaluationException(
					"Cannot access key `" + entry.getKey() + "` on object of type `" + map.getClass().getName() + "`. Expected serializable key.",
					"Unexpected internal error occurred while accessing map."
				);
			}
			mappedMap.put(
				(Serializable) entry.getKey(),
				coalesceItem(entry.getValue(), defaultValue)
			);
		}

		final Map<?, ?> unmodifiableMappedMap = Map.copyOf(mappedMap);
		Assert.isPremiseValid(
			unmodifiableMappedMap instanceof Serializable,
			() -> new GenericEvitaInternalError(
				"Expected unmodifiable mapped map to be serializable, but it is not.",
				"Unexpected internal error occurred while accessing map."
			)
		);
		return (Serializable) unmodifiableMappedMap;
	}

	@Nullable
	private static Serializable coalesceItem(
		@Nullable Object item,
		@Nullable Serializable defaultValue
	) {
		return (Serializable) (item == null ? defaultValue : item);
	}


	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		// todo lho impl
		throw new UnsupportedOperationException();
	}
}
