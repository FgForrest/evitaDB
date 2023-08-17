/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.externalApi.grpc.services;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureSubscriber;
import io.evitadb.api.requestResponse.cdc.NamedSubscription;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.CaptureResponseType;
import io.evitadb.utils.Assert;
import io.grpc.stub.StreamObserver;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Subscription;

import static io.evitadb.externalApi.grpc.dataType.ChangeDataCaptureConverter.toGrpcChangeSystemCapture;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCaptureResponseType;

/**
 * gRPC implementation of the {@link ChangeSystemCaptureSubscriber} interface that sends the captured system changes to
 * the client via the gRPC stream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class GrpcChangeSystemCaptureSubscriber implements ChangeSystemCaptureSubscriber {
	/**
	 * The gRPC stream observer that is used to send the captured system changes to the client.
	 */
	private final StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver;
	/**
	 * The subscription that is used to identify the subscription on the server.
	 */
	private NamedSubscription subscription;

	public GrpcChangeSystemCaptureSubscriber(@Nonnull StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver) {
		this.responseObserver = responseObserver;
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		Assert.isTrue(subscription instanceof NamedSubscription, "Subscription must implement `NamedSubscription` interface!");
		this.subscription = (NamedSubscription) subscription;
		responseObserver.onNext(GrpcRegisterSystemChangeCaptureResponse.newBuilder()
			//.setUuid(this.subscription.id().toString())
			//.setResponseType(GrpcCaptureResponseType.ACKNOWLEDGEMENT)
			.build());
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		Assert.notNull(this.subscription, "Observer hasn't yet been subscribed!");
		responseObserver.onNext(GrpcRegisterSystemChangeCaptureResponse.newBuilder()
			//.setUuid(this.subscription.id().toString())
			.setCapture(toGrpcChangeSystemCapture(item))
			//.setResponseType(toGrpcCaptureResponseType(CaptureResponseType.CHANGE))
			.build());
	}

	@Override
	public void onComplete() {
		Assert.notNull(this.subscription, "Observer hasn't yet been subscribed!");
		responseObserver.onCompleted();
	}

	@Override
	public void onError(Throwable throwable) {
		onComplete();
	}

}
