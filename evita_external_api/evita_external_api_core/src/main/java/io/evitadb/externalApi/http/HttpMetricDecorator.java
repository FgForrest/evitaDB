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

package io.evitadb.externalApi.http;


import com.linecorp.armeria.common.CompletableRpcResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.event.RequestEvent;
import io.evitadb.externalApi.event.RequestEvent.Result;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * This decorator invokes {@link RequestEvent} when the request is completed tracking number of success and error responses.
 * It also tracks the request cancellation - e.g. timed out requests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class HttpMetricDecorator implements DecoratingHttpServiceFunction {
	private final String apiCode;

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull HttpService delegate, @Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		ctx.log()
			.whenComplete()
			.thenAccept(requestLog -> {
				final Result result;
				HttpStatus httpStatus = requestLog.responseStatus();
				if (requestLog.responseContent() instanceof CompletableRpcResponse crr) {
					final Throwable error = crr.cause();
					if (error == null) {
						result = Result.SUCCESS;
					} else if (causeIs(error, ClosedStreamException.class)) {
						result = Result.CANCELLED;
					} else if (causeIs(error, RequestTimeoutException.class)) {
						result = Result.TIMED_OUT;
					} else if (causeIs(error, EvitaInvalidUsageException.class) && httpStatus.code() == HttpStatus.OK.code()) {
						httpStatus = HttpStatus.BAD_REQUEST;
						result = Result.ERROR;
					} else if (httpStatus.code() == HttpStatus.OK.code()) {
						httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
						result = Result.ERROR;
					} else {
						result = Result.ERROR;
					}
				} else {
					if (httpStatus.code() == HttpStatus.REQUEST_TIMEOUT.code()) {
						result = Result.TIMED_OUT;
					} else if (httpStatus.isSuccess()) {
						result = Result.SUCCESS;
					} else {
						result = Result.ERROR;
					}
				}
				new RequestEvent(this.apiCode, result, httpStatus.code()).commit();
			});
		return delegate.serve(ctx, req);
	}

	/**
	 * Checks if the provided throwable or any of its causes matches the specified exception type.
	 * The method traverses the causal chain of the given throwable, ensuring no cyclic references are evaluated.
	 *
	 * @param throwable the throwable to evaluate, must not be null
	 * @param exceptionType the class of the exception type to check for, must not be null
	 * @return true if the specified exception type is found in the causal chain of the throwable, false otherwise
	 */
	private static <T extends Exception> boolean causeIs(@Nonnull Throwable throwable, @Nonnull Class<T> exceptionType) {
		final List<Throwable> list = new ArrayList<>();
		while (throwable != null && !list.contains(throwable)) {
			list.add(throwable);
			throwable = throwable.getCause();
		}
		return list.stream().anyMatch(exceptionType::isInstance);
	}

}
