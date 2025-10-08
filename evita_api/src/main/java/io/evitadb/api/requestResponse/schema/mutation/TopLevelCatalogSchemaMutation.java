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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;

/**
 * This interface marks all mutations that needs to be executed on entire evitaDB level and not locally to
 * a single catalog schema instance.
 *
 * This is technical interface - the main purpose is to combine {@link CatalogSchemaMutation} and {@link TopLevelCatalogMutation}
 * interfaces so that the top level catalog mutations that also alter the catalog schema (like {@link CreateEntitySchemaMutation}
 * or {@link ModifyEntitySchemaMutation}) can implement this interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface TopLevelCatalogSchemaMutation<T> extends CatalogSchemaMutation, TopLevelCatalogMutation<T> {

}
