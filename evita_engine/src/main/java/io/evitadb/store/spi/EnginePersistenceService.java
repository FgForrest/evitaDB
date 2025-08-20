
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

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.store.spi.model.reference.TransactionMutationWithWalFileReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * This interface represents a link between {@link EngineState} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting engine state to/from durable
 * storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface EnginePersistenceService extends PersistenceService {
	String ENGINE_NAME = "evitaDB";

	/**
	 * Returns name of the bootstrap file that contains lead information to fetching the engine header in fixed record
	 * size format. This file can be traversed by jumping on expected offsets.
	 */
	@Nonnull
	static String getBootstrapFileName() {
		return ENGINE_NAME + BOOT_FILE_SUFFIX;
	}

	/**
	 * Returns name of the Write-Ahead-Log file that contains all mutations that were not yet propagated to the boot file.
	 *
	 * @param fileIndex index of the WAL file
	 * @return name of the WAL file
	 */
	@Nonnull
	static String getWalFileName(int fileIndex) {
		return ENGINE_NAME + '_' + fileIndex + WAL_FILE_SUFFIX;
	}

	/**
	 * Returns the last version that was written to the persistent storage.
	 *
	 * @return the last version that was written to the persistent storage
	 */
	long getVersion();

	/**
	 * Returns {@link EngineState} stored in current boot file.
	 *
	 * @return the state of the engine
	 */
	@Nonnull
	EngineState getEngineState();

	/**
	 * Stores {@link EngineState} stored in current boot file.
	 *
	 * @param engineState the state of the engine to store
	 */
	void storeEngineState(@Nonnull EngineState engineState);

	/**
	 * Appends the given transaction mutation to the write-ahead log (WAL) and appends its mutation chain.
	 *
	 * @param version  version the transaction mutation is written for
	 * @param transactionId unique identifier of the transaction that is being written
	 * @param mutation set of mutations that are part of the transaction mutation
	 * @return the number of Bytes written
	 */
	@Nonnull
	TransactionMutationWithWalFileReference appendWal(
		long version,
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<?> mutation
	);

	/**
	 * Retrieves the first non-processed transaction in the WAL.
	 *
	 * @param version version of the engine
	 * @return the first non-processed transaction in the WAL
	 */
	@Nonnull
	Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(long version);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * @param version version of the engine to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<EngineMutation<?>> getCommittedMutationStream(long version);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine state to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * @param version version of the engine state to start the stream with, if null is provided then the stream will
	 *                start with the last transaction in the WAL
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<EngineMutation<?>> getReversedCommittedMutationStream(@Nullable Long version);

	/**
	 * Truncates the log file to the given {@link LogFileRecordReference}. This method synchronizes the log file contents
	 * to be on par with current engine state.
	 *
	 * @param walFileReference the reference to the log file that should be truncated to
	 */
	void truncateWalFile(@Nonnull LogFileRecordReference walFileReference);

	/**
	 * Retrieves the last engine state version written in the WAL stream.
	 *
	 * @return the last engine state version written in the WAL stream
	 */
	long getLastVersionInMutationStream();

	/**
	 * Method closes this persistence service.
	 */
	@Override
	void close();
}
