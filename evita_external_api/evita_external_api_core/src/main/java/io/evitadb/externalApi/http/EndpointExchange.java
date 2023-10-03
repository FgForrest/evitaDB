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

import io.undertow.server.HttpServerExchange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An endpoint request/response exchange. Used as context object for endpoint processing.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface EndpointExchange extends AutoCloseable {

	/**
	 * Underlying HTTP server exchange
	 */
	@Nonnull
	HttpServerExchange serverExchange();

	/**
	 * HTTP method of the request.
	 */
	@Nonnull
	String httpMethod();

	/**
	 * Parsed content type of request body, if any request body is present.
	 */
	@Nullable
	String requestBodyContentType();

	/**
	 * Preferred content type of response body, if any response body is will be send.
	 */
	@Nullable
	String preferredResponseContentType();

	@Override
	default void close() {
		// do nothing
	}
}
