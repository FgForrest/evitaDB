/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter.toChangeCatalogCapture;
import static io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter.toHeartBeat;

/**
 * Internal subscriber of one catalog change capture stream. On top of what {@link ClientChangeCaptureSubscriber}
 * does for every stream, it knows which **incarnation** of the catalog its stream is bound to, and keeps the
 * consumer's resume position honest about it:
 *
 * - every delivered {@link ChangeCatalogCapture} carries {@link ChangeCatalogCapture#catalogId()} - the server
 *   sends the identity once, on the acknowledgement, rather than on each capture, so it is stamped here;
 * - when the acknowledgement does not carry the identity, the server predates it and ignored
 *   {@link ChangeCatalogCaptureRequest#catalogId()} - the check the server would have done is done here instead,
 *   against the catalog of the session the stream was registered in, before any capture can arrive.
 *
 * The identity is per stream, never per publisher: one {@link ClientChangeCatalogCaptureProcessor} serves several
 * streams, each registered separately and possibly in a different session - a stream opened after the catalog was
 * replaced is bound to the new incarnation while its older sibling is still bound to the previous one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public class ClientChangeCatalogCaptureSubscriber
	extends ClientChangeCaptureSubscriber<ChangeCatalogCapture, GrpcRegisterChangeCatalogCaptureRequest, GrpcRegisterChangeCatalogCaptureResponse> {

	/**
	 * The request the stream was registered with - its resume position is what the acknowledgement is checked
	 * against, and what a refusal reports back.
	 */
	private final ChangeCatalogCaptureRequest request;
	/**
	 * Identity of the catalog incarnation the captures of this stream belong to - empty until the acknowledgement
	 * is accepted, which happens before the server may push the first capture. Shared with the capture decoding
	 * function handed to the superclass, which cannot reference this instance while it is being constructed.
	 */
	private final AtomicReference<UUID> streamCatalogId;
	/**
	 * Identity of the catalog the session registering the stream is bound to - set by the stream initializer
	 * before it starts the RPC, so that it is in place whenever the acknowledgement arrives.
	 */
	@Nullable private volatile UUID registeringCatalogId;

	/**
	 * Creates the internal subscriber of one catalog change capture stream.
	 *
	 * @param delegate          downstream subscriber that receives the captures
	 * @param request           the request the stream is registered with
	 * @param streamingTimeout  per-message response deadline applied after every message
	 * @param flowControlWindow number of captures the server may push without further acknowledgement
	 */
	public ClientChangeCatalogCaptureSubscriber(
		@Nonnull Flow.Subscriber<? super ChangeCatalogCapture> delegate,
		@Nonnull ChangeCatalogCaptureRequest request,
		@Nonnull Duration streamingTimeout,
		int flowControlWindow
	) {
		this(delegate, request, new AtomicReference<>(), streamingTimeout, flowControlWindow);
	}

	/**
	 * Creates the internal subscriber with the holder of the stream identity the capture decoding reads.
	 *
	 * @param delegate          downstream subscriber that receives the captures
	 * @param request           the request the stream is registered with
	 * @param streamCatalogId   holder of the identity the captures are stamped with
	 * @param streamingTimeout  per-message response deadline applied after every message
	 * @param flowControlWindow number of captures the server may push without further acknowledgement
	 */
	private ClientChangeCatalogCaptureSubscriber(
		@Nonnull Flow.Subscriber<? super ChangeCatalogCapture> delegate,
		@Nonnull ChangeCatalogCaptureRequest request,
		@Nonnull AtomicReference<UUID> streamCatalogId,
		@Nonnull Duration streamingTimeout,
		int flowControlWindow
	) {
		super(
			delegate,
			ClientChangeCatalogCaptureSubscriber::deserializeHeartBeat,
			itemResponse -> deserializeCapture(itemResponse, streamCatalogId.get()),
			streamingTimeout,
			flowControlWindow
		);
		this.request = request;
		this.streamCatalogId = streamCatalogId;
	}

	/**
	 * Decodes the heartbeat carried by an acknowledgement or a heartbeat response.
	 *
	 * @param itemResponse the response received from the server
	 * @return the heartbeat, or empty when the response carries a capture
	 */
	@Nonnull
	private static Optional<HeartBeat> deserializeHeartBeat(@Nonnull GrpcRegisterChangeCatalogCaptureResponse itemResponse) {
		if (itemResponse.getResponseType() == GrpcCaptureResponseType.ACKNOWLEDGEMENT
			|| itemResponse.getResponseType() == GrpcCaptureResponseType.HEARTBEAT) {
			return Optional.of(toHeartBeat(itemResponse.getUuid(), itemResponse.getHeartBeat()));
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Decodes the capture carried by a change response and stamps it with the identity of the stream.
	 *
	 * @param itemResponse    the response received from the server
	 * @param streamCatalogId identity of the catalog incarnation the stream is bound to
	 * @return the capture, or empty when the response is an acknowledgement or a heartbeat
	 */
	@Nonnull
	private static Optional<ChangeCatalogCapture> deserializeCapture(
		@Nonnull GrpcRegisterChangeCatalogCaptureResponse itemResponse,
		@Nullable UUID streamCatalogId
	) {
		if (itemResponse.getResponseType() == GrpcCaptureResponseType.CHANGE) {
			return Optional.of(toChangeCatalogCapture(itemResponse.getCapture(), streamCatalogId));
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Records the identity of the catalog the session registering this stream is bound to. Must be called before
	 * the RPC is started - the acknowledgement may arrive on another thread before the starting call returns.
	 *
	 * @param catalogId identity of the registering session's catalog
	 */
	public void bindRegisteringCatalogId(@Nonnull UUID catalogId) {
		this.registeringCatalogId = catalogId;
	}

	/**
	 * Returns the identity of the catalog incarnation the captures of this stream belong to.
	 *
	 * @return the identity, or empty until the acknowledgement has been accepted
	 */
	@Nonnull
	public Optional<UUID> getStreamCatalogId() {
		return Optional.ofNullable(this.streamCatalogId.get());
	}

	/**
	 * Establishes the identity of the stream from the acknowledgement. A server that knows the identity names it,
	 * and has already refused a request expecting another one, so its word is taken. A server that predates the
	 * identity leaves it out and ignored the expectation - then the stream belongs to the catalog of the session
	 * it was registered in, and a request expecting another incarnation is refused here, exactly as a newer server
	 * would have refused it.
	 *
	 * @param acknowledgement the raw acknowledgement response
	 * @param heartBeat       the heartbeat decoded from it - its version is the catalog version a refusal reports
	 * @throws ChangeCaptureResumePositionInvalidException when the request expects a different incarnation than
	 *                                                     the one the stream is bound to
	 */
	@Override
	protected void verifyAcknowledgement(
		@Nonnull GrpcRegisterChangeCatalogCaptureResponse acknowledgement,
		@Nonnull HeartBeat heartBeat
	) {
		if (acknowledgement.hasCatalogId()) {
			this.streamCatalogId.set(EvitaDataTypesConverter.toUuid(acknowledgement.getCatalogId()));
			return;
		}
		final UUID theRegisteringCatalogId = this.registeringCatalogId;
		Assert.isPremiseValid(
			theRegisteringCatalogId != null,
			"The identity of the catalog the change capture stream was registered in must be bound before " +
				"the stream is started!"
		);
		final UUID expectedCatalogId = this.request.catalogId();
		if (expectedCatalogId != null && !expectedCatalogId.equals(theRegisteringCatalogId)) {
			throw new ChangeCaptureResumePositionInvalidException(
				Reason.DIFFERENT_INCARNATION,
				theRegisteringCatalogId,
				heartBeat.lastObservedVersion(),
				// an older server does not tell, and a guess would mislead a consumer reading it as retention
				null,
				expectedCatalogId,
				this.request.sinceVersion(),
				this.request.sinceIndex()
			);
		}
		this.streamCatalogId.set(theRegisteringCatalogId);
	}

}
