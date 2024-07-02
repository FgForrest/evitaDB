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

package io.evitadb.externalApi.observability.metric;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.prometheus.metrics.exporter.common.PrometheusScrapeHandler;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PrometheusMetricsHttpService implements HttpService {
	private final PrometheusScrapeHandler prometheusScrapeHandler;

	public PrometheusMetricsHttpService() {
		this.prometheusScrapeHandler = new PrometheusScrapeHandler();
	}
	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			prometheusScrapeHandler.handleRequest(new ArmeriaPrometheusHttpExchangeAdapter(ctx, req, outputStream));
		} catch (IOException e) {
			return HttpResponse.ofFailure(e);
		}

		return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.copyOf(outputStream.toByteArray()));
	}
}