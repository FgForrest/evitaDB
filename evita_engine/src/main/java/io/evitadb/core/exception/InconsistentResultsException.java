/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.exception;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.core.query.QueryPlanBuilder;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when debug verification finds different responses from alternative indexes or from the key-free baseline
 * and the preferred plan. These comparisons are enabled by {@link DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} and
 * {@link DebugMode#VERIFY_POSSIBLE_CACHING_TREES}, respectively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class InconsistentResultsException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 185377662501574496L;

	/** Describes a difference between the preferred index plan and an alternative. */
	public InconsistentResultsException(
		@Nonnull QueryPlanBuilder mainBuilder,
		@Nonnull EvitaResponse<EntityClassifier> mainResult,
		@Nonnull QueryPlanBuilder alternativeBuilder,
		@Nonnull EvitaResponse<EntityClassifier> alternativeResult
	) {
		this(mainBuilder.getDescription(), mainResult, alternativeBuilder.getDescription(), alternativeResult);
	}

	/** Describes a difference between two named execution paths, retaining both responses for diagnosis. */
	public InconsistentResultsException(
		@Nonnull String mainDescription,
		@Nonnull EvitaResponse<EntityClassifier> mainResult,
		@Nonnull String alternativeDescription,
		@Nonnull EvitaResponse<EntityClassifier> alternativeResult
	) {
		super(
			"Results for `" + mainDescription + "` and `" + alternativeDescription + "` differ!"
				+ "\n\n" + mainResult + "\nvs.\n" + alternativeResult,
			"Results for `" + mainDescription + "` and `" + alternativeDescription + "` differ!"
		);
	}
}
