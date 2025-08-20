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

package io.evitadb.externalApi.graphql.io;

import com.linecorp.armeria.common.HttpRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent.ResponseStatus;
import io.evitadb.externalApi.http.EndpointExecutionContext;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Endpoint execution context for GraphQL requests.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class GraphQLEndpointExecutionContext extends EndpointExecutionContext {

	@Nonnull private final ExecutedEvent requestExecutedEvent;

	@Nullable private String requestBodyContentType;
	@Nullable private String preferredResponseContentType;

	public GraphQLEndpointExecutionContext(
		@Nonnull HttpRequest httpRequest,
		@Nonnull Evita evita,
		@Nonnull ExecutedEvent requestExecutedEvent
	) {
		super(httpRequest, evita);
		this.requestExecutedEvent = requestExecutedEvent;
	}

	@Nonnull
	public ExecutedEvent requestExecutedEvent() {
		return this.requestExecutedEvent;
	}

	@Override
	public void provideRequestBodyContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			this.requestBodyContentType == null,
			() -> new GraphQLInternalError("Request body content type already provided.")
		);
		this.requestBodyContentType = contentType;
	}

	@Nullable
	@Override
	public String requestBodyContentType() {
		return this.requestBodyContentType;
	}

	@Override
	public void providePreferredResponseContentType(@Nonnull String contentType) {
		Assert.isPremiseValid(
			this.preferredResponseContentType == null,
			() -> new GraphQLInternalError("Preferred response content type already provided.")
		);
		this.preferredResponseContentType = contentType;
	}

	@Nullable
	@Override
	public String preferredResponseContentType() {
		return this.preferredResponseContentType;
	}

	@Override
	public void notifyError(@Nonnull Exception e) {
		this.requestExecutedEvent.provideResponseStatus(ResponseStatus.ERROR);
	}

	@Override
	public void close() {
		this.requestExecutedEvent.finish().commit();
	}
}
