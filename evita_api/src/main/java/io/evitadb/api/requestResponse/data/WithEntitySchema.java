/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;

/**
 * Contract providing access to the {@link EntitySchemaContract} that defines the structure and constraints
 * of the owning entity.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface WithEntitySchema {

	/**
	 * Returns schema of the owning entity, that fully describes its structure and capabilities. Schema is up-to-date to the
	 * moment the owning entity was fetched from evitaDB.
	 *
	 * @return schema of the entity type
	 */
	@Nonnull
	EntitySchemaContract getSchema();

}
