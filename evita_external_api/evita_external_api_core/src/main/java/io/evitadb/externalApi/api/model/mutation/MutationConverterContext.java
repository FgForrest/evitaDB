/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.api.model.mutation;

import java.util.Map;

/**
 * Convertion context for converting a tree of {@link io.evitadb.api.requestResponse.mutation.Mutation}s.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface MutationConverterContext {

	Map<String, Object> EMPTY = Map.of();

	/**
	 * Provides {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract} instance to children.
	 */
	String ENTITY_SCHEMA_KEY = "entitySchema";
	/**
	 * Provides {@link io.evitadb.api.requestResponse.schema.AttributeSchemaProvider} instance to children.
	 */
	String ATTRIBUTE_SCHEMA_PROVIDER_KEY = "attributeSchemaProvider";
}
