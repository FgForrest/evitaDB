/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;

/**
 * Descriptor of {@link GlobalAttributeSchema} for schema-based external APIs. It describes what properties of global attribute schema are
 * supported in API for better field names and docs maintainability.
 *
 * It should copy {@link GlobalAttributeSchema} closely so that it can be used for altering the schema though external API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface GlobalAttributeSchemaDescriptor extends EntityAttributeSchemaDescriptor {

	PropertyDescriptor GLOBAL_UNIQUENESS_TYPE = PropertyDescriptor.builder()
		.name("globalUniquenessType")
		.description("""
			When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
            entity having certain value of this attribute in entire catalog.
            
            As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and it's
            better to have this ensured by the database engine.
            
            If the attribute is localized you can choose between `UNIQUE_WITHIN_CATALOG` and `UNIQUE_WITHIN_CATALOG_LOCALE`
			modes. The first will ensure there is only single value within entire catalog regardless of locale,
			the second will ensure there is only single value within catalog and specific locale.
			""")
		.type(nonNullListRef(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("GlobalAttributeSchema")
		.description("""
			This is the definition object for attributes that are stored along with
			catalog. Definition objects allow to describe the structure of the catalog so that
			in any time everyone can consult complete structure of the catalog. Definition object is similar to Java reflection
			process where you can also at any moment see which fields and methods are available for the class.
			
			Catalog attributes allows defining set of data that are fetched in bulk along with the catalog body.
			Attributes may be indexed for fast filtering or can be used to sort along. Attributes are not automatically indexed
			in order not to waste precious memory space for data that will never be used in search queries.
			
			Filtering in attributes is executed by using constraints like `and`, `or`, `not`,
			`attribute{name}Equals`, `attribute{name}Contains` and many others. Sorting can be achieved with
			`attribute{name}Natural` or others.
			
			Attributes are not recommended for bigger data as they are all loaded at once when requested.
			""")
		.staticProperties(List.of(
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			UNIQUENESS_TYPE,
			GLOBAL_UNIQUENESS_TYPE,
			FILTERABLE,
			SORTABLE,
			LOCALIZED,
			NULLABLE,
			REPRESENTATIVE,
			TYPE,
			DEFAULT_VALUE,
			INDEXED_DECIMAL_PLACES
		))
		.build();
}
