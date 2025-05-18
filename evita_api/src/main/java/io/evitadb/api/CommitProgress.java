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

package io.evitadb.api;


import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletionStage;

/**
 * CommitProgress is an interface that represents the progress of a transaction commit operation in a database. It contains
 * multiple CompletableFuture objects that allow tracking the status of various stages of the commit process. It provides
 * the following guarantees:
 *
 * 1. later stage is not completed before the earlier stage is completed
 * 2. when a stage completes with an exception, all later stages are completed with the same exception immediately after
 * 3. all stages must eventually complete
 * 4. if WAL appending stage completes successfully, the changes are guaranteed to be visible to all readers later;
 * if onChangesVisible completes with exception afterwards, the system will replay the changes or mark a catalog
 * as corrupted (changes might be applied after evita server restart, or the error just might signalize lost connection
 * between the server and client and the server side already applied the changed, but the client couldn't receive
 * acknowledgment)
 * 5. if a session was read-only or didn't make any changes, all stages are completed before the record is created
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CommitProgress {

	/**
	 * Returns a {@link CompletionStage} that completes when the system verifies changes are not in conflict with
	 * changes from other transactions committed in the meantime.
	 *
	 * @return the {@link CompletionStage} for conflict resolution
	 */
	@Nonnull
	CompletionStage<CommitVersions> onConflictResolved();

	/**
	 * Returns a {@link CompletionStage} that completes when the changes are appended to the Write Ahead Log (WAL).
	 *
	 * @return the {@link CompletionStage} for WAL appending
	 */
	@Nonnull
	CompletionStage<CommitVersions> onWalAppended();

	/**
	 * Returns a {@link CompletionStage} that completes when the changes are visible to all readers.
	 *
	 * @return the {@link CompletionStage} for changes visibility
	 */
	@Nonnull
	CompletionStage<CommitVersions> onChangesVisible();

	/**
	 * Returns a {@link CompletionStage} that represents a specific stage in the transaction commit process,
	 * based on the provided {@link CommitBehavior}.
	 *
	 * @param commitBehavior the behavior defining the desired stage in the transaction commit process.
	 *
	 * @return the {@link CompletionStage} corresponding to the specified commit behavior.
	 * @throws EvitaInvalidUsageException if an unsupported commit behavior is provided.
	 */
	default CompletionStage<CommitVersions> on(@Nonnull CommitBehavior commitBehavior) {
		switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> {
				return onConflictResolved();
			}
			case WAIT_FOR_WAL_PERSISTENCE -> {
				return onWalAppended();
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				return onChangesVisible();
			}
			default -> throw new EvitaInvalidUsageException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Returns true if all the commit stages are completed successfully.
	 * @return true if all stages are completed successfully, false otherwise.
	 */
	boolean isCompletedSuccessfully();

	/**
	 * Returns true if either of the stages is completed exceptionally.
	 * @return true if any of the stages is completed exceptionally, false otherwise.
	 */
	boolean isCompletedExceptionally();

	/**
	 * CommitVersions is a record that contains the catalog and catalog schema versions that were assigned during
	 * the commit process. They guarantee to contain all changes made during the transaction, when the catalog
	 * of this particular version is visible to the reader.
	 *
	 * @param catalogVersion       the version of the catalog after the operation is completed
	 * @param catalogSchemaVersion the schema version of the catalog after the operation is completed
	 */
	record CommitVersions(
		long catalogVersion,
		int catalogSchemaVersion
	) {
	}
}
