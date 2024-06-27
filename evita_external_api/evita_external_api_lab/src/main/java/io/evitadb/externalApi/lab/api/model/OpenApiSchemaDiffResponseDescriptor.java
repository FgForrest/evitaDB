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
import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.SchemaDiff.ActionType;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for {@link io.evitadb.externalApi.lab.tools.schemaDiff.openApi.SchemaDiff}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface OpenApiSchemaDiffResponseDescriptor {

	PropertyDescriptor BREAKING_CHANGES = PropertyDescriptor.builder()
		.name("breakingChanges")
		.description("""
			Possible incompatible changes that may break existing clients
			""")
		.type(nonNullListRef(ChangeDescriptor.THIS))
		.build();
	PropertyDescriptor NON_BREAKING_CHANGES = PropertyDescriptor.builder()
		.name("nonBreakingChanges")
		.description("""
			Compatible changes that should not break existing clients
			""")
		.type(nonNullListRef(ChangeDescriptor.THIS))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("OpenApiSchemaDiffResponse")
		.description("""
			Contains all changes between the new and the old schema.
			""")
		.staticField(BREAKING_CHANGES)
		.staticField(NON_BREAKING_CHANGES)
		.build();

	interface ChangeDescriptor {

		PropertyDescriptor ACTION_TYPE = PropertyDescriptor.builder()
			.name("actionType")
			.description("Type of action in the change.")
			.type(nonNull(ActionType.class))
			.build();
		PropertyDescriptor BREAKING = PropertyDescriptor.builder()
			.name("breaking")
			.description("Severity of change, whether the change is breaking.")
			.type(nonNull(Boolean.class))
			.build();
		PropertyDescriptor METHOD = PropertyDescriptor.builder()
			.name("method")
			.description("HTTP method of changed endpoint.")
			.type(nonNull(String.class))
			.build();
		PropertyDescriptor PATH = PropertyDescriptor.builder()
			.name("path")
			.description("URL path of changed endpoint.")
			.type(nonNull(String.class))
			.build();
		PropertyDescriptor DETAIL = PropertyDescriptor.builder()
			.name("detail")
			.description("Detailed MarkDown description of the change.")
			.type(nonNull(String.class))
			.build();

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("OpenApiSchemaChange")
			.description("A single change (difference) in the schema.")
			.staticField(ACTION_TYPE)
			.staticField(BREAKING)
			.staticField(METHOD)
			.staticField(PATH)
			.staticField(DETAIL)
			.build();
	}
}
