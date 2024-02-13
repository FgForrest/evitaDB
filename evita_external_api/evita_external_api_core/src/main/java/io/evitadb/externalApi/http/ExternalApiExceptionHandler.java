/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.http;

import io.evitadb.exception.EvitaError;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Catches all exceptions thrown inside inner HTTP handlers. Internal {@link ExternalApiInternalError}s are logged, other
 * errors that should be propagated as errors to clients have to be properly handled by subclasses (this is because
 * each API will want to format and display errors differently).
 *
 * This is handler is not registered automatically, instead each API's registrar can use this as ancestor for its own
 * exception handling to not duplication common logic and also provide unified internal exception logging.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
public abstract class ExternalApiExceptionHandler implements HttpHandler {

    @Nonnull
    private final HttpHandler next;

    @Override
    public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
        try {
            next.handleRequest(exchange);
        } catch (Exception exception) {
            final EvitaError evitaError;
            if (exception instanceof EvitaError) {
                evitaError = (EvitaError) exception;
            } else {
                // wrap any exception occurred inside some external code which was not handled before
                evitaError = new ExternalApiInternalError(
                    "Unexpected internal Evita " + getExternalApiCode() + " error occurred: " + exception.getMessage(),
                    "Unexpected internal Evita " + getExternalApiCode() + " error occurred.",
                    exception
                );
            }

            if (evitaError instanceof final ExternalApiInternalError externalApiInternalError) {
                // log any API internal errors that Evita cannot handle because they are outside of Evita execution
                log.error(
                    "Internal Evita " + getExternalApiCode() + " API error occurred in " + externalApiInternalError.getErrorCode() + ": " + externalApiInternalError.getPrivateMessage(),
                    externalApiInternalError
                );
            }
            handleError(evitaError, exchange);
        }
    }

    /**
     * Common way to set basic error response.
     */
    protected void setResponse(@Nonnull HttpServerExchange exchange,
                               int statusCode,
                               @Nonnull String contentType,
                               @Nullable String body) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        exchange.getResponseSender().send(Optional.ofNullable(body).orElse(""));
    }

    /**
     * {@link ExternalApiProvider#getCode()} for properly identifying source of exception in application log.
     */
    @Nonnull
    protected abstract String getExternalApiCode();

    private void handleError(@Nonnull EvitaError evitaError, @Nonnull HttpServerExchange exchange) {
        if (!exchange.isResponseChannelAvailable()) {
            // should ideally never happen
            log.error(
                "Evita thrown an exception but " + getExternalApiCode() + " API response channel isn't available: error code {}, message:",
                evitaError.getErrorCode(),
                evitaError.getPrivateMessage()
            );
        }
        renderError(evitaError, exchange);
    }

    /**
     * Should render {@link EvitaError}'s public message to client in descriptive way. This method cannot throw any exception, otherwise
     * it will not be properly handled.
     */
    protected abstract void renderError(@Nonnull EvitaError evitaError, @Nonnull HttpServerExchange exchange);
}
