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

import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcHeartBeat;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.utils.IOUtils;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;

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
@Slf4j
@RequiredArgsConstructor
public class ChangeSystemCaptureSubscriber implements Subscriber<ChangeSystemCapture>, AutoCloseable {
	/**
	 * Flag ensuring the gRPC stream is finalized (via `onError` or `onCompleted`) exactly once
	 * across all termination paths ({@link #onError}, {@link #onComplete}, {@link #close}).
	 */
	private final AtomicBoolean streamFinalized = new AtomicBoolean(false);
	/**
	 * The gRPC stream observer used to send responses to the client.
	 */
	private final StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver;
	/**
	 * Future that is completed when the subscription is established, allowing the gRPC cancel
	 * handler to obtain the subscription reference for cancellation.
	 */
	private final CompletableFuture<Subscription> subscriptionFuture;
	/**
	 * Supplier that provides the current system version for heartbeat messages.
	 */
	private final LongSupplier versionSupplier;
	/**
	 * The Armeria service request context, used to extend the request timeout on activity.
	 */
	private final ServiceRequestContext serviceContext;
	/**
	 * The original response timeout in milliseconds, used to extend the timeout after each
	 * message or heartbeat.
	 */
	private final long responseTimeoutMillis;
	/**
	 * The delay between heartbeat messages in milliseconds — computed as 5 seconds less than
	 * the response timeout, clamped to [1 second, 5 minutes].
	 */
	private final long heartBeatDelay;
	/**
	 * Scheduled task that periodically sends heartbeat messages to keep the gRPC stream alive.
	 */
	private final DelayedAsyncTask heartBeatTask;
	/**
	 * The active subscription from the publisher, set during {@link #onSubscribe(Subscription)}.
	 */
	private Subscription subscription;
	/**
	 * Monotonically increasing counter for heartbeat message indexing. Atomic because
	 * it is accessed from both the publisher thread ({@link #onNext}) and the scheduler
	 * thread ({@link #sendHeartbeat}) via {@link #buildHeartBeatMessage()}.
	 */
	private final AtomicLong index = new AtomicLong(0L);

	public ChangeSystemCaptureSubscriber(
		@Nonnull Scheduler scheduler,
		@Nonnull StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver,
		@Nonnull CompletableFuture<Subscription> subscriptionFuture,
		@Nonnull LongSupplier versionSupplier,
		@Nonnull ServiceRequestContext serviceContext
	) {
		this.responseObserver = responseObserver;
		this.subscriptionFuture = subscriptionFuture;
		this.versionSupplier = versionSupplier;
		this.serviceContext = serviceContext;
		this.responseTimeoutMillis = this.serviceContext.requestTimeoutMillis();
		// calculate heartbeat delay to be 5 seconds less than request timeout,
		// but at least 1 second and at most 5 minutes
		this.heartBeatDelay = Math.min(Math.max(this.responseTimeoutMillis - 5000L, 1000L), 300000L);
		this.heartBeatTask = new DelayedAsyncTask(
			null,
			"System Subscriber Heartbeat",
			scheduler,
			this::sendHeartbeat,
			this.heartBeatDelay,
			TimeUnit.MILLISECONDS
		);
		this.heartBeatTask.schedule();
	}

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
				.setHeartBeat(buildHeartBeatMessage())
				.build()
		);
		subscription.request(1);
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		if (this.streamFinalized.get()) {
			return;
		}
		try {
			this.responseObserver.onNext(
				GrpcRegisterSystemChangeCaptureResponse
					.newBuilder()
					.setCapture(ChangeCaptureConverter.toGrpcChangeSystemCapture(item))
					.setResponseType(GrpcCaptureResponseType.CHANGE)
					.build()
			);
		} catch (Exception ex) {
			log.debug("CDC onNext failed (stream likely finalized concurrently): {}", ex.getMessage());
			this.subscription.cancel();
			return;
		}
		this.serviceContext.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofMillis(this.responseTimeoutMillis));
		this.subscription.request(1);
	}

	@Override
	public void onError(Throwable throwable) {
		if (this.streamFinalized.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.heartBeatTask::close);
			this.subscriptionFuture.completeExceptionally(throwable);
			this.responseObserver.onError(throwable);
		}
	}

	@Override
	public void onComplete() {
		if (this.streamFinalized.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.heartBeatTask::close);
			this.responseObserver.onCompleted();
		}
	}

	@Override
	public void close() {
		// signal the gRPC client that the stream was forcibly terminated
		if (this.streamFinalized.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.heartBeatTask::close);
			try {
				this.responseObserver.onError(
					Status.UNAVAILABLE
						.withDescription("CDC stream has been terminated by the server.")
						.asRuntimeException()
				);
			} catch (Exception ex) {
				log.debug("Failed to send UNAVAILABLE error to CDC client: {}", ex.getMessage(), ex);
			}
		}
	}

	/**
	 * Sends a heartbeat response to the client. The heartbeat contains information
	 * such as the unique subscription ID (if available) and the generated heartbeat message.
	 * This method also schedules the next heartbeat at a regular interval.
	 *
	 * @return the delay (in milliseconds) until the next heartbeat is scheduled
	 */
	private long sendHeartbeat() {
		if (this.streamFinalized.get()) {
			return -1L;
		}
		final GrpcRegisterSystemChangeCaptureResponse.Builder response = GrpcRegisterSystemChangeCaptureResponse
			.newBuilder();
		if (this.subscription instanceof ChangeCaptureSubscription ccs) {
			response.setUuid(toGrpcUuid(ccs.getSubscriptionId()));
		}
		try {
			this.responseObserver.onNext(
				response
					.setResponseType(GrpcCaptureResponseType.HEARTBEAT)
					.setHeartBeat(buildHeartBeatMessage())
					.build()
			);
		} catch (Exception ex) {
			log.debug("Heartbeat send failed (stream likely finalized concurrently): {}", ex.getMessage());
			return -1L;
		}
		this.serviceContext.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofMillis(this.responseTimeoutMillis));
		// plan the next heartbeat at regular interval
		return 0L;
	}

	/**
	 * Constructs a new {@link GrpcHeartBeat} message with updated heartbeat data.
	 * The message includes the current index, timestamp, last observed version,
	 * and the delay in milliseconds to the next heartbeat.
	 *
	 * @return a newly constructed {@link GrpcHeartBeat} message containing the latest heartbeat information
	 */
	@Nonnull
	private GrpcHeartBeat buildHeartBeatMessage() {
		return GrpcHeartBeat.newBuilder()
			.setIndex(this.index.getAndIncrement())
			.setTimestamp(toGrpcOffsetDateTime(OffsetDateTime.now()))
			.setLastObservedVersion(this.versionSupplier.getAsLong())
			.setMillisToNextHeartbeat(this.heartBeatDelay)
			.build();
	}

}
