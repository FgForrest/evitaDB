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

package io.evitadb.externalApi.observability.metric;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.Evita;
import io.prometheus.metrics.exporter.common.PrometheusScrapeHandler;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * This service provides Prometheus metrics in text format. The service mimics original PrometheusServlet behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PrometheusMetricsHttpService implements HttpService {
	private final Evita evita;
	private final PrometheusScrapeHandler prometheusScrapeHandler;

	public PrometheusMetricsHttpService(@Nonnull Evita evita) {
		this.evita = evita;
		this.prometheusScrapeHandler = new PrometheusScrapeHandler();
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
		return HttpResponse.of(
			this.evita.executeAsyncInRequestThreadPool(
				() -> {
					try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
						final ArmeriaPrometheusHttpExchangeAdapter exchange = new ArmeriaPrometheusHttpExchangeAdapter(ctx, req, outputStream);
						// return metrics for scrape
						this.prometheusScrapeHandler.handleRequest(exchange);
						return HttpResponse.of(exchange.headersBuilder().build(), HttpData.copyOf(outputStream.toByteArray()));
					} catch (IOException e) {
						return HttpResponse.ofFailure(e);
					}
				}
			)
		);
	}

}
