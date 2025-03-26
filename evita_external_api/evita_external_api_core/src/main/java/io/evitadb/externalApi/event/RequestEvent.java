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

import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * This event is base class for all query related events.
 */
@Description("Event that is fired when a readiness probe is either executed by client or invoked on the server side.")
@Label("Request")
@ExportInvocationMetric(label = "Requests invoked total")
@Getter
public class RequestEvent extends AbstractExternalApiEvent {
	protected static final String PACKAGE_NAME = "io.evitadb.externalApi";

	/**
	 * The HTTP response status code that was sent to client.
	 */
	@Label("HTTP status code")
	@Description("The HTTP response status code that was sent to client.")
	@ExportMetricLabel
	final String httpStatusCode;

	/**
	 * The result of the readiness probe.
	 */
	@Label("Request result")
	@Description("Simplified result of the request (success, error, cancelled).")
	@ExportMetricLabel
	final String requestResult;

	/**
	 * The name of the catalog the transaction relates to.
	 */
	@Label("API type")
	@Name("api")
	@Description("The identification of the API being probed.")
	@ExportMetricLabel
	final String api;

	public RequestEvent(@Nonnull String api, @Nonnull Result requestResult, int httpStatusCode) {
		this.httpStatusCode = String.valueOf(httpStatusCode);
		this.requestResult = requestResult.name();
		this.api = api;
	}

	/**
	 * Enum representing the result of a request.
	 *
	 * - {@code SUCCESS}: Indicates that the request returned success response.
	 * - {@code ERROR}: Indicates that the request returned error response - either server error or client error.
	 * - {@code TIMED_OUT}: Indicates that the request has timed out on the server side.
	 * - {@code CANCELLED}: Indicates that the request has been cancelled by the client.
	 */
	public enum Result {
		SUCCESS,
		ERROR,
		TIMED_OUT,
		CANCELLED
	}

}
