/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.trace;


import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.evitadb.api.observability.trace.TracingContext;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * Simple decorator that initializes tracing context for the service with client IP address.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class TracingDecorator extends SimpleDecoratingHttpService {

	/**
	 * Returns a new {@link HttpService} decorator that traces the service.
	 * @return a new {@link HttpService} decorator that traces the service.
	 */
	@Nonnull
	public static Function<? super HttpService, ? extends HttpService> newDecorator() {
		return TracingDecorator::new;
	}

	/**
	 * Creates a new instance that decorates the specified {@link HttpService}.
	 */
	protected TracingDecorator(@Nonnull HttpService delegate) {
		super(delegate);
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final String clientIpAddress = ctx.clientAddress().getHostAddress();
		return TracingContext.executeWithClientIpAddress(
			clientIpAddress,
			() -> unwrap().serve(ctx, req)
		);
	}

}
