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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This interface marks all implementations that alter the {@link CatalogSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface CatalogSchemaMutation extends SchemaMutation {

	/**
	 * Method applies the mutation operation on the catalog schema in the input and returns modified version
	 * as its return value. The create operation works with NULL input value and produces non-NULL result, the remove
	 * operation produces the opposite. Modification operations always accept and produce non-NULL values.
	 *
	 * @param catalogSchema current version of the schema as an input to mutate
	 */
	@Nullable
	CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema);

	/**
	 * Result object of the {@link #mutate(CatalogSchemaContract)} method allowing to return both the modified catalog
	 * schema and the list of entity schema mutations that were caused by the catalog schema mutation and needs to be
	 * propagated to the entity schemas.
	 *
	 * @param updatedCatalogSchema modified catalog schema
	 * @param entitySchemaMutations list of entity schema mutations that were caused by the catalog schema mutation
	 */
	record CatalogSchemaWithImpactOnEntitySchemas(
		@Nonnull CatalogSchemaContract updatedCatalogSchema,
		@Nullable ModifyEntitySchemaMutation[] entitySchemaMutations
	) {
		public CatalogSchemaWithImpactOnEntitySchemas(@Nonnull CatalogSchemaContract updatedCatalogSchema) {
			this(updatedCatalogSchema, null);
		}
	}

}
