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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data;

/**
 * This interface marks data objects that are turned into a tombstone once they are removed. Dropped data still occupy
 * the original place but may be cleaned by automatic tidy process. They can be also revived anytime by setting new
 * value. Dropped item must be handled by the system as non-existing data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface Droppable extends Versioned {

	/**
	 * Returns true if data object is removed (i.e. has tombstone flag present).
	 */
	boolean dropped();

	/**
	 * Returns true if price really exists (i.e. is non-removed).
	 */
	default boolean exists() {
		return !dropped();
	}

}
