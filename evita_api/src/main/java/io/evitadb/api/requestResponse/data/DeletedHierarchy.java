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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityContentRequire;

import javax.annotation.Nullable;

/**
 * Provides access to the result of {@link EvitaSessionContract#deleteEntityAndItsHierarchy(String, int, EntityContentRequire...)} .
 *
 * @param deletedEntities          count of all removed entities in the hierarchy
 * @param deletedEntityPrimaryKeys primary keys of all removed entities in the hierarchy
 * @param deletedRootEntity        requested contents of the removed root entity
 */
public record DeletedHierarchy<T>(
	int deletedEntities,
	int[] deletedEntityPrimaryKeys,
	@Nullable T deletedRootEntity
) {
}
