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

import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;

/**
 * Descriptor of {@link CatalogSchema} for schema-based external APIs. It describes what properties of catalog schema are
 * supported in API for better field names and docs maintainability.
 *
 * It should copy {@link CatalogSchema} closely so that it can be used for altering the schema though external API.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface CatalogSchemaDescriptor extends VersionedDescriptor, NamedSchemaDescriptor {

	PropertyDescriptor ATTRIBUTES = PropertyDescriptor.builder()
		.name("attributes")
		.description("""
			Contains index of generally (catalog-wide) shared `AttributeSchema` that could be used as attributes of any
            entity type that refers them. These attributes cannot be changed from within the entity schema. Entity schemas
            will not be able to define their own attribute of same name that would clash with the global one (they may only
            reference the attributes with the same name from the catalog schema).
            
            There may be entities that won't take advantage of certain global attributes (i.e. it's not guaranteed that all
            entity types in catalog have all global attributes).
            
            The "catalog-wide" unique attributes allows Evita to fetch entity of any (and up-front unknown) entity type by
            some unique attribute value - usually URL.
			""")
		// type is expected to be a map with attribute names as keys and attribute schemas as values
		.build();

	PropertyDescriptor ALL_ATTRIBUTES = PropertyDescriptor.builder()
		.name("allAttributes")
		.description("""
			Contains index of generally (catalog-wide) shared `AttributeSchema` that could be used as attributes of any
            entity type that refers them. These attributes cannot be changed from within the entity schema. Entity schemas
            will not be able to define their own attribute of same name that would clash with the global one (they may only
            reference the attributes with the same name from the catalog schema).
            
            There may be entities that won't take advantage of certain global attributes (i.e. it's not guaranteed that all
            entity types in catalog have all global attributes).
            
            The "catalog-wide" unique attributes allows Evita to fetch entity of any (and up-front unknown) entity type by
            some unique attribute value - usually URL.
			""")
		.type(nonNullListRef(GlobalAttributeSchemaDescriptor.THIS))
		.build();

	PropertyDescriptor ENTITY_SCHEMAS = PropertyDescriptor.builder()
		.name("entitySchemas")
		.description("""
			Contains index of entity schemas for all collections in this catalog.
			""")
		// type is expected to be a map with entity types as keys and entity schemas as values
		.build();

	PropertyDescriptor ALL_ENTITY_SCHEMAS = PropertyDescriptor.builder()
		.name("allEntitySchemas")
		.description("""
			Contains index of entity schemas for all collections in this catalog.
			""")
		.type(nonNullListRef(EntitySchemaDescriptor.THIS_GENERIC))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("CatalogSchema")
		.description("""
			Internal Evita's catalog schema containing structural information about one Evita catalog.
			""")
		.staticFields(List.of(
			VERSION,
			NAME,
			NAME_VARIANTS,
			DESCRIPTION
		))
		.build();

}
