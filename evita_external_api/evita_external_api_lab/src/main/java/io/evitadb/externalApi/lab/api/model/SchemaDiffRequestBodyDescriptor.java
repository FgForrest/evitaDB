/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.lab.api.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * TODO lho docs
 * TODO lho this should be form data not object, but we don't have form data support in descriptor yet
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface SchemaDiffRequestBodyDescriptor {

	PropertyDescriptor OLD_SCHEMA = PropertyDescriptor.builder()
		.name("oldSchema")
		.description("""
			Old schema definition to compare.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor NEW_SCHEMA = PropertyDescriptor.builder()
		.name("newSchema")
		.description("""
			New schema definition to compare.
			""")
		.type(nonNull(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SchemaDiffRequestBody")
		.description("""
			Request body for schema diff in form data format
			""")
		.staticField(OLD_SCHEMA)
		.staticField(NEW_SCHEMA)
		.build();
}
