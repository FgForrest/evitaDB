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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetAttributeSchemaUniqueMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor UNIQUENESS_TYPE = PropertyDescriptor.builder()
		.name("uniquenessType")
		.description("""
			When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
			having certain value of this attribute among other entities in the same collection.
						
			As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
			better to have this ensured by the database engine.
						
			If the attribute is localized you can choose between `UNIQUE_WITHIN_COLLECTION` and `UNIQUE_WITHIN_COLLECTION_LOCALE`
			modes. The first will ensure there is only single value within entire collection regardless of locale,
			the second will ensure there is only single value within collection and specific locale.
			""")
		.type(nonNull(AttributeUniquenessType.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetAttributeSchemaUniqueMutation")
		.description("""
			Mutation is responsible for setting value to a `AttributeSchema.unique`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AttributeSchema` or
			`GlobalAttributeSchema` alone.
			""")
		.staticFields(List.of(NAME, UNIQUENESS_TYPE))
		.build();
}
