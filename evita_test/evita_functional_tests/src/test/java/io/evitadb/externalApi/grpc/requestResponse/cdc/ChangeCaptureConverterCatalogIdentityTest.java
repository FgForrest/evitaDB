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

import com.google.protobuf.Int64Value;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the catalog identity of a change capture resume position travels with every request that carries
 * a position - the subscription and both history reads - in both directions, and that an absent identity stays
 * absent rather than turning into some default an older client never asked for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ChangeCaptureConverter - catalog identity of the resume position")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(CDC)
class ChangeCaptureConverterCatalogIdentityTest {
	private static final UUID CATALOG_ID = UUID.fromString("3c2b1a09-8f7e-4d6c-9b5a-493827160504");

	/**
	 * Creates a request resuming after version 40 of the passed incarnation.
	 *
	 * @param catalogId the expected identity, `null` for none
	 * @return the request
	 */
	@Nonnull
	private static ChangeCatalogCaptureRequest request(@Nullable UUID catalogId) {
		return ChangeCatalogCaptureRequest.builder()
			.catalogId(catalogId)
			.sinceVersion(40L)
			.sinceIndex(2)
			.content(ChangeCaptureContent.BODY)
			.build();
	}

	/**
	 * Asserts that the converted request carries the resume position of {@link #request(UUID)} with
	 * {@link #CATALOG_ID} - the criteria are compared by other tests, an absent array comes back empty.
	 *
	 * @param converted the request converted back from the gRPC message
	 */
	private static void assertSamePosition(@Nonnull ChangeCatalogCaptureRequest converted) {
		assertEquals(CATALOG_ID, converted.catalogId());
		assertEquals(40L, converted.sinceVersion());
		assertEquals(2, converted.sinceIndex());
	}

	@Nested
	@DisplayName("Subscription request")
	class Subscription {

		@Test
		@DisplayName("should carry the expected identity to the server and back")
		void shouldRoundTripExpectedIdentity() {
			final GrpcRegisterChangeCatalogCaptureRequest grpcRequest =
				ChangeCaptureConverter.toGrpcChangeCatalogCaptureRequest(request(CATALOG_ID));

			assertTrue(grpcRequest.hasCatalogId());
			assertSamePosition(ChangeCaptureConverter.toChangeCatalogCaptureRequest(grpcRequest));
		}

		@Test
		@DisplayName("should leave the identity unset when the request states none")
		void shouldLeaveIdentityUnsetWhenAbsent() {
			final GrpcRegisterChangeCatalogCaptureRequest grpcRequest =
				ChangeCaptureConverter.toGrpcChangeCatalogCaptureRequest(request(null));

			assertFalse(grpcRequest.hasCatalogId());
			assertNull(ChangeCaptureConverter.toChangeCatalogCaptureRequest(grpcRequest).catalogId());
		}

	}

	@Nested
	@DisplayName("Streamed history request")
	class StreamedHistory {

		@Test
		@DisplayName("should carry the expected identity to the server and back")
		void shouldRoundTripExpectedIdentity() {
			final GetMutationsHistoryRequest grpcRequest = ChangeCaptureConverter.toGrpcChangeCaptureRequest(
				request(CATALOG_ID)
			);

			assertTrue(grpcRequest.hasCatalogId());
			assertSamePosition(ChangeCaptureConverter.toChangeCaptureRequest(grpcRequest));
		}

		@Test
		@DisplayName("should leave the identity unset when the request states none")
		void shouldLeaveIdentityUnsetWhenAbsent() {
			final GetMutationsHistoryRequest grpcRequest = ChangeCaptureConverter.toGrpcChangeCaptureRequest(
				request(null)
			);

			assertFalse(grpcRequest.hasCatalogId());
			assertNull(ChangeCaptureConverter.toChangeCaptureRequest(grpcRequest).catalogId());
		}

	}

	@Nested
	@DisplayName("Paged history request")
	class PagedHistory {

		@Test
		@DisplayName("should pass the expected identity on in both directions")
		void shouldPassExpectedIdentityOn() {
			final GetMutationsHistoryPageRequest grpcRequest = GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(40L))
				.setCatalogId(toGrpcUuid(CATALOG_ID))
				.build();

			assertEquals(
				CATALOG_ID,
				ChangeCaptureConverter.toChangeCaptureRequest(grpcRequest, 100L, StreamDirection.REVERSE).catalogId()
			);
			assertEquals(
				CATALOG_ID, ChangeCaptureConverter.toChangeCaptureRequestForward(grpcRequest, 1L).catalogId()
			);
		}

		@Test
		@DisplayName("should state no identity when the page request carries none")
		void shouldStateNoIdentityWhenAbsent() {
			final GetMutationsHistoryPageRequest grpcRequest = GetMutationsHistoryPageRequest.newBuilder().build();

			assertNull(
				ChangeCaptureConverter.toChangeCaptureRequest(grpcRequest, 100L, StreamDirection.REVERSE).catalogId()
			);
			assertNull(ChangeCaptureConverter.toChangeCaptureRequestForward(grpcRequest, 1L).catalogId());
		}

	}

}
