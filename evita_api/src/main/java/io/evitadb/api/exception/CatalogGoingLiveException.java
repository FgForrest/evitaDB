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
 * Exception thrown when an attempt is made to access a catalog that is currently transitioning from warm-up state to live state.
 * This typically occurs during catalog initialization or recovery processes. The operation should be retried after the transition
 * is complete.
 *
 * This exception extends {@link EvitaInvalidUsageException} and is thrown to indicate a temporary unavailability of the catalog
 * rather than a permanent error condition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CatalogGoingLiveException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 5841060392678651511L;

	public CatalogGoingLiveException(@Nonnull String catalogName) {
		super(
			"Catalog `" + catalogName + "` is in process of transition from warm-up state to live state. " +
				"Please wait until the process is finished and try again."
		);
	}
}
