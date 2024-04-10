/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api;

import javax.annotation.Nonnull;

/**
 * Interface defines contract allowing to react to structural catalog changes, such as:
 *
 * - new catalog is created ({@link #onCatalogCreate(String)})
 * - existing catalog is deleted ({@link #onCatalogDelete(String)})
 * - new collection creation ({@link #onEntityCollectionCreate(String, String)})
 * - existing collection creation ({@link #onEntityCollectionDelete(String, String)}
 * - catalog schema modification ({@link #onCatalogSchemaUpdate(String)}
 * - entity schema modification ({@link #onEntitySchemaUpdate(String, String)}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogStructuralChangeObserverWithEvitaContractCallback extends CatalogStructuralChangeObserver {

	/**
	 * Initializes the observer with the given EvitaContract at the time it is registered to {@link EvitaContract}
	 * instance.
	 *
	 * @param evita the EvitaContract to be used for initialization
	 */
	void onInit(@Nonnull EvitaContract evita);

}
