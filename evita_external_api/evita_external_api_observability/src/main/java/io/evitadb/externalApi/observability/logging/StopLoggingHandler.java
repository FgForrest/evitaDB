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

package io.evitadb.externalApi.observability.logging;

import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.observability.ObservabilityManager;
import io.undertow.util.Methods;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stops recording of JFR events and stores them in a file that is available via Observability API.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class StopLoggingHandler extends LoggingEndpointHandler {

	public StopLoggingHandler(ObservabilityManager manager) {
		super(manager);
	}

	@Nonnull
	@Override
	protected EndpointResponse doHandleRequest(@Nonnull LoggingEndpointExchange exchange) {
		return new SuccessEndpointResponse(manager.stop());
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.POST_STRING);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}
}
