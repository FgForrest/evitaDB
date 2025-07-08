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


import io.evitadb.api.CommitProgress.CommitVersions;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletionStage;

/**
 * GoLiveProgress is an interface that represents the progress of a catalog go-live operation in a database.
 * The go-live operation switches a catalog from warm-up mode to transactional mode. It provides a way to track
 * the completion status and percentage of this operation.
 *
 * The interface provides the following guarantees:
 *
 * 1. the operation must eventually complete (either successfully or exceptionally)
 * 2. the percentage completion is monotonically increasing from 0 to 100
 * 3. once the operation completes successfully, the catalog is available for transactional operations
 * 4. if the operation completes exceptionally, the catalog remains in its previous state
 * 5. the completion stage allows asynchronous waiting for the operation to finish
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface GoLiveProgress {

	/**
	 * Returns the percentage of completion of the process or task being tracked.
	 *
	 * @return an integer value representing the percentage of completion, ranging from 0 to 100
	 */
	int percentCompleted();

	/**
	 * Returns a {@link CompletionStage} that completes when the operation switching catalog to transactional state
	 * has been finished (either successfully or exceptionally).
	 *
	 * @return the {@link CompletionStage} for the completion of the go live operation
	 */
	@Nonnull
	CompletionStage<CommitVersions> onCompletion();

	/**
	 * Indicates whether the tracked process or task has completed successfully.
	 *
	 * @return true if the process or task has completed successfully, false otherwise
	 */
	boolean isCompletedSuccessfully();

	/**
	 * Indicates whether the tracked process or task has completed exceptionally.
	 *
	 * @return true if the process or task has completed with an exception, false otherwise
	 */
	boolean isCompletedExceptionally();

}
