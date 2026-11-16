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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.prometheus.metrics.exporter.common.PrometheusHttpExchange;
import io.prometheus.metrics.exporter.common.PrometheusHttpRequest;
import io.prometheus.metrics.exporter.common.PrometheusHttpResponse;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;

/**
 * This class is an adapter for Armeria's {@link ServiceRequestContext} and {@link HttpRequest} to Prometheus'
 * {@link PrometheusHttpExchange}, {@link PrometheusHttpRequest} and {@link PrometheusHttpResponse}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ArmeriaPrometheusHttpExchangeAdapter implements PrometheusHttpExchange {
	private final ArmeriaAdapterRequest request;
	private final ArmeriaAdapterResponse response;

	/**
	 * Built once per scrape: the exporter reads the request through it, writes the body into `outputStream` and
	 * the status and headers into `headersBuilder()`, from which the caller assembles the Armeria response.
	 *
	 * @param ctx          context of the scrape, which keeps the original query after prefix routers rewrote the path
	 * @param req          the scrape request
	 * @param outputStream receives the scrape body
	 */
	public ArmeriaPrometheusHttpExchangeAdapter(
		@Nonnull ServiceRequestContext ctx,
		@Nonnull HttpRequest req,
		@Nonnull ByteArrayOutputStream outputStream
	) {
		this.request = new ArmeriaAdapterRequest(ctx, req);
		this.response = new ArmeriaAdapterResponse(ctx, ResponseHeaders.builder(), outputStream);
	}

	@Override
	public PrometheusHttpRequest getRequest() {
		return this.request;
	}

	@Override
	public PrometheusHttpResponse getResponse() {
		return this.response;
	}

	@Override
	public void handleException(IOException e) throws IOException {
		throw e;
	}

	@Override
	public void handleException(RuntimeException e) {
		throw e;
	}

	@Override
	public void close() {
	}

	/**
	 * Returns the {@link ResponseHeadersBuilder} for the response that contains properly set content length.
	 * @return the {@link ResponseHeadersBuilder} for the response
	 */
	@Nonnull
	ResponseHeadersBuilder headersBuilder() {
		return this.response.headersBuilder;
	}

	/**
	 * Adapter for Armeria's {@link HttpRequest} to Prometheus' {@link PrometheusHttpRequest}.
	 */
	@RequiredArgsConstructor
	public static class ArmeriaAdapterRequest implements PrometheusHttpRequest {
		/** Retains the original query after prefix routers rewrite the request path. */
		private final ServiceRequestContext context;
		private final HttpRequest request;

		@Nullable
		@Override
		public String getQueryString() {
			return this.context.query();
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			return Collections.enumeration(this.request.headers().getAll(name));
		}

		@Override
		public String getMethod() {
			return this.request.method().name();
		}

		@Override
		public String getRequestPath() {
			return this.request.path();
		}
	}

	/**
	 * Adapter for Armeria's {@link ServiceRequestContext} and {@link OutputStream} to Prometheus'
	 * {@link PrometheusHttpResponse}.
	 */
	@RequiredArgsConstructor
	public static class ArmeriaAdapterResponse implements PrometheusHttpResponse {
		private final ServiceRequestContext context;
		private final ResponseHeadersBuilder headersBuilder;
		private final OutputStream outputStream;

		@Override
		public void setHeader(String name, String value) {
			this.headersBuilder.set(name, value);
		}

		@Override
		public OutputStream sendHeadersAndGetBody(int statusCode, int contentLength) {
			// a length of 0 (body of unknown length, e.g. compressed) or -1 (no body, HEAD) still carries a status
			this.headersBuilder.status(statusCode);
			if (contentLength > 0) {
				this.headersBuilder.contentLength(contentLength);
				this.context.setMaxRequestLength(contentLength);
			}
			return this.outputStream;
		}
	}
}
