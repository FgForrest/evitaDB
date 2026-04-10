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
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Implementation of {@link ObjectPropertyAccessor} for {@link DateTimeRange} objects.
 * Enables dot-property access expressions in EvitaEL to navigate the range boundaries,
 * e.g. `validity.from`, `validity.to`.
 *
 * @see ObjectPropertyAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class DateTimeRangePropertyAccessor implements ObjectPropertyAccessor {

	public static final String FROM_PROPERTY = "from";
	public static final String TO_PROPERTY = "to";

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { DateTimeRange.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String propertyIdentifier
	) throws ExpressionEvaluationException {
		if (!(object instanceof DateTimeRange range)) {
			throw new ExpressionEvaluationException(
				"Cannot access property on object of type `" + object.getClass().getName() + "`. Expected DateTimeRange.",
				"Cannot access property."
			);
		}

		return switch (propertyIdentifier) {
			case FROM_PROPERTY -> {
				final OffsetDateTime from = range.getPreciseFrom();
				yield from;
			}
			case TO_PROPERTY -> {
				final OffsetDateTime to = range.getPreciseTo();
				yield to;
			}
			default ->
				throw new ExpressionEvaluationException(
					"Property `" + propertyIdentifier + "` does not exist on DateTimeRange.",
					"Property `" + propertyIdentifier + "` does not exist on date time range."
				);
		};
	}
}
