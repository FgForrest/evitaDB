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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.data.ComplexDataObjectConverter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;

/**
 * This is the definition object for {@link AssociatedData} that is stored along with
 * {@link Entity}. Definition objects allow to describe the structure of the entity type so that
 * in any time everyone can consult complete structure of the entity type. Definition object is similar to Java reflection
 * process where you can also at any moment see which fields and methods are available for the class.
 *
 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
 * binary arrays representing entire files (i.e. pictures, documents).
 *
 * The search query must contain specific {@link AssociatedDataContent} requirement in order associated data are fetched
 * along with the entity. Associated data are stored and fetched separately by their name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AssociatedDataSchemaContract extends NamedSchemaWithDeprecationContract {

	/**
	 * Contains the data type of the entity. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()} types or may
	 * represent complex type - which is POJO that can be automatically ({@link ComplexDataObjectConverter}) converted
	 * to the set of basic types.
	 */
	@Nonnull
	Class<? extends Serializable> getType();

	/**
	 * Returns attribute type that represents non-array type class. I.e. method just unwraps array types to plain ones.
	 */
	@Nonnull
	Class<? extends Serializable> getPlainType();

	/**
	 * Localized associated data has to be ALWAYS used in connection with specific {@link Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 */
	boolean isLocalized();

	/**
	 * When associated data is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 */
	boolean isNullable();

}
