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

package io.evitadb.api.exception;


import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to create a session on a catalog that is currently
 * transitioning from `{@link io.evitadb.api.CatalogState#WARMING_UP}` state to
 * `{@link io.evitadb.api.CatalogState#ALIVE}` state.
 *
 * This exception indicates a temporary unavailability condition. During the transition,
 * internal indexes are being built and the catalog cannot accept new sessions. Once the
 * transition completes, the catalog will be fully operational and accept parallel sessions.
 *
 * **Typical Causes:**
 * - Another session called `goLive()` and the transition is in progress
 * - Catalog recovery or initialization triggered automatic transition to live state
 *
 * **Resolution:**
 * Wait a moment and retry the session creation. The transition is typically fast unless
 * the catalog contains a large dataset requiring index rebuilding.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CatalogGoingLiveException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 5841060392678651511L;

	/**
	 * Creates a new exception for a catalog that is transitioning to live state.
	 *
	 * @param catalogName name of the catalog that is currently going live
	 */
	public CatalogGoingLiveException(@Nonnull String catalogName) {
		super(
			"Catalog `" + catalogName + "` is in process of transition from warm-up state to live state. " +
				"Please wait until the process is finished and try again."
		);
	}
}
