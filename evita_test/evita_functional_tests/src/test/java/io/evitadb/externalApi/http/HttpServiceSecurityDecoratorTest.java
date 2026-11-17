/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.lab.configuration.LabOptions;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SECURITY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the scheme and host checks of {@link HttpServiceSecurityDecorator} built from real endpoint options, with
 * requests arriving on a concrete local address the way a running server receives them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HTTP service security decorator")
@Tag(EXTERNAL_API)
@Tag(SECURITY)
class HttpServiceSecurityDecoratorTest {

	/**
	 * Supplies the no-argument options of the endpoints that serve plain HTTP by default, alone and together with the
	 * no-argument lab options, which require TLS and join every endpoint's decorator because the lab proxies the other
	 * endpoints.
	 *
	 * @return description, request path and the endpoint options the decorator is built from
	 */
	@Nonnull
	static Stream<Arguments> defaultPlainHttpEndpoints() {
		return Stream.of(
			Arguments.of(
				"system", "/system/server-name", new AbstractApiOptions[] {new SystemOptions()}
			),
			Arguments.of(
				"system beside lab", "/system/server-name",
				new AbstractApiOptions[] {new SystemOptions(), new LabOptions()}
			),
			Arguments.of(
				"observability", "/observability/liveness", new AbstractApiOptions[] {new ObservabilityOptions()}
			),
			Arguments.of(
				"observability beside lab", "/observability/liveness",
				new AbstractApiOptions[] {new ObservabilityOptions(), new LabOptions()}
			)
		);
	}

	/**
	 * The no-argument options listen on every network interface, so a plain HTTP request arriving on any concrete
	 * local address of their port must reach the decorated service.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("defaultPlainHttpEndpoints")
	@DisplayName("Should serve plain HTTP on a concrete local address with no-argument endpoint options")
	void shouldServePlainHttpOnConcreteLocalAddressWithDefaultOptions(
		@Nonnull String endpoints, @Nonnull String path, @Nonnull AbstractApiOptions[] configurations
	) throws Exception {
		final int port = configurations[0].getHost()[0].port();
		final HttpServiceSecurityDecorator decorator = new HttpServiceSecurityDecorator(
			(ctx, req) -> HttpResponse.of(HttpStatus.OK), ApiOptions.builder().build(), configurations
		);
		final HttpRequest request = HttpRequest.of(
			RequestHeaders.of(
				HttpMethod.GET, path,
				HttpHeaderNames.SCHEME, "http",
				HttpHeaderNames.AUTHORITY, "localhost:" + port
			)
		);
		final ServiceRequestContext context = ServiceRequestContext.builder(request)
			.sessionProtocol(SessionProtocol.HTTP)
			.localAddress(new InetSocketAddress("127.0.0.1", port))
			.build();

		final HttpStatus status = decorator.serve(context, request).aggregate().join().status();

		assertEquals(HttpStatus.OK, status, endpoints + " over plain HTTP");
	}
}
