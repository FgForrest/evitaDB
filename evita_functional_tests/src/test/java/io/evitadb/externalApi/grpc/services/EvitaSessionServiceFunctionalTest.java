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
import java.util.*;
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
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"ResultOfMethodCallIgnored", "UnusedParameters"})
@DisplayName("EvitaSessionService gRPC functional test")
@ExtendWith(EvitaParameterResolver.class)
@Tag(FUNCTIONAL_TEST)
@Slf4j
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

	public record AssociatedDataComplexObjectExample(
		@Nonnull String name,
		@Nonnull BigDecimal value,
		@Nonnull OffsetDateTime dateTime
	) implements Serializable {

	}

}
