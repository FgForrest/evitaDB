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

import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.grpc.MethodDescriptor.MethodType;
import jdk.jfr.Description;
import jdk.jfr.Label;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a gRPC procedure is called.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Getter
public abstract class AbstractProcedureCalledEvent extends AbstractGrpcApiEvent {

	@Label("Service name")
	@Description("Name of the gRPC service that was called (the name of the Java class).")
	@ExportMetricLabel
	final String serviceName;

	@Label("Procedure name")
	@Description("Name of the gRPC procedure that was called (the method name).")
	@ExportMetricLabel
	final String procedureName;

	@Label("Initiator of the call")
	@Description("Initiator of the gRPC call (either client or server).")
	@ExportMetricLabel
	String initiator;

	@Label("gRPC response status")
	@Description("State of the gRPC response (OK, ERROR, CANCELED).")
	@ExportMetricLabel
	String grpcResponseStatus;

	/**
	 * Private field for recognizing the type of the gRPC method.
	 */
	private final MethodType methodType;

	protected AbstractProcedureCalledEvent(
		@Nonnull String serviceName,
		@Nonnull String procedureName,
		@Nonnull MethodType methodType
	) {
		this.serviceName = serviceName;
		this.procedureName = procedureName;
		this.methodType = methodType;
		this.grpcResponseStatus = ResponseState.OK.name();
		this.begin();
	}

	/**
	 * Check if the method represents single client invocation (inspiration taken from `java-grpc-prometheus` library).
	 * @return true if the method represents single client invocation
	 */
	public boolean unaryCall() {
		return this.methodType == MethodType.UNARY;
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
	 * @param grpcResponseStatus the response state
	 */
	public void setGrpcResponseStatus(@Nonnull ResponseState grpcResponseStatus) {
		this.grpcResponseStatus = grpcResponseStatus.name();
	}

	/**
	 * Finish the event.
	 * @return this
	 */
	@Nonnull
	public AbstractProcedureCalledEvent finish() {
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
