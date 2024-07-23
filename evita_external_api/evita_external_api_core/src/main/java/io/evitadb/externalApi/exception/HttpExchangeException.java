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

import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception that defines to which HTTP status code should this exception be translated (with optional error message).
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Getter
public class HttpExchangeException extends ExternalApiInvalidUsageException {

    @Serial private static final long serialVersionUID = 1146819185676109664L;

    private final int statusCode;

    public HttpExchangeException(int statusCode, @Nonnull String publicMessage) {
        super(publicMessage);
        this.statusCode = statusCode;
    }
}
