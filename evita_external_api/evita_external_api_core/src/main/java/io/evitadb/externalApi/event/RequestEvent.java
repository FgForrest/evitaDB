/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
 * The event is begun when a request is served and finished by {@link #finish} once the request log completes, so its
 * own JFR duration - the source of the class-level duration metric - spans the whole asynchronous request.
 * `totalDurationMilliseconds` is Armeria's measurement of the same request, exported as a histogram of its own.
 * Fields other than `api` keep their defaults until `finish` fills them, which happens only when the event is
 * committed.
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
	/**
	 * Namespace shared by external API request events.
	 */
	protected static final String PACKAGE_NAME = "io.evitadb.externalApi";

	/**
	 * The API handling the request.
	 */
	@Label("API type")
	@Name("api")
	@Description("The identification of the API being called.")
	@ExportMetricLabel
	final String api;

	/**
	 * The HTTP status sent when the request completes.
	 */
	@Label("HTTP status code")
	@Description("The HTTP response status code that was sent to client.")
	@ExportMetricLabel
	String httpStatusCode;

	/**
	 * The classified result of the completed request.
	 */
	@Label("Request result")
	@Description("Simplified result of the request (SUCCESS, ERROR, TIMED_OUT, CANCELLED).")
	@ExportMetricLabel
	String requestResult;

	/**
	 * The service handling the request.
	 */
	@Label("Service name")
	@Description("The Armeria service name handling the request.")
	String serviceName;

	/**
	 * The endpoint handling the request.
	 */
	@Label("Method name")
	@Description("The endpoint or method name from RequestLog.")
	@ExportMetricLabel
	String methodName;

	/**
	 * The protocol used for the request.
	 */
	@Label("Session protocol")
	@Description("The session protocol (H1C, H1, H2C, H2, etc.).")
	@ExportMetricLabel
	String sessionProtocol;

	/**
	 * The total request duration reported by Armeria in milliseconds.
	 */
	@Label("Total duration")
	@Description("End-to-end request duration in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.65)
	double totalDurationMilliseconds;

	/**
	 * The request body receive duration in milliseconds.
	 */
	@Label("Request receive duration")
	@Description("Time to receive the full request body in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.65)
	double requestDurationMilliseconds;

	/**
	 * The response send duration in milliseconds.
	 */
	@Label("Response send duration")
	@Description("Time to send the response in milliseconds.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.65)
	double responseDurationMilliseconds;

	/**
	 * The received request length in bytes.
	 */
	@Label("Request length")
	@Description("Request content length in bytes.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(unit = "bytes", factor = 3)
	long requestLengthBytes;

	/**
	 * The sent response length in bytes.
	 */
	@Label("Response length")
	@Description("Response content length in bytes.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(unit = "bytes", factor = 3)
	long responseLengthBytes;

	/**
	 * Creates the event before the request is served. The caller begins timing at the service boundary.
	 *
	 * @param api the API handling the request
	 */
	public RequestEvent(@Nonnull String api) {
		this.api = api;
	}

	/**
	 * Ends timing, fills the completed request's fields and commits the event. The event must have been begun when the
	 * request arrived, otherwise its JFR duration is zero; call this once, when the request log completes. Nothing is
	 * filled or committed when no running recording enables the event.
	 *
	 * @param requestResult the classified request result
	 * @param httpStatusCode the HTTP response status
	 * @param serviceName the service handling the request
	 * @param methodName the endpoint handling the request
	 * @param sessionProtocol the request protocol
	 * @param totalDurationNanos the total duration reported by Armeria
	 * @param requestDurationNanos the request body receive duration
	 * @param responseDurationNanos the response send duration
	 * @param requestLengthBytes the received request length
	 * @param responseLengthBytes the sent response length
	 */
	public void finish(
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
		this.end();
		if (!this.shouldCommit()) {
			return;
		}
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
		this.commit();
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
