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

package io.evitadb.core.transaction.engine;

import io.evitadb.api.CommitProgress.CommitVersions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Immutable description of the state of one engine mutation tracked by {@link EngineMutationProgressRegistry}. It is
 * both the item published to the subscribers of the progress and - once the operation finishes - the compact record
 * the registry retains in place of the {@link io.evitadb.api.requestResponse.progress.Progress} itself.
 *
 * A failed operation keeps only the public part of its failure: the error code and the public message of an
 * {@link io.evitadb.exception.EvitaError}, or the type of any other exception. The private message never leaves the
 * server.
 *
 * @param progressId           id of the tracked operation, equal to the id of its engine transaction
 * @param percentCompleted     percentage of completion, 0 to 100
 * @param status               whether the operation still runs, succeeded or failed
 * @param catalogVersion       catalog version after a successful operation that reports {@link CommitVersions}
 * @param catalogSchemaVersion catalog schema version after a successful operation that reports {@link CommitVersions}
 * @param errorCode            error code of the failure, if the operation failed and the failure carried one
 * @param publicMessage        public message of the failure, if the operation failed
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public record EngineMutationProgressStatus(
	@Nonnull UUID progressId,
	int percentCompleted,
	@Nonnull Status status,
	@Nullable Long catalogVersion,
	@Nullable Integer catalogSchemaVersion,
	@Nullable String errorCode,
	@Nullable String publicMessage
) {

	/**
	 * Creates the status of a running operation.
	 *
	 * @param progressId       id of the tracked operation
	 * @param percentCompleted percentage of completion
	 * @return status of the running operation
	 */
	@Nonnull
	static EngineMutationProgressStatus running(@Nonnull UUID progressId, int percentCompleted) {
		return new EngineMutationProgressStatus(
			progressId, percentCompleted, Status.RUNNING, null, null, null, null
		);
	}

	/**
	 * Creates the terminal status of a successfully finished operation.
	 *
	 * @param progressId id of the tracked operation
	 * @param result     result of the operation, versions are taken from it when it is {@link CommitVersions}
	 * @return status of the successfully finished operation
	 */
	@Nonnull
	static EngineMutationProgressStatus succeeded(
		@Nonnull UUID progressId,
		@Nullable Object result
	) {
		if (result instanceof CommitVersions commitVersions) {
			return new EngineMutationProgressStatus(
				progressId, 100, Status.SUCCEEDED,
				commitVersions.catalogVersion(), commitVersions.catalogSchemaVersion(),
				null, null
			);
		}
		return new EngineMutationProgressStatus(
			progressId, 100, Status.SUCCEEDED, null, null, null, null
		);
	}

	/**
	 * Creates the terminal status of a failed operation.
	 *
	 * @param progressId    id of the tracked operation
	 * @param errorCode     error code of the failure, may be null
	 * @param publicMessage public message of the failure
	 * @return status of the failed operation
	 */
	@Nonnull
	static EngineMutationProgressStatus failed(
		@Nonnull UUID progressId,
		@Nullable String errorCode,
		@Nonnull String publicMessage
	) {
		return new EngineMutationProgressStatus(
			progressId, 100, Status.FAILED, null, null, errorCode, publicMessage
		);
	}

	/**
	 * Returns true when the operation has finished, successfully or not.
	 *
	 * @return true for a terminal status
	 */
	public boolean finished() {
		return this.status != Status.RUNNING;
	}

	/**
	 * Phase of the tracked operation.
	 */
	public enum Status {
		/**
		 * The operation has been accepted and runs.
		 */
		RUNNING,
		/**
		 * The operation has finished successfully.
		 */
		SUCCEEDED,
		/**
		 * The operation has failed after it had been accepted.
		 */
		FAILED
	}

}
