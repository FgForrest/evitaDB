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

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcEntityRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEntityResponse;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcSessionType;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.testUtils.TestChannelCreator;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.INTEGRATION_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings({"ResultOfMethodCallIgnored", "UnusedParameters"})
@DisplayName("Evita gRPC integration test")
@Tag(INTEGRATION_TEST)
@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public class EvitaGrpcIntegrationTest {
	private static final String THOUSAND_PRODUCTS = "ThousandProducts";
	private static ExternalApiServer EXTERNAL_API_SERVER;
	private static ManagedChannel CHANNEL;

	@DataSet(THOUSAND_PRODUCTS)
	List<SealedEntity> setUp(Evita evita) {
		final ExternalApiServer externalApiServer = new ExternalApiServer(
			evita,
			ApiOptions.builder()
				.enable(GrpcProvider.CODE)
				.enable(SystemProvider.CODE)
				.build()
		);

		// open the API on configured ports
		externalApiServer.start();

		EXTERNAL_API_SERVER = externalApiServer;
		CHANNEL = TestChannelCreator.getChannel(new ClientSessionInterceptor(), externalApiServer);

		return new TestDataProvider().generateEntities(evita);
	}

	@OnDataSetTearDown(THOUSAND_PRODUCTS)
	void onDataSetTearDown() {
		CHANNEL.shutdown();
		EXTERNAL_API_SERVER.close();
	}

	@AfterEach
	public void afterEach() {
		SessionIdHolder.reset();
	}

	@Test
	@UseDataSet(THOUSAND_PRODUCTS)
	@DisplayName("Should be able to get a session id from EvitaService and with its usage should be able to get valid response from EvitaSessionService")
	void shouldBeAbleToGetSessionAndWithItCallSessionDependentMethods(Evita evita) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(CHANNEL);
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = EvitaSessionServiceGrpc.newBlockingStub(CHANNEL);

		final String entityType = Entities.PRODUCT;
		final int primaryKey = 1;

		//make a call without session provided
		final GrpcEntityRequest entityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(entityType)
			.setPrimaryKey(primaryKey)
			.build();

		//should return exception because session is not provided
		assertThrows(
			StatusRuntimeException.class,
			() -> evitaSessionBlockingStub.getEntity(entityRequest)
		);

		//get a session id from EvitaService
		final GrpcEvitaSessionResponse response = evitaBlockingStub.createReadOnlySession(
			GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build()
		);

		//should get a valid sessionId
		assertNotNull(response.getSessionId());
		assertEquals(GrpcSessionType.READ_ONLY, response.getSessionType());

		//set the session id to the holder
		SessionIdHolder.setSessionId(TEST_CATALOG, response.getSessionId());

		final AtomicReference<GrpcEntityResponse> sessionResponse = new AtomicReference<>();

		//make same call as before but with session id provided
		final Executable executable = () ->
			sessionResponse.set(evitaSessionBlockingStub.getEntity(entityRequest));

		assertDoesNotThrow(executable);

		//now we are able to get the entity
		final GrpcEntityResponse entityResponse = sessionResponse.get();

		assertEquals(entityType, entityResponse.getEntity().getEntityType());
		assertEquals(primaryKey, entityResponse.getEntity().getPrimaryKey());
	}
}
