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

import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * This event is fired when a readiness probe is either executed by client or invoked on the server side.
 */
@ExportInvocationMetric(label = "Readiness probe invoked total")
@ExportDurationMetric(label = "Readiness probe duration")
@Description("Event that is fired when a readiness probe is either executed by client or invoked on the server side.")
@Label("Readiness probe")
@Getter
public class ReadinessEvent extends AbstractExternalApiEvent {

	/**
	 * The code of the API that was probed.
	 */
	@Label("API type")
	@Name("api")
	@Description("The identification of the API being probed.")
	@ExportMetricLabel
	final String api;

	/**
	 * Identifies whether the event represents whether event represents server or client view of readiness.
	 * Client view is the duration viewed from the HTTP client side affected by timeouts, server view is the real
	 * duration of the probe.
	 */
	@Label("Prospective (client/server)")
	@Name("prospective")
	@Description(
		"""
		Identifies whether the event represents whether event represents server or client view of readiness.
		Client view is the duration viewed from the HTTP client side affected by timeouts, server view is the real 
		duration of the probe."""
	)
	@ExportMetricLabel
	final String prospective;

	/**
	 * The result of the readiness probe.
	 */
	@Label("Probe result")
	@Description("The result of the readiness probe (ok, timeout, error).")
	@ExportMetricLabel
	String probeResult;

	public ReadinessEvent(@Nonnull String api, @Nonnull Prospective prospective) {
		this.api = api;
		this.prospective = prospective.name();
		this.begin();
	}

	/**
	 * Marks the readiness event as completed with a given result and ends the event.
	 *
	 * @param result The result of the readiness probe, which can be either READY, TIMEOUT, or ERROR.
	 */
	public void finish(@Nonnull Result result) {
		this.probeResult = result.name();
		super.end();
		this.commit();
	}

	/**
	 * Enum representing the perspective of the readiness event.
	 *
	 * - {@code CLIENT}: Represents the readiness duration from the HTTP client's side, which includes potential timeouts.
	 * - {@code SERVER}: Represents the actual duration of the readiness probe from the server's perspective.
	 */
	public enum Prospective {
		CLIENT,
		SERVER
	}


	/**
	 * Enum representing the result of a readiness probe.
	 *
	 * - {@code READY}: Indicates that the probe was successful and the system is ready.
	 * - {@code TIMEOUT}: Indicates that the probe timed out.
	 * - {@code ERROR}: Indicates that an error occurred during the probe.
	 */
	public enum Result {
		READY,
		TIMEOUT,
		ERROR
	}

}
