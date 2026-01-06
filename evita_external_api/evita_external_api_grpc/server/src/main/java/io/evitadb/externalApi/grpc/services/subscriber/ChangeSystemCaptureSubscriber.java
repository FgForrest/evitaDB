/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.exception.GrpcServerCancellationException;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A private static class implementing the {@link Subscriber} interface to handle
 * system change capture subscriptions. This class coordinates the receipt of change events,
 * processes them, and forwards the results to a response observer.
 *
 * It is specifically designed for managing a subscription lifecycle and handling events
 * of type {@link ChangeSystemCapture} within the context of gRPC communication.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class ChangeSystemCaptureSubscriber implements Subscriber<ChangeSystemCapture>, AutoCloseable {
	private final StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver;
	private final CompletableFuture<Subscription> subscriptionFuture;
	private final AtomicBoolean isClosed = new AtomicBoolean(false);
	private Subscription subscription;

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		this.subscriptionFuture.complete(subscription);

		final GrpcRegisterSystemChangeCaptureResponse.Builder response = GrpcRegisterSystemChangeCaptureResponse
			.newBuilder();
		if (subscription instanceof ChangeCaptureSubscription ccs) {
			response.setUuid(EvitaDataTypesConverter.toGrpcUuid(ccs.getSubscriptionId()));
		}
		this.responseObserver.onNext(
			response
				.setResponseType(GrpcCaptureResponseType.ACKNOWLEDGEMENT)
				.build()
		);
		subscription.request(1);
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		this.responseObserver.onNext(
			GrpcRegisterSystemChangeCaptureResponse
				.newBuilder()
				.setCapture(ChangeCaptureConverter.toGrpcChangeSystemCapture(item))
				.setResponseType(GrpcCaptureResponseType.CHANGE)
				.build()
		);
		this.subscription.request(1);
	}

	@Override
	public void onError(Throwable throwable) {
		if (this.isClosed.compareAndSet(false, true)) {
			this.subscriptionFuture.completeExceptionally(throwable);
			this.responseObserver.onError(throwable);
		}
	}

	@Override
	public void onComplete() {
		if (this.isClosed.compareAndSet(false, true)) {
			this.responseObserver.onCompleted();
		}
	}

	@Override
	public void close() throws Exception {
		if (this.isClosed.compareAndSet(false, true)) {
			final GrpcServerCancellationException throwable = new GrpcServerCancellationException();
			this.subscriptionFuture.completeExceptionally(throwable);
			this.responseObserver.onError(throwable);
		} else {
			// already closed, everything is fine
		}
	}

}
