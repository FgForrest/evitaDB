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

package io.evitadb.externalApi.rest.io;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.externalApi.http.EndpointExchange;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link EndpointExchange} for REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class RestEndpointExchange implements EndpointExchange {

	@Nonnull private final HttpServerExchange serverExchange;
	@Nullable private EvitaSessionContract session;
	@Nonnull private final String httpMethod;
	@Nullable private final String requestBodyContentType;
	@Nullable private final String preferredResponseContentType;

	@Nonnull
	@Override
	public HttpServerExchange serverExchange() {
		return serverExchange;
	}

	@Nonnull
	public EvitaSessionContract session() {
		Assert.isPremiseValid(
			session != null,
			() -> new RestInternalError("Session is not available for this exchange.")
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

	@Override
	public void close() {
		if (session != null) {
			session.close();
		}
	}
}
