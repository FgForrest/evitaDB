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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.core.Evita;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogState;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogStatistics;
import io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionStatistics;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaCatalogStatisticsResponse;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.VersionUtils.SemVer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the wire contract of the deprecated instance-wide `GetCatalogStatistics` RPC.
 *
 * The Java API no longer speaks this flat shape at all - the engine answers through the component model and the
 * message is assembled from it inside the gRPC layer. This test therefore asserts the *message*, not a Java record:
 * it is the only thing guarding the promise that the RPC's semantics did not move for clients that still call it,
 * including the fields a corrupted catalog would report as `-1` and the per-collection rows that make the response
 * grow with collection count.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Deprecated catalog statistics RPC keeps its wire contract")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
public class EvitaManagementServiceStatisticsTest {
	private static final String GRPC_STATISTICS_DATA_SET = "GrpcEvitaManagementServiceStatisticsTest";

	@DataSet(
		value = GRPC_STATISTICS_DATA_SET,
		openWebApi = {GrpcProvider.CODE, SystemProvider.CODE},
		destroyAfterClass = true
	)
	GrpcClientBuilder setUp(Evita evita, EvitaServer evitaServer) {
		new TestDataProvider().generateEntities(evita, 1);
		return TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);
	}

	@AfterEach
	public void afterEach() {
		SessionIdHolder.reset();
	}

	@Test
	@UseDataSet(GRPC_STATISTICS_DATA_SET)
	@DisplayName("populate every scalar and every per-collection row")
	// the procedure under test is deprecated on purpose - guarding a frozen contract means calling it
	@SuppressWarnings({"deprecation", "removal"})
	void shouldReturnCatalogStatisticsOverTheWire(GrpcClientBuilder clientBuilder) {
		final EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub managementStub =
			clientBuilder.build(EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub.class);

		final GrpcEvitaCatalogStatisticsResponse response = managementStub.getCatalogStatistics(
			Empty.newBuilder().build()
		);
		assertNotNull(response);
		assertFalse(response.getCatalogStatisticsList().isEmpty(), "At least the test catalog must be reported");

		final GrpcCatalogStatistics statistics = response.getCatalogStatisticsList()
			.stream()
			.filter(it -> TEST_CATALOG.equals(it.getCatalogName()))
			.findFirst()
			.orElseThrow();

		assertEquals(TEST_CATALOG, statistics.getCatalogName());
		assertFalse(statistics.getUnusable());
		assertFalse(statistics.getCorrupted());
		assertFalse(statistics.getReadOnly());
		assertTrue(statistics.hasCatalogId(), "A healthy catalog must report its id");
		// the exact lifecycle state depends on whether the dataset went live, which is not what this test is about -
		// what matters is that a state was projected at all, since a broken identity would surface as UNKNOWN
		assertTrue(
			statistics.getCatalogState() == GrpcCatalogState.WARMING_UP
				|| statistics.getCatalogState() == GrpcCatalogState.ALIVE,
			"Expected an active catalog state, but was " + statistics.getCatalogState()
		);

		// every one of these is fed from a separate statistics component, and a component that failed to be delivered
		// would surface here as the legacy `-1` marker - so asserting them positive is what proves the projection
		assertTrue(
			statistics.getTotalRecords() > 0,
			"Expected a positive record count, but was " + statistics.getTotalRecords()
		);
		assertTrue(
			statistics.getIndexCount() > 0,
			"Expected a positive index count, but was " + statistics.getIndexCount()
		);
		assertTrue(
			statistics.getSizeOnDiskInBytes() > 0,
			"Expected a positive size on disk, but was " + statistics.getSizeOnDiskInBytes()
		);

		assertFalse(
			statistics.getEntityCollectionStatisticsList().isEmpty(),
			"The per-collection rows are part of this RPC's contract and must not be dropped"
		);
		long summedCollectionRecords = 0L;
		for (final GrpcEntityCollectionStatistics collection : statistics.getEntityCollectionStatisticsList()) {
			assertFalse(collection.getEntityType().isBlank());
			assertTrue(
				collection.getTotalRecords() > 0,
				"Expected a positive record count for `" + collection.getEntityType() + "`"
			);
			assertTrue(
				collection.getIndexCount() > 0,
				"Expected a positive index count for `" + collection.getEntityType() + "`"
			);
			assertTrue(
				collection.getSizeOnDiskInBytes() > 0,
				"Expected a positive size on disk for `" + collection.getEntityType() + "`"
			);
			summedCollectionRecords += collection.getTotalRecords();
		}

		// the catalog total has always been the sum of its collections' totals - the component model computes both
		// from the same per-collection counter, and this pins that they cannot drift apart
		assertEquals(summedCollectionRecords, statistics.getTotalRecords());
	}
}
