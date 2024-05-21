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

package io.evitadb.externalApi.grpc.metric.event;

import io.evitadb.core.metric.annotation.ExportDurationMetric;
import io.evitadb.core.metric.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.annotation.ExportMetricLabel;
import io.grpc.MethodDescriptor.MethodType;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a gRPC procedure is called.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractGrpcApiEvent.PACKAGE_NAME + ".GrpcProcedureCalled")
@Description("Event that is fired when a gRPC procedure is called.")
@ExportInvocationMetric(label = "gRPC procedure called total")
@ExportDurationMetric(label = "gRPC procedure called duration")
@Label("gRPC procedure called")
@Getter
public class GrpcProcedureCalledEvent extends AbstractGrpcApiEvent {

	@Label("Name of the service that was called")
	@ExportMetricLabel("serviceName")
	private final String serviceName;

	@Label("Name of the procedure that was called")
	@ExportMetricLabel("procedureName")
	private final String procedureName;

	@Label("Initiator of the call (client or server)")
	@ExportMetricLabel("initiator")
	private String initiator;

	@Label("State of the response (OK, ERROR, CANCELED)")
	@ExportMetricLabel("responseState")
	private String responseState;

	/**
	 * Private field for recognizing the type of the gRPC method.
	 */
	private final MethodType methodType;

	public GrpcProcedureCalledEvent(
		@Nonnull String serviceName,
		@Nonnull String procedureName,
		@Nonnull MethodType methodType
	) {
		this.serviceName = serviceName;
		this.procedureName = procedureName;
		this.methodType = methodType;
		this.responseState = ResponseState.OK.name();
		this.begin();
	}

	/**
	 * Check if the method streams requests (inspiration taken from `java-grpc-prometheus` library).
	 * @return true if the method streams requests
	 */
	public boolean streamsRequests() {
		return this.methodType == MethodType.CLIENT_STREAMING || this.methodType == MethodType.BIDI_STREAMING;
	}

	/**
	 * Check if the method streams responses (inspiration taken from `java-grpc-prometheus` library).
	 * @return true if the method streams responses
	 */
	public boolean streamsResponses() {
		return this.methodType == MethodType.SERVER_STREAMING || this.methodType == MethodType.BIDI_STREAMING;
	}

	/**
	 * Set the initiator of the call.
	 * @param initiator the initiator
	 */
	public void setInitiator(@Nonnull InitiatorType initiator) {
		this.initiator = initiator.name();
	}

	/**
	 * Set the response state.
	 * @param responseState the response state
	 */
	public void setResponseState(@Nonnull ResponseState responseState) {
		this.responseState = responseState.name();
	}

	/**
	 * Finish the event.
	 * @return this
	 */
	@Nonnull
	public GrpcProcedureCalledEvent finish() {
		this.end();
		return this;
	}

	/**
	 * Enum representing the initiator of the call.
	 */
	public enum InitiatorType {

		CLIENT, SERVER

	}

	/**
	 * Enum representing the state of the response.
	 */
	public enum ResponseState {

		OK, ERROR, CANCELED

	}

}


