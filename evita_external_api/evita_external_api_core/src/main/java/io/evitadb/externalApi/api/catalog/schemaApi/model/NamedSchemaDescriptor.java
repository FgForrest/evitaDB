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

import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of {@link NamedSchemaContract} for schema-based external APIs. It describes what properties of named schema are
 * supported in API for better field names and docs maintainability.
 *
 * Note: this descriptor is meant to only ancestor for other specific schema descriptors.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface NamedSchemaDescriptor {

	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			Contains unique name of the model. Case-sensitive. Distinguishes one model item from another
			within single entity instance.
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor NAME_VARIANTS = PropertyDescriptor.builder()
		.name("nameVariants")
		.description("""
			Map contains the `name` variants in different naming conventions. The name
			is guaranteed to be unique among other references in same convention. These names are used to quickly
			translate to / from names used in different protocols. Each API protocol prefers names in different naming
			conventions.
			""")
		.type(nonNullRef(NameVariantsDescriptor.THIS))
		.build();

	PropertyDescriptor DESCRIPTION = PropertyDescriptor.builder()
		.name("description")
		.description("""
			Contains description of the model is optional but helps authors of the schema / client API to better
			explain the original purpose of the model to the consumers.
			""")
		.type(nullable(String.class))
		.build();
}
