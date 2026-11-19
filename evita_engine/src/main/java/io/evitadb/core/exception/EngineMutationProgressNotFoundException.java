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

package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * Signalled to a subscriber of an engine mutation progress whose id the engine does not know: the id was never
 * issued, the outcome of the operation has already expired, was evicted by the retention limit, or the server has
 * been restarted since (the progress records are kept in memory only). The client should fall back to reading the
 * state of the catalog the operation targeted.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class EngineMutationProgressNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -5203949406236236917L;

	/**
	 * Creates the exception for the unknown progress id.
	 *
	 * @param progressId the id the client subscribed with
	 */
	public EngineMutationProgressNotFoundException(@Nonnull UUID progressId) {
		super(
			"Engine mutation progress `" + progressId + "` is unknown or its outcome has already expired."
		);
	}

}
