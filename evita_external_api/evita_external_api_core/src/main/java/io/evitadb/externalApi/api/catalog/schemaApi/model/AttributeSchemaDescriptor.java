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

import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import java.util.List;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of {@link AttributeSchema} for schema-based external APIs. It describes what properties of attribute schema are
 * supported in API for better field names and docs maintainability.
 *
 * It should copy {@link AttributeSchema} closely so that it can be used for altering the schema though external API.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface AttributeSchemaDescriptor extends NamedSchemaWithDeprecationDescriptor {

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
		.type(nonNullListRef(ScopedAttributeUniquenessTypeDescriptor.THIS))
		.build();

	PropertyDescriptor FILTERABLE = PropertyDescriptor.builder()
		.name("filterable")
		.description("""
			When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
			as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
			(memory/disk) space in the form of index.
						
			When attribute is filterable, extra result `attributeHistogram`
			can be requested for this attribute.
			
			Returns array of scope in which this attribute is filterable.
			""")
		.type(nonNull(Scope[].class))
		.build();

	PropertyDescriptor SORTABLE = PropertyDescriptor.builder()
		.name("sortable")
		.description("""
			When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
			as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
			(memory/disk) space in the form of index.
			
			Returns array of scope in which this attribute is sortable.
			""")
		.type(nonNull(Scope[].class))
		.build();

	PropertyDescriptor LOCALIZED = PropertyDescriptor.builder()
		.name("localized")
		.description("""
			When attribute is localized, it has to be ALWAYS used in connection with specific `Locale`.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor NULLABLE = PropertyDescriptor.builder()
		.name("nullable")
		.description("""
			When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
			non-null checks upon upserting of the entity.
			""")
		.type(nonNull(Boolean.class))
		.build();

	PropertyDescriptor DEFAULT_VALUE = PropertyDescriptor.builder()
		.name("defaultValue")
		.description("""
			Default value is used when the entity is created without this attribute specified. Default values allow to pass
			non-null checks even if no attributes of such name are specified.
			""")
		.type(nullable(Any.class))
		.build();

	PropertyDescriptor TYPE = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Data type of the attribute. Must be one of Evita-supported values.
			Internally the scalar is converted into Java-corresponding data type.
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor INDEXED_DECIMAL_PLACES = PropertyDescriptor.builder()
		.name("indexedDecimalPlaces")
		.description("""
			Determines how many fractional places are important when entities are compared during filtering or sorting. It is
			significant to know that all values of this attribute will be converted to `Int`, so the attribute
			number must not ever exceed maximum limits of `Int` type when scaling the number by the power
			of ten using `indexedDecimalPlaces` as exponent.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AttributeSchema")
		.description("""
			This is the definition object for attributes that are stored along with
			entity or reference. Definition objects allow to describe the structure of the entity type so that
			in any time everyone can consult complete structure of the entity type. Definition object is similar to Java reflection
			process where you can also at any moment see which fields and methods are available for the class.
			
			Entity attributes allows defining set of data that are fetched in bulk along with the entity body.
			Attributes may be indexed for fast filtering or can be used to sort along. Attributes are not automatically indexed
			in order not to waste precious memory space for data that will never be used in search queries.
			
			Filtering in attributes is executed by using constraints like `and`, `or`, `not`,
			`attribute_{name}_equals`, `attribute_{name}_contains` and many others. Sorting can be achieved with
			`attribute_{name}_natural` or others.
			
			Attributes are not recommended for bigger data as they are all loaded at once when requested.
			Large data that are occasionally used store in associated data.
			""")
		.staticProperties(List.of(
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			UNIQUENESS_TYPE,
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
