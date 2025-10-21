/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import com.google.protobuf.Int32Value;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.CatalogState;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import io.evitadb.externalApi.grpc.testUtils.SessionInitializer;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.VersionUtils.SemVer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EvitaSystemService gRPC functional test")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EvitaSessionServiceWarmupCatalogTest {
	private static final String GRPC_THOUSAND_PRODUCTS_WARM_UP = "GrpcThousandProductsInWarmUpState";

	@DataSet(value = GRPC_THOUSAND_PRODUCTS_WARM_UP, expectedCatalogState = CatalogState.WARMING_UP, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		final GrpcClientBuilder clientBuilder = TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);

		final List<SealedEntity> entities = new TestDataProvider().generateEntities(evita, 10);

		return new DataCarrier(
			"entities", entities,
			"clientBuilder", clientBuilder
		);
	}

	@OnDataSetTearDown(GRPC_THOUSAND_PRODUCTS_WARM_UP)
	void onDataSetTearDown(GrpcClientBuilder clientBuilder) {

	}

	@AfterEach
	void afterEach() {
		SessionIdHolder.reset();
	}

	@Test
	@UseDataSet(value = GRPC_THOUSAND_PRODUCTS_WARM_UP, destroyAfterTest = true)
	@DisplayName("Should be able to insert new entities with primaryKey generated while inserting into the database")
	void shouldBeAbleToInsertEntitiesInWarmupCatalogState(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final String entityType = Entities.PRODUCT;
		final int entitiesToInsert = 100;

		final int lastUsedPrimaryKey = evitaBlockingStub.getEntityCollectionSize(
			GrpcEntityCollectionSizeRequest
				.newBuilder()
				.setEntityType(Entities.PRODUCT)
				.build()
			)
			.getSize();

		for (int i = 1; i <= entitiesToInsert; i++) {
			final GrpcUpsertEntityResponse entityResponse = evitaBlockingStub.upsertEntity(
				GrpcUpsertEntityRequest.newBuilder()
					.setEntityMutation(GrpcEntityMutation.newBuilder()
						.setEntityUpsertMutation(
							GrpcEntityUpsertMutation.newBuilder()
								.setEntityType(entityType)
								.build()
						)
						.build())
					.build()
			);
			final int pk = entityResponse.hasEntityReferenceWithAssignedPrimaryKeys() ?
				entityResponse.getEntityReferenceWithAssignedPrimaryKeys().getPrimaryKey() :
				entityResponse.getEntityReference().getPrimaryKey();
			assertNotEquals(0, pk);
			assertEquals(lastUsedPrimaryKey + i, pk);
		}

		GrpcGoLiveAndCloseResponse grpcGoLiveAndCloseResponse = evitaBlockingStub.goLiveAndClose(Empty.getDefaultInstance());
		assertTrue(grpcGoLiveAndCloseResponse.getSuccess());
	}

	@Test
	@UseDataSet(value = GRPC_THOUSAND_PRODUCTS_WARM_UP, destroyAfterTest = true)
	@DisplayName("Should be able to update entities in warmup catalog state")
	void shouldBeAbleToUpdateEntitiesInWarmupCatalogState(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final String entityType = Entities.PRODUCT;

		final int entityCount = evitaBlockingStub.getEntityCollectionSize(GrpcEntityCollectionSizeRequest.newBuilder().setEntityType(Entities.PRODUCT).build()).getSize();
		final String attributeName = ATTRIBUTE_PRIORITY;

		final long attributePriorityValue = 10L;

		for (int i = 1; i <= entityCount; i++) {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			final GrpcUpsertEntityResponse entityResponse = evitaBlockingStub.upsertEntity(
				GrpcUpsertEntityRequest.newBuilder()
					.setRequire(stringWithParameters.query())
					.addAllPositionalQueryParams(
						stringWithParameters.parameters()
							.stream()
							.map(QueryConverter::convertQueryParam)
							.toList()
					)
					.setEntityMutation(GrpcEntityMutation.newBuilder()
						.setEntityUpsertMutation(
							GrpcEntityUpsertMutation.newBuilder()
								.setEntityType(entityType)
								.setEntityPrimaryKey(Int32Value.of(i))
								.addMutations(
									GrpcLocalMutation.newBuilder()
										.setUpsertAttributeMutation(
											GrpcUpsertAttributeMutation.newBuilder()
												.setAttributeName(attributeName)
												.setAttributeValue(EvitaDataTypesConverter.toGrpcEvitaValue(attributePriorityValue))
												.build()
										)
								)
								.build()
						)
						.build()
					)
					.build()
			);
			assertNotEquals(0, entityResponse.getEntity().getPrimaryKey());
			assertEquals(i, entityResponse.getEntity().getPrimaryKey());

			final GrpcEvitaValue attributeValue = entityResponse.getEntity().getGlobalAttributesMap().get(attributeName);
			assertTrue(attributeValue.getLongValue() == attributePriorityValue);
		}

		GrpcGoLiveAndCloseResponse grpcGoLiveAndCloseResponse = evitaBlockingStub.goLiveAndClose(Empty.getDefaultInstance());
		assertTrue(grpcGoLiveAndCloseResponse.getSuccess());
	}
}
