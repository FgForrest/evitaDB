/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query;

import javax.annotation.Nonnull;

/**
 * Marks query as one that can operate on attributes of entity or reference.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface AttributeConstraint<T extends TypeDefiningConstraint<T>> extends PropertyTypeDefiningConstraint<T> {

	/**
	 * Returns attribute name that needs to be examined. If the {@link #getAttributeNames()} returns more than one
	 * attribute name, this method throws an exception.
	 *
	 * The method allows unified access to attribute name for constraints that can operate on attributes in constraint
	 * visitor implementations. It may not be necessarily used in evitaDB itself.
	 *
	 * @throws IllegalArgumentException if the {@link #getAttributeNames()} returns more than one attribute name
	 */
	@Nonnull
	default String getAttributeName() throws IllegalArgumentException {
		final String[] attributeNames = getAttributeNames();
		if (attributeNames.length == 0) {
			throw new IllegalArgumentException("Constraint does not define any attribute name!");
		} else if (attributeNames.length > 1) {
			throw new IllegalArgumentException("Constraint defines more than one attribute name!");
		} else {
			return attributeNames[0];
		}
	}

	/**
	 * Returns set of attribute names that needs to be examined.
	 *
	 * The method allows unified access to attribute name for constraints that can operate on attributes in constraint
	 * visitor implementations. It may not be necessarily used in evitaDB itself.
	 */
	@Nonnull
	default String[] getAttributeNames() {
		return new String[] {getAttributeName()};
	}

}
