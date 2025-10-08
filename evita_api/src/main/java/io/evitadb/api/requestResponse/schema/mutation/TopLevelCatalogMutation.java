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

package io.evitadb.api.requestResponse.schema.mutation;


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;

import javax.annotation.Nonnull;

/**
 * This interface marks all mutations that needs to be executed on the entire evitaDB level and not locally to
 * a single catalog instance.
 *
 * This is technical interface - it mainly serves as a marker for the engine mutations that are targeted at specific
 * catalog and provides the catalog name via {@link #getCatalogName()} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface TopLevelCatalogMutation<T> extends EngineMutation<T> {

	/**
	 * Returns the name of the involved catalog.
	 * @see CatalogSchemaContract#getName()
	 */
	@Nonnull
	String getCatalogName();

}
