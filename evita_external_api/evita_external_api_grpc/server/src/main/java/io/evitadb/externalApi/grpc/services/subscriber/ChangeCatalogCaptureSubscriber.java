/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.grpc.services.subscriber;

import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcHeartBeat;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.stub.ServerCallStreamObserver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.LongSupplier;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;

/**
 * {@link Subscriber} for catalog-level change capture events. Bridges an evitaDB
 * {@link ChangeCatalogCapture} publisher to a gRPC client, adapting the response
 * format to the connected client's semantic version.
 *
 * Shared lifecycle and cancellation-race handling is implemented in
 * {@link AbstractChangeCaptureSubscriber}; this class only provides the
 * type-specific response message building.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ChangeCatalogCaptureSubscriber
	extends AbstractChangeCaptureSubscriber<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureResponse> {

	/**
	 * The semantic version of the connected client, used to adapt the response format
	 * for backward compatibility. May be null if the client did not report its version.
	 */
	private final SemVer clientVersion;

	public ChangeCatalogCaptureSubscriber(
		@Nonnull Scheduler scheduler,
		@Nonnull String catalogName,
		@Nonnull ServerCallStreamObserver<GrpcRegisterChangeCatalogCaptureResponse> responseObserver,
		@Nullable SemVer clientVersion,
		@Nonnull LongSupplier versionSupplier,
		@Nonnull ServiceRequestContext serviceContext
	) {
		super(
			scheduler,
			catalogName,
			"Subscriber Heartbeat",
			responseObserver,
			versionSupplier,
			serviceContext
		);
		this.clientVersion = clientVersion;
	}

	@Nonnull
	@Override
	protected GrpcRegisterChangeCatalogCaptureResponse buildAcknowledgementResponse(
		@Nullable UUID subscriptionId,
		@Nonnull GrpcHeartBeat heartBeat
	) {
		final GrpcRegisterChangeCatalogCaptureResponse.Builder response = GrpcRegisterChangeCatalogCaptureResponse
			.newBuilder();
		if (subscriptionId != null) {
			response.setUuid(toGrpcUuid(subscriptionId));
		}
		return response
			.setResponseType(GrpcCaptureResponseType.ACKNOWLEDGEMENT)
			.setHeartBeat(heartBeat)
			.build();
	}

	@Nonnull
	@Override
	protected GrpcRegisterChangeCatalogCaptureResponse buildCaptureResponse(@Nonnull ChangeCatalogCapture capture) {
		return GrpcRegisterChangeCatalogCaptureResponse
			.newBuilder()
			.setCapture(ChangeCaptureConverter.toGrpcChangeCatalogCapture(capture, this.clientVersion))
			.setResponseType(GrpcCaptureResponseType.CHANGE)
			.build();
	}

	@Nonnull
	@Override
	protected GrpcRegisterChangeCatalogCaptureResponse buildHeartbeatResponse(
		@Nullable UUID subscriptionId,
		@Nonnull GrpcHeartBeat heartBeat
	) {
		final GrpcRegisterChangeCatalogCaptureResponse.Builder response = GrpcRegisterChangeCatalogCaptureResponse
			.newBuilder();
		if (subscriptionId != null) {
			response.setUuid(toGrpcUuid(subscriptionId));
		}
		return response
			.setResponseType(GrpcCaptureResponseType.HEARTBEAT)
			.setHeartBeat(heartBeat)
			.build();
	}
}
