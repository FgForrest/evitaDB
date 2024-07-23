/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.exception;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Uncategorized evitaDB internal error. All details must be passed in the messages.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class GenericEvitaInternalError extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 7967637521505334780L;

	/**
	 * Method is targeted to be used on the client.
	 */
	@Nonnull
	public static EvitaInternalError createExceptionWithErrorCode(@Nonnull String publicMessage, @Nonnull String errorCode) {
		return new GenericEvitaInternalError(publicMessage, publicMessage, errorCode);
	}

	public GenericEvitaInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

	public GenericEvitaInternalError(@Nonnull String publicMessage) {
		super(publicMessage);
	}

	public GenericEvitaInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	public GenericEvitaInternalError(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(publicMessage, cause);
	}

	public GenericEvitaInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull String errorCode) {
		super(privateMessage, publicMessage, errorCode);
	}
}
