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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.data.EntityContract;

/**
 * Evolution mode allows to specify how strict is evitaDB when unknown information is presented to her for the first
 * time. When no evolution mode is set, each violation of the {@link EntitySchemaContract} is
 * reported by an exception. This behaviour can be changed by this evolution mode, however.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public enum CatalogEvolutionMode {

	/**
	 * When new entity is inserted and no collection of the {@link EntityContract#getType()} exists, it is silently
	 * created with empty schema and with all {@link CatalogEvolutionMode} allowed.
	 */
	ADDING_ENTITY_TYPES

}
