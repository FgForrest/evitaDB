/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.spi.store.catalog.wal;

/**
 * Says who chose the catalog version a Write-Ahead Log read is bounded by, and therefore what it means when that
 * version cannot be found on disk.
 *
 * The distinction cannot be made after the fact. A version the engine chose for itself is one it has already
 * observed to be durable, so its absence is damage and must be reported as such; a version that arrived from
 * outside the database was never promised by anyone, so its absence is a bad argument and reporting it as damage
 * both misleads the operator and moves `io_evitadb_errors_total` for something nobody can fix. Because the
 * internal variant is an `EvitaInternalError` - and merely constructing one of those is what the error counter
 * observes - the choice has to be made *before* the exception is built. That is why this travels down to the
 * reader instead of being translated on the way back up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum VersionSource {

	/**
	 * The bound came from the engine's own bookkeeping - a durable catalog version, a transaction-processing
	 * pipeline's checkpoint, a recovery scan. Nothing outside the database influenced it.
	 *
	 * Not finding it means the log no longer holds something the engine recorded as written, which is a genuine
	 * fault: it is reported as a
	 * {@link io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException} and counted as an internal
	 * error.
	 */
	INTERNAL,

	/**
	 * The bound was supplied by a client over an external API - a change-data-capture subscription, a mutation
	 * history query - and the database has no control over what it contains. It may name a version that was
	 * rotated out of retention, one that has not happened yet, or one that never existed.
	 *
	 * Not finding it means the caller asked for something that is not there, which is a usage error and is
	 * reported as an {@link io.evitadb.exception.EvitaInvalidUsageException}. It is deliberately not counted as
	 * an internal error: no amount of operator attention makes a client stop sending the argument.
	 */
	CLIENT

}
