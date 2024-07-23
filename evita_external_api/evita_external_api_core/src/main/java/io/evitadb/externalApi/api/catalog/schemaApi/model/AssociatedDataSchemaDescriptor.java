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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link AssociatedDataSchema}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface AssociatedDataSchemaDescriptor extends NamedSchemaWithDeprecationDescriptor {

	PropertyDescriptor TYPE = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Data type of the associated data. Must be one of Evita-supported values.
			Internally the type is converted into Java-corresponding data type.
			The type may be scalar type or may represent complex object type (JSON).
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor LOCALIZED = PropertyDescriptor.builder()
		.name("localized")
		.description("""
			Localized associated data has to be ALWAYS used in connection with specific `Locale`. In other
			words - it cannot be stored unless associated locale is also provided.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor NULLABLE = PropertyDescriptor.builder()
		.name("nullable")
		.description("""
			When associated data is nullable, its values may be missing in the entities. Otherwise, the system will enforce
			non-null checks upon upserting of the entity.
			""")
		.type(nonNull(Boolean.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AssociatedDataSchema")
		.description("""
			This is the definition object for associated data that is stored along with
			entity. Definition objects allow to describe the structure of the entity type so that
			in any time everyone can consult complete structure of the entity type.
						
			Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
			along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
			in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
			binary arrays representing entire files (i.e. pictures, documents).
			""")
		.staticFields(List.of(
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			LOCALIZED,
			NULLABLE,
			TYPE
		))
		.build();
}
