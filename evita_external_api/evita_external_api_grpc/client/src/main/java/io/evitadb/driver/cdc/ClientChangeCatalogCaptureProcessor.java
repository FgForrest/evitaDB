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

package io.evitadb.driver.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.grpc.stub.ClientResponseObserver;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter.toChangeCatalogCapture;
import static io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter.toHeartBeat;

/**
 * Implementation of {@link ClientChangeCapturePublisher} for the {@link ChangeCatalogCapture}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ClientChangeCatalogCaptureProcessor extends
	ClientChangeCapturePublisher<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse> {

	public ClientChangeCatalogCaptureProcessor(
		int queueSize,
		@Nonnull Duration streamingTimeout,
		@Nonnull ExecutorService executorService,
		@Nonnull Consumer<ClientResponseObserver<GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse>> streamInitializer,
		@Nonnull Consumer<ClientChangeCapturePublisher<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse>> onCloseCallback
	) {
		super(queueSize, streamingTimeout, executorService, streamInitializer, onCloseCallback);
	}

	@Nonnull
	@Override
	protected Optional<HeartBeat> deserializeAcknowledgementResponse(GrpcRegisterChangeCatalogCaptureResponse itemResponse) {
		if (itemResponse.getResponseType() == GrpcCaptureResponseType.ACKNOWLEDGEMENT || itemResponse.getResponseType() == GrpcCaptureResponseType.HEARTBEAT) {
			return Optional.of(toHeartBeat(itemResponse.getUuid(), itemResponse.getHeartBeat()));
		} else {
			return Optional.empty();
		}
	}

	@Nonnull
	@Override
	protected Optional<ChangeCatalogCapture> deserializeCaptureResponse(GrpcRegisterChangeCatalogCaptureResponse itemResponse) {
		if (itemResponse.getResponseType() == GrpcCaptureResponseType.CHANGE) {
			return Optional.of(toChangeCatalogCapture(itemResponse.getCapture()));
		} else {
			return Optional.empty();
		}
	}

}