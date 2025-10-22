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

import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing scope-specific global uniqueness type of attribute.
 * It is used to represent both input ({@link ScopedGlobalAttributeUniquenessType}) in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ScopedGlobalAttributeUniquenessTypeDescriptor extends ScopedDataDescriptor {

	PropertyDescriptor UNIQUENESS_TYPE = PropertyDescriptor.builder()
		.name("uniquenessType")
		.description("""
			When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
			entity having certain value of this attribute in entire catalog.

			As an example of unique attribute can be URL - there is no sense in having two entities with same URL, and 
			it's better to have this ensured by the database engine.
            
            If the attribute is localized you can choose between `UNIQUE_WITHIN_CATALOG` and `UNIQUE_WITHIN_CATALOG_LOCALE`
			modes. The first will ensure there is only single value within entire catalog regardless of locale,
			the second will ensure there is only single value within catalog and specific locale.
			""")
		.type(nonNull(GlobalAttributeUniquenessType.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedGlobalAttributeUniquenessType")
		.description("""
			Represents combination of global uniqueness type and entity scope it should be applied to.
			""")
		.staticProperties(List.of(SCOPE, UNIQUENESS_TYPE))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedGlobalAttributeUniquenessType")
		.build();
}
