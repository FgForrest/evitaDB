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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.function.QuadriFunction;
import io.evitadb.function.TriFunction;

import javax.annotation.Nonnull;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Configures and registers provider of particular external API to HTTP server ({@link ExternalApiServer}).
 * Each provider have to have unique code and have to implement {@link #register(Evita, ExternalApiServer, ApiOptions, AbstractApiConfiguration)}
 * method which registers provider to the server to be later started by the server.
 *
 * It is based on {@link java.util.ServiceLoader} which requires appropriate registration of implementation of this interface.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ExternalApiProviderRegistrar<T extends AbstractApiConfiguration> {

	/**
	 * Returns unique identification code of the API registrar.
	 *
	 * @return same code as linked {@link ExternalApiProvider#getCode()}
	 */
	@Nonnull
	String getExternalApiCode();

	/**
	 * Returns configuration initialized with default values for this external API.
	 *
	 * @return configuration object instance with sane default values
	 */
	@Nonnull
	Class<T> getConfigurationClass();

	/**
	 * @return order of the API provider. Providers with lower order are registered first.
	 */
	default int getOrder() {
		return 0;
	}

	/**
	 * Configures and registers this provider
	 *
	 * @param evita                    ready-to-use Evita with access to internal data structures
	 * @param externalApiServer        the server the created provider will be registered to (not serving yet)
	 * @param apiOptions               options for this provider
	 * @param externalApiConfiguration configuration parameters for this provider (structure is defined by provider itself)
	 */
	@Nonnull
	ExternalApiProvider<T> register(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@Nonnull T externalApiConfiguration
	);

	default TriFunction<ServiceRequestContext, HttpRequest, HttpService, HttpResponse> getApiHandlerPortSslValidatingFunction(T externalApiConfiguration) {
		return (context, httpRequest, delegate) -> {
			try {
				final TlsMode tlsMode = externalApiConfiguration.getTlsMode();
				final boolean wasTlsRequest = httpRequest.scheme().equals("https");
				if (tlsMode == TlsMode.FORCE_TLS  && !wasTlsRequest) {
					return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT, "This endpoint requires TLS.");
				}
				if (tlsMode == TlsMode.FORCE_NO_TLS && wasTlsRequest) {
					return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT, "This endpoint does not support TLS.");
				}
				return delegate.serve(context, httpRequest);
			} catch (Exception e) {
				return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT, "Internal server error: " + e.getMessage());
			}
		};
	}
}
