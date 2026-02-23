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

package io.evitadb.externalApi.event;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * JFR event capturing per-request metrics including invocation counts, timing and payload sizes
 * from Armeria RequestLog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Description("Event that is fired when a request is completed, tracking success/error counts, timing and payload sizes.")
@Label("Request")
@ExportInvocationMetric(label = "Requests invoked total")
@ExportDurationMetric(label = "Request total duration")
@HistogramSettings(factor = 1.65)
@Getter
public class RequestEvent extends AbstractExternalApiEvent {
	protected static final String PACKAGE_NAME = "io.evitadb.externalApi";

	@Label("API type")
	@Name("api")
	@Description("The identification of the API being called.")
	@ExportMetricLabel
	final String api;

	@Label("HTTP status code")
	@Description("The HTTP response status code that was sent to client.")
	@ExportMetricLabel
	final String httpStatusCode;

	@Label("Request result")
	@Description("Simplified result of the request (SUCCESS, ERROR, TIMED_OUT, CANCELLED).")
	@ExportMetricLabel
	final String requestResult;

	@Label("Service name")
	@Description("The Armeria service name handling the request.")
	final String serviceName;

	@Label("Method name")
	@Description("The endpoint or method name from RequestLog.")
	@ExportMetricLabel
	final String methodName;

	@Label("Session protocol")
	@Description("The session protocol (H1C, H1, H2C, H2, etc.).")
	@ExportMetricLabel
	final String sessionProtocol;

	@Label("Total duration")
	@Description("End-to-end request duration in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.65)
	final double totalDurationMilliseconds;

	@Label("Request receive duration")
	@Description("Time to receive the full request body in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.65)
	final double requestDurationMilliseconds;

	@Label("Response send duration")
	@Description("Time to send the response in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.65)
	final double responseDurationMilliseconds;

	@Label("Request length")
	@Description("Request content length in bytes.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(unit = "bytes", factor = 3)
	final long requestLengthBytes;

	@Label("Response length")
	@Description("Response content length in bytes.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(unit = "bytes", factor = 3)
	final long responseLengthBytes;

	public RequestEvent(
		@Nonnull String api,
		@Nonnull Result requestResult,
		int httpStatusCode,
		@Nonnull String serviceName,
		@Nonnull String methodName,
		@Nonnull String sessionProtocol,
		long totalDurationNanos,
		long requestDurationNanos,
		long responseDurationNanos,
		long requestLengthBytes,
		long responseLengthBytes
	) {
		this.api = api;
		this.httpStatusCode = String.valueOf(httpStatusCode);
		this.requestResult = requestResult.name();
		this.serviceName = serviceName;
		this.methodName = methodName;
		this.sessionProtocol = sessionProtocol;
		this.totalDurationMilliseconds = totalDurationNanos / 1_000_000.0;
		this.requestDurationMilliseconds = requestDurationNanos / 1_000_000.0;
		this.responseDurationMilliseconds = responseDurationNanos / 1_000_000.0;
		this.requestLengthBytes = requestLengthBytes;
		this.responseLengthBytes = responseLengthBytes;
	}

	/**
	 * Enum representing the result of a request.
	 *
	 * - {@code SUCCESS}: Indicates that the request returned success response.
	 * - {@code CLIENT_ERROR}: Indicates that the request returned error response - client error.
	 * - {@code ERROR}: Indicates that the request returned error response - server error.
	 * - {@code TIMED_OUT}: Indicates that the request has timed out on the server side.
	 * - {@code CANCELLED}: Indicates that the request has been cancelled by the client.
	 */
	public enum Result {
		SUCCESS,
		CLIENT_ERROR,
		ERROR,
		TIMED_OUT,
		CANCELLED
	}

}
