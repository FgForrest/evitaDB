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
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Implementation of {@link ObjectPropertyAccessor} for the map nodes of a {@link ComplexDataObject}: dot notation reads
 * a property of the stored object, e.g. `$value.title` or `$entity.associatedData['article'].perex`.
 *
 * A property the map does not carry is `null`, never an error - a complex data object built from a POJO or from JSON
 * routinely omits null properties, so `?.` and `??` are the tools to handle it. A {@link ComplexDataObject} is
 * navigated as its root. The returned child is unwrapped - see {@link DataItemSupport}.
 *
 * Unlike {@link MapPropertyAccessor}, no property name is reserved: a stored object may well have an `entries`
 * property, and dot notation on it must read that property.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DataItemPropertyAccessor implements ObjectPropertyAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { DataItemMap.class, ComplexDataObject.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String propertyIdentifier
	) throws ExpressionEvaluationException {
		final Serializable navigable = DataItemSupport.toNavigable(object);
		if (navigable instanceof DataItemMap map) {
			return DataItemSupport.getChild(map, propertyIdentifier);
		}
		throw new ExpressionEvaluationException(
			"Cannot access property `" + propertyIdentifier + "` on object of type `" +
				DataItemSupport.typeName(navigable) + "`. Expected an object of a complex " +
				"data object.",
			"Cannot access property `" + propertyIdentifier + "`."
		);
	}

}
