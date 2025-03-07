/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.exception.EvitaError;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Fallback exception handler which is executed if any of the APIs do not catch all of their exceptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class FallbackExceptionHandler extends ExternalApiExceptionHandler {

    public FallbackExceptionHandler(@Nonnull HttpService next) {
        super(next);
    }

    @Nonnull
    @Override
    protected String getExternalApiCode() {
        return "unknown";
    }

    @Override
    protected HttpResponse renderError(@Nonnull EvitaError evitaError, @Nonnull HttpRequest request) {
        if (evitaError instanceof EvitaInvalidUsageException) {
            log.warn(
                "Invalid client request on URL `" + request.path() + "`: error code {}, message {}",
                evitaError.getErrorCode(),
                evitaError.getPrivateMessage()
            );
            return buildResponse(HttpStatus.BAD_REQUEST.code(), MediaType.PLAIN_TEXT_UTF_8, null);
        } else {
            log.error(
                "Unhandled Evita external API exception on URL `" + request.path() + "`: error code {}, message {}",
                evitaError.getErrorCode(),
                evitaError.getPrivateMessage()
            );
            return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR.code(), MediaType.PLAIN_TEXT_UTF_8, null);
        }
    }
}
