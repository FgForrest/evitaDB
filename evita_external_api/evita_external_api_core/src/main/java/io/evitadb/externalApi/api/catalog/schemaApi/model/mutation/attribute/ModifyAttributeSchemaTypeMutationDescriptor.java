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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyAttributeSchemaTypeMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor TYPE = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Type of the attribute. Must be one of supported data types or its array.
			""")
		.type(nonNullRef(SCALAR_ENUM))
		.build();
	PropertyDescriptor INDEXED_DECIMAL_PLACES = PropertyDescriptor.builder()
		.name("indexedDecimalPlaces")
		.description("""
			Determines how many fractional places are important when entities are compared during filtering or sorting. It is
			significant to know that all values of this attribute will be converted to `Integer`, so the attribute
			number must not ever exceed maximum limits of `Integer` type when scaling the number by the power
			of ten using `indexedDecimalPlaces` as exponent.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyAttributeSchemaTypeMutation")
		.description("""
			Mutation is responsible for setting value to a `AttributeSchema.type`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AttributeSchema` or
			`GlobalAttributeSchema` alone.
			""")
		.staticFields(List.of(MUTATION_TYPE, NAME, TYPE, INDEXED_DECIMAL_PLACES))
		.build();
}
