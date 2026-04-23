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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.spi.store.engine.EnginePersistenceService;

import java.io.Closeable;

/**
 * Root sealed interface of the persistence service hierarchy in evitaDB. It defines the minimal lifecycle contract —
 * lifecycle status inspection and resource release — shared across all concrete persistence service types.
 *
 * The sealed hierarchy ensures that only the two permitted branches can extend this interface:
 * - {@link io.evitadb.spi.store.engine.EnginePersistenceService} — manages the global engine state file
 * - {@link RichPersistenceService} — base for catalog and entity-collection services that store rich data structures
 *
 * Implementations are `AutoCloseable` (via `Closeable`) and should be used inside try-with-resources blocks or
 * explicitly closed when the owning catalog / engine is shut down. Once closed, no further reads or writes are
 * permitted.
 *
 * File naming constants {@link #BOOT_FILE_SUFFIX} and {@link #WAL_FILE_SUFFIX} are defined here because they are
 * shared across all concrete service implementations regardless of catalog or entity type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
sealed public interface PersistenceService
	extends Closeable
	permits EnginePersistenceService, RichPersistenceService {

	/**
	 * This constant represents the current version of the storage protocol. The version is changed everytime
	 * the storage protocol on disk changes and the data with the old protocol version cannot be read by the new
	 * protocol version.
	 *
	 * This means that the data needs to be converted from old to new protocol version first.
	 */
	int STORAGE_PROTOCOL_VERSION = 5;

	/**
	 * File suffix for the bootstrap file that records the last known catalog header location. The bootstrap file uses
	 * a fixed-size record format so that it can be traversed by jumping to expected byte offsets without parsing the
	 * entire file, making startup recovery O(1) instead of O(n).
	 */
	String BOOT_FILE_SUFFIX = ".boot";

	/**
	 * File suffix for Write-Ahead-Log files that accumulate mutations committed in recent transactions but not yet
	 * merged into the main catalog data file. Multiple WAL files may exist when the active file reaches its size
	 * limit; they are rotated using a numeric index embedded in the file name.
	 */
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
