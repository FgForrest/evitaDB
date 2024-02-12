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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.driver.observability.trace;

import io.evitadb.driver.trace.ClientTracingContext;
import io.grpc.ClientInterceptor;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ClientTracingContext} for the driver. It depends on a gRPC library and as such, it returns
 * all necessary gRPC related objects.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class DriverTracingContext implements ClientTracingContext {

	/**
	 * Retrieves the client interceptor for tracing.
	 *
	 * @return the client interceptor for tracing
	 */
	@Override
	@Nullable
	public ClientInterceptor getClientInterceptor() {
		return GrpcTelemetry.create(OpenTelemetryClientTracerSetup.getOpenTelemetry()).newClientInterceptor();
	}

	/**
	 * Set the tracing endpoint URL and protocol for the OpenTelemetryClientTracerSetup.
	 * The tracing endpoint URL and protocol are used to configure the export of traces to the specified endpoint.
	 *
	 * @param tracingEndpointUrl        The URL of the tracing endpoint.
	 * @param tracingEndpointProtocol   The protocol to be used for exporting traces (e.g., HTTP, GRPC).
	 * @throws NullPointerException     If either tracingEndpointUrl or tracingEndpointProtocol is null.
	 */
	@Override
	public void setTracingEndpointUrlAndProtocol(@Nonnull String tracingEndpointUrl, @Nonnull String tracingEndpointProtocol) {
		OpenTelemetryClientTracerSetup.setTracingEndpointUrlAndProtocol(tracingEndpointUrl, tracingEndpointProtocol);
	}
}
