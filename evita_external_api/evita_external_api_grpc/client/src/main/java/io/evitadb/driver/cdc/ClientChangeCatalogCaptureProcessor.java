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
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse;
import io.evitadb.utils.Assert;
import io.grpc.stub.ClientResponseObserver;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Consumer;

/**
 * Implementation of {@link ClientChangeCapturePublisher} for the {@link ChangeCatalogCapture}.
 *
 * Every stream of the publisher gets its own {@link ClientChangeCatalogCaptureSubscriber}, which tracks the catalog
 * incarnation that particular stream is bound to - see that class for why the identity cannot be kept here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ClientChangeCatalogCaptureProcessor extends
	ClientChangeCapturePublisher<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse> {

	/**
	 * The request every stream of this publisher is registered with.
	 */
	private final ChangeCatalogCaptureRequest request;

	/**
	 * Creates the publisher of catalog change captures.
	 *
	 * @param queueSize         maximum number of captures buffered for each subscriber
	 * @param streamingTimeout  per-message response deadline of every stream
	 * @param executorService   executor delivering the captures to the subscribers
	 * @param request           the request every stream of this publisher is registered with
	 * @param streamInitializer starts the gRPC stream of one subscriber; it must bind the identity of the catalog of
	 *                          the session it registers the stream in
	 *                          ({@link ClientChangeCatalogCaptureSubscriber#bindRegisteringCatalogId}) before
	 *                          starting the RPC
	 * @param onCloseCallback   callback executed when the publisher is closed
	 * @see ClientChangeCapturePublisher#ClientChangeCapturePublisher(int, Duration, ExecutorService, Consumer, Consumer)
	 */
	public ClientChangeCatalogCaptureProcessor(
		int queueSize,
		@Nonnull Duration streamingTimeout,
		@Nonnull ExecutorService executorService,
		@Nonnull ChangeCatalogCaptureRequest request,
		@Nonnull Consumer<ClientChangeCatalogCaptureSubscriber> streamInitializer,
		@Nonnull Consumer<ClientChangeCapturePublisher<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse>> onCloseCallback
	) {
		super(
			queueSize, streamingTimeout, executorService,
			observer -> streamInitializer.accept(asCatalogCaptureSubscriber(observer)),
			onCloseCallback
		);
		this.request = request;
	}

	/**
	 * Narrows the observer the superclass hands to the stream initializer to the subscriber type this publisher
	 * creates in {@link #createInternalSubscriber}.
	 *
	 * @param observer the internal subscriber of the stream being initialized
	 * @return the same instance
	 */
	@Nonnull
	private static ClientChangeCatalogCaptureSubscriber asCatalogCaptureSubscriber(
		@Nonnull ClientResponseObserver<GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse> observer
	) {
		Assert.isPremiseValid(
			observer instanceof ClientChangeCatalogCaptureSubscriber,
			"The catalog change capture publisher initializes only the streams of the subscribers it creates itself!"
		);
		return (ClientChangeCatalogCaptureSubscriber) observer;
	}

	@Nonnull
	@Override
	protected ClientChangeCaptureSubscriber<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse> createInternalSubscriber(
		@Nonnull Subscriber<? super ChangeCatalogCapture> subscriber,
		@Nonnull Duration streamingTimeout,
		int flowControlWindow
	) {
		return new ClientChangeCatalogCaptureSubscriber(subscriber, this.request, streamingTimeout, flowControlWindow);
	}

}
