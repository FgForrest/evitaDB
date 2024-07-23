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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import java.util.List;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface UpsertAttributeMutationDescriptor extends AttributeMutationDescriptor {

	PropertyDescriptor VALUE = PropertyDescriptor.builder()
		.name("value")
		.description("""
			New value of this attribute. Data type is expected to be the same as in schema or must be explicitly
			set via `valueType`.
			""")
		.type(nonNull(Any.class))
		.build();
	PropertyDescriptor VALUE_TYPE = PropertyDescriptor.builder()
		.name("valueType")
		.description("""
			Data type of passed value of this attribute. Required only when inserting new attribute
			without prior schema. Otherwise data type is found in schema.
			""")
		.type(nullableRef(SCALAR_ENUM))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("UpsertAttributeMutation")
		.description("""
			Upsert attribute mutation will either update existing attribute or create new one.
			""")
		.staticFields(List.of(NAME, LOCALE, VALUE, VALUE_TYPE))
		.build();
}
