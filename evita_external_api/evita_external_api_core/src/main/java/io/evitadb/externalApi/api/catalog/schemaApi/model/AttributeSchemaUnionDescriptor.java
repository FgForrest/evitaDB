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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.api.model.UnionDescriptor;

/**
 * Descriptor of union of {@link AttributeSchemaContract} and {@link GlobalAttributeSchemaContract} for schema-based external APIs. It describes what properties of attribute schema are
 * supported in API for better field names and docs maintainability.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface AttributeSchemaUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("AttributeSchemaUnion")
		.description("""
			This is the definition object for attributes that are stored along with
			entity. Definition objects allow to describe the structure of the entity type so that
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
		.build();
}
