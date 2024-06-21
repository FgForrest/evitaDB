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

import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.configuration.ApiWithOriginControl;
import io.evitadb.externalApi.http.AdditionalHeaders;

import io.evitadb.externalApi.http.CorsPreflightService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Endpoint for CORS handling for specific actual REST endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CorsEndpoint {

	@Nullable private final Set<String> allowedOrigins;
	@Nonnull private final Set<String> allowedMethods = createHashSet(10);
	@Nonnull private final Set<String> allowedHeaders = createHashSet(2);

	public CorsEndpoint(@Nonnull ApiWithOriginControl restConfig) {
		this.allowedOrigins = restConfig.getAllowedOrigins() == null ? null : Set.of(restConfig.getAllowedOrigins());

		// default headers for tracing that are allowed on every endpoint by default
		this.allowedHeaders.add(AdditionalHeaders.OPENTELEMETRY_TRACEPARENT_STRING);
		this.allowedHeaders.add(AdditionalHeaders.EVITADB_CLIENTID_HEADER_STRING);
	}

	public void addMetadataFromHandler(@Nonnull RestEndpointHandler<?> handler) {
		addMetadata(
			handler.getSupportedHttpMethods(),
			!handler.getSupportedRequestContentTypes().isEmpty(),
			!handler.getSupportedResponseContentTypes().isEmpty()
		);
	}

	public void addMetadata(@Nonnull Set<String> supportedHttpMethods,
	                        boolean supportsRequestContentType,
	                        boolean supportsResponseContentType) {
		allowedMethods.addAll(supportedHttpMethods);
		if (supportsRequestContentType) {
			allowedHeaders.add("Content-Type");
		}
		if (supportsResponseContentType) {
			allowedHeaders.add("Accept");
		}
	}

	@Nonnull
	public HttpService toService() {
		return new CorsPreflightService(allowedOrigins, allowedMethods, allowedHeaders);
	}
}
