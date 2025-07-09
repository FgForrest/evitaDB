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

package io.evitadb.store.spi;

import java.io.Closeable;

/**
 * This interface defines shared methods for permited set of persistence services.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
sealed interface PersistenceService
	extends Closeable
	permits EnginePersistenceService, RichPersistenceService {

	/**
	 * This constant represents the current version of the storage protocol. The version is changed everytime
	 * the storage protocol on disk changes and the data with the old protocol version cannot be read by the new
	 * protocol version.
	 *
	 * This means that the data needs to be converted from old to new protocol version first.
	 */
	int STORAGE_PROTOCOL_VERSION = 3;
	String BOOT_FILE_SUFFIX = ".boot";
	String WAL_FILE_SUFFIX = ".wal";

	/**
	 * Returns true if underlying file was not yet created.
	 *
	 * @return true if underlying file was not yet created
	 */
	boolean isNew();

	/**
	 * Returns true if the persistence service is closed.
	 *
	 * @return true if the persistence service is closed
	 */
	boolean isClosed();
}
