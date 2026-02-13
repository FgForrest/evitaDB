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
import java.util.Locale;
import java.util.Map;

/**
 * Element accessor implementation for {@link Map} types. Provides keyed access to map elements
 * using string keys.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class MapElementAccessor implements ObjectElementAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		// we want to support serializable maps
		//noinspection unchecked
		return new Class[] { Map.class };
	}

	@Nullable
	@Override
	public Serializable get(@Nonnull Serializable object, @Nonnull String elementName) throws ExpressionEvaluationException {
		if (!(object instanceof Map<?, ?> map)) {
			throw new ExpressionEvaluationException(
				"Cannot access element by key on object of type `" + object.getClass().getName() + "`. Expected Map.",
				"Cannot access element by key. Expected Map."
			);
		}

		if (map.isEmpty()) {
			return null;
		}

		final Object key;
		if (map.keySet().stream().anyMatch(Locale.class::isInstance)) {
			key = Locale.forLanguageTag(elementName);
		} else {
			key = elementName;
		}

		final Object element = map.get(key);
		if (element == null) {
			return null;
		}
		if (!(element instanceof Serializable)) {
			throw new ExpressionEvaluationException(
				"Cannot access element by key `" + key + "` on object of type `" + object.getClass().getName() + "`. Expected serializable element.",
				"Cannot access element by key."
			);
		}
		return (Serializable) element;
	}
}
