/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.utils.Assert;
import io.grpc.stub.ClientResponseObserver;

import javax.annotation.Nonnull;
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
		@Nonnull ExecutorService executorService,
		@Nonnull Consumer<ClientResponseObserver<GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse>> streamInitializer,
		@Nonnull Consumer<ClientChangeCapturePublisher<ChangeSystemCapture, GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse>> onCloseCallback
	) {
		super(queueSize, executorService, streamInitializer, onCloseCallback);
	}

	@Nonnull
	@Override
	protected UUID deserializeAcknowledgementResponse(GrpcRegisterSystemChangeCaptureResponse itemResponse) {
		Assert.isPremiseValid(
			itemResponse.getResponseType() == GrpcCaptureResponseType.ACKNOWLEDGEMENT,
			"Response type must be ACKNOWLEDGEMENT for ChangeSystemCapture, but was: " + itemResponse.getResponseType()
		);
		return EvitaDataTypesConverter.toUuid(itemResponse.getUuid());
	}

	@Nonnull
	@Override
	protected ChangeSystemCapture deserializeCaptureResponse(GrpcRegisterSystemChangeCaptureResponse itemResponse) {
		Assert.isPremiseValid(
			itemResponse.getResponseType() == GrpcCaptureResponseType.CHANGE,
			"Response type must be CHANGE for ChangeSystemCapture, but was: " + itemResponse.getResponseType()
		);
		return toChangeSystemCapture(itemResponse.getCapture());
	}
}
