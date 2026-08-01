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

package io.evitadb.externalApi.grpc.requestResponse.cdc;

import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the mutation-history surface of {@link ChangeCaptureConverter}, covering the
 * paged request's anchor resolution and the transaction-header body round-trip.
 *
 * Both areas were defective and untested: the paged request derived its `sinceIndex` default from the
 * presence of `sinceVersion`, and the inverse capture converter dropped the `infrastructureMutation`
 * arm of the body oneof entirely.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ChangeCaptureConverter mutations-history surface")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(CDC)
class ChangeCaptureConverterMutationsHistoryTest {

	/**
	 * Stable timestamp used in capture round-trips so the assertions don't depend on wall clock.
	 */
	private static final OffsetDateTime TIMESTAMP =
		OffsetDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneOffset.UTC);

	/**
	 * Catalog version the paged handler resolves as the upper bound of the request when the client
	 * supplies no explicit anchor.
	 */
	private static final long REQUESTED_CATALOG_VERSION = 100L;

	@Nested
	@DisplayName("paged request anchor resolution")
	class PagedRequestAnchor {

		@ParameterizedTest(name = "direction {0}")
		@DisplayName("should apply the direction default for sinceIndex when only sinceVersion is set")
		@EnumSource(StreamDirection.class)
		void shouldApplyDirectionDefaultForIndexWhenOnlyVersionIsSet(
			final StreamDirection direction
		) {
			// the regression: this used to resolve to 0 - the slot reserved for the transaction header -
			// which the DATA and SCHEMA area predicates then discard, emptying the whole anchor version
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(42L))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequest(
				request, REQUESTED_CATALOG_VERSION, direction
			);

			assertEquals(42L, converted.sinceVersion());
			assertEquals(
				direction == StreamDirection.FORWARD ? 0 : Integer.MAX_VALUE,
				converted.sinceIndex()
			);
		}

		@ParameterizedTest(name = "direction {0}")
		@DisplayName("should apply the direction default for sinceIndex when neither anchor field is set")
		@EnumSource(StreamDirection.class)
		void shouldApplyDirectionDefaultForIndexWhenNothingIsSet(
			final StreamDirection direction
		) {
			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequest(
				GetMutationsHistoryPageRequest.newBuilder().build(), REQUESTED_CATALOG_VERSION, direction
			);

			assertEquals(REQUESTED_CATALOG_VERSION, converted.sinceVersion());
			assertEquals(
				direction == StreamDirection.FORWARD ? 0 : Integer.MAX_VALUE,
				converted.sinceIndex()
			);
		}

		@Test
		@DisplayName("should honour an explicit sinceIndex supplied together with sinceVersion")
		void shouldHonourExplicitIndexAlongsideVersion() {
			// this is the workaround the proto documentation advised while 1.1 was open - it must keep
			// producing exactly the same request after the fix, so no client following it regresses
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(42L))
				.setSinceIndex(Int32Value.of(7))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequest(
				request, REQUESTED_CATALOG_VERSION, StreamDirection.REVERSE
			);

			assertEquals(42L, converted.sinceVersion());
			assertEquals(7, converted.sinceIndex());
		}

		@Test
		@DisplayName("should honour an explicit sinceIndex supplied without sinceVersion")
		void shouldHonourExplicitIndexWithoutVersion() {
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceIndex(Int32Value.of(3))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequest(
				request, REQUESTED_CATALOG_VERSION, StreamDirection.REVERSE
			);

			assertEquals(REQUESTED_CATALOG_VERSION, converted.sinceVersion());
			assertEquals(3, converted.sinceIndex());
		}

		@Test
		@DisplayName("should clamp a sinceVersion beyond the requested catalog version instead of rejecting it")
		void shouldClampVersionBeyondRequestedCatalogVersion() {
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(REQUESTED_CATALOG_VERSION + 500L))
				.setSinceIndex(Int32Value.of(1))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequest(
				request, REQUESTED_CATALOG_VERSION, StreamDirection.REVERSE
			);

			assertEquals(REQUESTED_CATALOG_VERSION, converted.sinceVersion());
		}

		@Test
		@DisplayName("should keep a sinceVersion that is exactly the requested catalog version")
		void shouldKeepVersionEqualToRequestedCatalogVersion() {
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(REQUESTED_CATALOG_VERSION))
				.setSinceIndex(Int32Value.of(1))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequest(
				request, REQUESTED_CATALOG_VERSION, StreamDirection.REVERSE
			);

			assertEquals(REQUESTED_CATALOG_VERSION, converted.sinceVersion());
		}

	}

	@Nested
	@DisplayName("forward paged request anchor resolution")
	class ForwardPagedRequestAnchor {

		/**
		 * Catalog version the forward paged handler resolves as the lower-bound floor of the request.
		 */
		private static final long FLOOR_CATALOG_VERSION = 100L;

		@Test
		@DisplayName("should reset sinceIndex to 0 when an explicit sinceVersion is clamped up to the floor")
		void shouldResetIndexWhenVersionIsClampedUpToFloor() {
			// the regression: a stale index meant for the client's requested (too-low) version was carried
			// over onto the clamped floor version, silently skipping that version's header and early records
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(FLOOR_CATALOG_VERSION - 10L))
				.setSinceIndex(Int32Value.of(7))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequestForward(
				request, FLOOR_CATALOG_VERSION
			);

			assertEquals(FLOOR_CATALOG_VERSION, converted.sinceVersion());
			assertEquals(0, converted.sinceIndex());
		}

		@Test
		@DisplayName("should reset sinceIndex to 0 when sinceVersion is left unset entirely")
		void shouldResetIndexWhenVersionIsUnset() {
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceIndex(Int32Value.of(7))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequestForward(
				request, FLOOR_CATALOG_VERSION
			);

			assertEquals(FLOOR_CATALOG_VERSION, converted.sinceVersion());
			assertEquals(0, converted.sinceIndex());
		}

		@Test
		@DisplayName("should honour an explicit sinceIndex when sinceVersion is above the floor, unclamped")
		void shouldHonourIndexWhenVersionIsUnclamped() {
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(FLOOR_CATALOG_VERSION + 10L))
				.setSinceIndex(Int32Value.of(7))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequestForward(
				request, FLOOR_CATALOG_VERSION
			);

			assertEquals(FLOOR_CATALOG_VERSION + 10L, converted.sinceVersion());
			assertEquals(7, converted.sinceIndex());
		}

		@Test
		@DisplayName("should honour an explicit sinceIndex when sinceVersion exactly equals the floor")
		void shouldHonourIndexWhenVersionEqualsFloor() {
			final GetMutationsHistoryPageRequest request = GetMutationsHistoryPageRequest
				.newBuilder()
				.setSinceVersion(Int64Value.of(FLOOR_CATALOG_VERSION))
				.setSinceIndex(Int32Value.of(7))
				.build();

			final ChangeCatalogCaptureRequest converted = ChangeCaptureConverter.toChangeCaptureRequestForward(
				request, FLOOR_CATALOG_VERSION
			);

			assertEquals(FLOOR_CATALOG_VERSION, converted.sinceVersion());
			assertEquals(7, converted.sinceIndex());
		}

	}

	@Nested
	@DisplayName("transaction header body round-trip")
	class TransactionHeaderRoundTrip {

		@Test
		@DisplayName("should preserve mutationCount when a transaction header travels through both converters")
		void shouldPreserveMutationCountThroughBothConverters() {
			// mutationCount is the primitive a client needs to verify it received a whole transaction;
			// before the fix the inverse converter dropped the infrastructureMutation arm and the body
			// came back null, making the check unreachable through the Java client in any content mode
			final UUID transactionId = UUID.fromString("6c2f6d5a-1f8d-4a2e-9c1b-0f4a3d7e8b21");
			final TransactionMutation source = new TransactionMutation(
				transactionId, 42L, 13, 4096L, TIMESTAMP
			);
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				42L, 0, TIMESTAMP, CaptureArea.INFRASTRUCTURE, null, null, Operation.TRANSACTION, source
			);

			final GrpcChangeCatalogCapture grpc =
				ChangeCaptureConverter.toGrpcChangeCatalogCapture(capture, null);
			assertTrue(
				grpc.hasInfrastructureMutation(),
				"server must serialise a transaction header into the infrastructureMutation arm"
			);

			final ChangeCatalogCapture roundTripped = ChangeCaptureConverter.toChangeCatalogCapture(grpc);

			assertNotNull(roundTripped.body(), "transaction header body must survive the round-trip");
			final TransactionMutation result =
				assertInstanceOf(TransactionMutation.class, roundTripped.body());
			assertEquals(13, result.getMutationCount());
			assertEquals(42L, result.getVersion());
			assertEquals(4096L, result.getWalSizeInBytes());
			assertEquals(transactionId, result.getTransactionId());
			assertEquals(TIMESTAMP, result.getCommitTimestamp());
		}

		@Test
		@DisplayName("should preserve the capture envelope alongside the transaction header body")
		void shouldPreserveCaptureEnvelope() {
			final TransactionMutation source = new TransactionMutation(
				UUID.fromString("6c2f6d5a-1f8d-4a2e-9c1b-0f4a3d7e8b21"), 42L, 13, 4096L, TIMESTAMP
			);
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				42L, 0, TIMESTAMP, CaptureArea.INFRASTRUCTURE, null, null, Operation.TRANSACTION, source
			);

			final ChangeCatalogCapture roundTripped = ChangeCaptureConverter.toChangeCatalogCapture(
				ChangeCaptureConverter.toGrpcChangeCatalogCapture(capture, null)
			);

			assertEquals(42L, roundTripped.version());
			// the transaction header always occupies index 0 of its version
			assertEquals(0, roundTripped.index());
			assertEquals(CaptureArea.INFRASTRUCTURE, roundTripped.area());
			assertEquals(Operation.TRANSACTION, roundTripped.operation());
			assertEquals(TIMESTAMP, roundTripped.timestamp());
		}

		@Test
		@DisplayName("should return a null body when no body oneof arm is set at all")
		void shouldReturnNullBodyForHeaderOnlyCapture() {
			// CHANGE_HEADER content mode leaves every arm of the body oneof unset - that must stay a
			// legitimate null body rather than being mistaken for an unhandled mutation type
			final ChangeCatalogCapture capture = new ChangeCatalogCapture(
				42L, 0, TIMESTAMP, CaptureArea.INFRASTRUCTURE, null, null, Operation.TRANSACTION, null
			);

			final ChangeCatalogCapture roundTripped = ChangeCaptureConverter.toChangeCatalogCapture(
				ChangeCaptureConverter.toGrpcChangeCatalogCapture(capture, null)
			);

			assertEquals(42L, roundTripped.version());
			assertEquals(null, roundTripped.body());
		}

	}

}
