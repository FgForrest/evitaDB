/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.graphql.io;

import com.linecorp.armeria.common.HttpRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.http.EndpointExecutionContext;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Endpoint execution context for GraphQL schema DSL requests.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
class GraphQLSchemaEndpointExecutionContext extends EndpointExecutionContext {

	@Nullable private String requestBodyContentType;
	@Nullable private String preferredResponseContentType;

	public GraphQLSchemaEndpointExecutionContext(
		@Nonnull HttpRequest serverExchange,
		@Nonnull Evita evita
	) {
		super(serverExchange, evita);
	}

	@Override
	public void provideRequestBodyContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			this.requestBodyContentType == null,
			() -> new GraphQLInternalError("Request body content type already provided.")
		);
		requestBodyContentType = contentType;
	}

	@Nullable
	@Override
	public String requestBodyContentType() {
		return requestBodyContentType;
	}

	@Override
	public void providePreferredResponseContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			preferredResponseContentType == null,
			() -> new GraphQLInternalError("Preferred response content type already provided.")
		);
		preferredResponseContentType = contentType;
	}

	@Nullable
	@Override
	public String preferredResponseContentType() {
		return preferredResponseContentType;
	}
}
