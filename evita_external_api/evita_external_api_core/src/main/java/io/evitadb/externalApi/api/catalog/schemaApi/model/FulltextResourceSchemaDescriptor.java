/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.*;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the declared FulltextResourceSchema value without exposing compiled analysis policies. */
public interface FulltextResourceSchemaDescriptor {
	/** Resource name used by analysis steps. */
	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("Resource name used by analysis steps.")
		.type(nonNull(String.class))
		.build();
	/** Declared words or synonym groups. */
	PropertyDescriptor CONTENT = PropertyDescriptor.builder()
		.name("content")
		.description("Declared words or synonym groups.")
		.type(nonNullRef(FulltextResourceContentDescriptor.THIS))
		.build();
	/** A named list analysis steps read. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FulltextResourceSchema")
		.description("A named list that analysis steps read by its name, such as a stop list or a synonym list.")
		.staticProperties(List.of(NAME, CONTENT))
		.build();
}
