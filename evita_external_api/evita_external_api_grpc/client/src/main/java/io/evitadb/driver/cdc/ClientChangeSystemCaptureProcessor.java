/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.grpc.stub.ClientResponseObserver;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter.toChangeSystemCapture;

/**
 * Implementation of {@link ClientChangeCapturePublisher} for the {@link ChangeSystemCapture}.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2025
 */
public class ClientChangeSystemCaptureProcessor extends
	ClientChangeCapturePublisher<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse> {

	public ClientChangeSystemCaptureProcessor(
		int queueSize,
		@Nonnull Duration streamingTimeout,
		@Nonnull ExecutorService executorService,
		@Nonnull Consumer<ClientResponseObserver<GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse>> streamInitializer,
		@Nonnull Consumer<ClientChangeCapturePublisher<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse>> onCloseCallback
	) {
		super(queueSize, streamingTimeout, executorService, streamInitializer, onCloseCallback);
	}

	@Nonnull
	@Override
	protected Optional<UUID> deserializeAcknowledgementResponse(GrpcRegisterSystemChangeCaptureResponse itemResponse) {
		if (itemResponse.getResponseType() == GrpcCaptureResponseType.ACKNOWLEDGEMENT) {
			return Optional.of(EvitaDataTypesConverter.toUuid(itemResponse.getUuid()));
		} else {
			return Optional.empty();
		}
	}

	@Nonnull
	@Override
	protected Optional<ChangeSystemCapture> deserializeCaptureResponse(GrpcRegisterSystemChangeCaptureResponse itemResponse) {
		if (itemResponse.getResponseType() == GrpcCaptureResponseType.CHANGE) {
			return Optional.of(toChangeSystemCapture(itemResponse.getCapture()));
		} else {
			return Optional.empty();
		}
	}
}
