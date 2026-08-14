/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.catalog;

import io.evitadb.core.session.SessionRegistry;

/**
 * This interface represents a listener called by {@link SessionRegistry} when last active session using particular
 * catalog version is closed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CatalogConsumersListener {

	/**
	 * Notifies listener that any active session no longer uses the catalog version.
	 *
	 * @param lastKnownMinimalActiveVersionRead minimal catalog version that is still being read from
	 * @param lastKnownMinimalActiveVersionWritten minimal catalog version that is still being written on top of
	 */
	void catalogConsumersLeft(
		long lastKnownMinimalActiveVersionRead,
		long lastKnownMinimalActiveVersionWritten
	);

	/**
	 * Notifies listener that a consumer started using a particular catalog version and that version must not be
	 * reclaimed until the matching {@link #catalogVersionReleased(long)} arrives.
	 *
	 * This exists because {@link #catalogConsumersLeft(long, long)} fires only when the *last* reader of a version
	 * leaves, and therefore only ever reports a rising minimum. A point-in-time backup pins a version in the **past**
	 * (`BackupTask` registers the bootstrap record's own version), which no departure notification can express - a
	 * floor derived from departures alone stays above it and the retention logic concludes nothing is pinned there.
	 *
	 * @param catalogVersion the catalog version the consumer started using
	 */
	default void catalogVersionPinned(long catalogVersion) {
		// listeners that do not reclaim files have nothing to hold back
	}

	/**
	 * Notifies listener that a consumer stopped using a catalog version pinned by
	 * {@link #catalogVersionPinned(long)}. Calls are paired and counted, so the version stays pinned until as many
	 * releases as pins have arrived.
	 *
	 * @param catalogVersion the catalog version the consumer stopped using
	 */
	default void catalogVersionReleased(long catalogVersion) {
		// listeners that do not reclaim files have nothing to release
	}

}
