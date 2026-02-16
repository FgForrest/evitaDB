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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.time.OffsetDateTime;

/**
 * Exception thrown when a client requests historical data from a point in time or catalog version that
 * is no longer available. evitaDB maintains historical snapshots of catalog state to support point-in-time
 * queries, but storage constraints limit how far back history is retained.
 *
 * **This exception occurs when:**
 *
 * - Requesting data from a timestamp older than the oldest available snapshot
 * - Requesting data for a catalog version that has been purged
 * - No historical data has been preserved yet (immediately after catalog creation)
 * - Attempting to restore from a backup that doesn't exist
 *
 * The exception provides context about the oldest available data point (either as an {@link OffsetDateTime}
 * or as a catalog version number) to help clients adjust their queries. Clients should use more recent
 * timestamps or versions within the available history window.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TemporalDataNotAvailableException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2157655513551186466L;
	/**
	 * The oldest available timestamp for historical data, or null if reporting catalog version instead.
	 */
	@Getter private final OffsetDateTime offsetDateTime;
	/**
	 * The oldest available catalog version, or null if reporting timestamp instead.
	 */
	@Getter private final Long catalogVersion;

	/**
	 * Creates a new exception indicating that no historical data is available at all.
	 */
	public TemporalDataNotAvailableException() {
		super("No historical data is available.");
		this.offsetDateTime = null;
		this.catalogVersion = null;
	}

	/**
	 * Creates a new exception indicating the oldest available timestamp for historical data.
	 *
	 * @param offsetDateTime the earliest point in time for which data is still available
	 */
	public TemporalDataNotAvailableException(@Nonnull OffsetDateTime offsetDateTime) {
		super("The oldest data available is from " + offsetDateTime + ".");
		this.offsetDateTime = offsetDateTime;
		this.catalogVersion = null;
	}

	/**
	 * Creates a new exception indicating the oldest available catalog version for historical data.
	 *
	 * @param catalogVersion the earliest catalog version for which data is still available
	 */
	public TemporalDataNotAvailableException(long catalogVersion) {
		super("The oldest data available is for catalog version " + catalogVersion + ".");
		this.offsetDateTime = null;
		this.catalogVersion = catalogVersion;
	}

}
