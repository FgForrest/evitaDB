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

package io.evitadb.externalApi.grpc.requestResponse;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the fields of a refused change capture resume position and of unavailable temporal data survive
 * the gRPC boundary: the server's error mapping ({@link GlobalExceptionHandlerInterceptor}) describes them in the
 * {@link ErrorInfo} metadata and {@link ErrorInfoConverter} rebuilds the very same exception on the client - and
 * that every other status, malformed metadata included, is left to the handling the client had before.
 *
 * The round trips go through the real server mapping into a real `StatusRuntimeException` with trailers, the same
 * object a gRPC client observer receives; only the malformed cases hand-build the status, because no server
 * produces them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC error details of recognised exceptions")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(CDC)
class ErrorInfoConverterTest {
	/**
	 * Identity of the catalog incarnation the refusals report.
	 */
	private static final UUID CATALOG_ID = UUID.fromString("1f0e2d3c-4b5a-4968-8776-a5b4c3d2e1f0");
	/**
	 * Identity of the catalog incarnation the refused requests expected.
	 */
	private static final UUID REQUESTED_CATALOG_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

	/**
	 * Sends the exception through the server's error mapping and returns the status exception the client
	 * observer would receive - without the server-side cause, which does not cross the wire.
	 *
	 * @param exception the exception raised on the server
	 * @return the status exception carrying the mapped status and its trailers
	 */
	@Nonnull
	private static StatusRuntimeException sentByServer(@Nonnull Throwable exception) {
		final AtomicReference<Throwable> emitted = new AtomicReference<>();
		GlobalExceptionHandlerInterceptor.sendErrorToClient(
			exception,
			new StreamObserver<Object>() {
				@Override
				public void onNext(Object value) {
					throw new AssertionError("No message expected, only the error.");
				}

				@Override
				public void onError(Throwable throwable) {
					emitted.set(throwable);
				}

				@Override
				public void onCompleted() {
					throw new AssertionError("No completion expected, only the error.");
				}
			}
		);
		final StatusRuntimeException serverSide = assertInstanceOf(StatusRuntimeException.class, emitted.get());
		// the server-side cause never crosses the wire - the client has nothing but the status and its trailers
		return new StatusRuntimeException(serverSide.getStatus().withCause(null), serverSide.getTrailers());
	}

	/**
	 * Hand-builds a status carrying an {@link ErrorInfo} with the passed metadata.
	 *
	 * @param code     the status code
	 * @param metadata the error info metadata
	 * @return the status exception
	 */
	@Nonnull
	private static StatusRuntimeException statusWith(@Nonnull Code code, @Nonnull Map<String, String> metadata) {
		return StatusProto.toStatusRuntimeException(
			com.google.rpc.Status.newBuilder()
				.setCode(code.value())
				.setMessage("hand-built")
				.addDetails(
					Any.pack(
						ErrorInfo.newBuilder()
							.setReason("hand-built")
							.setDomain("hand-built")
							.putAllMetadata(metadata)
							.build()
					)
				)
				.build()
		);
	}

	/**
	 * Returns the metadata the server produces for a fully populated refusal.
	 *
	 * @return a mutable copy of the metadata
	 */
	@Nonnull
	private static Map<String, String> wellFormedRefusalMetadata() {
		return new HashMap<>(
			ErrorInfoConverter.toErrorInfoMetadata(
				new ChangeCaptureResumePositionInvalidException(
					Reason.DIFFERENT_INCARNATION, CATALOG_ID, 12L, 3L, REQUESTED_CATALOG_ID, 40L, 7
				)
			)
		);
	}

	/**
	 * Asserts that the rebuilt refusal equals the original in every field and in its message.
	 *
	 * @param expected the exception the server raised
	 * @param actual   the exception the client rebuilt
	 */
	private static void assertSameRefusal(
		@Nonnull ChangeCaptureResumePositionInvalidException expected,
		@Nonnull EvitaInvalidUsageException actual
	) {
		final ChangeCaptureResumePositionInvalidException rebuilt =
			assertInstanceOf(ChangeCaptureResumePositionInvalidException.class, actual);
		assertEquals(expected.getReason(), rebuilt.getReason());
		assertEquals(expected.getCatalogId(), rebuilt.getCatalogId());
		assertEquals(expected.getCurrentCatalogVersion(), rebuilt.getCurrentCatalogVersion());
		assertEquals(expected.getCatalogVersion(), rebuilt.getCatalogVersion());
		assertEquals(expected.getRequestedCatalogId(), rebuilt.getRequestedCatalogId());
		assertEquals(expected.getRequestedSinceVersion(), rebuilt.getRequestedSinceVersion());
		assertEquals(expected.getRequestedSinceIndex(), rebuilt.getRequestedSinceIndex());
		assertEquals(expected.getPublicMessage(), rebuilt.getPublicMessage());
	}

	@Nested
	@DisplayName("Round trip through the server's error mapping")
	class RoundTrip {

		@Test
		@DisplayName("should rebuild a refused resume position with every field and the same message")
		void shouldRebuildRefusedResumePositionWithAllFields() {
			for (Reason reason : Reason.values()) {
				final ChangeCaptureResumePositionInvalidException refusal = new ChangeCaptureResumePositionInvalidException(
					reason, CATALOG_ID, 12L, 3L, REQUESTED_CATALOG_ID, 40L, 7
				);
				final StatusRuntimeException status = sentByServer(refusal);
				assertEquals(Code.INVALID_ARGUMENT, status.getStatus().getCode());
				final EvitaInvalidUsageException rebuilt = ErrorInfoConverter.toTypedException(status);
				assertNotNull(rebuilt, "The refusal for reason " + reason + " was not rebuilt.");
				assertSameRefusal(refusal, rebuilt);
			}
		}

		@Test
		@DisplayName("should rebuild a refused resume position whose optional fields are absent")
		void shouldRebuildRefusedResumePositionWithoutOptionalFields() {
			final ChangeCaptureResumePositionInvalidException refusal = new ChangeCaptureResumePositionInvalidException(
				Reason.AHEAD_OF_CATALOG, CATALOG_ID, 12L, null, null, null, null
			);

			final EvitaInvalidUsageException rebuilt = ErrorInfoConverter.toTypedException(sentByServer(refusal));

			assertNotNull(rebuilt);
			assertSameRefusal(refusal, rebuilt);
		}

		@Test
		@DisplayName("should rebuild a refusal raised after a failed read as itself, without the server-side cause")
		void shouldRebuildRefusalRaisedAfterFailedRead() {
			final ChangeCaptureResumePositionInvalidException refusal = new ChangeCaptureResumePositionInvalidException(
				Reason.OUTSIDE_RETENTION, CATALOG_ID, 12L, 3L, null, 1L, 0, new IllegalStateException("purged")
			);

			final EvitaInvalidUsageException rebuilt = ErrorInfoConverter.toTypedException(sentByServer(refusal));

			assertNotNull(rebuilt);
			assertSameRefusal(refusal, rebuilt);
			assertNull(rebuilt.getCause(), "The server-side cause does not cross the wire.");
		}

		@Test
		@DisplayName("should rebuild unavailable temporal data reporting a version, a moment or nothing")
		void shouldRebuildTemporalDataNotAvailableInEveryVariant() {
			final TemporalDataNotAvailableException[] variants = {
				new TemporalDataNotAvailableException(5L),
				new TemporalDataNotAvailableException(5L, new IllegalStateException("purged")),
				new TemporalDataNotAvailableException(OffsetDateTime.of(2026, 10, 8, 14, 32, 0, 0, ZoneOffset.UTC)),
				new TemporalDataNotAvailableException()
			};
			for (TemporalDataNotAvailableException original : variants) {
				final EvitaInvalidUsageException rebuilt = ErrorInfoConverter.toTypedException(sentByServer(original));
				assertNotNull(rebuilt, "Not rebuilt: " + original.getMessage());
				final TemporalDataNotAvailableException typed = assertInstanceOf(
					TemporalDataNotAvailableException.class, rebuilt
				);
				assertSame(TemporalDataNotAvailableException.class, typed.getClass());
				assertEquals(original.getCatalogVersion(), typed.getCatalogVersion());
				assertEquals(original.getOffsetDateTime(), typed.getOffsetDateTime());
				assertEquals(original.getPublicMessage(), typed.getPublicMessage());
			}
		}

		@Test
		@DisplayName("should find the status in the cause chain of the throwable it is handed")
		void shouldRebuildFromStatusInCauseChain() {
			final StatusRuntimeException status = sentByServer(new TemporalDataNotAvailableException(5L));

			final EvitaInvalidUsageException rebuilt = ErrorInfoConverter.toTypedException(
				new RuntimeException("wrapped", status)
			);

			assertInstanceOf(TemporalDataNotAvailableException.class, rebuilt);
		}

	}

	@Nested
	@DisplayName("Statuses left to the existing handling")
	class LeftAlone {

		@Test
		@DisplayName("should not describe nor rebuild any other client error")
		void shouldNotRebuildOtherClientError() {
			final EvitaInvalidUsageException other = new EvitaInvalidUsageException("Something else is wrong.");
			assertTrue(ErrorInfoConverter.toErrorInfoMetadata(other).isEmpty());

			assertNull(ErrorInfoConverter.toTypedException(sentByServer(other)));
		}

		@Test
		@DisplayName("should not describe nor rebuild a subtype of unavailable temporal data it does not know")
		void shouldNotRebuildUnknownTemporalDataSubtype() {
			// rebuilding it as its parent would silently drop whatever the subtype adds
			final TemporalDataNotAvailableException unknownSubtype = new TemporalDataNotAvailableException(5L) {
			};
			assertTrue(ErrorInfoConverter.toErrorInfoMetadata(unknownSubtype).isEmpty());

			assertNull(ErrorInfoConverter.toTypedException(sentByServer(unknownSubtype)));
		}

		@Test
		@DisplayName("should not rebuild an error of a server that sends no metadata")
		void shouldNotRebuildErrorWithoutMetadata() {
			// an older server names the exception in the error info domain, but sends none of its fields - rebuilding
			// it from the domain alone would invent the "no historical data" variant with the wrong message
			assertNull(ErrorInfoConverter.toTypedException(statusWith(Code.INVALID_ARGUMENT, Map.of())));
		}

		@Test
		@DisplayName("should not rebuild transport statuses and server faults")
		void shouldNotRebuildTransportStatuses() {
			assertNull(ErrorInfoConverter.toTypedException(Status.UNAVAILABLE.asRuntimeException()));
			assertNull(ErrorInfoConverter.toTypedException(Status.DEADLINE_EXCEEDED.asRuntimeException()));
			assertNull(ErrorInfoConverter.toTypedException(Status.CANCELLED.asRuntimeException()));
			assertNull(ErrorInfoConverter.toTypedException(sentByServer(new GenericEvitaInternalError("boom"))));
			assertNull(ErrorInfoConverter.toTypedException(new IllegalStateException("not a status at all")));
		}

		@Test
		@DisplayName("should not rebuild well-formed metadata carried by a status that is not a client error")
		void shouldNotRebuildWellFormedMetadataUnderOtherCode() {
			assertNull(ErrorInfoConverter.toTypedException(statusWith(Code.UNAVAILABLE, wellFormedRefusalMetadata())));
		}

		@Test
		@DisplayName("should not rebuild a refusal whose metadata is malformed or incomplete")
		void shouldNotRebuildMalformedRefusal() {
			// sanity: the unmodified metadata is rebuilt, so every null below is caused by the single change
			assertNotNull(
				ErrorInfoConverter.toTypedException(statusWith(Code.INVALID_ARGUMENT, wellFormedRefusalMetadata()))
			);
			final String[][] corruptions = {
				{ErrorInfoConverter.REASON, "NO_SUCH_REASON"},
				{ErrorInfoConverter.CATALOG_ID, "not-a-uuid"},
				{ErrorInfoConverter.CURRENT_CATALOG_VERSION, "twelve"},
				{ErrorInfoConverter.OLDEST_AVAILABLE_CATALOG_VERSION, "3.5"},
				{ErrorInfoConverter.REQUESTED_CATALOG_ID, "not-a-uuid"},
				{ErrorInfoConverter.REQUESTED_SINCE_VERSION, ""},
				{ErrorInfoConverter.REQUESTED_SINCE_INDEX, "99999999999"},
				{ErrorInfoConverter.EXCEPTION_CLASS, "io.evitadb.api.exception.NoSuchException"}
			};
			for (String[] corruption : corruptions) {
				final Map<String, String> metadata = wellFormedRefusalMetadata();
				metadata.put(corruption[0], corruption[1]);
				assertNull(
					ErrorInfoConverter.toTypedException(statusWith(Code.INVALID_ARGUMENT, metadata)),
					"Rebuilt despite " + corruption[0] + "=" + corruption[1]
				);
			}
			for (String required : new String[]{
				ErrorInfoConverter.REASON, ErrorInfoConverter.CATALOG_ID, ErrorInfoConverter.CURRENT_CATALOG_VERSION
			}) {
				final Map<String, String> metadata = wellFormedRefusalMetadata();
				metadata.remove(required);
				assertNull(
					ErrorInfoConverter.toTypedException(statusWith(Code.INVALID_ARGUMENT, metadata)),
					"Rebuilt without " + required
				);
			}
		}

		@Test
		@DisplayName("should not rebuild unavailable temporal data whose metadata is malformed")
		void shouldNotRebuildMalformedTemporalDataNotAvailable() {
			final String exceptionClass = TemporalDataNotAvailableException.class.getName();
			assertNull(
				ErrorInfoConverter.toTypedException(
					statusWith(
						Code.INVALID_ARGUMENT,
						Map.of(
							ErrorInfoConverter.EXCEPTION_CLASS, exceptionClass,
							ErrorInfoConverter.CATALOG_VERSION, "5",
							ErrorInfoConverter.OFFSET_DATE_TIME, "2026-10-08T14:32Z"
						)
					)
				),
				"No server sends both alternatives."
			);
			assertNull(
				ErrorInfoConverter.toTypedException(
					statusWith(
						Code.INVALID_ARGUMENT,
						Map.of(ErrorInfoConverter.EXCEPTION_CLASS, exceptionClass, ErrorInfoConverter.CATALOG_VERSION, "five")
					)
				)
			);
			assertNull(
				ErrorInfoConverter.toTypedException(
					statusWith(
						Code.INVALID_ARGUMENT,
						Map.of(ErrorInfoConverter.EXCEPTION_CLASS, exceptionClass, ErrorInfoConverter.OFFSET_DATE_TIME, "yesterday")
					)
				)
			);
		}

	}

}
