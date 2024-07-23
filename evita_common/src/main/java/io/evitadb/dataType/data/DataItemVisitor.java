/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.dataType.data;

import io.evitadb.dataType.ComplexDataObject;

import javax.annotation.Nonnull;
import java.util.function.BiConsumer;

/**
 * Interface represents the visitor pattern for traversing entire {@link ComplexDataObject} tree.
 */
public interface DataItemVisitor {

	/**
	 * Method is called on each {@link DataItemArray} of the tree. The implementation is responsible for iterating
	 * over {@link DataItemArray#children()} on its own.
	 */
	void visit(@Nonnull DataItemArray arrayItem);

	/**
	 * Method is called on each {@link DataItemMap} of the tree. The implementation is responsible for iterating
	 * over {@link DataItemArray#forEach(BiConsumer)} on its own.
	 */
	void visit(@Nonnull DataItemMap mapItem);

	/**
	 * Method is called on each {@link DataItemValue} of the tree.
	 */
	void visit(@Nonnull DataItemValue valueItem);

}
