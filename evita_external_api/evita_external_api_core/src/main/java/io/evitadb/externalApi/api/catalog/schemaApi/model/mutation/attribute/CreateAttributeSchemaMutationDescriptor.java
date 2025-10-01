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

import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import java.util.List;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface CreateAttributeSchemaMutationDescriptor extends AttributeSchemaMutationDescriptor {

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
	PropertyDescriptor UNIQUE_IN_SCOPES = PropertyDescriptor.builder()
		.name("uniqueInScopes")
		.description("""
			The ScopedAttributeUniquenessType class encapsulates the relationship between an attribute's
			uniqueness type and the scope in which this uniqueness characteristic is enforced.
			
			It makes use of two parameters:
			- scope: Defines the context or domain (live or archived) where the attribute resides.
			- uniquenessType: Determines the uniqueness enforcement (e.g., unique within the entire collection or specific locale).
			
			The combination of these parameters allows for scoped uniqueness checks within attribute schemas,
			providing fine-grained control over attribute constraints based on the entity's scope.
			""")
		.type(nullableListRef(ScopedAttributeUniquenessTypeDescriptor.THIS_INPUT))
		.build();
	PropertyDescriptor FILTERABLE_IN_SCOPES = PropertyDescriptor.builder()
		.name("filterableInScopes")
		.description("""
			When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
			as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
			(memory/disk) space in the form of index.
			
			This array defines in which scopes the attribute will be filterable. It will not be filterable in not-specified scopes.
			""")
		.type(nullable(Scope[].class))
		.build();
	PropertyDescriptor SORTABLE_IN_SCOPES = PropertyDescriptor.builder()
		.name("sortableInScopes")
		.description("""
			When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
			as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
			(memory/disk) space in the form of index.
			
			This array defines in which scopes the attribute will be sortable. It will not be sortable in not-specified scopes.
			""")
		.type(nullable(Scope[].class))
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
		.name("CreateAttributeSchemaMutation")
		.description("""
			Mutation is responsible for setting up a new `AttributeSchema` in the `EntitySchema`.
			Mutation can be used for altering also the existing `AttributeSchema` alone.
			""")
		.staticFields(List.of(
			MUTATION_TYPE,
			NAME,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			UNIQUE_IN_SCOPES,
			FILTERABLE_IN_SCOPES,
			SORTABLE_IN_SCOPES,
			LOCALIZED,
			NULLABLE,
			REPRESENTATIVE,
			TYPE,
			DEFAULT_VALUE,
			INDEXED_DECIMAL_PLACES
		))
		.build();
}
