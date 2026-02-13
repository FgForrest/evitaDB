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

import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * The {@code MapPropertyAccessor} class provides access to properties of {@link Map} objects.
 * It supports retrieving specific properties of a map.
 * This implementation ensures that both keys and values within the map are serializable.
 * If a requested property is not present or invalid, an {@link ExpressionEvaluationException} is thrown.
 *
 * The following property is supported:
 * - {@code entries}: Returns a {@code List} of {@link SerializableMapEntry} that contains all serializable key-value pairs from the map.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class MapPropertyAccessor implements ObjectPropertyAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		// we want to support serializable maps
		//noinspection unchecked
		return new Class[] { Map.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String propertyIdentifier
	) throws ExpressionEvaluationException {
		if (!(object instanceof Map<?,?> map)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected Map.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case "entries" -> {
				final List<SerializableMapEntry> entries = map.entrySet()
					.stream()
					.map(e -> {
						if (!(e.getKey() instanceof Serializable) || !(e.getValue() instanceof Serializable)) {
							throw new ExpressionEvaluationException(
								"Cannot access map entry `" + e.getKey() + "` on object of type `" + object.getClass().getName() + "`. Expected serializable entry.",
								"Cannot access map entry."
							);
						}
						return new SerializableMapEntry((Serializable) e.getKey(), (Serializable) e.getValue());
					})
					.toList();

				if (!(entries instanceof Serializable)) {
					throw new ExpressionEvaluationException(
						"Expected List<SerializableMapEntry> to be serializable, but it is not.",
						"Unexpected internal error occurred while accessing map entries."
					);
				}

				yield (Serializable) entries;
			}
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on Map.",
					"Property `" + propertyIdentifier + "` does not exist on map."
				);
		};
	}
}
