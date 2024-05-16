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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;

import javax.annotation.Nonnull;

/**
 * This schema is an extension of standard {@link AttributeSchema} that adds support for marking the attribute as
 * globally unique. The global attribute schema can be used only at {@link CatalogSchema} level.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface GlobalAttributeSchemaContract extends EntityAttributeSchemaContract {

	/**
	 * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
	 * entity having certain value of this attribute in entire {@link CatalogContract}.
	 * {@link AttributeSchemaContract#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
	 * better to have this ensured by the database engine.
	 */
	boolean isUniqueGlobally();

	/**
	 * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
	 * entity having certain value of this attribute in entire {@link CatalogContract}.
	 * {@link AttributeSchemaContract#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #isUniqueGlobally()} in that it is possible to have multiple entities with same
	 * value of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different
	 * locales.
	 */
	boolean isUniqueGloballyWithinLocale();

	/**
	 * Returns type of uniqueness of the attribute. See {@link #isUniqueGlobally()} ()} and {@link #isUniqueGloballyWithinLocale()}.
	 * @return type of uniqueness
	 */
	@Nonnull
	GlobalAttributeUniquenessType getGlobalUniquenessType();

}
