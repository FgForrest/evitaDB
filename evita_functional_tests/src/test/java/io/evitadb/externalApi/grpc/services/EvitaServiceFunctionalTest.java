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

import com.google.protobuf.Empty;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcEntityTypesResponse;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcSessionType;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.test.annotation.UseDataSet;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"ResultOfMethodCallIgnored", "UnusedParameters"})
@DisplayName("EvitaService gRPC functional test")
@Tag(FUNCTIONAL_TEST)
@Slf4j
class EvitaServiceFunctionalTest extends EvitaGrpcAbstractTest {

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should throw an exception when sending non-existent catalog as parameter")
	void shouldThrowWhenAskingForNonExistingCatalog(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
	void shouldReturnSessionIdWhenExistingCatalogPassed(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
	void shouldEachTimeGetNewReadOnlySession(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
	void shouldReturnSessionIdOfReadWriteSessionWhenExistingCatalogPassed(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
	void shouldBeAbleConcurrentlyCreateSessionsAndCallSessionService(Evita evita, ManagedChannel channel) throws Exception {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
						makeSessionCall(TEST_CATALOG, response.getSessionId(), channel);
					}
					latch.countDown();
				} catch (Exception ex) {
					terminatingException.set(ex);
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Timeouted!");

		if (terminatingException.get() != null) {
			throw terminatingException.get();
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should get each time new ReadWrite session when asked")
	void shouldEachTimeGetNewReadWriteSession(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
	void shouldGetBinaryReadOnlySession(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

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
	void shouldGetBinaryReadWriteSession(Evita evita, ManagedChannel channel) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

		final AtomicReference<GrpcEvitaSessionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaBlockingStub.createBinaryReadWriteSession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build()));

		assertDoesNotThrow(executable);

		assertEquals(GrpcSessionType.BINARY_READ_WRITE, response.get().getSessionType());
	}

	/*@Test
	@UseDataSet(THOUSAND_PRODUCTS)
	@DisplayName("Should be able to create new catalog")
	void shouldBeAbleToCreateNewCatalog(Evita evita) {
		final EvitaSystemServiceGrpc.EvitaSystemServiceBlockingStub evitaBlockingStub = EvitaSystemServiceGrpc.newBlockingStub(channel);
		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
		final CreateCatalogResponse createCatalogResponse = evitaBlockingStub.createCatalog(
			CreateCatalogRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);
		assertTrue(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
		assertCatalogSchema(evitaSystemDataProvider.getCatalog(DUMMY_CATALOG).getSchema(), createCatalogResponse.getCatalogSchema());
	}

	@Test
	@UseDataSet(THOUSAND_PRODUCTS)
	@DisplayName("Should be able to replace catalog with a new one")
	void shouldBeAbleToReplaceCatalogWithNewOne(Evita evita) {
		final EvitaSystemServiceGrpc.EvitaSystemServiceBlockingStub evitaBlockingStub = EvitaSystemServiceGrpc.newBlockingStub(channel);
		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
		final String replacedCatalogName = "ReplaceCatalog";
		final CreateCatalogResponse createCatalogResponse = evitaBlockingStub.createCatalog(
			CreateCatalogRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);
		assertNotEquals(createCatalogResponse.getCatalogSchema().getDefaultInstanceForType(), createCatalogResponse.getCatalogSchema());
		assertTrue(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
		final CreateCatalogResponse createReplaceCatalogResponse = evitaBlockingStub.createCatalog(
			CreateCatalogRequest.newBuilder().setCatalogName(replacedCatalogName).build()
		);
		assertNotEquals(createReplaceCatalogResponse.getCatalogSchema().getDefaultInstanceForType(), createReplaceCatalogResponse.getCatalogSchema());
		assertEquals(Empty.getDefaultInstance(), evitaBlockingStub.replaceCatalog(
			ReplaceCatalogRequest.newBuilder()
				.setCatalogNameTobeReplaced(DUMMY_CATALOG)
				.setCatalogNameTobeReplacedWith(replacedCatalogName)
				.build()
		));
		assertTrue(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(replacedCatalogName)));
	}

	@Test
	@UseDataSet(THOUSAND_PRODUCTS)
	@DisplayName("Should be able to delete existing catalog")
	void shouldBeAbleToDeleteExisting(Evita evita) {
		final EvitaSystemServiceGrpc.EvitaSystemServiceBlockingStub evitaBlockingStub = EvitaSystemServiceGrpc.newBlockingStub(channel);
		final CreateCatalogResponse createCatalogResponse = evitaBlockingStub.createCatalog(
			CreateCatalogRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);
		assertNotEquals(GrpcCatalogSchema.getDefaultInstance(), createCatalogResponse.getCatalogSchema());
		assertTrue(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));

		final DeleteCatalogResponse deleteCatalogResponse = evitaBlockingStub.deleteCatalog(
			DeleteCatalogRequest.newBuilder().setCatalogName(DUMMY_CATALOG).build()
		);

		assertTrue(deleteCatalogResponse.getSuccess());

		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
	}

	@Test
	@UseDataSet(THOUSAND_PRODUCTS)
	@DisplayName("Should not be able to delete non-existing catalog")
	void shouldNotBeAbleToDeleteNonExisting(Evita evita) {
		final EvitaSystemServiceGrpc.EvitaSystemServiceBlockingStub evitaBlockingStub = EvitaSystemServiceGrpc.newBlockingStub(channel);
		final String nonExistingCatalogName = "NonExistingCatalog";

		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(nonExistingCatalogName)));

		final DeleteCatalogResponse deleteCatalogResponse = evitaBlockingStub.deleteCatalog(
			DeleteCatalogRequest.newBuilder().setCatalogName(nonExistingCatalogName).build()
		);

		assertFalse(deleteCatalogResponse.getSuccess());
		assertFalse(evita.getCatalogNames().stream().anyMatch(name -> name.equals(DUMMY_CATALOG)));
	}

	@Test
	@UseDataSet(THOUSAND_PRODUCTS)
	@DisplayName("Should return list of available evita catalogs")
	void shouldReturnListOfEvitaCatalogs(Evita evita) {
		final EvitaSystemServiceGrpc.EvitaSystemServiceBlockingStub evitaBlockingStub = EvitaSystemServiceGrpc.newBlockingStub(channel);

		final List<String> catalogNamesList = evitaBlockingStub.getCatalogNames(Empty.newBuilder().build()).getCatalogNamesList();

		assertArrayEquals(evita.getCatalogNames().toArray(), catalogNamesList.toArray());
	}*/

	private void makeSessionCall(@Nonnull String catalogName, @Nonnull String sessionId, @Nonnull ManagedChannel channel) {
		SessionIdHolder.setSessionId(catalogName, sessionId);
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = EvitaSessionServiceGrpc.newBlockingStub(channel);

		final AtomicReference<GrpcEntityTypesResponse> response = new AtomicReference<>();

		final Executable executable = () -> response.set(evitaSessionBlockingStub.getAllEntityTypes(Empty.newBuilder().build()));

		assertDoesNotThrow(executable);
		assertNotEquals(0, response.get().getEntityTypesCount());
	}
}
