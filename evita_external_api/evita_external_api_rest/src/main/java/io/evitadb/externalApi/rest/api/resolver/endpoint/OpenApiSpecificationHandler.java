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

package io.evitadb.externalApi.rest.api.resolver.endpoint;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponseWriter;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.openApi.OpenApiWriter;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import io.netty.channel.EventLoop;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Returns OpenAPI schema for whole collection.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class OpenApiSpecificationHandler<C extends RestHandlingContext> extends RestEndpointHandler<C> {

	public OpenApiSpecificationHandler(@Nonnull C restHandlingContext) {
		super(restHandlingContext);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		return executionContext.executeAsyncInRequestThreadPool(
			() -> new SuccessEndpointResponse(this.restHandlingContext.getOpenApi())
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		final LinkedHashSet<String> mediaTypes = createLinkedHashSet(2);
		mediaTypes.add(MimeTypes.APPLICATION_JSON);
		mediaTypes.add(MimeTypes.APPLICATION_YAML);
		return mediaTypes;
	}

	@Override
	protected void writeResponse(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull HttpResponseWriter responseWriter,
		@Nonnull Object openApiSpecification,
		@Nonnull EventLoop eventLoop) {
		final String preferredResponseMediaType = executionContext.preferredResponseContentType();
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			if (preferredResponseMediaType.equals(MimeTypes.APPLICATION_YAML)) {
				OpenApiWriter.toYaml(openApiSpecification, outputStream);
			} else if (preferredResponseMediaType.equals(MimeTypes.APPLICATION_JSON)) {
				OpenApiWriter.toJson(openApiSpecification, outputStream);
			} else {
				throw createInternalError("Should never happen!");
			}
			responseWriter.write(HttpData.copyOf(outputStream.toByteArray()));
		} catch (IOException e) {
			throw createInternalError("Could not serialize OpenAPI specification: " + e.getMessage());
		}
	}
}
