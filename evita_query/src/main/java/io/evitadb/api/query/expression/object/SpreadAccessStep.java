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

package io.evitadb.api.query.expression.object;

import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
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
 * An {@link ObjectAccessStep} that applies a mapping expression to each element of a collection,
 * array, or map using the spread operator syntax (`.*[expr]`). Each element is made available as
 * the `$` (this) variable within the mapping expression context.
 *
 * When the compact variant is used (`.*![expr]`), null values are filtered out from the resulting
 * collection or map.
 *
 * For example, in the expression `$items.*[$.price]`, this step iterates over each item in
 * `$items` and evaluates `$.price` for each one, producing a list of prices.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class SpreadAccessStep implements ObjectAccessStep {

	@Serial private static final long serialVersionUID = 3389942116283673090L;

	@Nonnull private final ExpressionNode mappingExpression;
	/**
	 * Whether to compact the result collection/map, i.e. filter out null values.
	 */
	private final boolean compact;

	@Nullable @Getter private final ObjectAccessStep next;

	@Nonnull
	@Override
	public Serializable getAccessedIdentifier() {
		return null;
	}

	@Nullable
	@Override
	public Serializable compute(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable operand
	) throws ExpressionEvaluationException {
		if (operand == null) {
			throw new ExpressionEvaluationException(
				"Cannot spread operand, operand is null. If this is expected, use optional chaining (`?.*`) instead."
			);
		}

		final Serializable result;
		if (operand instanceof Collection<?> collection) {
			result = mapCollection(context, collection);
		} else if (operand.getClass().isArray()) {
			result = mapArray(context, operand);
		} else if (operand instanceof Map<?, ?> map) {
			result = mapMap(context, map);
		} else {
			throw new ExpressionEvaluationException(
				"Operand is not a collection, it is `" + operand.getClass().getName() + "`.",
				"Operand is not a collection."
			);
		}

		if (getNext() == null) {
			return result;
		}

		return getNext().compute(context, result);
	}

	@Nonnull
	private Serializable mapCollection(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Collection<?> collection
	) {
		final List<Serializable> mappedCollection = collection.stream()
			.map(item -> {
				if (item != null && !(item instanceof Serializable)) {
					throw new ExpressionEvaluationException(
						"Cannot access item `" + item.getClass().getName() + "`. Expected serializable item.",
						"Unexpected internal error occurred while accessing collection."
					);
				}
				return mapItem(context, (Serializable) item);
			})
			.filter(item -> item != null || !this.compact)
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
	private Serializable mapArray(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Object array
	) {
		if (array instanceof Object[] typedArray) {
			final Object[] mappedArray = new Object[typedArray.length];
			for (int i = 0; i < typedArray.length; i++) {
				final Object item = typedArray[i];
				if (item != null && !(item instanceof Serializable)) {
					throw new ExpressionEvaluationException(
						"Cannot access item `" + item.getClass().getName() + "`. Expected serializable item.",
						"Unexpected internal error occurred while accessing collection."
					);
				}
				mappedArray[i] = mapItem(context, (Serializable) item);
			}
			return mappedArray;
		} else {
			throw new ExpressionEvaluationException(
				"Cannot map element of class `" + array.getClass().getName() + "`. Expected object type.",
				"Cannot map element. Expected object type."
			);
		}
	}

	@Nonnull
	private Serializable mapMap(
		@Nonnull ExpressionEvaluationContext context,
		@Nonnull Map<?, ?> map
	) {
		final Map<Serializable, Serializable> mappedMap = createHashMap(map.size());
		for (final Map.Entry<?, ?> entry : map.entrySet()) {
			final Object key = entry.getKey();
			if (!(key instanceof Serializable)) {
				throw new ExpressionEvaluationException(
					"Cannot access key `" + key + "` on object of type `" + map.getClass().getName() + "`. Expected serializable key.",
					"Unexpected internal error occurred while accessing map."
				);
			}

			final Object value = entry.getValue();
			if (value != null && !(value instanceof Serializable)) {
				throw new ExpressionEvaluationException(
					"Cannot access map value `" + value.getClass().getName() + "`. Expected serializable value.",
					"Unexpected internal error occurred while accessing map."
				);
			}

			final Serializable mappedValue = mapItem(context, (Serializable) value);
			if (mappedValue != null || !this.compact) {
				mappedMap.put(
					(Serializable) key,
					mappedValue
				);
			}
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
	private Serializable mapItem(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable item
	) {
		return this.mappingExpression.compute(context.withThis(item));
	}
}
