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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This interface is meant to be internal to the evitaDB server and allows providing {@link EntitySchemaProvider} from
 * outside of the mutation. This allows us to provide access to virtual entity schemas that are not yet stored in the
 * catalog schema. This is used in {@link EvitaSessionContract#updateCatalogSchema(LocalCatalogSchemaMutation...)}
 * to validate changes in the catalog schema and all its mutations before they're actually aplied to the system.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface CatalogSchemaMutationWithProvidedEntitySchemaAccessor extends CatalogSchemaMutation {

	@Nullable
	@Override
	default CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema) {
		return mutate(catalogSchema, MutationEntitySchemaAccessor.INSTANCE);
	}

	/**
	 * Method applies the mutation operation on the catalog schema in the input and returns modified version
	 * as its return value. The create operation works with NULL input value and produces non-NULL result, the remove
	 * operation produces the opposite. Modification operations always accept and produce non-NULL values.
	 *
	 * @param catalogSchema current version of the schema as an input to mutate
	 * @param entitySchemaAccessor
	 */
	@Nullable
	CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor);

}
