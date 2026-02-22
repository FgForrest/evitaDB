/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.evitadb.externalApi.event.RequestEvent;
import io.evitadb.externalApi.event.RequestEvent.Result;
import io.evitadb.utils.ExceptionUtils;

import javax.annotation.Nonnull;

/**
 * This decorator invokes {@link RequestEvent} when the request is completed tracking number of success and error responses.
 * It also tracks the request cancellation - e.g. timed out requests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class HttpMetricDecorator extends SimpleDecoratingHttpService implements WebSocketDecoratingHandler {
	private final String apiCode;

	/**
	 * Creates a new instance that decorates the specified {@link HttpService}.
	 */
	public HttpMetricDecorator(@Nonnull HttpService delegate, @Nonnull String apiCode) {
		super(delegate);
		this.apiCode = apiCode;
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		ctx.log()
			.whenComplete()
			.thenAccept(requestLog -> {
				final Result result;
				HttpStatus httpStatus = requestLog.responseStatus();
				if (requestLog.responseContent() instanceof CompletableRpcResponse crr) {
					final Throwable error = crr.cause();
					if (error == null) {
						result = Result.SUCCESS;
					} else if (ExceptionUtils.causeChainContains(error, ClosedStreamException.class)) {
						result = Result.CANCELLED;
					} else if (ExceptionUtils.causeChainContains(error, RequestTimeoutException.class)) {
						result = Result.TIMED_OUT;
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
				// publish the event
				final String serviceName = requestLog.serviceName();
				new RequestEvent(
					this.apiCode,
					result,
					httpStatus.code(),
					serviceName != null ? serviceName : "",
					requestLog.name(),
					requestLog.sessionProtocol().toString(),
					requestLog.totalDurationNanos(),
					requestLog.requestDurationNanos(),
					requestLog.responseDurationNanos(),
					requestLog.requestLength(),
					requestLog.responseLength()
				).commit();
			});
		return this.unwrap().serve(ctx, req);
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		// not logging is done for WebSocket connections, these are long-lived and don't have a single result state
		return this.unwrapWebSocketHandler().handle(ctx, in);
	}
}
