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

package io.evitadb.driver.observability.trace;

import io.opentelemetry.api.OpenTelemetry;

import javax.annotation.Nonnull;

/**
 * This class is responsible for passing an instance of {@link OpenTelemetry} to the EvitaClient driver, which will use
 * it for tracing purposes. The OpenTelemetry instance has to be set before any tracing is performed.
 * It follows the pattern of auto instrumentation libraries, that requires the user to set the OpenTelemetry instance
 * from the application code that will be using EvitaClient.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class OpenTelemetryClientTracerSetup {
	private static OpenTelemetry OPEN_TELEMETRY;

	/**
	 * Retrieves the instance of OpenTelemetry. If the instance is not initialized, it will be
	 * initialized before returning.
	 *
	 * @return the OpenTelemetry instance
	 */
	@Nonnull
	public static OpenTelemetry getOpenTelemetry() {
		if (OPEN_TELEMETRY == null) {
			throw new IllegalArgumentException("OpenTelemetry instance is not initialized properly! You have to always call `setOpenTelemetry` method to pass your OpenTelemetry instance.");
		}
		return OPEN_TELEMETRY;
	}

	/**
	 * Sets the OpenTelemetry instance to be used for tracing.
	 * This method is crucial to call to enable tracing capabilities from the EvitaClient driver.
	 * It follows the singleton pattern, so it is not recommended to set the OpenTelemetry instance more than once.
	 * @param openTelemetry the OpenTelemetry instance used for tracing from EvitaClient
	 */
	public static void setOpenTelemetry(@Nonnull OpenTelemetry openTelemetry) {
		OPEN_TELEMETRY = openTelemetry;
	}
}
