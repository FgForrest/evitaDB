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

package io.evitadb.api;

import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;

import javax.annotation.Nonnull;

/**
 * Interface allows to modify the schemas generated by {@link EvitaSessionContract#defineEntitySchema(String)} method
 * just before they're applied to the schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SchemaPostProcessor {

	/**
	 * Method is called after the schema is generated from the example class and before it's applied to the catalog.
	 * @param catalogSchemaBuilder schema builder for the catalog
	 * @param entitySchemaBuilder schema builder for the entity
	 */
	void postProcess(@Nonnull CatalogSchemaBuilder catalogSchemaBuilder, @Nonnull EntitySchemaBuilder entitySchemaBuilder);

}
