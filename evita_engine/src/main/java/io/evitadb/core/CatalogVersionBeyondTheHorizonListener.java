/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core;

/**
 * This interface represents a listener called by {@link SessionRegistry} when last active session using particular
 * catalog version is closed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CatalogVersionBeyondTheHorizonListener {

	/**
	 * Notifies listener that any active session no longer uses the catalog version.
	 *
	 * @param catalogVersion                The new catalog version that is no longer used by any active session
	 * @param activeSessionsToOlderVersions true if there are still active sessions using older versions
	 */
	void catalogVersionBeyondTheHorizon(
		long catalogVersion,
		boolean activeSessionsToOlderVersions
	);
}
