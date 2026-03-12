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

package io.evitadb.spi.store.catalog.exception;


import io.evitadb.exception.EvitaInternalError;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown from the catalog write-ahead log (WAL) transaction stage when the catalog version that is about
 * to be written does not follow sequentially from the version that was last written to the WAL file.
 *
 * The WAL enforces a strict, monotonically increasing version sequence. `CurrentMutationLogFile.checkNextVersionMatch`
 * verifies that each new catalog version is exactly `lastWrittenVersion + 1`. If a gap or a repeat is detected — for
 * example because two transaction-processing threads raced, or because the WAL was not properly initialised after a
 * restart — this exception is raised to prevent silent data corruption.
 *
 * The exception carries {@link #currentTransactionVersion}, which is the *last successfully written* catalog version
 * at the moment the violation was detected. The `ConflictResolutionAndWalAppendingTransactionStage` catches this
 * exception, logs the discrepancy between the WAL state and the transaction manager's view, and uses
 * `currentTransactionVersion` to compute how many catalog versions were dropped so that the state can be reconciled.
 *
 * This is an {@link EvitaInternalError}: it represents a serious internal invariant violation that cannot be corrected
 * by the client, and every occurrence warrants investigation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class CatalogWriteAheadLastTransactionMismatchException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 6117942525622800073L;
	/**
	 * The last catalog version that was successfully written to the WAL file before the mismatch was detected.
	 * Consumers of this exception (e.g. `ConflictResolutionAndWalAppendingTransactionStage`) subtract this value
	 * from the transaction manager's own last-written version to determine how many versions were skipped or repeated.
	 */
	@Getter private final long currentTransactionVersion;

	/**
	 * @param currentTransactionVersion the last catalog version successfully recorded in the WAL before the violation
	 * @param privateMessage            detailed internal diagnostic message (not exposed to API clients)
	 * @param publicMessage             sanitised message safe for exposure through the public API
	 */
	public CatalogWriteAheadLastTransactionMismatchException(long currentTransactionVersion, @Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
		this.currentTransactionVersion = currentTransactionVersion;
	}
}
