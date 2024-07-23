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

package io.evitadb.externalApi.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This exception represents an error that is caused by the client data or usage of Evita's external API. All exceptions of this
 * type can be solved by the client side by changing its behaviour.
 *
 * This exception should be base for external APIs concrete exceptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ExternalApiInvalidUsageException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -3315457374934812685L;

	public ExternalApiInvalidUsageException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

	public ExternalApiInvalidUsageException(@Nonnull String publicMessage) {
		super(publicMessage);
	}

	public ExternalApiInvalidUsageException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	public ExternalApiInvalidUsageException(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(publicMessage, cause);
	}
}
