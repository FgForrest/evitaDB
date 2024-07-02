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

package io.evitadb.externalApi.rest.io;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.externalApi.http.EndpointRequest;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.utils.Assert;
import io.netty.util.concurrent.EventExecutor;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Subscriber;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link EndpointRequest} for REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class RestEndpointExchange implements EndpointRequest {

	@Nonnull private final HttpRequest httpRequest;
	@Nullable private EvitaSessionContract session;
	@Nonnull private final String httpMethod;
	@Nullable private final String requestBodyContentType;
	@Nullable private final String preferredResponseContentType;

	@Nonnull
	public EvitaSessionContract session() {
		Assert.isPremiseValid(
			session != null,
			() -> new RestInternalError("Session is not available for this exchange.")
		);
		Assert.isPremiseValid(
			this.session.isActive(),
			() -> new RestInternalError("Session has been already closed. No one should access the session!")
		);
		return session;
	}

	/**
	 * Sets a session for this exchange. Can be set only once to avoid overwriting errors.
	 */
	public void session(@Nonnull EvitaSessionContract session) {
		Assert.isPremiseValid(
			this.session == null,
			() -> new RestInternalError("Session cannot overwritten when already set.")
		);
		this.session = session;
	}

	/**
	 * Closes the current session (and transaction) if it is open.
	 */
	public void closeSessionIfOpen() {
		if (session != null) {
			session.close();
		}
	}

	@Nonnull
	@Override
	public HttpRequest httpRequest() {
		return httpRequest;
	}

	@Nonnull
	@Override
	public String httpMethod() {
		return httpMethod;
	}

	@Nullable
	@Override
	public String requestBodyContentType() {
		return requestBodyContentType;
	}

	@Nullable
	@Override
	public String preferredResponseContentType() {
		return preferredResponseContentType;
	}
}
