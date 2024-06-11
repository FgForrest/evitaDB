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

package io.evitadb.externalApi.graphql.utils;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.SchemaPrinter.Options;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Preconfigured GraphQL schema printer to string.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class GraphQLSchemaPrinter {

	private static final Set<String> IMPLICIT_DIRECTIVES = Set.of("deprecated", "skip", "include", "specifiedBy");

	@Nonnull private static final SchemaPrinter schemaPrinter;

	static {
		schemaPrinter = new SchemaPrinter(Options.defaultOptions()
			.includeDirectives(directive -> !IMPLICIT_DIRECTIVES.contains(directive)));
	}

	/**
	 * Prints GraphQL schema to string in DSL.
	 */
	@Nonnull
	public static String print(@Nonnull GraphQLSchema schema) {
		return schemaPrinter.print(schema);
	}
}
