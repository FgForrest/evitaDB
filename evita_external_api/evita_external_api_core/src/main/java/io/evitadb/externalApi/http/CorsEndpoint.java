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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.netty.util.AsciiString;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Endpoint for CORS handling for specific actual REST endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CorsEndpoint {

	@Nonnull private final Set<HttpMethod> allowedMethods = createHashSet(HttpMethod.values().length);
	@Nonnull private final Set<AsciiString> allowedHeaders = createHashSet(4);

	public CorsEndpoint(@Nonnull HeaderOptions headerOptions) {
		// default headers for tracing that are allowed on every endpoint by default
		headerOptions.allHeaders()
			.map(AsciiString::new)
			.forEach(this.allowedHeaders::add);
	}

	/**
	 * Adds allowed metadata from endpoint.
	 */
	public void addMetadataFromEndpoint(@Nonnull EndpointHandler<?> endpointHandler) {
		addMetadata(
			endpointHandler.getSupportedHttpMethods(),
			!endpointHandler.getSupportedRequestContentTypes().isEmpty(),
			!endpointHandler.getSupportedResponseContentTypes().isEmpty()
		);
	}

	public void addMetadata(@Nonnull Set<HttpMethod> supportedHttpMethods,
	                        boolean supportsRequestContentType,
	                        boolean supportsResponseContentType) {
		this.allowedMethods.addAll(supportedHttpMethods);
		if (supportsRequestContentType) {
			this.allowedHeaders.add(HttpHeaderNames.CONTENT_TYPE);
		}
		if (supportsResponseContentType) {
			this.allowedHeaders.add(HttpHeaderNames.ACCEPT);
		}
	}


	/**
	 * Creates CORS preflight handler
	 */
	public HttpService toHandler() {
		return CorsService.preflightHandler(this.allowedMethods, this.allowedHeaders);
	}

	/**
	 * Creates CORS preflight handler. Non-preflight requests are delegated to the passed delegate.
	 */
	public HttpService toHandler(@Nonnull HttpService delegate) {
		return CorsService.filter(delegate, this.allowedMethods, this.allowedHeaders);
	}
}
