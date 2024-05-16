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

package io.evitadb.externalApi.lab.api.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.dataType.GenericObject;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Requests query of entities.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface QueryEntitiesRequestBodyDescriptor {

	PropertyDescriptor QUERY = PropertyDescriptor.builder()
		.name("query")
		.description("""
			Query in EvitaQL language
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor POSITIONAL_ARGUMENTS = PropertyDescriptor.builder()
		.name("positionalArguments")
		.description("""
			Positional arguments for query.
			""")
		.type(nonNull(Any[].class))
		.build();
	PropertyDescriptor NAMED_ARGUMENTS = PropertyDescriptor.builder()
		.name("namedArguments")
		.description("""
			Named arguments for query.
			""")
		.type(nonNull(GenericObject.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("QueryEntitiesRequestBody")
		.description("""
			Request body for querying entities.
			""")
		.staticField(QUERY)
		.staticField(POSITIONAL_ARGUMENTS)
		.staticField(NAMED_ARGUMENTS)
		.build();
}
