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

import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Shared helpers that let EvitaEL navigate a {@link ComplexDataObject} - the tree of {@link DataItemMap},
 * {@link DataItemArray} and {@link DataItemValue} nodes associated data of a POJO type is stored as.
 *
 * The one rule every accessor of that tree follows is **unwrapping**: a {@link DataItemValue} never reaches an
 * expression, its {@link DataItemValue#value() value} does, so `$value.title == 'x'` compares a `String` rather than a
 * wrapper. Maps and arrays stay as they are, because they are what the next access step navigates.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DataItemSupport {

	/**
	 * Unwraps one node of a complex data object for an expression: a {@link DataItemValue} becomes its value, a
	 * {@link ComplexDataObject} becomes its unwrapped root, maps, arrays and anything that is not part of a complex
	 * data object pass through unchanged.
	 *
	 * @param item the node or any other value
	 * @return the value the expression sees
	 */
	@Nullable
	public static Serializable unwrap(@Nullable Serializable item) {
		if (item instanceof DataItemValue dataItemValue) {
			return dataItemValue.value();
		} else if (item instanceof ComplexDataObject complexDataObject) {
			return unwrap(complexDataObject.root());
		} else {
			return item;
		}
	}

	/**
	 * Unwraps the root of a complex data object into the node an expression navigates - see
	 * {@link #unwrap(Serializable)}. A complex data object passed through a property, element or method accessor is
	 * navigated as its root, so `$entity.associatedData['x'].title` reads the `title` of the root map.
	 *
	 * @param object a complex data object, or any node of one
	 * @return the root of the complex data object unwrapped, or the node itself
	 */
	@Nullable
	public static Serializable toNavigable(@Nullable Serializable object) {
		return object instanceof ComplexDataObject complexDataObject ? unwrap(complexDataObject.root()) : object;
	}

	/**
	 * Returns the child of a map node unwrapped, or `null` when the map has no such key - a missing property is
	 * `null` in a complex data object, so that `?.` and `??` can handle it.
	 *
	 * @param map  the map node
	 * @param name the key
	 * @return the unwrapped child, or `null`
	 */
	@Nullable
	public static Serializable getChild(@Nullable DataItemMap map, @Nullable String name) {
		if (map == null || name == null) {
			return null;
		}
		final DataItem child = map.getProperty(name);
		return unwrap(child);
	}

}
