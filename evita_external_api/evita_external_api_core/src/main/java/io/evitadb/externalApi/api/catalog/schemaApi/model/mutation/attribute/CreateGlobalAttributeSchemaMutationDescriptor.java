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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import java.util.List;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface CreateGlobalAttributeSchemaMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor DESCRIPTION = PropertyDescriptor.builder()
		.name("description")
		.description("""
			Contains description of the model is optional but helps authors of the schema / client API to better
			explain the original purpose of the model to the consumers.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor DEPRECATION_NOTICE = PropertyDescriptor.builder()
		.name("deprecationNotice")
		.description("""
			Deprecation notice contains information about planned removal of this attribute from the model / client API.
			This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
			""")
		.type(nullable(String.class))
		.build();
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
		.type(nullable(AttributeUniquenessType.class))
		.build();

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
		.type(nullable(GlobalAttributeUniquenessType.class))
		.build();
	PropertyDescriptor FILTERABLE = PropertyDescriptor.builder()
		.name("filterable")
		.description("""
			When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
			as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
			(memory/disk) space in the form of index.
			""")
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor SORTABLE = PropertyDescriptor.builder()
		.name("sortable")
		.description("""
			When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
			as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
			(memory/disk) space in the form of index.
			""")
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor LOCALIZED = PropertyDescriptor.builder()
		.name("localized")
		.description("""
			Localized attribute has to be ALWAYS used in connection with specific `locale`. In other
			words - it cannot be stored unless associated locale is also provided.
			""")
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor NULLABLE = PropertyDescriptor.builder()
		.name("nullable")
		.description("""
			When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
			non-null checks upon upserting of the entity.
			""")
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor REPRESENTATIVE = PropertyDescriptor.builder()
		.name("representative")
		.description("""
			If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	        primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	        affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	        helpful to developers in quickly finding their way around the data. There should be very few representative
	        attributes in the entity type, and the unique ones are usually the best to choose.
			""")
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor TYPE = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Type of the attribute. Must be one of supported data types or its array.
			""")
		.type(nonNullRef(SCALAR_ENUM))
		.build();
	PropertyDescriptor DEFAULT_VALUE = PropertyDescriptor.builder()
		.name("defaultValue")
		.description("""
			Default value is used when the entity is created without this attribute specified. Default values allow to pass
			non-null checks even if no attributes of such name are specified.
			""")
		.type(nullable(Any.class))
		.build();
	PropertyDescriptor INDEXED_DECIMAL_PLACES = PropertyDescriptor.builder()
		.name("indexedDecimalPlaces")
		.description("""
			Determines how many fractional places are important when entities are compared during filtering or sorting. It is
			significant to know that all values of this attribute will be converted to `Integer`, so the attribute
			number must not ever exceed maximum limits of `Integer` type when scaling the number by the power
			of ten using `indexedDecimalPlaces` as exponent.
			""")
		.type(nullable(Integer.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("CreateGlobalAttributeSchemaMutation")
		.description("""
			Mutation is responsible for setting up a new `GlobalAttributeSchema` in the `CatalogSchema`.
			Mutation can be used for altering also the existing `GlobalAttributeSchema` alone.
			""")
		.staticFields(List.of(
			NAME,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			UNIQUENESS_TYPE,
			GLOBAL_UNIQUENESS_TYPE,
			FILTERABLE,
			SORTABLE,
			LOCALIZED,
			NULLABLE,
			TYPE,
			DEFAULT_VALUE,
			INDEXED_DECIMAL_PLACES
		))
		.build();
}
