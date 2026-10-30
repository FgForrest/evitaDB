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
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Implementation of {@link ObjectElementAccessor} for the nodes of a {@link ComplexDataObject}: bracket notation reads
 * a property of a map node by name (`$value['title']`) or an element of an array node by index (`$value.blocks[0]`).
 *
 * Both overloads are implemented for every supported type and report a mismatch - an index on a map, a name on an
 * array, an index out of bounds - as an {@link ExpressionEvaluationException}, never as the
 * `UnsupportedOperationException` of the interface defaults: an expression meeting a stored value of an unexpected
 * shape is a data problem the caller decides about, not a programming error. A missing property is `null`. The
 * returned child is unwrapped - see {@link DataItemSupport}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DataItemElementAccessor implements ObjectElementAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { DataItemMap.class, DataItemArray.class, ComplexDataObject.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String elementName
	) throws ExpressionEvaluationException {
		final Serializable navigable = DataItemSupport.toNavigable(object);
		if (navigable instanceof DataItemMap map) {
			return DataItemSupport.getChild(map, elementName);
		}
		throw new ExpressionEvaluationException(
			"Cannot access element `" + elementName + "` by name on object of type `" +
				DataItemSupport.typeName(navigable) +
				"`. Expected an object of a complex data object.",
			"Cannot access element `" + elementName + "` by name."
		);
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		int elementIndex
	) throws ExpressionEvaluationException {
		final Serializable navigable = DataItemSupport.toNavigable(object);
		if (navigable instanceof DataItemArray array) {
			final DataItem[] children = array.children();
			if (elementIndex < 0 || elementIndex >= children.length) {
				throw new ExpressionEvaluationException(
					"Index `" + elementIndex + "` is out of bounds for an array of size `" + children.length + "`.",
					"Index `" + elementIndex + "` is out of bounds."
				);
			}
			return DataItemSupport.unwrap(children[elementIndex]);
		}
		throw new ExpressionEvaluationException(
			"Cannot access element `" + elementIndex + "` by index on object of type `" +
				DataItemSupport.typeName(navigable) +
				"`. Expected an array of a complex data object.",
			"Cannot access element `" + elementIndex + "` by index."
		);
	}

}
