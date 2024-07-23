/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EvitaService gRPC functional test")
@ExtendWith(EvitaParameterResolver.class)
@Tag(FUNCTIONAL_TEST)
@Slf4j
class EvitaServiceFunctionalTest {
	private static final String GRPC_THOUSAND_PRODUCTS = "EvitaServiceFunctionalTest";
	private static final String DUMMY_CATALOG = "dummy-catalog";

	@DataSet(value = GRPC_THOUSAND_PRODUCTS, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		final GrpcClientBuilder clientBuilder = TestGrpcClientBuilderCreator.getBuilder(new ClientSessionInterceptor(), evitaServer.getExternalApiServer());
		final List<SealedEntity> entities = new TestDataProvider().generateEntities(evita, 10);
		return new DataCarrier(
			"entities", entities,
			"clientBuilder", clientBuilder
		);
	}

	@OnDataSetTearDown(GRPC_THOUSAND_PRODUCTS)
	void onDataSetTearDown(GrpcClientBuilder grpcClientBuilder) {

	}

	@AfterEach
	public void afterEach() {
		SessionIdHolder.reset();
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should throw an exception when sending non-existent catalog as parameter")
	void shouldThrowWhenAskingForNonExistingCatalog(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		assertThrows(
			StatusRuntimeException.class,
			() -> evitaBlockingStub.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName("non-existing-catalog")
				.build()
			)
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return sessionId when sending existing catalog as parameter")
	void shouldReturnSessionIdWhenExistingCatalogPassed(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);

		assertNotNull(response.get().getSessionId());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should get each time new session when asked")
	void shouldEachTimeGetNewReadOnlySession(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);
		assertNotNull(response.get().getSessionId());
		assertEquals(GrpcSessionType.READ_ONLY, response.get().getSessionType());

		final String sessionId1 = response.get().getSessionId();

		assertDoesNotThrow(executable);
		assertNotNull(response.get().getSessionId());
		assertEquals(GrpcSessionType.READ_ONLY, response.get().getSessionType());

		final String sessionId2 = response.get().getSessionId();

		assertNotEquals(sessionId1, sessionId2);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return sessionId of ReadWrite session when sending existing catalog as parameter")
	void shouldReturnSessionIdOfReadWriteSessionWhenExistingCatalogPassed(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createReadWriteSession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);

		assertNotNull(response.get().getSessionId());
		assertEquals(GrpcSessionType.READ_WRITE, response.get().getSessionType());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able concurrently create sessions and call SessionService using correct sessions specified by its id and type from multiple threads in parallel")
	void shouldBeAbleConcurrentlyCreateSessionsAndCallSessionService(GrpcClientBuilder clientBuilder) throws Exception {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final int numberOfThreads = 10;
		final int iterations = 100;
		final ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
		final CountDownLatch latch = new CountDownLatch(numberOfThreads);

		final AtomicReference<Exception> terminatingException = new AtomicReference<>();
		for (int i = 0; i < numberOfThreads; i++) {
			service.execute(() -> {
				try {
					for (int j = 0; j < iterations; j++) {
						final GrpcEvitaSessionResponse response = Math.random() < 0.5 ?
							evitaBlockingStub.createReadOnlySession(
								GrpcEvitaSessionRequest.newBuilder()
									.setCatalogName(TEST_CATALOG)
									.build()
							) :
							evitaBlockingStub.createReadWriteSession(
								GrpcEvitaSessionRequest.newBuilder()
									.setCatalogName(TEST_CATALOG)
									.build()
							);
						makeSessionCall(TEST_CATALOG, response.getSessionId(), clientBuilder);
					}
					latch.countDown();
				} catch (Exception ex) {
					terminatingException.set(ex);
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(60, TimeUnit.SECONDS), "Timeouted!");

		if (terminatingException.get() != null) {
			throw terminatingException.get();
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should get each time new ReadWrite session when asked")
	void shouldEachTimeGetNewReadWriteSession(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createReadWriteSession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);
		assertNotNull(response.get().getSessionId());
		assertEquals(GrpcSessionType.READ_WRITE, response.get().getSessionType());

		final String sessionId1 = response.get().getSessionId();

		assertDoesNotThrow(executable);
		assertNotNull(response.get().getSessionId());
		assertEquals(GrpcSessionType.READ_WRITE, response.get().getSessionType());

		final String sessionId2 = response.get().getSessionId();

		assertNotEquals(sessionId1, sessionId2);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should get BinaryReadOnly session when asked")
	void shouldGetBinaryReadOnlySession(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createBinaryReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);

		assertEquals(GrpcSessionType.BINARY_READ_ONLY, response.get().getSessionType());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should get BinaryReadWrite session when asked")
	void shouldGetBinaryReadWriteSession(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createBinaryReadWriteSession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);

		assertEquals(GrpcSessionType.BINARY_READ_WRITE, response.get().getSessionType());
	}

	@Test
	@UseDataSet(value = GRPC_THOUSAND_PRODUCTS, destroyAfterTest = true)
	@DisplayName("Should be able to create new catalog")
	void shouldBeAbleToCreateNewCatalog(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		assertFalse(evita.getCatalogNames().contains(DUMMY_CATALOG));
		final GrpcDefineCatalogResponse createCatalogResponse = evitaBlockingStub.defineCatalog(
			GrpcDefineCatalogRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);
		assertTrue(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
		assertTrue(createCatalogResponse.getSuccess());
		assertTrue(
			evitaBlockingStub.getCatalogNames(Empty.newBuilder().build())
				.getCatalogNamesList()
				.stream()
				.anyMatch(it -> it.equals(DUMMY_CATALOG))
		);
	}

	@Test
	@UseDataSet(value = GRPC_THOUSAND_PRODUCTS, destroyAfterTest = true)
	@DisplayName("Should be able to delete existing catalog")
	void shouldBeAbleToDeleteExisting(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		if (!evita.getCatalogNames().contains(DUMMY_CATALOG)) {
			assertNotNull(evita.defineCatalog(DUMMY_CATALOG));
		}
		assertTrue(evita.getCatalogNames().contains(DUMMY_CATALOG));

		final GrpcDeleteCatalogIfExistsResponse deleteCatalogResponse = evitaBlockingStub.deleteCatalogIfExists(
			GrpcDeleteCatalogIfExistsRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);

		assertTrue(deleteCatalogResponse.getSuccess());
		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
	}

	@Test
	@UseDataSet(value = GRPC_THOUSAND_PRODUCTS, destroyAfterTest = true)
	@DisplayName("Should be able to rename catalog")
	void shouldBeAbleToRenameCatalog(Evita evita, GrpcClientBuilder clientBuilder) {
		assertFalse(evita.getCatalogNames().contains(DUMMY_CATALOG));

		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final GrpcRenameCatalogResponse renameCatalogResponse = evitaBlockingStub.renameCatalog(
			GrpcRenameCatalogRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.setNewCatalogName(DUMMY_CATALOG)
				.build()
		);

		assertTrue(renameCatalogResponse.getSuccess());
		assertFalse(evita.getCatalogNames().contains(TEST_CATALOG));
		assertTrue(evita.getCatalogNames().contains(DUMMY_CATALOG));
		assertFalse(() -> evita.queryCatalog(DUMMY_CATALOG, session -> { return session.getAllEntityTypes().isEmpty(); }));
	}

	@Test
	@UseDataSet(value = GRPC_THOUSAND_PRODUCTS, destroyAfterTest = true)
	@DisplayName("Should be able to replace catalog with a new one")
	void shouldBeAbleToReplaceCatalogWithNewOne(Evita evita, GrpcClientBuilder clientBuilder) {
		assertFalse(evita.getCatalogNames().contains(DUMMY_CATALOG));

		final EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceBlockingStub.class);
		assertNotNull(evita.defineCatalog(DUMMY_CATALOG));
		assertTrue(evita.getCatalogNames().contains(DUMMY_CATALOG));
		assertTrue(() -> evita.queryCatalog(DUMMY_CATALOG, session -> { return session.getAllEntityTypes().isEmpty(); }));

		final GrpcReplaceCatalogResponse replaceCatalogResponse = evitaBlockingStub.replaceCatalog(
			GrpcReplaceCatalogRequest.newBuilder()
				.setCatalogNameToBeReplaced(DUMMY_CATALOG)
				.setCatalogNameToBeReplacedWith(TEST_CATALOG)
				.build()
		);

		assertTrue(replaceCatalogResponse.getSuccess());
		assertFalse(evita.getCatalogNames().contains(TEST_CATALOG));
		assertTrue(evita.getCatalogNames().contains(DUMMY_CATALOG));
		assertFalse(() -> evita.queryCatalog(DUMMY_CATALOG, session -> { return session.getAllEntityTypes().isEmpty(); }));
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should not be able to delete non-existing catalog")
	void shouldNotBeAbleToDeleteNonExisting(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		assertFalse(evita.getCatalogNames().contains(DUMMY_CATALOG));

		final GrpcDeleteCatalogIfExistsResponse deleteCatalogResponse = evitaBlockingStub.deleteCatalogIfExists(
			GrpcDeleteCatalogIfExistsRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);

		assertFalse(deleteCatalogResponse.getSuccess());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return list of available evita catalogs")
	void shouldReturnListOfEvitaCatalogs(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final List<String> catalogNamesList = evitaBlockingStub.getCatalogNames(Empty.newBuilder().build()).getCatalogNamesList();

		assertArrayEquals(evita.getCatalogNames().toArray(), catalogNamesList.toArray());
	}

	private void makeSessionCall(@Nonnull String catalogName, @Nonnull String sessionId, @Nonnull GrpcClientBuilder clientBuilder) {
		SessionIdHolder.setSessionId(catalogName, sessionId);
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		final AtomicReference<GrpcEntityTypesResponse> response = new AtomicReference<>();

		final Executable executable = () -> response.set(evitaSessionBlockingStub.getAllEntityTypes(Empty.newBuilder().build()));

		assertDoesNotThrow(executable);
		assertNotEquals(0, response.get().getEntityTypesCount());
	}
}
