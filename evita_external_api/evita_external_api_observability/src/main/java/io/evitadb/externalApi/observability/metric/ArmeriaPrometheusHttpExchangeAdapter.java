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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AsciiString;
import io.prometheus.metrics.exporter.common.PrometheusHttpExchange;
import io.prometheus.metrics.exporter.common.PrometheusHttpRequest;
import io.prometheus.metrics.exporter.common.PrometheusHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;

public class ArmeriaPrometheusHttpExchangeAdapter implements PrometheusHttpExchange {
	private final ArmeriaAdapterRequest request;
	private final ArmeriaAdapterResponse response;

	public ArmeriaPrometheusHttpExchangeAdapter(ServiceRequestContext ctx, HttpRequest req, ByteArrayOutputStream outputStream) {
		this.request = new ArmeriaAdapterRequest(req);
		this.response = new ArmeriaAdapterResponse(ctx, outputStream);
	}

	@Override
	public PrometheusHttpRequest getRequest() {
		return request;
	}

	@Override
	public PrometheusHttpResponse getResponse() {
		return response;
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

	public static class ArmeriaAdapterRequest implements PrometheusHttpRequest {

		private final HttpRequest request;

		public ArmeriaAdapterRequest(HttpRequest request) {
			this.request = request;
		}

		@Override
		public String getQueryString() {
			return request.uri().getQuery();
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			return Collections.enumeration(request.headers().names().stream().map(AsciiString::toString).toList());
		}

		@Override
		public String getMethod() {
			return request.method().name();
		}

		@Override
		public String getRequestPath() {
			return request.path();
		}
	}

	public static class ArmeriaAdapterResponse implements PrometheusHttpResponse {

		private final ServiceRequestContext context;
		private final OutputStream outputStream;

		public ArmeriaAdapterResponse(ServiceRequestContext context, OutputStream outputStream) {
			this.context = context;
			this.outputStream = outputStream;
		}

		@Override
		public void setHeader(String name, String value) {
			context.addAdditionalResponseHeader(name, value);
		}

		@Override
		public OutputStream sendHeadersAndGetBody(int statusCode, int contentLength) throws IOException {
			final String contentLengthHeader = context.additionalResponseHeaders().get("Content-Length");
			if (contentLengthHeader != null && contentLength > 0) {
				context.setMaxRequestLength(contentLength);
			}
			return outputStream;
		}
	}
}
