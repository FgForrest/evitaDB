/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ExceptionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Generic error handler for Armeria server. Logs the error and returns 500 Internal Server Error response.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
class LoggingServerErrorHandler implements ServerErrorHandler {
	public static final ServerErrorHandler INSTANCE = new LoggingServerErrorHandler();

	@Nullable
	@Override
	public HttpResponse onServiceException(@Nonnull ServiceRequestContext ctx, @Nonnull Throwable cause) {
		final Throwable rootCause = ExceptionUtils.getRootCause(cause);
		if (rootCause instanceof RequestTimeoutException) {
			return HttpResponse.of(HttpStatus.REQUEST_TIMEOUT, MediaType.PLAIN_TEXT, "Request timed out.");
		} else if (rootCause instanceof ClosedStreamException) {
			return HttpResponse.of(HttpStatus.REQUEST_TIMEOUT, MediaType.PLAIN_TEXT, "Client closed stream.");
		} else if (rootCause instanceof EvitaInvalidUsageException) {
			return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Bad request.");
		} else {
			log.error("Armeria server error: " + cause.getMessage(), cause);
			return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT, "Internal server error. Please try again later.");
		}
	}
}
