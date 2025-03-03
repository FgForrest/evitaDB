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

package io.evitadb.core;


/**
 * Interface allowing to exchange information that particular catalog version is being consumed by someone and signall
 * that is no longer necessary.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CatalogConsumerControl {

	/**
	 * Registers a session consuming catalog in the specified version.
	 *
	 * @param version the version of the catalog
	 */
	void registerConsumerOfCatalogInVersion(long version);

	/**
	 * Unregisters a session that is consuming a catalog in the specified version.
	 *
	 * @param version the version of the catalog
	 */
	void unregisterConsumerOfCatalogInVersion(long version);

}
