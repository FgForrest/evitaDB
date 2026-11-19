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
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.UUID;

/**
 * Signalled to a subscriber of an engine mutation progress when the observed operation failed after it had been
 * accepted. It carries only the public part of the original failure - its error code and public message - because
 * that is all the engine retains about a finished operation (see `EngineMutationProgressRegistry`): live and late
 * subscribers get the same failure, and the private message never leaves the server.
 *
 * It is an {@link EvitaInvalidUsageException} so that the external APIs pass its public message to the client - also
 * when the original failure was an internal error, whose details are reduced to its type by then. Its error code is
 * the code of the original failure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class EngineMutationFailedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2675405317071796421L;
	/**
	 * Error code of the original failure, or null when the original failure carried none.
	 */
	@Nullable private final String originalErrorCode;

	/**
	 * Creates the exception from the retained public description of the failure.
	 *
	 * @param progressId        the id of the failed operation
	 * @param originalErrorCode error code of the original failure, may be null
	 * @param publicMessage     public message of the original failure
	 */
	public EngineMutationFailedException(
		@Nonnull UUID progressId,
		@Nullable String originalErrorCode,
		@Nonnull String publicMessage
	) {
		super("Engine mutation `" + progressId + "` failed: " + publicMessage);
		this.originalErrorCode = originalErrorCode;
	}

	/**
	 * Returns the error code of the original failure when it carried one, so that a client can tell failures apart
	 * the same way it would had the operation failed synchronously.
	 */
	@Nonnull
	@Override
	public String getErrorCode() {
		return this.originalErrorCode == null ? super.getErrorCode() : this.originalErrorCode;
	}

}
