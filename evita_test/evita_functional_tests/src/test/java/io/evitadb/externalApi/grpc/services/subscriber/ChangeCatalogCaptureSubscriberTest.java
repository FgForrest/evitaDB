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

package io.evitadb.externalApi.grpc.services.subscriber;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse;
import io.evitadb.test.TestConstants;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the catalog-specific part of the gRPC change capture stream: the acknowledgement and every heartbeat
 * name the catalog incarnation the subscription is bound to - which is what a client stamps the captures with, and
 * what tells it the server checked the expected identity - while the captures themselves do not repeat it. The
 * heartbeats report whatever the version supplier says, so a supplier following the incarnation is reported as is.
 *
 * The lifecycle shared with the system stream is covered by {@link AbstractChangeCaptureSubscriberTest}; the
 * heartbeat tick is driven directly, the scheduled one never fires within a test.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ChangeCatalogCaptureSubscriber - catalog identity on the stream")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(CDC)
class ChangeCatalogCaptureSubscriberTest implements TestConstants {
	/**
	 * Long enough that the heartbeat delay clamps to its 5-minute ceiling and the scheduled heartbeat never fires.
	 */
	private static final long LONG_TIMEOUT_MILLIS = 600_000L;
	private static final UUID CATALOG_ID = UUID.fromString("5d4c3b2a-1908-4f7e-8d6c-5b4a39281706");

	private Scheduler scheduler;
	@SuppressWarnings("unchecked")
	private final ServerCallStreamObserver<GrpcRegisterChangeCatalogCaptureResponse> responseObserver =
		mock(ServerCallStreamObserver.class);
	private final AtomicLong liveVersion = new AtomicLong(7L);
	private ChangeCatalogCaptureSubscriber subscriber;

	@BeforeEach
	void setUp() {
		this.scheduler = new Scheduler(ThreadPoolOptions.requestThreadPoolBuilder().minThreadCount(1).build());
		final ServiceRequestContext serviceContext = mock(ServiceRequestContext.class);
		final ServiceConfig serviceConfig = mock(ServiceConfig.class);
		final Server server = mock(Server.class);
		final ServerConfig serverConfig = mock(ServerConfig.class);
		when(serviceContext.requestTimeoutMillis()).thenReturn(LONG_TIMEOUT_MILLIS);
		when(serviceContext.config()).thenReturn(serviceConfig);
		when(serviceConfig.server()).thenReturn(server);
		when(server.config()).thenReturn(serverConfig);
		when(serverConfig.idleTimeoutMillis()).thenReturn(LONG_TIMEOUT_MILLIS);
		this.subscriber = new ChangeCatalogCaptureSubscriber(
			this.scheduler, TEST_CATALOG, CATALOG_ID, this.responseObserver, null, this.liveVersion::get, serviceContext
		);
	}

	@AfterEach
	void tearDown() {
		this.subscriber.close();
		this.scheduler.shutdownNow();
	}

	@Test
	@DisplayName("should name the incarnation on the acknowledgement and on heartbeats, never on captures")
	void shouldNameIncarnationOnAcknowledgementAndHeartbeatsOnly() {
		this.subscriber.onSubscribe(mock(Subscription.class));
		this.subscriber.onNext(
			new ChangeCatalogCapture(
				CATALOG_ID, 8L, 0, OffsetDateTime.now(ZoneOffset.UTC), CaptureArea.DATA, "product", 1,
				Operation.UPSERT, null
			)
		);
		this.liveVersion.set(9L);
		this.subscriber.sendHeartbeat();

		final ArgumentCaptor<GrpcRegisterChangeCatalogCaptureResponse> emitted =
			ArgumentCaptor.forClass(GrpcRegisterChangeCatalogCaptureResponse.class);
		verify(this.responseObserver, times(3)).onNext(emitted.capture());
		final List<GrpcRegisterChangeCatalogCaptureResponse> responses = emitted.getAllValues();

		final GrpcRegisterChangeCatalogCaptureResponse acknowledgement = responses.get(0);
		assertEquals(GrpcCaptureResponseType.ACKNOWLEDGEMENT, acknowledgement.getResponseType());
		assertTrue(acknowledgement.hasCatalogId(), "The client relies on the acknowledgement naming the incarnation.");
		assertEquals(CATALOG_ID, toUuid(acknowledgement.getCatalogId()));
		assertEquals(7L, acknowledgement.getHeartBeat().getLastObservedVersion());

		final GrpcRegisterChangeCatalogCaptureResponse change = responses.get(1);
		assertEquals(GrpcCaptureResponseType.CHANGE, change.getResponseType());
		assertFalse(change.hasCatalogId(), "A capture does not repeat the identity the acknowledgement named.");

		final GrpcRegisterChangeCatalogCaptureResponse heartbeat = responses.get(2);
		assertEquals(GrpcCaptureResponseType.HEARTBEAT, heartbeat.getResponseType());
		assertEquals(CATALOG_ID, toUuid(heartbeat.getCatalogId()));
		assertEquals(9L, heartbeat.getHeartBeat().getLastObservedVersion());
	}

}
