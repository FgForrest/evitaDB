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
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
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
import io.evitadb.externalApi.grpc.utils.QueryUtil;
import io.evitadb.externalApi.grpc.utils.QueryWithParameters;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.function.QuadriConsumer;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.AssertionFailedError;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.externalApi.grpc.query.QueryConverter.convertQueryParam;
import static io.evitadb.externalApi.grpc.testUtils.GrpcAssertions.*;
import static io.evitadb.externalApi.grpc.testUtils.TestDataProvider.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SESSION;

@SuppressWarnings({"ResultOfMethodCallIgnored", "UnusedParameters"})
@DisplayName("EvitaSessionService gRPC functional test")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SESSION)
class EvitaSessionServiceFunctionalTest {
	private static final String GRPC_THOUSAND_PRODUCTS = "GrpcEvitaSessionServiceFunctionalTest";
	private static final QuadriConsumer<String, List<Object>, Map<String, Object>, String> NO_OP = (queryString, positionalArguments, namedArguments, error) -> {
		// no-op
	};

	@DataSet(value = GRPC_THOUSAND_PRODUCTS, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		final GrpcClientBuilder clientBuilder = TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);
		final List<SealedEntity> entities = new TestDataProvider().generateEntities(evita, 1000);
		return new DataCarrier(
			"entities", entities,
			"clientBuilder", clientBuilder
		);
	}

	@AfterEach
	public void afterEach() {
		SessionIdHolder.reset();
	}

	@OnDataSetTearDown(GRPC_THOUSAND_PRODUCTS)
	void onDataSetTearDown(GrpcClientBuilder clientBuilder) {

	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return default instance of entity when trying to get a non-existent entity")
	void shouldThrowWhenAskingForNonExistingEntity(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final int primaryKey = -1;
		final String entityType = Entities.PRODUCT;

		final AtomicReference<GrpcEntityResponse> response = new AtomicReference<>();

		assertDoesNotThrow(() -> response.set(evitaSessionBlockingStub.getEntity(GrpcEntityRequest.newBuilder()
			.setPrimaryKey(primaryKey)
			.setEntityType(entityType)
			.build())));

		assertEquals(GrpcSealedEntity.getDefaultInstance(), response.get().getEntity());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return an existing entity specified by its primary key and entity type")
	void shouldReturnExistingEntitySpecifiedByPrimaryKeyAndEntityType(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		//noinspection ConstantConditions
		final int primaryKey = entities.stream()
			.filter(entity -> entity.getType().equals(Entities.PRODUCT))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable entity not found!"))
			.getPrimaryKey();
		final String entityType = Entities.PRODUCT;

		final AtomicReference<GrpcEntityResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.getEntity(GrpcEntityRequest.newBuilder()
				.setPrimaryKey(primaryKey)
				.setEntityType(entityType)
				.build()
			));

		assertDoesNotThrow(executable);

		assertEquals(primaryKey, response.get().getEntity().getPrimaryKey());
		assertEquals(entityType, response.get().getEntity().getEntityType());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return enriched existing entity specified by its primary key and entity type and require query")
	void shouldReturnExistingEnrichedEntitySpecifiedByPrimaryKeyAndEntityTypeAndEntityContentRequires(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		//noinspection ConstantConditions
		final int primaryKey = entities.stream()
			.filter(entity -> entity.getType().equals(Entities.PRODUCT) && !entity.getPrices().isEmpty())
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable entity not found!"))
			.getPrimaryKey();
		final String entityType = Entities.PRODUCT;

		final String stringEntityContentRequires = "priceContentRespectingFilter()";

		final AtomicReference<GrpcEntityResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.getEntity(GrpcEntityRequest.newBuilder()
				.setPrimaryKey(primaryKey)
				.setEntityType(entityType)
				.setRequire(stringEntityContentRequires)
				.build()
			));

		assertDoesNotThrow(executable);

		assertEquals(primaryKey, response.get().getEntity().getPrimaryKey());
		assertEquals(entityType, response.get().getEntity().getEntityType());
		assertNotNull(response.get().getEntity().getPriceForSale());
		assertNotEquals(0, response.get().getEntity().getPricesCount());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of entity references")
	void shouldReturnDataChunkOfEntityReferences(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(8);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(3));
		params.add(convertQueryParam(4));
		params.add(convertQueryParam(5));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));

		final String stringQuery = """
					query(
						collection(?),
						filterBy(
							entityPrimaryKeyInSet(?, ?, ?, ?, ?)
						),
						require(
							page(?, ?)
						)
					)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		final EvitaResponse<EntityReference> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), EntityReference.class);

		assertEquals(entityResponse.getRecordData().size(), response.get().getRecordPage().getEntityReferencesCount());

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final GrpcEntityReference grpcEntityReference = response.get().getRecordPage().getEntityReferencesList().get(i);
			final EntityReference entityReference = entityResponse.getRecordData().get(i);
			assertEquals(entityReference.getType(), grpcEntityReference.getEntityType());
			assertEquals(entityReference.getPrimaryKey(), grpcEntityReference.getPrimaryKey());
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return list of entity references")
	void shouldReturnListOfEntityReferences(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(8);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(3));
		params.add(convertQueryParam(4));
		params.add(convertQueryParam(5));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					entityPrimaryKeyInSet(?, ?, ?, ?, ?)
				),
				require(
					page(?, ?)
				)
			)
			""";

		final AtomicReference<GrpcQueryListResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.queryList(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		final List<EntityReferenceContract> entityResponse = evita.createReadOnlySession(TEST_CATALOG).queryListOfEntityReferences(query.parsedQuery());

		for (int i = 0; i < entityResponse.size(); i++) {
			final GrpcEntityReference grpcEntityReference = response.get().getEntityReferencesList().get(i);
			final EntityReferenceContract entityReference = entityResponse.get(i);
			assertEquals(entityReference.getType(), grpcEntityReference.getEntityType());
			assertEquals(entityReference.getPrimaryKey(), grpcEntityReference.getPrimaryKey());
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should throw when queryOne returns more than one entity with queryOne")
	void shouldThrowWhenQueryOneReturnsMoreThanOneEntity(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(8);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(3));
		params.add(convertQueryParam(4));
		params.add(convertQueryParam(5));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					entityPrimaryKeyInSet(?, ?, ?, ?, ?)
				),
				require(
					page(?, ?)
				)
			)
			""";

		final Executable executable = () ->
			evitaSessionBlockingStub.queryOne(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			);

		assertThrows(StatusRuntimeException.class, executable);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return one entity when using queryOne and only one matches")
	void shouldReturnOneEntityWhenUsingQueryOneAndProperlySpecified(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(4);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					entityPrimaryKeyInSet(?)
				),
				require(
					page(?, ?)
				)
			)
			""";

		final AtomicReference<GrpcQueryOneResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.queryOne(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build())
			);

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		final EntityReferenceContract entity = evita.createReadOnlySession(TEST_CATALOG)
			.queryOneEntityReference(query.parsedQuery())
			.orElseThrow();

		final GrpcEntityReference grpcEntityReference = response.get().getEntityReference();
		assertEquals(entity.getType(), grpcEntityReference.getEntityType());
		assertEquals(entity.getPrimaryKey(), grpcEntityReference.getPrimaryKey());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should throw when trying to get hierarchy statistics of non-hierarchical entity collection")
	void shouldFailWhenTryingToGetHierarchyStatisticsOnNotHierarchicalCollection(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(8);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(3));
		params.add(convertQueryParam(4));
		params.add(convertQueryParam(5));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					entityPrimaryKeyInSet(?, ?, ?, ?, ?)
				),
				require(
					page(?, ?),
					hierarchyOfSelf()
				)
			)
			""";

		assertThrows(StatusRuntimeException.class, () -> evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
			.setQuery(stringQuery)
			.addAllPositionalQueryParams(params)
			.build()
		));
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities with one language specified")
	void shouldReturnDataChunkOfEnrichedEntitiesWithOneLanguageSpecified(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(17);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(ATTRIBUTE_NAME));
		params.add(convertQueryParam("a"));
		params.add(convertQueryParam(CZECH_LOCALE));
		params.add(convertQueryParam(CURRENCY_CZK));
		params.add(convertQueryParam(PRICE_LIST_VIP));
		params.add(convertQueryParam(PRICE_LIST_BASIC));
		params.add(convertQueryParam(Entities.BRAND));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(Entities.CATEGORY));
		params.add(convertQueryParam(Entities.STORE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						attributeContains(?, ?),
						entityLocaleEquals(?),
						priceInCurrency(?),
						priceInPriceLists(?, ?),
						userFilter(						
							facetHaving(?, entityPrimaryKeyInSet(?, ?))
						)
					)
				),
				require(
					page(?, ?),
					entityFetch(
						attributeContentAll(),
						priceContentRespectingFilter(),
						referenceContent(?, ?),
						associatedDataContentAll()
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, grpcSealedEntity);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities with rich references")
	void shouldReturnDataChunkOfEnrichedEntitiesWithRichReferences(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Integer primaryKey = entities.stream()
			.filter(it -> !it.getReferences(Entities.PARAMETER).isEmpty())
			.map(EntityContract::getPrimaryKey)
			.findAny()
			.orElseThrow();

		final List<GrpcQueryParam> params = new ArrayList<>(17);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(primaryKey));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(Entities.CATEGORY));
		params.add(convertQueryParam(ATTRIBUTE_CODE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					entityPrimaryKeyInSet(?)
				),
				require(
					page(?, ?),
					entityFetch(
						priceContentRespectingFilter(),
						referenceContent(?, entityFetch(attributeContent(?)), entityGroupFetch())
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, grpcSealedEntity);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of entities with filtered and sorted references")
	void shouldReturnDataChunkOfEntitiesWithFilteredAndSortedReferences(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalParameters, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Map<Integer, SealedEntity> storesIndexedByPk = originalParameters.stream()
			.collect(Collectors.toMap(
				EntityContract::getPrimaryKey,
				Function.identity()
			));

		final Map<Integer, Set<String>> productsWithLotsOfParameters = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.PARAMETER)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.map(storesIndexedByPk::get)
						.map(store -> store.getAttribute(ATTRIBUTE_CODE, String.class))
						.collect(Collectors.toSet())
				)
			);

		final AtomicBoolean atLeastFirst = new AtomicBoolean();
		final Random rnd = new Random(5);
		final String[] randomParameters = productsWithLotsOfParameters
			.values()
			.stream()
			.flatMap(Collection::stream)
			.filter(it -> atLeastFirst.compareAndSet(false, true) || rnd.nextInt(10) == 0)
			.distinct()
			.toArray(String[]::new);

		final List<GrpcQueryParam> params = new ArrayList<>(17);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(productsWithLotsOfParameters.keySet().toArray(Integer[]::new)));
		params.add(convertQueryParam(CZECH_LOCALE));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));
		params.add(convertQueryParam(Entities.PARAMETER));
		params.add(convertQueryParam(ATTRIBUTE_CODE));
		params.add(convertQueryParam(randomParameters));
		params.add(convertQueryParam(ATTRIBUTE_NAME));
		params.add(convertQueryParam(OrderDirection.DESC));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						entityPrimaryKeyInSet(?),
						entityLocaleEquals(?)
					)
				),
				require(
					page(?, ?),
					entityFetch(
						referenceContent(
							?,
							filterBy(
								entityHaving(
									attributeInSet(?, ?)
								)
							),
							orderBy(
								entityProperty(
									attributeNatural(?, ?)
								)
							),
							entityFetch(
								attributeContentAll(),
								associatedDataContentAll()
							)
						)
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, grpcSealedEntity);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities when using multiple filter conditions, using query enums and ordering by attributes with passed mix of named and positional parameters")
	void shouldReturnDataChunkOfEnrichedEntitiesWhenFilteringByMultipleConditionsAndOrderingByAttributesMixedParams(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> positionalParams = new ArrayList<>(19);
		positionalParams.add(convertQueryParam(Entities.PRODUCT));
		positionalParams.add(convertQueryParam(ATTRIBUTE_SIZE));
		positionalParams.add(convertQueryParam(ATTRIBUTE_NAME));
		positionalParams.add(convertQueryParam(CZECH_LOCALE));
		positionalParams.add(convertQueryParam(CURRENCY_CZK));
		positionalParams.add(convertQueryParam(PRICE_LIST_VIP));
		positionalParams.add(convertQueryParam(PRICE_LIST_BASIC));
		positionalParams.add(convertQueryParam(Entities.BRAND));
		positionalParams.add(convertQueryParam(1));
		positionalParams.add(convertQueryParam(2));
		positionalParams.add(convertQueryParam(ATTRIBUTE_NAME));
		positionalParams.add(convertQueryParam(OrderDirection.DESC));
		positionalParams.add(convertQueryParam(Entities.CATEGORY));
		positionalParams.add(convertQueryParam(Entities.STORE));
		positionalParams.add(convertQueryParam(FacetStatisticsDepth.COUNTS));
		positionalParams.add(convertQueryParam(QueryPriceMode.WITH_TAX));

		final Map<String, GrpcQueryParam> namedParams = CollectionUtils.createHashMap(4);
		namedParams.put("sizeIs", convertQueryParam(AttributeSpecialValue.NOT_NULL));
		namedParams.put("nameContains", convertQueryParam("a"));
		namedParams.put("page", convertQueryParam(1));
		namedParams.put("pageSize", convertQueryParam(20));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						attributeIs(?, @sizeIs),
						attributeContains(?, @nameContains),
						entityLocaleEquals(?),
						priceInCurrency(?),
						priceInPriceLists(?, ?),
						userFilter(
							facetHaving(?, entityPrimaryKeyInSet(?, ?)),						
						)
					)
				),
				orderBy(
					attributeNatural(?, ?),
					priceNatural()
				),
				require(
					page(@page, @pageSize),
					entityFetch(
						attributeContentAll(),
						priceContentRespectingFilter(),
						referenceContent(?, ?),
						associatedDataContentAll()
					),
					facetSummary(?),
					priceType(?),
					queryTelemetry()
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(positionalParams)
				.putAllNamedQueryParams(namedParams)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, positionalParams, namedParams, null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, grpcSealedEntity);
		}

		assertFacetSummary(Objects.requireNonNull(
				entityResponse.getExtraResult(FacetSummary.class)),
			response.get().getExtraResults().getFacetGroupStatisticsList()
		);

		final GrpcQueryTelemetry queryTelemetry = response.get().getExtraResults().getQueryTelemetry();
		assertNotEquals(GrpcQueryTelemetry.getDefaultInstance(), queryTelemetry);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities when using multiple filter conditions, using query enums and ordering by attributes with passed positional parameters")
	void shouldReturnDataChunkOfEnrichedEntitiesWhenFilteringByMultipleConditionsAndOrderingByAttributesPositional(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> positionalParams = new ArrayList<>(19);
		positionalParams.add(convertQueryParam(Entities.PRODUCT));
		positionalParams.add(convertQueryParam(ATTRIBUTE_SIZE));
		positionalParams.add(convertQueryParam(AttributeSpecialValue.NOT_NULL));
		positionalParams.add(convertQueryParam(ATTRIBUTE_NAME));
		positionalParams.add(convertQueryParam("a"));
		positionalParams.add(convertQueryParam(CZECH_LOCALE));
		positionalParams.add(convertQueryParam(CURRENCY_CZK));
		positionalParams.add(convertQueryParam(PRICE_LIST_VIP));
		positionalParams.add(convertQueryParam(PRICE_LIST_BASIC));
		positionalParams.add(convertQueryParam(Entities.BRAND));
		positionalParams.add(convertQueryParam(1));
		positionalParams.add(convertQueryParam(2));
		positionalParams.add(convertQueryParam(ATTRIBUTE_NAME));
		positionalParams.add(convertQueryParam(OrderDirection.DESC));
		positionalParams.add(convertQueryParam(1));
		positionalParams.add(convertQueryParam(20));
		positionalParams.add(convertQueryParam(Entities.CATEGORY));
		positionalParams.add(convertQueryParam(Entities.STORE));
		positionalParams.add(convertQueryParam(FacetStatisticsDepth.COUNTS));
		positionalParams.add(convertQueryParam(QueryPriceMode.WITH_TAX));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						attributeIs(?, ?),
						attributeContains(?, ?),
						entityLocaleEquals(?),
						priceInCurrency(?),
						priceInPriceLists(?, ?),
						userFilter(
							facetHaving(?, entityPrimaryKeyInSet(?, ?))
						)
					)
				),
				orderBy(
					attributeNatural(?, ?),
					priceNatural()
				),
				require(
					page(?, ?),
					entityFetch(
						attributeContentAll(),
						associatedDataContentAll(),
						priceContentRespectingFilter(),
						referenceContent(?, ?)
					),				
					facetSummary(?),
					priceType(?)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(positionalParams)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, positionalParams, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, grpcSealedEntity);
		}

		assertFacetSummary(Objects.requireNonNull(
				entityResponse.getExtraResult(FacetSummary.class)),
			response.get().getExtraResults().getFacetGroupStatisticsList()
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities when using multiple filter conditions, using query enums and ordering by attributes with passed named parameters")
	void shouldReturnDataChunkOfEnrichedEntitiesWhenFilteringByMultipleConditionsAndOrderingByAttributesNamed(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Map<String, GrpcQueryParam> namedParams = CollectionUtils.createHashMap(19);
		namedParams.put("entitiesProduct", convertQueryParam(Entities.PRODUCT));
		namedParams.put("attributeSizeName", convertQueryParam(ATTRIBUTE_SIZE));
		namedParams.put("attributeSizeValue", convertQueryParam(AttributeSpecialValue.NOT_NULL));
		namedParams.put("attributeNameName", convertQueryParam(ATTRIBUTE_NAME));
		namedParams.put("attributeNameValue", convertQueryParam("a"));
		namedParams.put("locale", convertQueryParam(CZECH_LOCALE));
		namedParams.put("currency", convertQueryParam(CURRENCY_CZK));
		namedParams.put("priceListVip", convertQueryParam(PRICE_LIST_VIP));
		namedParams.put("priceListBasic", convertQueryParam(PRICE_LIST_BASIC));
		namedParams.put("entitiesStore", convertQueryParam(Entities.STORE));
		namedParams.put("facetId1", convertQueryParam(1));
		namedParams.put("facetId2", convertQueryParam(2));
		namedParams.put("entitiesBrand", convertQueryParam(Entities.BRAND));
		namedParams.put("attributeOrderDirection", convertQueryParam(OrderDirection.DESC));
		namedParams.put("page", convertQueryParam(1));
		namedParams.put("pageSize", convertQueryParam(20));
		namedParams.put("entitiesCategory", convertQueryParam(Entities.CATEGORY));
		namedParams.put("facetStatisticsDepth", convertQueryParam(FacetStatisticsDepth.COUNTS));
		namedParams.put("queryPriceMode", convertQueryParam(QueryPriceMode.WITH_TAX));

		final String stringQuery = """
			query(
				collection(@entitiesProduct),
				filterBy(
					and(
						attributeIs(@attributeSizeName, @attributeSizeValue),
						attributeContains(@attributeNameName, @attributeNameValue),
						entityLocaleEquals(@locale),
						priceInCurrency(@currency),
						priceInPriceLists(@priceListVip, @priceListBasic),
						userFilter(
							facetHaving(@entitiesBrand, entityPrimaryKeyInSet(@facetId1, @facetId2))
						)
					)
				),
				orderBy(
					attributeNatural(@attributeNameName, @attributeOrderDirection),
					priceNatural()
				),
				require(
					page(@page, @pageSize),
					entityFetch(
						attributeContentAll(),
						priceContentRespectingFilter(),
						referenceContent(@entitiesCategory, @entitiesStore),
						associatedDataContentAll()
					),
					facetSummary(@facetStatisticsDepth),
					priceType(@queryPriceMode)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.putAllNamedQueryParams(namedParams)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, Collections.emptyList(), namedParams, null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, grpcSealedEntity);
		}

		assertFacetSummary(Objects.requireNonNull(
				entityResponse.getExtraResult(FacetSummary.class)),
			response.get().getExtraResults().getFacetGroupStatisticsList()
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities with more than one language specified")
	void shouldReturnDataChunkOfEnrichedEntitiesWithMoreThanOneLanguageSpecified(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(17);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(ATTRIBUTE_NAME));
		params.add(convertQueryParam("a"));
		params.add(convertQueryParam(CZECH_LOCALE));
		params.add(convertQueryParam(CURRENCY_CZK));
		params.add(convertQueryParam(PRICE_LIST_VIP));
		params.add(convertQueryParam(PRICE_LIST_BASIC));
		params.add(convertQueryParam(Entities.BRAND));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(Locale.ENGLISH));
		params.add(convertQueryParam(CZECH_LOCALE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						attributeContains(?, ?),
						entityLocaleEquals(?),
						priceInCurrency(?),
						priceInPriceLists(?, ?),
						userFilter(
							facetHaving(?, entityPrimaryKeyInSet(?, ?))
						)
					)
				),
				require(
					page(?, ?),
					entityFetch(
						attributeContentAll(),
						priceContentRespectingFilter(),
						referenceContentAll(),
						associatedDataContentAll(),
						dataInLocales(?, ?)
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity enrichedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, enrichedEntity);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return extra result of parents consisting of products referencing to its categories")
	void shouldReturnParentsOfProductsReferencingToItsCategories(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(23);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(ATTRIBUTE_NAME));
		params.add(convertQueryParam("a"));
		params.add(convertQueryParam(CZECH_LOCALE));
		params.add(convertQueryParam(CURRENCY_CZK));
		params.add(convertQueryParam(PRICE_LIST_VIP));
		params.add(convertQueryParam(PRICE_LIST_BASIC));
		params.add(convertQueryParam(Entities.BRAND));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(Locale.ENGLISH));
		params.add(convertQueryParam(CZECH_LOCALE));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(ATTRIBUTE_QUANTITY));
		params.add(convertQueryParam(ATTRIBUTE_PRIORITY));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(FacetStatisticsDepth.IMPACT));
		params.add(convertQueryParam(Entities.CATEGORY));
		params.add(convertQueryParam(EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY));
		params.add(convertQueryParam("a"));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						attributeContains(?, ?),
						entityLocaleEquals(?),
						priceInCurrency(?),
						priceInPriceLists(?, ?),
						userFilter(
							facetHaving(?, entityPrimaryKeyInSet(?, ?))
						)
					)
				),
				require(
					page(?, ?),
					entityFetch(
						attributeContentAll(),
						priceContentRespectingFilter(),
						referenceContentAll(),
						hierarchyContent(entityFetch(referenceContentAll())),
						associatedDataContentAll(),
						dataInLocales(?, ?)
					),
					attributeHistogram(?, ?, ?),
					priceHistogram(?),
					facetSummary(?),
					hierarchyOfReference(?, ?, fromRoot(?, entityFetch(attributeContentAll())))
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity enrichedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEntity(entity, enrichedEntity);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return all requested extra results")
	void shouldReturnAllRequestedExtraResults(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(21);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(CURRENCY_USD));
		params.add(convertQueryParam(PRICE_LIST_VIP));
		params.add(convertQueryParam(PRICE_LIST_BASIC));
		params.add(convertQueryParam(PRICE_LIST_B2B));
		params.add(convertQueryParam(PRICE_LIST_REFERENCE));
		params.add(convertQueryParam(PRICE_LIST_SELLOUT));
		params.add(convertQueryParam(Entities.PARAMETER));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(Locale.ENGLISH));
		params.add(convertQueryParam(CZECH_LOCALE));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(ATTRIBUTE_QUANTITY));
		params.add(convertQueryParam(ATTRIBUTE_PRIORITY));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(FacetStatisticsDepth.IMPACT));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						priceInCurrency(?),
						priceInPriceLists(?, ?, ?, ?, ?),
						userFilter(
							facetHaving(?, entityPrimaryKeyInSet(?))
						)
					)
				),
				require(
					page(?, ?),
					entityFetch(
						dataInLocales(?, ?)
					),
					attributeHistogram(?, ?, ?),
					priceHistogram(?),
					facetSummary(?)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getSealedEntitiesCount());
		assertEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		assertAttributeHistograms(Objects.requireNonNull(entityResponse.getExtraResult(AttributeHistogram.class)), response.get().getExtraResults().getAttributeHistogramMap());
		assertPriceHistogram(Objects.requireNonNull(entityResponse.getExtraResult(PriceHistogram.class)), response.get().getExtraResults().getPriceHistogram());
		assertFacetSummary(Objects.requireNonNull(entityResponse.getExtraResult(FacetSummary.class)), response.get().getExtraResults().getFacetGroupStatisticsList());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich facet summary")
	void shouldReturnRichFacetSummary(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(21);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(CURRENCY_USD));
		params.add(convertQueryParam(PRICE_LIST_VIP));
		params.add(convertQueryParam(PRICE_LIST_BASIC));
		params.add(convertQueryParam(PRICE_LIST_B2B));
		params.add(convertQueryParam(PRICE_LIST_REFERENCE));
		params.add(convertQueryParam(PRICE_LIST_SELLOUT));
		params.add(convertQueryParam(Entities.PARAMETER));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam(FacetStatisticsDepth.IMPACT));
		params.add(convertQueryParam("code"));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					and(
						priceInCurrency(?),
						priceInPriceLists(?, ?, ?, ?, ?),
						userFilter(
							facetHaving(?, entityPrimaryKeyInSet(?))
						)
					)
				),
				require(
					page(?, ?),
					facetSummary(?,entityFetch(priceContentRespectingFilter(), attributeContent(?)),entityGroupFetch())
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		assertNotEquals(0, response.get().getRecordPage().getEntityReferencesCount());

		final EvitaResponse<EntityReference> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), EntityReference.class);

		assertFacetSummary(
			Objects.requireNonNull(entityResponse.getExtraResult(FacetSummary.class)),
			response.get().getExtraResults().getFacetGroupStatisticsList()
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of entity references with applied within hierarchy filter with computed hierarchy statistics and parents trees consisting of entity primary keys")
	void shouldReturnDataChunkOfEnrichedEntitiesWithHierarchyStatisticsAndParentsOfIntegers(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(5);
		params.add(convertQueryParam(Entities.CATEGORY));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(20));
		params.add(convertQueryParam("children"));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					hierarchyWithinSelf(entityPrimaryKeyInSet(?))
				),
				require(
					page(?, ?),
					hierarchyOfSelf(
						children(?)
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		final EvitaResponse<EntityReference> referenceResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), EntityReference.class);

		final Hierarchy hierarchyOfSelf = referenceResponse.getExtraResult(Hierarchy.class);

		if (hierarchyOfSelf != null) {
			final GrpcExtraResults extraResults = response.get().getExtraResults();
			assertHierarchy(
				hierarchyOfSelf,
				extraResults.getSelfHierarchy(),
				extraResults.getHierarchyMap()
			);
		}

		for (int i = 0; i < referenceResponse.getRecordData().size(); i++) {
			final EntityReference reference = referenceResponse.getRecordData().get(i);
			final GrpcEntityReference entityReference = response.get().getRecordPage().getEntityReferencesList().get(i);
			assertEquals(reference.getType(), entityReference.getEntityType());
			assertEquals(reference.getPrimaryKey(), entityReference.getPrimaryKey());
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return data chunk of enriched entities with computed hierarchy statistics and parents trees consisting of enriched entities")
	void shouldReturnDataChunkOfEnrichedEntitiesWithHierarchyStatisticsAndParentsOfEntities(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(3);
		params.add(convertQueryParam(Entities.CATEGORY));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));
		params.add(convertQueryParam("megaMenu"));

		final String stringQuery = """
			query(
				collection(?),
				require(
					page(?, ?),
					entityFetch(),
					hierarchyOfSelf(
						fromRoot(
							?,
							entityFetch(attributeContentAll())
						)
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		final EvitaResponse<SealedEntity> entityResponse = evita.createReadOnlySession(TEST_CATALOG).query(query.parsedQuery(), SealedEntity.class);

		final GrpcExtraResults extraResults = response.get().getExtraResults();
		assertHierarchy(
			entityResponse.getExtraResult(Hierarchy.class),
			extraResults.getSelfHierarchy(),
			extraResults.getHierarchyMap()
		);

		for (int i = 0; i < entityResponse.getRecordData().size(); i++) {
			final SealedEntity entity = entityResponse.getRecordData().get(i);
			final GrpcSealedEntity grpcSealedEntity = response.get().getRecordPage().getSealedEntitiesList().get(i);
			assertEquals(entity.getType(), grpcSealedEntity.getEntityType());
			assertEquals(entity.getPrimaryKey(), grpcSealedEntity.getPrimaryKey());
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should throw when trying to get size of non-existing collection")
	void shouldThrowWhenTryingToGetSizeOfNonExistingCollection(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		assertThrows(StatusRuntimeException.class, () -> evitaSessionBlockingStub.getEntityCollectionSize(
			GrpcEntityCollectionSizeRequest.newBuilder()
				.setEntityType("non-existing-collection")
				.build()
		));
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return entity count in collection when passing name of an existing one")
	void shouldReturnWhenTryingToGetSizeOfExistingCollection(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcEntityCollectionSizeResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.getEntityCollectionSize(
				GrpcEntityCollectionSizeRequest.newBuilder()
					.setEntityType(Entities.PRODUCT)
					.build()
			));

		assertDoesNotThrow(executable);

		final int size = response.get().getSize();

		assertTrue(size > 0);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should throw when trying to delete a collection without read/write session")
	void shouldNotDeleteCollectionWithoutReadWriteSession(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		assertThrows(StatusRuntimeException.class, () -> evitaSessionBlockingStub.deleteCollection(
			GrpcDeleteCollectionRequest.newBuilder()
				.setEntityType("non-existing-collection")
				.build()
		));
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should not throw when trying to delete non-existing collection")
	void shouldNotDeleteNonExistingCollection(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final AtomicReference<GrpcDeleteCollectionResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.deleteCollection(
				GrpcDeleteCollectionRequest.newBuilder()
					.setEntityType("non-existing-collection")
					.build()
			));

		assertDoesNotThrow(executable);
		assertFalse(response.get().getDeleted());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should delete collection when passing name of an existing collection")
	void shouldDeleteExistingCollection(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final AtomicReference<GrpcDeleteCollectionResponse> response = new AtomicReference<>();

		assertDoesNotThrow(() -> evita.createReadWriteSession(TEST_CATALOG).getEntityCollectionSize(Entities.BRAND));

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.deleteCollection(
				GrpcDeleteCollectionRequest.newBuilder()
					.setEntityType(Entities.BRAND)
					.build()
			));

		assertDoesNotThrow(executable);
		assertTrue(response.get().getDeleted());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return catalog schema")
	void shouldReturnCatalogSchema(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcCatalogSchemaResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.getCatalogSchema(GrpcGetCatalogSchemaRequest.newBuilder().setNameVariants(true).build()));

		assertDoesNotThrow(executable);

		final GrpcCatalogSchema catalogSchema = response.get().getCatalogSchema();
		assertNotNull(catalogSchema);
		assertEquals(TEST_CATALOG, catalogSchema.getName());
		assertEquals(3, catalogSchema.getAttributesCount());

		final GrpcGlobalAttributeSchema enabledAttribute = catalogSchema.getAttributesMap().get(ATTRIBUTE_ENABLED);
		assertEquals(GrpcEvitaDataType.BOOLEAN, enabledAttribute.getType());
		assertTrue(enabledAttribute.getDefaultValue().isInitialized());
		assertTrue(enabledAttribute.getDefaultValue().getBooleanValue());
		assertEquals("Sets visibility of the entity.", enabledAttribute.getDescription().getValue());

		final GrpcGlobalAttributeSchema inheritanceAttribute = catalogSchema.getAttributesMap().get(ATTRIBUTE_INHERITANCE);
		assertEquals(GrpcEvitaDataType.BOOLEAN_ARRAY, inheritanceAttribute.getType());
		assertTrue(inheritanceAttribute.getDefaultValue().isInitialized());
		final GrpcBooleanArray booleanArrayValue = inheritanceAttribute.getDefaultValue().getBooleanArrayValue();
		final List<Boolean> booleanList = booleanArrayValue.getValueList();
		assertEquals(3, booleanList.size());
		assertTrue(booleanList.get(0));
		assertFalse(booleanList.get(1));
		assertTrue(booleanList.get(2));

		final GrpcGlobalAttributeSchema opticsValue = catalogSchema.getAttributesMap().get(ATTRIBUTE_OPTICS);
		assertEquals(GrpcEvitaDataType.BYTE_ARRAY, opticsValue.getType());
		assertTrue(opticsValue.getDefaultValue().isInitialized());
		final GrpcIntegerArray byteArrayValue = opticsValue.getDefaultValue().getIntegerArrayValue();
		final List<Integer> byteList = byteArrayValue.getValueList();
		assertEquals(3, byteList.size());
		assertEquals(1, byteList.get(0));
		assertEquals(5, byteList.get(1));
		assertEquals(12, byteList.get(2));
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return catalog schema")
	void shouldReturnEntitySchema(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcEntitySchemaResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(
				evitaSessionBlockingStub.getEntitySchema(
					GrpcEntitySchemaRequest.newBuilder()
						.setEntityType(Entities.PRODUCT)
						.build()
				)
			);

		assertDoesNotThrow(executable);

		final GrpcEntitySchema entitySchema = response.get().getEntitySchema();
		assertNotNull(entitySchema);
		assertEquals(Entities.PRODUCT, entitySchema.getName());
		assertEquals(12, entitySchema.getAttributesCount());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return set of stored entity types")
	void shouldReturnSetOfEntityTypes(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcEntityTypesResponse> response = new AtomicReference<>();

		final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG);

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.getAllEntityTypes(Empty.newBuilder().build()));

		assertDoesNotThrow(executable);

		final Set<String> expectedEntityTypes = session.getAllEntityTypes();
		final List<String> actualEntityTypes = response.get().getEntityTypesList();

		assertEquals(expectedEntityTypes.size(), actualEntityTypes.size());
		for (String entityType : session.getAllEntityTypes()) {
			assertTrue(actualEntityTypes.contains(entityType));
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should not be able switch catalog state to ALIVE and close current session since testing instance of evita's catalog is by default in ALIVE state ")
	void shouldNotBeAbleToSwitchCatalogStateToAliveAndClose(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Executable executable = () -> evitaSessionBlockingStub.goLiveAndClose(Empty.newBuilder().build());

		assertThrows(StatusRuntimeException.class, executable);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able to close currently active session upon which this test operates")
	void shouldBeAbleToCloseCurrentlyActiveSessionUponWhichThisTestOperates(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Executable executable = () -> evitaSessionBlockingStub.close(
			GrpcCloseRequest.newBuilder()
				.setCommitBehaviour(GrpcCommitBehavior.WAIT_FOR_CHANGES_VISIBLE)
				.build()
		);

		assertDoesNotThrow(executable);

		final Executable executableFail = () -> evitaSessionBlockingStub.getEntity(GrpcEntityRequest.newBuilder().setEntityType(Entities.PRODUCT).setPrimaryKey(1).build());

		assertThrows(StatusRuntimeException.class, executableFail);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should not be able to mutate entity with read-only session")
	void shouldNotBeAbleMutateEntityWithReadOnlySession(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Executable executable = () -> evitaSessionBlockingStub.upsertEntity(
			GrpcUpsertEntityRequest.newBuilder()
				.setEntityMutation(
					GrpcEntityMutation.newBuilder()
						.setEntityUpsertMutation(
							GrpcEntityUpsertMutation.newBuilder()
								.setEntityType(Entities.PRODUCT)
								.setEntityPrimaryKey(Int32Value.of(1))
								.addMutations(
									GrpcLocalMutation.newBuilder()
										.setUpsertAttributeMutation(
											GrpcUpsertAttributeMutation.newBuilder()
												.setAttributeName(ATTRIBUTE_PRIORITY)
												.setAttributeLocale(EvitaDataTypesConverter.toGrpcLocale(CZECH_LOCALE))
												.setAttributeValue(EvitaDataTypesConverter.toGrpcEvitaValue(Long.MAX_VALUE))
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
				.build()
		);

		assertThrows(StatusRuntimeException.class, executable);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able to mutate entity attributes with read-write session with one mutation specified")
	void shouldBeAbleMutateEntityAttributesWithReadWriteSession(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final SealedEntity selectedEntity = entities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null && it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.findFirst()
			.orElseThrow();

		final GrpcEntityRequest entityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(Entities.PRODUCT)
			.setPrimaryKey(selectedEntity.getPrimaryKey())
			.setRequire("attributeContentAll()")
			.build();

		final GrpcEntityResponse originalEntity = evitaSessionBlockingStub.getEntity(entityRequest);

		final long priorityValue = 10;
		final BigDecimal quantityValueDelta = BigDecimal.valueOf(-20.34);
		final String name = "new-attribute-name";

		final AtomicReference<GrpcUpsertEntityResponse> upsertEntityResponse = new AtomicReference<>();

		final Executable executable = () -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			upsertEntityResponse.set(evitaSessionBlockingStub.upsertEntity(
					GrpcUpsertEntityRequest.newBuilder()
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.setEntityMutation(
							GrpcEntityMutation.newBuilder()
								.setEntityUpsertMutation(
									GrpcEntityUpsertMutation.newBuilder()
										.setEntityType(originalEntity.getEntity().getEntityType())
										.setEntityPrimaryKey(Int32Value.of(originalEntity.getEntity().getPrimaryKey()))
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setApplyDeltaAttributeMutation(
													GrpcApplyDeltaAttributeMutation.newBuilder()
														.setAttributeName(ATTRIBUTE_QUANTITY)
														.setBigDecimalRequiredRangeAfterApplication(
															EvitaDataTypesConverter.toGrpcBigDecimalNumberRange(
																BigDecimalNumberRange.between(
																	BigDecimal.valueOf(0),
																	BigDecimal.valueOf(1000)
																)
															)
														)
														.setBigDecimalDelta(EvitaDataTypesConverter.toGrpcBigDecimal(quantityValueDelta))
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setUpsertAttributeMutation(
													GrpcUpsertAttributeMutation.newBuilder()
														.setAttributeName(ATTRIBUTE_PRIORITY)
														.setAttributeValue(EvitaDataTypesConverter.toGrpcEvitaValue(priorityValue))
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setUpsertAttributeMutation(
													GrpcUpsertAttributeMutation.newBuilder()
														.setAttributeName(ATTRIBUTE_NAME)
														.setAttributeLocale(EvitaDataTypesConverter.toGrpcLocale(CZECH_LOCALE))
														.setAttributeValue(EvitaDataTypesConverter.toGrpcEvitaValue(name))
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setRemoveAttributeMutation(
													GrpcRemoveAttributeMutation.newBuilder()
														.setAttributeName(ATTRIBUTE_SIZE)
														.build()
												)
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
			);
		};

		assertDoesNotThrow(executable);

		assertNull(originalEntity.getEntity().getLocalizedAttributesMap().get(ATTRIBUTE_NAME));
		final GrpcEvitaValue attributeName = upsertEntityResponse.get().getEntity().getLocalizedAttributesMap().get(CZECH_LOCALE.toLanguageTag()).getAttributesMap().get(ATTRIBUTE_NAME);
		assertNotNull(attributeName);
		assertEquals(name, attributeName.getStringValue());

		assertNotNull(originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_SIZE));
		assertNull(upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_SIZE));

		assertNotEquals(
			originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_PRIORITY),
			upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_PRIORITY)
		);
		assertNotEquals(
			originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_QUANTITY),
			upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_QUANTITY)
		);
		assertEquals(upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_PRIORITY).getLongValue(), priorityValue);
		final BigDecimal originalQuantityValue = EvitaDataTypesConverter.toBigDecimal(originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_QUANTITY).getBigDecimalValue());
		final BigDecimal updatedQuantityValue = EvitaDataTypesConverter.toBigDecimal(upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_QUANTITY).getBigDecimalValue());
		assertEquals(originalQuantityValue.add(quantityValueDelta), updatedQuantityValue);
		//updated attributes should have increased version
		assertEquals(
			Int32Value.of(originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_QUANTITY).getVersion().getValue() + 1),
			upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_QUANTITY).getVersion()
		);
		assertEquals(
			Int32Value.of(originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_PRIORITY).getVersion().getValue() + 1),
			upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_PRIORITY).getVersion()
		);
		//new attribute should have version 1
		assertEquals(
			Int32Value.of(1),
			upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_MANUFACTURED).getVersion()
		);
		//not changed attributes should have same version
		assertEquals(originalEntity.getEntity().getGlobalAttributesMap().get(ATTRIBUTE_EAN).getVersion(), upsertEntityResponse.get().getEntity().getGlobalAttributesMap().get(ATTRIBUTE_EAN).getVersion());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able to mutate entity associated data with read-write session")
	void shouldBeAbleMutateEntityAssociatedDataWithReadWriteSession(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final SealedEntity existingEntity = entities.stream()
			.filter(e -> e.getType().equals(Entities.PRODUCT) &&
				e.getAssociatedDataValues().stream().anyMatch(a -> a.key().associatedDataName().equals(ASSOCIATED_DATA_REFERENCED_FILES)) &&
				e.getAssociatedDataValues().stream().noneMatch(a -> a.key().associatedDataName().equals(ASSOCIATED_DATA_LABELS))
			)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable entity not found!"));

		final GrpcEntityRequest entityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(Entities.PRODUCT)
			.setPrimaryKey(Objects.requireNonNull(existingEntity.getPrimaryKey()))
			.setRequire("associatedDataContentAll(), dataInLocalesAll()")
			.build();

		final GrpcEntityResponse originalEntity = evitaSessionBlockingStub.getEntity(entityRequest);

		final AssociatedDataComplexObjectExample associatedData = new AssociatedDataComplexObjectExample(
			"test", new BigDecimal("100.453"),
			OffsetDateTime.of(2023, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC)
		);
		final ComplexDataObject associatedDataComplexObject = (ComplexDataObject) ComplexDataObjectConverter.getSerializableForm(associatedData);

		final String czLocaleString = CZECH_LOCALE.toLanguageTag();

		final AtomicReference<GrpcUpsertEntityResponse> upsertEntityResponse = new AtomicReference<>();

		final Executable executable = () -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			upsertEntityResponse.set(
				evitaSessionBlockingStub.upsertEntity(
					GrpcUpsertEntityRequest.newBuilder()
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.setEntityMutation(
							GrpcEntityMutation.newBuilder()
								.setEntityUpsertMutation(
									GrpcEntityUpsertMutation.newBuilder()
										.setEntityType(originalEntity.getEntity().getEntityType())
										.setEntityPrimaryKey(Int32Value.of(originalEntity.getEntity().getPrimaryKey()))
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setUpsertAssociatedDataMutation(
													GrpcUpsertAssociatedDataMutation.newBuilder()
														.setAssociatedDataName(ASSOCIATED_DATA_LABELS)
														.setAssociatedDataLocale(EvitaDataTypesConverter.toGrpcLocale(CZECH_LOCALE))
														.setAssociatedDataValue(
															GrpcEvitaAssociatedDataValue.newBuilder()
																.setRoot(
																	EvitaDataTypesConverter.toGrpcDataItem(associatedDataComplexObject.root())
																)
																.build())
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setRemoveAssociatedDataMutation(
													GrpcRemoveAssociatedDataMutation.newBuilder()
														.setAssociatedDataName(ASSOCIATED_DATA_REFERENCED_FILES)
														.build()
												)
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
			);
		};

		assertDoesNotThrow(executable);

		assertNull(originalEntity.getEntity().getLocalizedAssociatedDataMap().get(czLocaleString));
		assertNotNull(upsertEntityResponse.get().getEntity().getLocalizedAssociatedDataMap().get(czLocaleString));
		assertNotNull(upsertEntityResponse.get().getEntity().getLocalizedAssociatedDataMap().get(czLocaleString));
		assertNotNull(originalEntity.getEntity().getGlobalAssociatedDataMap().get(ASSOCIATED_DATA_REFERENCED_FILES));
		assertNull(upsertEntityResponse.get().getEntity().getGlobalAssociatedDataMap().get(ASSOCIATED_DATA_REFERENCED_FILES));
		assertEquals(
			existingEntity.getAssociatedDataValue(ASSOCIATED_DATA_REFERENCED_FILES)
				.map(AssociatedDataValue::value)
				.orElseThrow(),
			EvitaDataTypesConverter.toComplexObject(originalEntity.getEntity().getGlobalAssociatedDataMap().get(ASSOCIATED_DATA_REFERENCED_FILES).getRoot())
		);
		assertEquals(
			associatedDataComplexObject,
			EvitaDataTypesConverter.toComplexObject(
				upsertEntityResponse.get().getEntity().getLocalizedAssociatedDataMap().get(czLocaleString).getAssociatedDataMap().get(ASSOCIATED_DATA_LABELS).getRoot())
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able to mutate entity prices with read-write session")
	void shouldBeAbleMutateEntityPricesWithReadWriteSession(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final SealedEntity existingEntity = entities.stream()
			.filter(e -> e.getType().equals(Entities.PRODUCT) &&
				e.getPrices().size() > 2 && e.getPrices().size() < 6
			)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable entity not found!"));

		//noinspection ConstantConditions
		final GrpcEntityRequest entityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(Entities.PRODUCT)
			.setPrimaryKey(existingEntity.getPrimaryKey())
			.setRequire("priceContentRespectingFilter()")
			.build();

		final List<String> existingPriceLists = existingEntity.getPrices().stream().map(PriceContract::priceList).toList();

		final String insertNewPriceIntoNonExistingPriceList = Arrays.stream(PRICE_LIST_NAMES)
			.filter(p -> !existingPriceLists.contains(p)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable price list not found!"));
		final String insertNewPriceIntoExistingPriceList = existingPriceLists.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable price list not found!"));
		final GrpcCurrency insertCurrency = EvitaDataTypesConverter.toGrpcCurrency(CURRENCY_CZK);
		final int insertPriceId = 1000000;

		final PriceContract priceToRemove = existingEntity.getPrices().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable price not found!"));
		final GrpcCurrency removedPriceCurrency = EvitaDataTypesConverter.toGrpcCurrency(priceToRemove.currency());

		final PriceContract toUpdate = existingEntity.getPrices().stream().skip(existingEntity.getPrices().size() - 1).findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable price not found!"));
		final String updatedPriceList = toUpdate.priceList();
		final GrpcCurrency updatedCurrency = EvitaDataTypesConverter.toGrpcCurrency(toUpdate.currency());
		final int updatedPriceId = toUpdate.priceId();

		final GrpcEntityResponse originalEntity = evitaSessionBlockingStub.getEntity(entityRequest);

		final AtomicReference<GrpcUpsertEntityResponse> upsertEntityResponse = new AtomicReference<>();

		final Executable executable = () -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			upsertEntityResponse.set(evitaSessionBlockingStub.upsertEntity(
					GrpcUpsertEntityRequest.newBuilder()
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.setEntityMutation(
							GrpcEntityMutation.newBuilder()
								.setEntityUpsertMutation(
									GrpcEntityUpsertMutation.newBuilder()
										.setEntityType(originalEntity.getEntity().getEntityType())
										.setEntityPrimaryKey(Int32Value.of(originalEntity.getEntity().getPrimaryKey()))
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setUpsertPriceMutation(
													GrpcUpsertPriceMutation.newBuilder()
														.setPriceId(updatedPriceId)
														.setPriceList(updatedPriceList)
														.setCurrency(updatedCurrency)
														.setPriceWithoutTax(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(100)))
														.setTaxRate(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(10.55)))
														.setPriceWithTax(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(110.55)))
														.setIndexed(true)
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setUpsertPriceMutation(
													GrpcUpsertPriceMutation.newBuilder()
														.setPriceId(insertPriceId)
														.setPriceList(insertNewPriceIntoNonExistingPriceList)
														.setCurrency(insertCurrency)
														.setPriceWithoutTax(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(10)))
														.setTaxRate(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(50)))
														.setPriceWithTax(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(15)))
														.setIndexed(false)
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setUpsertPriceMutation(
													GrpcUpsertPriceMutation.newBuilder()
														.setPriceId(insertPriceId)
														.setPriceList(insertNewPriceIntoExistingPriceList)
														.setCurrency(insertCurrency)
														.setPriceWithoutTax(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(10)))
														.setTaxRate(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(50)))
														.setPriceWithTax(EvitaDataTypesConverter.toGrpcBigDecimal(BigDecimal.valueOf(15)))
														.setIndexed(false)
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setRemovePriceMutation(
													GrpcRemovePriceMutation.newBuilder()
														.setPriceId(priceToRemove.priceId())
														.setPriceList(priceToRemove.priceList())
														.setCurrency(removedPriceCurrency)
														.build()
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setSetPriceInnerRecordHandlingMutation(
													GrpcSetPriceInnerRecordHandlingMutation.newBuilder()
														.setPriceInnerRecordHandling(GrpcPriceInnerRecordHandling.SUM)
														.build()
												)
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
			);
		};

		assertDoesNotThrow(executable);


		assertNotEquals(originalEntity.getEntity().getPriceInnerRecordHandling(), upsertEntityResponse.get().getEntity().getPriceInnerRecordHandling());

		final Map<String, List<GrpcPrice>> originalEntityPricesByPriceList = originalEntity.getEntity().getPricesList().stream().collect(Collectors.groupingBy(GrpcPrice::getPriceList));
		final Map<String, List<GrpcPrice>> upsertedEntityPricesByPriceList = upsertEntityResponse.get().getEntity().getPricesList().stream().collect(Collectors.groupingBy(GrpcPrice::getPriceList));

		assertTrue(originalEntityPricesByPriceList.get(priceToRemove.priceList()).stream().anyMatch(p -> p.getPriceId() == priceToRemove.priceId() && p.getCurrency().equals(removedPriceCurrency)));
		assertFalse(upsertedEntityPricesByPriceList.get(priceToRemove.priceList()).stream().anyMatch(p -> p.getPriceId() == priceToRemove.priceId() && p.getCurrency().equals(removedPriceCurrency)));

		assertNotNull(originalEntityPricesByPriceList.get(insertNewPriceIntoExistingPriceList));
		assertTrue(upsertedEntityPricesByPriceList.get(insertNewPriceIntoExistingPriceList).stream().anyMatch(p -> p.getPriceId() == insertPriceId && p.getCurrency().equals(insertCurrency)));

		assertFalse(originalEntityPricesByPriceList.containsKey(insertNewPriceIntoNonExistingPriceList));
		assertTrue(upsertedEntityPricesByPriceList.containsKey(insertNewPriceIntoNonExistingPriceList));

		final Optional<GrpcPrice> preUpdatePrice = originalEntityPricesByPriceList.get(updatedPriceList).stream().filter(p -> p.getPriceId() == updatedPriceId && p.getCurrency().equals(updatedCurrency)).findFirst();
		final Optional<GrpcPrice> postUpdatePrice = upsertedEntityPricesByPriceList.get(updatedPriceList).stream().filter(p -> p.getPriceId() == updatedPriceId && p.getCurrency().equals(updatedCurrency)).findFirst();

		assertTrue(preUpdatePrice.isPresent());
		assertTrue(postUpdatePrice.isPresent());

		assertNotEquals(
			EvitaDataTypesConverter.toBigDecimal(preUpdatePrice.get().getPriceWithoutTax()),
			EvitaDataTypesConverter.toBigDecimal(postUpdatePrice.get().getPriceWithoutTax())
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able to mutate entity hierarchy placement with read-write session")
	void shouldBeAbleMutateEntityHierarchyPlacementWithReadWriteSession(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final List<SealedEntity> originalCategoryEntities = evita.createReadOnlySession(TEST_CATALOG)
			.queryListOfSealedEntities(Query.query(
				collection(Entities.CATEGORY), require(entityFetch(hierarchyContent()))
			));
		final Map<Integer, Integer> parentChildIndex = originalCategoryEntities
			.stream()
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getParentEntity().map(EntityClassifier::getPrimaryKey).orElse(0)
				)
			);
		final String entityType = Entities.CATEGORY;

		final SealedEntity categoryWithoutHierarchyPlacement = originalCategoryEntities.stream().filter(e -> e.getType().equals(entityType) &&
				e.getParentEntity().isEmpty())
			.findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable category not found!"));

		final SealedEntity categoryWithHierarchyPlacement = originalCategoryEntities.stream()
			.filter(e -> e.getType().equals(entityType) && e.getParentEntity().isPresent() && !relatesTo(e.getPrimaryKey(), categoryWithoutHierarchyPlacement.getPrimaryKey(), parentChildIndex))
			.findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable category not found!"));

		//noinspection ConstantConditions
		final GrpcEntityRequest toAddHierarchyEntityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(entityType)
			.setPrimaryKey(categoryWithoutHierarchyPlacement.getPrimaryKey())
			.build();

		//noinspection ConstantConditions
		final GrpcEntityRequest toRemoveHierarchyEntityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(entityType)
			.setPrimaryKey(categoryWithHierarchyPlacement.getPrimaryKey())
			.setRequire(hierarchyContent().toString())
			.build();

		//noinspection ConstantConditions
		final int parentPrimaryKey = originalCategoryEntities.stream().filter(e -> !Objects.equals(e.getPrimaryKey(), categoryWithoutHierarchyPlacement.getPrimaryKey()))
			.findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable category not found!")).getPrimaryKey();

		//adding hierarchy to non-hierarchical entity
		final GrpcEntityResponse originalToAddHierarchyEntity = evitaSessionBlockingStub.getEntity(toAddHierarchyEntityRequest);
		final AtomicReference<GrpcUpsertEntityResponse> upsertToAddHierarchyEntityResponse = new AtomicReference<>();

		final Executable addHierarchyExecutable = () -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			upsertToAddHierarchyEntityResponse.set(
				evitaSessionBlockingStub.upsertEntity(
					GrpcUpsertEntityRequest.newBuilder()
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.setEntityMutation(
							GrpcEntityMutation.newBuilder()
								.setEntityUpsertMutation(
									GrpcEntityUpsertMutation.newBuilder()
										.setEntityType(originalToAddHierarchyEntity.getEntity().getEntityType())
										.setEntityPrimaryKey(Int32Value.of(originalToAddHierarchyEntity.getEntity().getPrimaryKey()))
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setSetParentMutation(
													GrpcSetParentMutation.newBuilder()
														.setPrimaryKey(parentPrimaryKey)
												)
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
			);
		};

		assertDoesNotThrow(addHierarchyExecutable);

		assertFalse(originalToAddHierarchyEntity.getEntity().hasParent());
		assertTrue(upsertToAddHierarchyEntityResponse.get().getEntity().hasParent());

		//removing hierarchy from hierarchical entity
		final GrpcEntityResponse originalToRemoveHierarchyEntity = evitaSessionBlockingStub.getEntity(toRemoveHierarchyEntityRequest);
		final AtomicReference<GrpcUpsertEntityResponse> upsertToRemoveHierarchyEntityResponse = new AtomicReference<>();

		final Executable removeHierarchyExecutable = () -> upsertToRemoveHierarchyEntityResponse.set(evitaSessionBlockingStub.upsertEntity(
				GrpcUpsertEntityRequest.newBuilder()
					.setEntityMutation(
						GrpcEntityMutation.newBuilder()
							.setEntityUpsertMutation(
								GrpcEntityUpsertMutation.newBuilder()
									.setEntityType(originalToRemoveHierarchyEntity.getEntity().getEntityType())
									.setEntityPrimaryKey(Int32Value.of(originalToRemoveHierarchyEntity.getEntity().getPrimaryKey()))
									.addMutations(
										GrpcLocalMutation.newBuilder()
											.setRemoveParentMutation(
												GrpcRemoveParentMutation.newBuilder().build()
											)
											.build()
									)
									.build()
							)
							.build()
					)
					.build()
			)
		);

		assertDoesNotThrow(removeHierarchyExecutable);

		assertTrue(originalToRemoveHierarchyEntity.getEntity().hasParent());
		assertFalse(upsertToRemoveHierarchyEntityResponse.get().getEntity().hasParent());
	}

	private boolean relatesTo(int primaryKey, int parentPrimaryKey, Map<Integer, Integer> parentChildIndex) {
		if (primaryKey == parentPrimaryKey) {
			return true;
		} else {
			final Integer nextPrimaryKey = parentChildIndex.get(primaryKey);
			if (nextPrimaryKey == 0) {
				return false;
			} else {
				return relatesTo(nextPrimaryKey, parentPrimaryKey, parentChildIndex);
			}
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should be able to create entity with specified references with read-write session")
	void shouldBeAbleMutateEntityReferencesWithReadWriteSession(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_WRITE);

		final GrpcEntityRequest newEntityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(Entities.PRODUCT)
			.setRequire("referenceContentAll(entityFetch(attributeContentAll()))")
			.build();

		final long attributeValue = 102L;
		final String attributeName = "categoryPriority";
		final GrpcCardinality cardinality = GrpcCardinality.ZERO_OR_MORE;
		final String referenceEntityType = Entities.CATEGORY;
		final int referencePrimaryKey = 9;
		final String referenceParameterEntityType = Entities.PARAMETER;
		final String referenceGroupEntityType = Entities.PARAMETER_GROUP;
		final int referenceGroupPrimaryKey = 1;

		final AtomicReference<GrpcUpsertEntityResponse> upsertEntityResponse = new AtomicReference<>();

		final Executable executableInsert = () -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			upsertEntityResponse.set(
				evitaSessionBlockingStub.upsertEntity(
					GrpcUpsertEntityRequest.newBuilder()
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.setEntityMutation(
							GrpcEntityMutation.newBuilder()
								.setEntityUpsertMutation(
									GrpcEntityUpsertMutation.newBuilder()
										.setEntityType(newEntityRequest.getEntityType())
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setInsertReferenceMutation(
													GrpcInsertReferenceMutation.newBuilder()
														.setReferenceName(referenceEntityType)
														.setReferencePrimaryKey(referencePrimaryKey)
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setInsertReferenceMutation(
													GrpcInsertReferenceMutation.newBuilder()
														.setReferenceName(referenceParameterEntityType)
														.setReferencePrimaryKey(referenceGroupPrimaryKey)
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setSetReferenceGroupMutation(
													GrpcSetReferenceGroupMutation.newBuilder()
														.setReferenceName(referenceParameterEntityType)
														.setReferencePrimaryKey(referenceGroupPrimaryKey)
														.setGroupType(StringValue.of(referenceGroupEntityType))
														.setGroupPrimaryKey(referenceGroupPrimaryKey)
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setReferenceAttributeMutation(
													GrpcReferenceAttributeMutation.newBuilder()
														.setReferenceName(referenceEntityType)
														.setReferencePrimaryKey(referencePrimaryKey)
														.setAttributeMutation(GrpcAttributeMutation.newBuilder()
															.setUpsertAttributeMutation(GrpcUpsertAttributeMutation.newBuilder()
																.setAttributeName(attributeName)
																.setAttributeValue(EvitaDataTypesConverter.toGrpcEvitaValue(attributeValue))
																.build())
															.build()
														)
												)
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
			);
		};

		assertDoesNotThrow(executableInsert);
		assertEquals(2, upsertEntityResponse.get().getEntity().getReferencesCount());
		final GrpcReference referenceCategoryAfterInsert = upsertEntityResponse.get().getEntity().getReferencesList()
			.stream()
			.filter(it -> referenceEntityType.equals(it.getReferencedEntityReference().getEntityType()))
			.findFirst()
			.orElseThrow(() -> new AssertionFailedError("Failed to find reference with entity type " + referenceEntityType + " in result!"));
		assertEquals(referenceEntityType, referenceCategoryAfterInsert.getReferencedEntityReference().getEntityType());
		assertEquals(referencePrimaryKey, referenceCategoryAfterInsert.getReferencedEntityReference().getPrimaryKey());
		assertEquals(1, referenceCategoryAfterInsert.getGlobalAttributesCount());
		assertEquals(cardinality, referenceCategoryAfterInsert.getReferenceCardinality());
		assertEquals(attributeValue, referenceCategoryAfterInsert.getGlobalAttributesMap().get(attributeName).getLongValue());

		final GrpcReference referenceParameterAfterInsert = upsertEntityResponse.get().getEntity().getReferencesList()
			.stream()
			.filter(it -> referenceParameterEntityType.equals(it.getReferencedEntityReference().getEntityType()))
			.findFirst()
			.orElseThrow(() -> new AssertionFailedError("Failed to find reference with entity type " + referenceParameterEntityType + " in result!"));
		assertEquals(referenceParameterEntityType, referenceParameterAfterInsert.getReferencedEntityReference().getEntityType());
		assertEquals(referenceGroupPrimaryKey, referenceParameterAfterInsert.getGroupReferencedEntityReference().getPrimaryKey());
		assertEquals(referenceGroupEntityType, referenceParameterAfterInsert.getGroupReferencedEntityReference().getEntityType());

		final int removeReferenceId = 6;
		final SealedEntity existingEntity = entities.stream().filter(entity ->
				{
					final Collection<ReferenceContract> references = entity.getReferences();
					return references.stream().filter(reference ->
						reference.getReferenceName().equals(referenceEntityType) &&
							(reference.getReferencedPrimaryKey() != referencePrimaryKey ||
								reference.getReferencedPrimaryKey() == removeReferenceId)
					).count() == 2 && references.size() == 3;
				}
			).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable reference not found!"));

		//noinspection ConstantConditions
		final GrpcEntityRequest existingEntityRequest = GrpcEntityRequest.newBuilder()
			.setEntityType(Entities.PRODUCT)
			.setPrimaryKey(existingEntity.getPrimaryKey())
			.setRequire("referenceContentAll(entityFetch(attributeContentAll()))")
			.build();

		final GrpcEntityResponse originalEntity = evitaSessionBlockingStub.getEntity(existingEntityRequest);

		final Executable executableUpdate = () -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(QueryConstraints.entityFetchAll().getRequirements());
			upsertEntityResponse.set(
				evitaSessionBlockingStub.upsertEntity(
					GrpcUpsertEntityRequest.newBuilder()
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.setEntityMutation(
							GrpcEntityMutation.newBuilder()
								.setEntityUpsertMutation(
									GrpcEntityUpsertMutation.newBuilder()
										.setEntityType(existingEntityRequest.getEntityType())
										.setEntityPrimaryKey(Int32Value.of(existingEntityRequest.getPrimaryKey()))
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setInsertReferenceMutation(
													GrpcInsertReferenceMutation.newBuilder()
														.setReferenceName(referenceEntityType)
														.setReferencePrimaryKey(referencePrimaryKey)
														.setReferencedEntityType(StringValue.of(referenceEntityType))
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setRemoveReferenceMutation(
													GrpcRemoveReferenceMutation.newBuilder()
														.setReferenceName(referenceEntityType)
														.setReferencePrimaryKey(removeReferenceId)
												)
												.build()
										)
										.addMutations(
											GrpcLocalMutation.newBuilder()
												.setReferenceAttributeMutation(
													GrpcReferenceAttributeMutation.newBuilder()
														.setReferenceName(referenceEntityType)
														.setReferencePrimaryKey(referencePrimaryKey)
														.setAttributeMutation(GrpcAttributeMutation.newBuilder()
															.setUpsertAttributeMutation(GrpcUpsertAttributeMutation.newBuilder()
																.setAttributeName(attributeName)
																.setAttributeValue(EvitaDataTypesConverter.toGrpcEvitaValue(attributeValue))
																.build())
															.build()
														)
												)
												.build()
										)
										.build()
								)
								.build()
						)
						.build()
				)
			);
		};

		assertDoesNotThrow(executableUpdate);

		final GrpcReference newReferenceAfterUpdate = upsertEntityResponse.get().getEntity().getReferencesList().stream()
			.filter(r ->
				r.getReferencedEntityReference().getEntityType().equals(referenceEntityType) &&
					r.getReferencedEntityReference().getPrimaryKey() == referencePrimaryKey)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Suitable reference not found!"));

		//one added, one removed
		assertEquals(originalEntity.getEntity().getReferencesCount(), upsertEntityResponse.get().getEntity().getReferencesCount());
		assertEquals(referenceEntityType, newReferenceAfterUpdate.getReferencedEntityReference().getEntityType());
		assertEquals(referencePrimaryKey, newReferenceAfterUpdate.getReferencedEntityReference().getPrimaryKey());
		assertFalse(newReferenceAfterUpdate.hasGroupReferencedEntityReference());

		final Optional<GrpcReference> existingReferenceAfterUpdate = upsertEntityResponse.get().getEntity().getReferencesList().stream()
			.filter(r ->
				r.getReferencedEntityReference().getEntityType().equals(referenceEntityType) &&
					r.getReferencedEntityReference().getPrimaryKey() != referencePrimaryKey
			)
			.findFirst();

		if (existingReferenceAfterUpdate.isPresent() && existingReferenceAfterUpdate.get().getGlobalAttributesMap().size() > 0) {
			assertNotEquals(attributeValue, existingReferenceAfterUpdate.get().getGlobalAttributesMap().get(attributeName).getLongValue());
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@DisplayName("Should return query record page in binary data format")
	void shouldReturnQueryRecordPageInBinaryDataFormat(Evita evita, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub = clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.BINARY_READ_ONLY);

		final List<GrpcQueryParam> params = new ArrayList<>(8);
		params.add(convertQueryParam(Entities.PRODUCT));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(2));
		params.add(convertQueryParam(3));
		params.add(convertQueryParam(4));
		params.add(convertQueryParam(5));
		params.add(convertQueryParam(1));
		params.add(convertQueryParam(Integer.MAX_VALUE));

		final String stringQuery = """
			query(
				collection(?),
				filterBy(
					entityPrimaryKeyInSet(?, ?, ?, ?, ?)
				),
				require(
					page(?, ?),
					entityFetch(
						attributeContentAll(),
						priceContentRespectingFilter(),
						referenceContentAll(),
						associatedDataContentAll()
					)
				)
			)
			""";

		final AtomicReference<GrpcQueryResponse> response = new AtomicReference<>();

		final Executable executable = () ->
			response.set(evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
				.setQuery(stringQuery)
				.addAllPositionalQueryParams(params)
				.build()
			));

		assertDoesNotThrow(executable);

		final QueryWithParameters query = QueryUtil.parseQuery(stringQuery, params, Collections.emptyMap(), null, NO_OP);

		assertNotNull(query);

		final List<BinaryEntity> entityResponse = evita.createSession(new SessionTraits(TEST_CATALOG, SessionFlags.BINARY)).queryList(query.parsedQuery(), BinaryEntity.class);

		for (int i = 0; i < entityResponse.size(); i++) {
			final GrpcBinaryEntity grpcBinaryEntity = response.get().getRecordPage().getBinaryEntitiesList().get(i);
			final BinaryEntity binaryEntity = entityResponse.get(i);
			assertBinaryEntity(binaryEntity, grpcBinaryEntity);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should omit catalog versions unknown to history instead of failing the whole GetTransactionOverview call")
	void shouldOmitCatalogVersionsUnknownToHistoryFromTransactionOverview(GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// versions far beyond anything this catalog ever reached cannot be known to history; before the
		// fix, DefaultCatalogPersistenceService handed back a positionally-aligned list holding nulls at
		// those slots, and mapping those straight through the @Nonnull converter NPE'd and failed the
		// entire RPC, contradicting the documented contract that unknown versions are simply omitted
		final AtomicReference<GetTransactionOverviewResponse> response = new AtomicReference<>();
		assertDoesNotThrow(
			() -> response.set(
				evitaSessionBlockingStub.getTransactionOverview(
					GetTransactionOverviewRequest
						.newBuilder()
						.addCatalogVersion(Long.MAX_VALUE - 1L)
						.addCatalogVersion(Long.MAX_VALUE)
						.build()
				)
			)
		);

		assertNotNull(response.get());
		assertTrue(
			response.get().getTransactionOverviewsList().isEmpty(),
			"no overview may be returned for catalog versions unknown to history"
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should return GetTransactionOverview entries correlatable by their own catalog version when the request mixes known and unknown versions")
	void shouldReturnTransactionOverviewEntriesCorrelatableByCatalogVersion(
		Evita evita,
		GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final long currentCatalogVersion = evita.queryCatalog(
			TEST_CATALOG, EvitaSessionContract::getCatalogVersion
		);
		final long unknownCatalogVersion = Long.MAX_VALUE;

		final AtomicReference<GetTransactionOverviewResponse> response = new AtomicReference<>();
		assertDoesNotThrow(
			() -> response.set(
				evitaSessionBlockingStub.getTransactionOverview(
					GetTransactionOverviewRequest
						.newBuilder()
						// the unknown version deliberately comes first, so a positional reading of the
						// response would mis-attribute the surviving entry to it
						.addCatalogVersion(unknownCatalogVersion)
						.addCatalogVersion(currentCatalogVersion)
						.build()
				)
			)
		);

		assertNotNull(response.get());
		final List<GrpcTransactionOverview> overviews = response.get().getTransactionOverviewsList();
		// exactly one of the two requested versions can possibly be known to history, so the unknown one
		// must have been dropped rather than padded - a response of size 2 would mean it was materialized
		assertTrue(
			overviews.size() <= 1,
			"the version unknown to history must be omitted, leaving at most one entry, but got " +
				overviews.size()
		);
		final Set<Long> requestedVersions = Set.of(unknownCatalogVersion, currentCatalogVersion);
		for (final GrpcTransactionOverview overview : overviews) {
			assertTrue(
				requestedVersions.contains(overview.getCatalogVersion()),
				"every returned entry must carry one of the requested catalog versions"
			);
			assertNotEquals(
				unknownCatalogVersion,
				overview.getCatalogVersion(),
				"a version unknown to history must never be materialized into an entry"
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should keep a record's entity capture and its local-mutation captures together, never split across a mutations-history page boundary")
	void shouldKeepARecordIntactAcrossMutationsHistoryPageBoundary(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// the shared dataset's initial 1000-entity generation runs while the catalog is still warming up,
		// before goLiveAndClose() - mutations committed during warm-up never reach CDC/WAL history, so this
		// test seeds its own real, committed record rather than assuming any history already exists. One
		// upsert carrying three local mutations - the entity-level capture plus all three locals share a
		// single (version, index) pair and must never be split across a page boundary. The LAST entity
		// matching this filter is picked (rather than findFirst, which
		// shouldBeAbleMutateEntityAttributesWithReadWriteSession already targets, harmlessly there only
		// because that test's session is a dry run and never persists) so this real, persisted write cannot
		// perturb that other test's expectations.
		final SealedEntity selectedEntity = entities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null && it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.reduce((first, second) -> second)
			.orElseThrow();
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(selectedEntity.getType(), selectedEntity.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 555L)
					.setAttribute(ATTRIBUTE_QUANTITY, BigDecimal.valueOf(77))
					.setAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, "record-alignment-test")
					.upsertVia(session);
			}
		);
		final long recordVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		// a gRPC session pins to the catalog version visible when it was created, so it must be opened only
		// after the commit above - otherwise its own implicit "current version" fallback would still point
		// at the pre-commit snapshot and the explicit sinceVersion below would be clamped back down to it
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// page 1: the transaction header alone - a record of size 1
		final GetMutationsHistoryPageResponse headerPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(1))
				.build()
		);
		assertEquals(1, headerPage.getChangeCaptureCount());
		assertTrue(headerPage.getHasNext());

		// page 2: the entity-level capture and its three local-mutation captures together, never split
		final GetMutationsHistoryPageResponse recordPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.setPage(Int32Value.of(2))
				.setPageSize(Int32Value.of(1))
				.build()
		);
		final List<GrpcChangeCatalogCapture> recordCaptures = recordPage.getChangeCaptureList();
		assertEquals(4, recordCaptures.size(), "the entity capture plus its three local mutations must arrive together");
		final long expectedVersion = recordCaptures.get(0).getVersion().getValue();
		final int expectedIndex = recordCaptures.get(0).getIndex().getValue();
		for (final GrpcChangeCatalogCapture capture : recordCaptures) {
			assertEquals(expectedVersion, capture.getVersion().getValue());
			assertEquals(expectedIndex, capture.getIndex().getValue());
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should echo a stable paging anchor and keep pages gap/duplicate-free across a mutations-history page boundary")
	void shouldEchoStableAnchorAcrossMutationsHistoryPages(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// one commit touching three distinct entities - header(V) + three entity-level payload records -
		// gives this test real, self-contained history spanning more than one page at pageSize 3, without
		// depending on the warm-up-only generation history described in the sibling test above
		final List<SealedEntity> targets = entities.subList(entities.size() - 3, entities.size());
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int idx = 0; idx < targets.size(); idx++) {
					final SealedEntity target = targets.get(idx);
					session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 1000L + idx)
						.upsertVia(session);
				}
			}
		);
		final long expectedAnchorVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		// opened only after the commit above - see the sibling record-alignment test for why order matters
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);
		final int pageSize = 3;

		final GetMutationsHistoryPageResponse firstPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(expectedAnchorVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertEquals(expectedAnchorVersion, firstPage.getSinceVersion());
		assertTrue(firstPage.getHasNext(), "one header plus three entity records must not fit a single page of size 3");

		final GetMutationsHistoryPageResponse secondPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				// pinned explicitly, exactly as the proto comment prescribes for page 2 onward
				.setSinceVersion(Int64Value.of(firstPage.getSinceVersion()))
				.setPage(Int32Value.of(2))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertEquals(expectedAnchorVersion, secondPage.getSinceVersion());

		final GetMutationsHistoryPageResponse combinedPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(expectedAnchorVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize * 2))
				.build()
		);

		final List<GrpcChangeCatalogCapture> pagedCaptures = new ArrayList<>(firstPage.getChangeCaptureList());
		pagedCaptures.addAll(secondPage.getChangeCaptureList());
		final List<GrpcChangeCatalogCapture> combinedCaptures = combinedPage.getChangeCaptureList();

		assertEquals(
			combinedCaptures.size(), pagedCaptures.size(),
			"two pages fetched separately with a pinned anchor must cover exactly the same records as one combined page, with no gap or duplicate at the boundary"
		);
		for (int idx = 0; idx < pagedCaptures.size(); idx++) {
			assertEquals(combinedCaptures.get(idx).getVersion().getValue(), pagedCaptures.get(idx).getVersion().getValue());
			assertEquals(combinedCaptures.get(idx).getIndex().getValue(), pagedCaptures.get(idx).getIndex().getValue());
		}

		// well past the end of history: hasNext must flip to false rather than staying (incorrectly) true
		final GetMutationsHistoryPageResponse pastTheEnd = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(expectedAnchorVersion))
				.setPage(Int32Value.of(10_000_000))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertTrue(pastTheEnd.getChangeCaptureList().isEmpty());
		assertFalse(pastTheEnd.getHasNext());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should keep mutations-history paging stable across a pinned anchor despite a commit landing between page fetches, and drift when the anchor is left unpinned")
	void shouldPinMutationsHistoryPagingAnchorAcrossAConcurrentCommit(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// the shared dataset's initial generation runs entirely during warm-up, before goLiveAndClose() -
		// none of it reaches CDC/WAL history, so this test seeds its own baseline first: one commit
		// touching five distinct entities gives page 1 and page 2 real, stable content to compare against,
		// independent of whatever mutation history (if any) preceded this test
		final List<SealedEntity> pool = entities.subList(entities.size() - 6, entities.size());
		final List<SealedEntity> baselineTargets = pool.subList(0, 5);
		final SealedEntity concurrentTarget = pool.get(5);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int idx = 0; idx < baselineTargets.size(); idx++) {
					final SealedEntity target = baselineTargets.get(idx);
					session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 2000L + idx)
						.upsertVia(session);
				}
			}
		);
		// a gRPC session pins to the catalog version visible when it was created, so it is (re-)opened right
		// after each commit below - see the record-alignment test above for why order matters here
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final int pageSize = 3;
		final GetMutationsHistoryPageResponse firstPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		final long pinnedAnchor = firstPage.getSinceVersion();
		assertTrue(firstPage.getHasNext(), "the baseline commit alone must already span more than one page at this size");

		// a second, real commit lands strictly after the anchor above was resolved, simulating a concurrent
		// writer between two page fetches. A distinct entity from the baseline's pool is used so this
		// commit is unambiguously "new" relative to everything firstPage/pinnedAnchor already saw.
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(concurrentTarget.getType(), concurrentTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 424242L)
					.upsertVia(session);
			}
		);
		// re-opened again so its implicit "current version" fallback reflects the commit just above, which
		// is exactly what lets unpinnedSecondPage below demonstrate real drift
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final GetMutationsHistoryPageResponse pinnedSecondPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(pinnedAnchor))
				.setPage(Int32Value.of(2))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		final GetMutationsHistoryPageResponse unpinnedSecondPage = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				// sinceVersion deliberately left unset - re-resolves to the post-commit catalog version
				.setPage(Int32Value.of(2))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);

		// ground truth: one combined fetch anchored at the pinned version, covering both pages at once
		final GetMutationsHistoryPageResponse combined = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(pinnedAnchor))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize * 2))
				.build()
		);
		final List<GrpcChangeCatalogCapture> expectedSecondPage = combined.getChangeCaptureList()
			.subList(firstPage.getChangeCaptureList().size(), combined.getChangeCaptureList().size());

		final List<GrpcChangeCatalogCapture> pinnedCaptures = pinnedSecondPage.getChangeCaptureList();
		assertEquals(
			expectedSecondPage.size(), pinnedCaptures.size(),
			"a pinned anchor must keep page 2 lined up with the ground truth despite the intervening commit"
		);
		for (int idx = 0; idx < expectedSecondPage.size(); idx++) {
			assertEquals(expectedSecondPage.get(idx).getVersion().getValue(), pinnedCaptures.get(idx).getVersion().getValue());
			assertEquals(expectedSecondPage.get(idx).getIndex().getValue(), pinnedCaptures.get(idx).getIndex().getValue());
		}

		// an unpinned anchor re-resolves to the post-commit version, so its page-2 window shifts by
		// however many records the commit added and no longer lines up with the pinned ground truth -
		// this is exactly the skip/duplicate instability pinning `sinceVersion` exists to prevent
		final List<GrpcChangeCatalogCapture> unpinnedCaptures = unpinnedSecondPage.getChangeCaptureList();
		boolean unpinnedWindowDrifted = unpinnedCaptures.size() != expectedSecondPage.size();
		if (!unpinnedWindowDrifted) {
			for (int idx = 0; idx < unpinnedCaptures.size(); idx++) {
				if (unpinnedCaptures.get(idx).getVersion().getValue() != expectedSecondPage.get(idx).getVersion().getValue() ||
					unpinnedCaptures.get(idx).getIndex().getValue() != expectedSecondPage.get(idx).getIndex().getValue()) {
					unpinnedWindowDrifted = true;
					break;
				}
			}
		}
		assertTrue(
			unpinnedWindowDrifted,
			"an unpinned anchor must drift once a commit lands between page fetches - otherwise pinning would be pointless"
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should stream mutations history in chronological (oldest-first) order via the forward RPC")
	void shouldStreamMutationsHistoryForwardInChronologicalOrder(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// the shared dataset's initial generation runs during warm-up and never reaches CDC/WAL history (see
		// the sibling paged tests above), so this test seeds its own real, committed history: one commit
		// touching two distinct entities gives a single transaction of known, checkable shape
		final List<SealedEntity> targets = entities.subList(entities.size() - 20, entities.size() - 18);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int idx = 0; idx < targets.size(); idx++) {
					final SealedEntity target = targets.get(idx);
					session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 3000L + idx)
						.upsertVia(session);
				}
			}
		);
		final long recordVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		// opened only after the commit above - see the record-alignment paged test for why order matters
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final Iterator<GetMutationsHistoryResponse> stream = evitaSessionBlockingStub.getMutationsHistoryForward(
			GetMutationsHistoryRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.build()
		);
		final List<GrpcChangeCatalogCapture> captures = new ArrayList<>();
		stream.forEachRemaining(response -> captures.addAll(response.getChangeCaptureList()));

		// header(V) + two entity records, each an entity-level capture plus the one local mutation it fanned
		// out into - 1 + 2 * 2 = 5 captures total, the header (index 0) leading the forward stream
		assertEquals(5, captures.size());
		assertEquals(recordVersion, captures.get(0).getVersion().getValue());
		assertEquals(0, captures.get(0).getIndex().getValue(), "the transaction header occupies index 0 and must lead the forward stream");

		int previousIndex = -1;
		for (final GrpcChangeCatalogCapture capture : captures) {
			assertEquals(recordVersion, capture.getVersion().getValue());
			assertTrue(
				capture.getIndex().getValue() >= previousIndex,
				"index must be non-decreasing in forward order, was " + capture.getIndex().getValue() + " after " + previousIndex
			);
			previousIndex = capture.getIndex().getValue();
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should page mutations history in chronological (oldest-first) order via the forward RPC, record-aligned")
	void shouldPageMutationsHistoryForward(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// one commit touching three distinct entities - header(V) + three entity-level payload records -
		// gives this test real, self-contained history spanning more than one page at pageSize 1
		final List<SealedEntity> targets = entities.subList(entities.size() - 23, entities.size() - 20);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int idx = 0; idx < targets.size(); idx++) {
					final SealedEntity target = targets.get(idx);
					session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 4000L + idx)
						.upsertVia(session);
				}
			}
		);
		final long floorVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final int pageSize = 1;
		final GetMutationsHistoryPageResponse firstPage = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(floorVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertEquals(floorVersion, firstPage.getSinceVersion());
		assertEquals(1, firstPage.getChangeCaptureCount(), "page 1 is the transaction header alone - a record of size 1");
		assertEquals(0, firstPage.getChangeCapture(0).getIndex().getValue());
		assertTrue(firstPage.getHasNext());

		// keep fetching pages, pinned to the same floor, until exhausted, and reassemble
		final List<GrpcChangeCatalogCapture> pagedCaptures = new ArrayList<>(firstPage.getChangeCaptureList());
		boolean hasNext = firstPage.getHasNext();
		int page = 2;
		while (hasNext) {
			final GetMutationsHistoryPageResponse nextPage = evitaSessionBlockingStub.getMutationsHistoryPageForward(
				GetMutationsHistoryPageRequest.newBuilder()
					.setSinceVersion(Int64Value.of(floorVersion))
					.setPage(Int32Value.of(page))
					.setPageSize(Int32Value.of(pageSize))
					.build()
			);
			pagedCaptures.addAll(nextPage.getChangeCaptureList());
			hasNext = nextPage.getHasNext();
			page++;
		}

		final GetMutationsHistoryPageResponse combinedPage = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(floorVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pagedCaptures.size()))
				.build()
		);
		final List<GrpcChangeCatalogCapture> combinedCaptures = combinedPage.getChangeCaptureList();
		assertFalse(combinedPage.getHasNext());
		assertEquals(
			combinedCaptures.size(), pagedCaptures.size(),
			"records paged one at a time with a pinned floor must cover exactly the same captures as one combined page"
		);
		for (int idx = 0; idx < pagedCaptures.size(); idx++) {
			assertEquals(combinedCaptures.get(idx).getVersion().getValue(), pagedCaptures.get(idx).getVersion().getValue());
			assertEquals(combinedCaptures.get(idx).getIndex().getValue(), pagedCaptures.get(idx).getIndex().getValue());
		}
		// chronological order: non-decreasing index (only one catalog version is involved in this commit)
		int previousIndex = -1;
		for (final GrpcChangeCatalogCapture capture : pagedCaptures) {
			assertTrue(capture.getIndex().getValue() >= previousIndex);
			previousIndex = capture.getIndex().getValue();
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should include the last eligible version at an inclusive time-frame upper bound while paging mutations history forward")
	void shouldIncludeLastEligibleVersionAtTimeFrameUpperBoundWhenPagingMutationsHistoryForward(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		final SealedEntity target = entities.get(entities.size() - 27);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 6000L)
					.upsertVia(session);
			}
		);
		final long lastVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// `to` set safely after the commit's own timestamp, so `getLastCatalogVersionBefore(to)` unambiguously
		// resolves to `lastVersion` - the regression: an exclusive `<` on the resolved stop bound used to drop
		// this very version's records from the page instead of including them, even though `lastVersion` is
		// itself anchored at as `sinceVersion` below
		final OffsetDateTime to = OffsetDateTime.now().plusMinutes(1);
		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(lastVersion))
				.setTimeFrame(EvitaDataTypesConverter.toGrpcDateTimeRange(DateTimeRange.until(to)))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);

		assertFalse(page.getHasNext());
		assertTrue(
			page.getChangeCaptureList().stream().anyMatch(capture -> capture.getVersion().getValue() == lastVersion),
			"the last committed version, exactly at the resolved `to` boundary, must be included in the result"
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should include the last eligible version at an inclusive time-frame upper bound while paging mutations history in reverse")
	void shouldIncludeLastEligibleVersionAtTimeFrameUpperBoundWhenPagingMutationsHistoryReverse(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		final SealedEntity target = entities.get(entities.size() - 29);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 6200L)
					.upsertVia(session);
			}
		);
		final long lastVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// exercises the reverse handler's `to`-bound resolution and anchor echo end-to-end - coverage the
		// reverse RPC lacked entirely before this test. It does NOT reproduce the `.endVersion()` vs
		// `.startVersion()` checkpoint-batching regression itself: that only diverges when the resolved
		// block spans more than one catalog version, and this shared, single-commit dataset's checkpoint
		// (TransactionOptions.DEFAULT_CHECKPOINT_INTERVAL, 1s) reliably fires once per commit here, so
		// start() == end() always and the two resolutions coincide - confirmed empirically, including for
		// the pre-existing forward-direction sibling test below, which has the same limitation despite
		// asserting the analogous scenario. Reproducing an actual multi-version block needs a dedicated
		// dataset with an inflated checkpoint interval (see DeferredCheckpointPersistenceTest), which is
		// disproportionate to add here; noted as a known gap rather than a false regression guarantee.
		final OffsetDateTime to = OffsetDateTime.now().plusMinutes(1);
		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setTimeFrame(EvitaDataTypesConverter.toGrpcDateTimeRange(DateTimeRange.until(to)))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);

		assertEquals(lastVersion, page.getSinceVersion());
		assertTrue(
			page.getChangeCaptureList().stream().anyMatch(capture -> capture.getVersion().getValue() == lastVersion),
			"the last committed version, exactly at the resolved `to` boundary, must be included in the result"
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should return an empty page instead of the newest mutations when the time-frame floor lies in the future")
	void shouldReturnEmptyPageWhenTimeFrameFloorIsInTheFuture(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// seed real WAL history first - the shared dataset has none until a post-goLive commit lands (all
		// its entities are generated during warm-up), and without any committed history at all, a `from`
		// in the future and a `from` right now look identical, defeating the point of this test
		final SealedEntity target = entities.get(entities.size() - 28);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 6100L)
					.upsertVia(session);
			}
		);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// the regression: `getFirstCatalogVersionAfter` clamps a future moment down to the newest known
		// block instead of signalling "no such version", so a naive implementation would surface the newest
		// mutations here instead of correctly finding nothing - only a future `from` is checked this way,
		// since the checkpoint-based version index has no lag-free way to tell "nothing was ever committed
		// after this past moment" apart from "committed after it but not yet checkpointed"
		final OffsetDateTime from = OffsetDateTime.now().plusYears(10);
		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setTimeFrame(EvitaDataTypesConverter.toGrpcDateTimeRange(DateTimeRange.since(from)))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(20))
				.build()
		);

		assertEquals(0, page.getChangeCaptureCount());
		assertFalse(page.getHasNext());
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should reach a commit that lands after the pinned floor while paging mutations history forward")
	void shouldReachConcurrentlyCommittedDataWhenPagingMutationsHistoryForward(Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		final List<SealedEntity> pool = entities.subList(entities.size() - 26, entities.size() - 23);
		final SealedEntity baselineTarget = pool.get(0);
		final SealedEntity concurrentTarget = pool.get(1);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(baselineTarget.getType(), baselineTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 5000L)
					.upsertVia(session);
			}
		);
		final long floorVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// pageSize matches the baseline transaction's own record count exactly (header + one entity-upsert
		// record = 2 records), so it fits entirely on page 1 - and the peek-ahead naturally flips hasNext
		// from false to true the moment anything more is appended
		final int pageSize = 2;
		final GetMutationsHistoryPageResponse baselinePage = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(floorVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertFalse(baselinePage.getHasNext(), "the single baseline commit must fit entirely on page 1 at this page size");
		final int baselineCaptureCount = baselinePage.getChangeCaptureCount();

		// a second, real commit lands strictly after the floor above was resolved, appended to the WAL's
		// tail - simulating a concurrent writer while a forward ("load newer") traversal is in progress
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(concurrentTarget.getType(), concurrentTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 5001L)
					.upsertVia(session);
			}
		);
		// re-opened so the session reflects the commit just made - see the record-alignment paged test above
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// page 1, re-fetched with the SAME pinned floor as before: the append-only WAL means a forward floor
		// never drifts on its own (unlike the reverse RPC's ceiling, which drifts every time "current version"
		// advances) - this must return exactly the same baseline content, now with hasNext flipped to true,
		// since the new commit is reachable as a further page rather than folded in or lost
		final GetMutationsHistoryPageResponse baselinePageAfterConcurrentCommit = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(floorVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertEquals(floorVersion, baselinePageAfterConcurrentCommit.getSinceVersion());
		assertEquals(baselineCaptureCount, baselinePageAfterConcurrentCommit.getChangeCaptureCount());
		assertTrue(
			baselinePageAfterConcurrentCommit.getHasNext(),
			"the concurrently committed record must now be reachable as a further page"
		);

		// page 2, same pinned floor: must contain exactly the new commit's captures - proving the forward
		// traversal's peek-ahead (hasNext) mechanism picks up data appended to the WAL after the floor was
		// first resolved, rather than caching a stale view of how much history existed at that time
		final GetMutationsHistoryPageResponse newCommitPage = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(floorVersion))
				.setPage(Int32Value.of(2))
				.setPageSize(Int32Value.of(pageSize))
				.build()
		);
		assertFalse(newCommitPage.getChangeCaptureList().isEmpty(), "the concurrently committed record must appear on the next page");
		assertFalse(newCommitPage.getHasNext());
		for (final GrpcChangeCatalogCapture capture : newCommitPage.getChangeCaptureList()) {
			assertTrue(
				capture.getVersion().getValue() > floorVersion,
				"every capture beyond the baseline page must belong to the newer, concurrently committed version"
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should page mutations history restricted to the DATA area when sinceVersion is supplied without sinceIndex")
	void shouldPageMutationsHistoryWithDataCriteriaWhenSinceIndexIsOmitted(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// a real, committed data change - the shared dataset's own 1000-entity generation runs during
		// warm-up and never reaches CDC/WAL history (see the sibling paged tests above)
		final SealedEntity target = entities.get(entities.size() - 31);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7000L)
					.upsertVia(session);
			}
		);
		final long recordVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		// opened only after the commit above - see the record-alignment paged test for why order matters
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// `sinceVersion` deliberately supplied WITHOUT `sinceIndex`: that exact shape is the §1.1 regression,
		// where the absent index gutted the anchor version and the RPC returned nothing at all
		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.addCriteria(
					GrpcChangeCaptureCriteria.newBuilder()
						.setArea(GrpcChangeCaptureArea.DATA)
						.build()
				)
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);

		final List<GrpcChangeCatalogCapture> captures = page.getChangeCaptureList();
		assertFalse(captures.isEmpty(), "a DATA-filtered page must not come back empty for a version that really carries a data change");
		assertTrue(
			captures.stream().anyMatch(capture -> capture.getVersion().getValue() == recordVersion),
			"the anchor version's own data change must be present - its absence is exactly the §1.1 regression"
		);
		for (final GrpcChangeCatalogCapture capture : captures) {
			assertEquals(
				GrpcChangeCaptureArea.DATA, capture.getArea(),
				"every capture must belong to the requested DATA area - the INFRASTRUCTURE transaction header must have been filtered out"
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should page mutations history restricted to the SCHEMA area when sinceVersion is supplied without sinceIndex")
	void shouldPageMutationsHistoryWithSchemaCriteriaWhenSinceIndexIsOmitted(
		Evita evita, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// a brand-new entity type rather than a change to the shared PRODUCT schema, so this commit cannot
		// perturb any sibling test's expectations - the only entity-type-enumerating test in this class
		// compares the gRPC response against a live session, so it stays self-consistent either way
		final String newEntityType = "cdcSchemaCriteriaProbe";
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(newEntityType).updateVia(session);
			}
		);
		final long recordVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// same omitted-`sinceIndex` shape as the DATA sibling above, on the other capture area
		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.addCriteria(
					GrpcChangeCaptureCriteria.newBuilder()
						.setArea(GrpcChangeCaptureArea.SCHEMA)
						.build()
				)
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);

		final List<GrpcChangeCatalogCapture> captures = page.getChangeCaptureList();
		assertFalse(captures.isEmpty(), "a SCHEMA-filtered page must not come back empty for a version that really carries a schema change");
		assertTrue(
			captures.stream().anyMatch(capture -> capture.getVersion().getValue() == recordVersion),
			"the anchor version's own schema change must be present - its absence is exactly the §1.1 regression"
		);
		for (final GrpcChangeCatalogCapture capture : captures) {
			assertEquals(
				GrpcChangeCaptureArea.SCHEMA, capture.getArea(),
				"every capture must belong to the requested SCHEMA area - the INFRASTRUCTURE transaction header must have been filtered out"
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should preserve request order in GetTransactionOverview when known and unknown catalog versions are mixed")
	void shouldPreserveRequestOrderInTransactionOverviewAcrossKnownAndUnknownVersions(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// two separate commits, so two distinct catalog versions are genuinely known to history - the
		// existing correlatable-by-version sibling test only ever has one, which cannot show ordering
		final SealedEntity firstTarget = entities.get(entities.size() - 32);
		final SealedEntity secondTarget = entities.get(entities.size() - 33);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(firstTarget.getType(), firstTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7100L)
					.upsertVia(session);
			}
		);
		final long firstVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(secondTarget.getType(), secondTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7101L)
					.upsertVia(session);
			}
		);
		final long secondVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		assertTrue(secondVersion > firstVersion, "the two seeded commits must produce two distinct catalog versions");
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final long unknownCatalogVersion = Long.MAX_VALUE;
		final GetTransactionOverviewRequest request = GetTransactionOverviewRequest
			.newBuilder()
			// deliberately NOT ascending, with the unknown version wedged between the two known ones: a
			// response that is sorted, or that lets the dropped slot shift the survivors, fails here
			.addCatalogVersion(secondVersion)
			.addCatalogVersion(unknownCatalogVersion)
			.addCatalogVersion(firstVersion)
			.build();

		// a version only becomes resolvable once a checkpoint has materialised it into a bootstrap version
		// block, and CheckpointCoordinator ticks on a timer (TransactionOptions.DEFAULT_CHECKPOINT_INTERVAL,
		// 1s) - poll until both are visible, otherwise the ordering assertion below could hold vacuously on
		// a single surviving entry. `pollInSameThread()` is mandatory here: the gRPC session established by
		// SessionInitializer above lives in this thread's client context, so awaitility's default poll
		// executor would issue the call from a thread with no session and get UNAUTHENTICATED back.
		final AtomicReference<List<GrpcTransactionOverview>> overviews = new AtomicReference<>(List.of());
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread().until(() -> {
			overviews.set(
				evitaSessionBlockingStub.getTransactionOverview(request).getTransactionOverviewsList()
			);
			return overviews.get().size() == 2;
		});

		final List<GrpcTransactionOverview> resolved = overviews.get();
		assertEquals(2, resolved.size(), "exactly the two known versions may survive - the unknown one must be omitted, not padded");
		assertEquals(
			secondVersion, resolved.get(0).getCatalogVersion(),
			"the newer version was requested first, so it must come back first"
		);
		assertEquals(
			firstVersion, resolved.get(1).getCatalogVersion(),
			"the older version was requested last, so it must come back last"
		);
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should exclude the version resolved from an exclusive time-frame lower bound while paging mutations history in reverse")
	void shouldExcludeVersionResolvedFromTimeFrameFromWhenPagingMutationsHistoryReverse(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// A prior commit, checkpointed before the boundary moment is even chosen. Without it the boundary
		// can resolve to the catalog's very first version block, whose `startVersion` is the bootstrap
		// version - a version that carries no CDC captures at all, which makes the precondition below
		// unsatisfiable. Running this test on its own used to do exactly that (it only ever passed because
		// sibling CDC tests happened to commit first), so the history it needs is seeded here explicitly.
		final OffsetDateTime priorMarker = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
		final SealedEntity priorTarget = entities.get(entities.size() - 40);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(priorTarget.getType(), priorTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7199L)
					.upsertVia(session);
			}
		);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcCatalogVersionAtResponse> priorBlock = new AtomicReference<>();
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread().until(() -> {
			priorBlock.set(
				evitaSessionBlockingStub.getCatalogVersionAt(
					GrpcCatalogVersionAtRequest.newBuilder()
						.setTheMoment(EvitaDataTypesConverter.toGrpcOffsetDateTime(priorMarker))
						.build()
				)
			);
			return !EvitaDataTypesConverter.toOffsetDateTime(priorBlock.get().getIntroducedAt())
				.isBefore(priorMarker);
		});

		// The boundary sits strictly after that block, so the block resolved from it further down is a
		// strictly later one - hence its `startVersion` is a real committed transaction with captures,
		// never the bootstrap version. Truncated to whole seconds on purpose:
		// `EvitaDataTypesConverter#toDateTimeRange` rebuilds the bound with
		// `Instant.ofEpochSecond(...getSeconds())` and so drops sub-second precision, whereas
		// GetCatalogVersionAt's `theMoment` keeps nanoseconds - at nanosecond precision the two resolve
		// instants up to a second apart, and the commits below land inside that second, which made the
		// boundary read back from the server disagree with the one the paged RPC actually applied.
		final OffsetDateTime beforeSeed = EvitaDataTypesConverter
			.toOffsetDateTime(priorBlock.get().getIntroducedAt())
			.truncatedTo(ChronoUnit.SECONDS)
			.plusSeconds(1);
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread()
			.until(() -> OffsetDateTime.now().isAfter(beforeSeed));

		// two commits, so something still remains on the far side of the bound once the version resolved
		// from `from` has been excluded - otherwise an empty result would satisfy the assertion trivially
		final SealedEntity firstTarget = entities.get(entities.size() - 34);
		final SealedEntity secondTarget = entities.get(entities.size() - 35);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(firstTarget.getType(), firstTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7200L)
					.upsertVia(session);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(secondTarget.getType(), secondTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7201L)
					.upsertVia(session);
			}
		);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// The reverse handler resolves the lower bound as `getFirstCatalogVersionAfter(from).startVersion()`
		// and then keeps only strictly greater versions. Read that very number back over GetCatalogVersionAt,
		// which runs the identical lookup on the identical session, instead of recomputing it client-side.
		//
		// The lookup is checkpoint-granular, and until a checkpoint covering the commits above has been
		// written it clamps to the newest block it already knows - an older one. In that regime the answer
		// still moves, so two separate RPCs could legitimately disagree. Poll until the resolved block is
		// genuinely introduced at or after `beforeSeed`: past that point the answer is fixed, because blocks
		// only appear on commit, this test commits nothing further, and the FIRST block at or after a fixed
		// moment never moves once it exists.
		//
		// NOTE the asymmetry: `from` is exclusive on the REVERSE RPC only. On the forward RPC the version
		// resolved from `from` becomes the traversal anchor and is itself included.
		final GrpcCatalogVersionAtRequest boundaryRequest = GrpcCatalogVersionAtRequest.newBuilder()
			.setTheMoment(EvitaDataTypesConverter.toGrpcOffsetDateTime(beforeSeed))
			.build();
		final AtomicReference<GrpcCatalogVersionAtResponse> boundary = new AtomicReference<>();
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread().until(() -> {
			boundary.set(evitaSessionBlockingStub.getCatalogVersionAt(boundaryRequest));
			return !EvitaDataTypesConverter.toOffsetDateTime(boundary.get().getIntroducedAt())
				.isBefore(beforeSeed);
		});
		final long excludedVersion = boundary.get().getStartVersion();

		final GetMutationsHistoryPageResponse baseline = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(500))
				.build()
		);
		assertTrue(
			baseline.getChangeCaptureList().stream()
				.anyMatch(capture -> capture.getVersion().getValue() == excludedVersion),
			"precondition: the unbounded page must actually contain version " + excludedVersion +
				" - without it the exclusivity assertion below would prove nothing"
		);

		final GetMutationsHistoryPageResponse bounded = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setTimeFrame(EvitaDataTypesConverter.toGrpcDateTimeRange(DateTimeRange.since(beforeSeed)))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(500))
				.build()
		);
		assertFalse(
			bounded.getChangeCaptureList().isEmpty(),
			"the bounded page must still carry the versions committed beyond the excluded one"
		);
		for (final GrpcChangeCatalogCapture capture : bounded.getChangeCaptureList()) {
			assertTrue(
				capture.getVersion().getValue() > excludedVersion,
				"`timeFrame.from` is exclusive on the reverse RPC, so version " + excludedVersion +
					" must be absent, but got " + capture.getVersion().getValue()
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should include the version resolved from an inclusive time-frame lower bound while paging mutations history forward")
	void shouldIncludeVersionResolvedFromTimeFrameFromWhenPagingMutationsHistoryForward(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// the deliberate counterpart of the reverse test above: the two directions are NOT symmetric here.
		// The reverse handler turns the version resolved from `from` into an exclusive floor
		// (`version > firstRequestedCatalogVersion`), whereas the forward handler makes that same version
		// the traversal anchor, which is inclusive - so the version resolved from `from` must be present
		// here, exactly where the reverse RPC requires it to be absent.
		//
		// Same prior-commit seeding as the reverse test, and for the same reason: without it the boundary
		// can resolve to the catalog's very first block, whose `startVersion` is the capture-less bootstrap
		// version, and the assertion below would be unsatisfiable.
		final OffsetDateTime priorMarker = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
		final SealedEntity priorTarget = entities.get(entities.size() - 41);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(priorTarget.getType(), priorTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7500L)
					.upsertVia(session);
			}
		);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcCatalogVersionAtResponse> priorBlock = new AtomicReference<>();
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread().until(() -> {
			priorBlock.set(
				evitaSessionBlockingStub.getCatalogVersionAt(
					GrpcCatalogVersionAtRequest.newBuilder()
						.setTheMoment(EvitaDataTypesConverter.toGrpcOffsetDateTime(priorMarker))
						.build()
				)
			);
			return !EvitaDataTypesConverter.toOffsetDateTime(priorBlock.get().getIntroducedAt())
				.isBefore(priorMarker);
		});

		// truncated to whole seconds for the same reason as in the reverse test: `toDateTimeRange` rebuilds
		// the bound at second granularity while GetCatalogVersionAt's `theMoment` keeps nanoseconds
		final OffsetDateTime boundary = EvitaDataTypesConverter
			.toOffsetDateTime(priorBlock.get().getIntroducedAt())
			.truncatedTo(ChronoUnit.SECONDS)
			.plusSeconds(1);
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread()
			.until(() -> OffsetDateTime.now().isAfter(boundary));

		final SealedEntity firstTarget = entities.get(entities.size() - 42);
		final SealedEntity secondTarget = entities.get(entities.size() - 43);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(firstTarget.getType(), firstTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7501L)
					.upsertVia(session);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(secondTarget.getType(), secondTarget.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7502L)
					.upsertVia(session);
			}
		);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final AtomicReference<GrpcCatalogVersionAtResponse> anchorBlock = new AtomicReference<>();
		await().atMost(60, TimeUnit.SECONDS).pollInSameThread().until(() -> {
			anchorBlock.set(
				evitaSessionBlockingStub.getCatalogVersionAt(
					GrpcCatalogVersionAtRequest.newBuilder()
						.setTheMoment(EvitaDataTypesConverter.toGrpcOffsetDateTime(boundary))
						.build()
				)
			);
			return !EvitaDataTypesConverter.toOffsetDateTime(anchorBlock.get().getIntroducedAt())
				.isBefore(boundary);
		});
		final long includedVersion = anchorBlock.get().getStartVersion();

		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPageForward(
			GetMutationsHistoryPageRequest.newBuilder()
				.setTimeFrame(EvitaDataTypesConverter.toGrpcDateTimeRange(DateTimeRange.since(boundary)))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(500))
				.build()
		);

		assertFalse(page.getChangeCaptureList().isEmpty(), "the forward page must carry the seeded commits");
		assertTrue(
			page.getChangeCaptureList().stream()
				.anyMatch(capture -> capture.getVersion().getValue() == includedVersion),
			"`timeFrame.from` resolves to the forward traversal's anchor, which is inclusive, so version " +
				includedVersion + " must be present - it is the version the reverse RPC excludes"
		);
		for (final GrpcChangeCatalogCapture capture : page.getChangeCaptureList()) {
			assertTrue(
				capture.getVersion().getValue() >= includedVersion,
				"nothing older than the resolved anchor may appear in a forward traversal, but got " +
					capture.getVersion().getValue()
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should keep capture indexes identical under criteria filtering, not renumber them densely")
	void shouldKeepCaptureIndexIndependentOfCriteriaFiltering(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// one upsert carrying several local mutations - the entity-level capture and all of its locals share
		// a single (version, index) pair, alongside the transaction header at index 0
		final SealedEntity target = entities.get(entities.size() - 36);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 7300L)
					.setAttribute(ATTRIBUTE_QUANTITY, BigDecimal.valueOf(88))
					.upsertVia(session);
			}
		);
		final long recordVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		final GetMutationsHistoryPageResponse unfiltered = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);
		final GetMutationsHistoryPageResponse filtered = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.addCriteria(
					GrpcChangeCaptureCriteria.newBuilder()
						.setArea(GrpcChangeCaptureArea.DATA)
						.build()
				)
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);

		final List<GrpcChangeCatalogCapture> unfilteredForVersion = unfiltered.getChangeCaptureList().stream()
			.filter(capture -> capture.getVersion().getValue() == recordVersion)
			.toList();
		final List<GrpcChangeCatalogCapture> filteredForVersion = filtered.getChangeCaptureList().stream()
			.filter(capture -> capture.getVersion().getValue() == recordVersion)
			.toList();

		assertFalse(filteredForVersion.isEmpty(), "the DATA criterion must leave the entity captures behind");
		assertTrue(
			filteredForVersion.size() < unfilteredForVersion.size(),
			"the DATA criterion must actually remove something (at minimum the INFRASTRUCTURE transaction " +
				"header) - otherwise matching indexes below would prove nothing"
		);

		// the surviving captures must carry exactly the indexes they had in the unfiltered stream: filtering
		// must never renumber them densely, or a client could not correlate a filtered page against an
		// unfiltered one, nor run the §3.3 completeness check across the two
		final List<GrpcChangeCatalogCapture> unfilteredDataOnly = unfilteredForVersion.stream()
			.filter(capture -> capture.getArea() == GrpcChangeCaptureArea.DATA)
			.toList();
		assertEquals(
			unfilteredDataOnly.size(), filteredForVersion.size(),
			"filtering must drop only the non-DATA captures, never any DATA one"
		);
		for (int idx = 0; idx < filteredForVersion.size(); idx++) {
			assertEquals(
				unfilteredDataOnly.get(idx).getIndex().getValue(),
				filteredForVersion.get(idx).getIndex().getValue(),
				"capture index must be filter-independent, but position " + idx + " shifted"
			);
		}
	}

	@Test
	@UseDataSet(GRPC_THOUSAND_PRODUCTS)
	@Tag(CDC)
	@DisplayName("Should satisfy the mutationCount completeness check on an unfiltered CHANGE_BODY page")
	void shouldSatisfyCompletenessCheckOnUnfilteredChangeBodyPage(
		Evita evita, List<SealedEntity> entities, GrpcClientBuilder clientBuilder
	) {
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub evitaSessionBlockingStub =
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);

		// three distinct entity upserts in ONE transaction: `mutationCount` counts top-level WAL records, so
		// this transaction's header must report more than one - a single-record transaction would satisfy the
		// check below trivially
		final List<SealedEntity> targets = entities.subList(entities.size() - 39, entities.size() - 36);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int idx = 0; idx < targets.size(); idx++) {
					final SealedEntity target = targets.get(idx);
					session.getEntity(target.getType(), target.getPrimaryKey(), QueryConstraints.entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 7400L + idx)
						.upsertVia(session);
				}
			}
		);
		final long recordVersion = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
		SessionInitializer.setSession(clientBuilder, GrpcSessionType.READ_ONLY);

		// both preconditions the documented protocol requires: CHANGE_BODY (in HEADER mode the body is null
		// and no count is available at all) and an unfiltered stream (a DATA-only criterion would remove the
		// very header that carries the count)
		final GetMutationsHistoryPageResponse page = evitaSessionBlockingStub.getMutationsHistoryPage(
			GetMutationsHistoryPageRequest.newBuilder()
				.setSinceVersion(Int64Value.of(recordVersion))
				.setContent(GrpcChangeCaptureContent.CHANGE_BODY)
				.setPage(Int32Value.of(1))
				.setPageSize(Int32Value.of(100))
				.build()
		);

		final List<GrpcChangeCatalogCapture> forVersion = page.getChangeCaptureList().stream()
			.filter(capture -> capture.getVersion().getValue() == recordVersion)
			.toList();
		final GrpcChangeCatalogCapture header = forVersion.stream()
			.filter(capture -> capture.getArea() == GrpcChangeCaptureArea.INFRASTRUCTURE)
			.findFirst()
			.orElseThrow(() -> new AssertionError(
				"an unfiltered stream must carry the transaction header that holds mutationCount"
			));
		assertTrue(
			header.hasInfrastructureMutation(),
			"CHANGE_BODY was requested, so the header must arrive with its body populated - a null body is " +
				"exactly what made this check unreachable before §1.2 was fixed"
		);

		final int mutationCount = header.getInfrastructureMutation().getTransactionMutation().getMutationCount();
		assertTrue(
			mutationCount > 1,
			"the seeded transaction must carry more than one top-level record, or the completeness check " +
				"would hold trivially - got " + mutationCount
		);

		// the documented check itself: `mutationCount` counts top-level WAL records, and every top-level
		// record occupies exactly one (version, index) slot that its local-mutation fan-out shares - so the
		// number of distinct non-header indexes must reproduce it exactly
		final Set<Integer> topLevelRecordIndexes = forVersion.stream()
			.filter(capture -> capture.getArea() != GrpcChangeCaptureArea.INFRASTRUCTURE)
			.map(capture -> capture.getIndex().getValue())
			.collect(Collectors.toSet());
		assertEquals(
			mutationCount, topLevelRecordIndexes.size(),
			"the transaction header's mutationCount must match the number of distinct top-level records " +
				"actually delivered, or a client cannot tell a complete transaction from a truncated one"
		);
	}

	public record AssociatedDataComplexObjectExample(
		@Nonnull String name,
		@Nonnull BigDecimal value,
		@Nonnull OffsetDateTime dateTime
	) implements Serializable {

	}

}
