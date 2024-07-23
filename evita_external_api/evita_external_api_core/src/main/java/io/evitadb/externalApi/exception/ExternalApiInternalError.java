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

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Extension of {@link EvitaInternalError} that covers external API errors that can occur in APIs which is not caused by
 * client. Used primarily to distinguish between base Evita internal errors and API errors for {@link io.evitadb.externalApi.http.ExternalApiExceptionHandler}.
 *
 * This exception should be base for external APIs concrete exceptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ExternalApiInternalError extends EvitaInternalError {

    @Serial private static final long serialVersionUID = -2562900668744134688L;

    public ExternalApiInternalError(@Nonnull String publicMessage) {
        super(publicMessage);
    }

    public ExternalApiInternalError(@Nonnull String publicMessage, @Nonnull Throwable cause) {
        super(publicMessage, cause);
    }

    public ExternalApiInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage) {
        super(privateMessage, publicMessage);
    }

    public ExternalApiInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
        super(privateMessage, publicMessage, cause);
    }
}
