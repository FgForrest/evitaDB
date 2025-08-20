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

package io.evitadb.api.requestResponse.schema.dto;

import java.util.Locale;

/**
 * This enum represents the uniqueness type of an {@link AttributeSchema}. It is used to determine whether the attribute
 * value must be unique among all the entity attributes of this type or whether it must be unique only among attributes
 * of the same locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public enum AttributeUniquenessType {

	/**
	 * The attribute is not unique (default).
	 */
	NOT_UNIQUE,
	/**
	 * The attribute value must be unique among all the entities of the same collection.
	 */
	UNIQUE_WITHIN_COLLECTION,
	/**
	 * The localized attribute value must be unique among all values of the same {@link Locale} among all the entities
	 * using of the same collection.
	 */
	UNIQUE_WITHIN_COLLECTION_LOCALE

}
