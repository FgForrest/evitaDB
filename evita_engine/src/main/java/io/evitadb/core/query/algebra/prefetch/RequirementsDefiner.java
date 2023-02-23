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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.query.require.CombinableEntityContentRequire;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.query.QueryContext;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nullable;

/**
 * Implementations of this interface signalize that the entities needs to be prefetched with particular {@link StoragePart}
 * loaded in order they can operate correctly. The storage parts loading is triggered by placing respective
 * {@link CombinableEntityContentRequire} in {@link QueryContext#fetchEntities(int[], EntityContentRequire...) fetch method}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface RequirementsDefiner {

	/**
	 * Returns all requirements that must be fulfilled in order to filtering logic can safely operate on
	 * {@link SealedEntity} instances.
	 */
	@Nullable
	EntityRequire getEntityRequire();

}
