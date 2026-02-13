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

import io.evitadb.api.query.expression.evaluate.object.accessor.ObjectPropertyAccessor;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Provides property access for objects of type {@link SerializableMapEntry}. This class is responsible for
 * retrieving specific properties of the {@link SerializableMapEntry} such as "key" or "value".
 *
 * The supported properties are:
 * - "key": Retrieves the key of the {@link SerializableMapEntry}.
 * - "value": Retrieves the value of the {@link SerializableMapEntry}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class MapEntryPropertyAccessor implements ObjectPropertyAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { SerializableMapEntry.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String propertyIdentifier
	) throws ExpressionEvaluationException {
		if (!(object instanceof SerializableMapEntry entry)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected Map.Entry.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case "key" -> entry.key();
			case "value" -> entry.value();
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on Map.Entry.",
					"Property `" + propertyIdentifier + "` does not exist on map entry."
				);
		};
	}
}
