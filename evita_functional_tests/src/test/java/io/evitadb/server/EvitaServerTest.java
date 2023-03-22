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

package io.evitadb.server;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestChannelCreator;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionTerminationResponse;
import io.evitadb.externalApi.grpc.generated.GrpcSessionType;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.CollectionUtils.Property;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.property;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test tries to start up the {@link EvitaServer} with default configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class EvitaServerTest implements TestConstants, EvitaTestSupport {
	private static final String DIR_EVITA_SERVER_TEST = "evitaServerTest";

	@Test
	void shouldStartAndStopServerCorrectly() throws IOException {
		cleanTestSubDirectory(DIR_EVITA_SERVER_TEST);
		final Path configFilePath = EvitaTestSupport.bootstrapEvitaServerConfigurationFile();

		final Set<String> apis = ExternalApiServer.gatherExternalApiProviders()
			.stream()
			.map(ExternalApiProviderRegistrar::getExternalApiCode)
			.collect(Collectors.toSet());

		final int[] ports = getPortManager().allocatePorts(DIR_EVITA_SERVER_TEST, apis.size());
		final AtomicInteger index = new AtomicInteger();
		//noinspection unchecked
		final EvitaServer evitaServer = new EvitaServer(
			configFilePath,
			createHashMap(
				Stream.concat(
					Stream.of(
						property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
						property("cache.enabled", "false")
					),
					apis.stream()
						.map(it -> property("api.endpoints." + it + ".host", "localhost:" + ports[index.getAndIncrement()]))
				)
				.toArray(Property[]::new)
			)
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			evita.defineCatalog(TEST_CATALOG);
			assertFalse(evita.getConfiguration().cache().enabled());

			final ExternalApiServer externalApiServer = evitaServer.getExternalApiServer();
			final GrpcProvider grpcProvider = externalApiServer.getExternalApiProviderByCode(GrpcProvider.CODE);
			assertNotNull(grpcProvider);

			final ManagedChannel channel = TestChannelCreator.getChannel(new ClientSessionInterceptor(), externalApiServer);

			final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

			//get a session id from EvitaService
			final GrpcEvitaSessionResponse response = evitaBlockingStub.createReadOnlySession(
				GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build()
			);

			//should get a valid sessionId
			assertNotNull(response.getSessionId());
			assertEquals(response.getSessionType(), GrpcSessionType.READ_ONLY);

			final GrpcEvitaSessionTerminationResponse terminationResponse = evitaBlockingStub.terminateSession(
				GrpcEvitaSessionTerminationRequest.newBuilder()
					.setCatalogName(TEST_CATALOG)
					.setSessionId(response.getSessionId())
					.build()
			);

			assertTrue(terminationResponse.getTerminated());

		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				evitaServer.stop();
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
	}

}