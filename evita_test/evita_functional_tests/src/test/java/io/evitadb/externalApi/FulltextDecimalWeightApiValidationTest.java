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


package io.evitadb.externalApi;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcBigDecimal;
import io.evitadb.externalApi.grpc.generated.GrpcCloseRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcModifyEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetAssociatedDataSchemaSearchableMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaSearchableMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaSearchableThroughMutation;
import io.evitadb.externalApi.grpc.generated.GrpcUpdateEntitySchemaRequest;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.graphql;
import static io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.json;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies decimal weight refusals and place diagnostics through all three real schema endpoints. */
@ExtendWith(EvitaParameterResolver.class)
@Tag(EXTERNAL_API)
@Tag(FULLTEXT)
@Tag(SCHEMA)
@DisplayName("Fulltext decimal weight API validation")
class FulltextDecimalWeightApiValidationTest {
	/** Shared server fixture; rejected mutations leave the prerequisite schemas unchanged. */
	private static final String DATA_SET = "FulltextDecimalWeightApiValidation";

	/**
	 * A raw mutation input whose invalid weight must reach the API before a Java mutation can be constructed.
	 *
	 * @param mutation mutation aggregate property accepted by the endpoint
	 * @param name attribute, associated data or reference name
	 * @param property weight property of that mutation
	 * @param place expected named place in the refusal
	 * @param weight decimal outside the shared bounds or engine range
	 */
	private record InvalidWeight(
		@Nonnull String mutation,
		@Nonnull String name,
		@Nonnull String property,
		@Nonnull String place,
		@Nonnull BigDecimal weight
	) {
		/**
		 * Builds a decimal string input without constructing an already validated Java mutation.
		 *
		 * @return mutation aggregate with the exact invalid decimal text
		 */
		@Nonnull
		private Map<String, Object> input() {
			final Map<String, Object> settings = CollectionUtils.createHashMap(2);
			settings.put("name", this.name);
			settings.put(this.property, this.weight.toPlainString());
			final Map<String, Object> aggregate = CollectionUtils.createHashMap(1);
			aggregate.put(this.mutation, settings);
			return aggregate;
		}

		/**
		 * Builds the matching protobuf mutation while preserving all invalid decimal digits.
		 *
		 * @return wire mutation accepted by the gRPC schema endpoint
		 */
		@Nonnull
		private GrpcEntitySchemaMutation grpc() {
			final GrpcBigDecimal decimal = EvitaDataTypesConverter.toGrpcBigDecimal(this.weight);
			final GrpcEntitySchemaMutation.Builder builder = GrpcEntitySchemaMutation.newBuilder();
			return switch (this.mutation) {
				case "setAttributeSchemaSearchableMutation" -> builder.setSetAttributeSchemaSearchableMutation(
					GrpcSetAttributeSchemaSearchableMutation.newBuilder().setName(this.name).setSearchWeight(decimal)
				).build();
				case "setAssociatedDataSchemaSearchableMutation" ->
					builder.setSetAssociatedDataSchemaSearchableMutation(
						GrpcSetAssociatedDataSchemaSearchableMutation.newBuilder()
							.setName(this.name).setSearchWeight(decimal)
					).build();
				case "setReferenceSchemaSearchableThroughMutation" ->
					builder.setSetReferenceSchemaSearchableThroughMutation(
						GrpcSetReferenceSchemaSearchableThroughMutation.newBuilder()
							.setName(this.name).setWeight(decimal)
					).build();
				default -> throw new IllegalStateException(this.mutation);
			};
		}
	}

	/**
	 * Covers negative values, excessive significant precision, both scale bounds and both float range failures.
	 *
	 * @return each refusal applied to attribute, associated data and reference weights
	 */
	@Nonnull
	private static Stream<InvalidWeight> invalidWeights() {
		return Stream.of(
			new BigDecimal("-1"),
			new BigDecimal("1." + "2".repeat(100)),
			new BigDecimal("1E-101"),
			new BigDecimal("1E+101"),
			new BigDecimal("1E-46"),
			new BigDecimal("1E+39")
		).flatMap(weight -> Stream.of(
			new InvalidWeight("setAttributeSchemaSearchableMutation", "text", "searchWeight",
				"Attribute `text`", weight),
			new InvalidWeight("setAssociatedDataSchemaSearchableMutation", "text", "searchWeight",
				"Associated data `text`", weight),
			new InvalidWeight("setReferenceSchemaSearchableThroughMutation", "related", "weight",
				"Reference `related`", weight)
		));
	}

	/**
	 * Opens the three APIs once and defines only the places needed by the rejected setter mutations.
	 *
	 * @param evita engine owning the shared schemas
	 * @param server running server used to create a raw gRPC client
	 * @return shared catalog name and client builder for endpoint requests
	 */
	@DataSet(value = DATA_SET,
		openWebApi = {GraphQLProvider.CODE, RestProvider.CODE, GrpcProvider.CODE, SystemProvider.CODE},
		readOnly = false, destroyAfterClass = true)
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita, @Nonnull EvitaServer server) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		evita.updateCatalog(catalog, session -> {
			session.getEntitySchemaOrThrow("owner").openForWrite()
				.withAttribute("text", String.class)
				.withAssociatedData("text", String.class)
				.withReferenceToEntity("related", "target", Cardinality.ZERO_OR_MORE)
				.updateVia(session);
		});
		final GrpcClientBuilder client = TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(EvitaClientConfiguration.builder().build().clientId(), new SemVer(2026, 3)),
			server.getExternalApiServer());
		return new DataCarrier("catalog", catalog, "client", client);
	}

	/**
	 * Verifies that GraphQL rejects the decimal and reports the affected place through its public error body.
	 *
	 * @param invalid decimal input and expected diagnostic
	 * @param catalog shared catalog containing the mutation prerequisites
	 * @param tester real GraphQL endpoint client
	 */
	@Tag(GRAPHQL)
	@ParameterizedTest
	@MethodSource("invalidWeights")
	@UseDataSet(DATA_SET)
	void shouldRejectInvalidGraphQLWeight(
		@Nonnull InvalidWeight invalid, @Nonnull String catalog, @Nonnull GraphQLTester tester
	) {
		final String response = tester.test(catalog).urlPathSuffix("/schema")
			.document("mutation { updateOwnerSchema(mutations: [" + graphql(invalid.input(), "") +
				"]) { version } }")
			.executeAndExpectErrorsAndThen().extract().asString();
		assertTrue(response.contains(invalid.place()), response);
	}

	/**
	 * Verifies that REST rejects decimal string input and reports the affected place in a bad request response.
	 *
	 * @param invalid decimal input and expected diagnostic
	 * @param catalog shared catalog containing the mutation prerequisites
	 * @param tester real REST endpoint client
	 */
	@Tag(REST)
	@ParameterizedTest
	@MethodSource("invalidWeights")
	@UseDataSet(DATA_SET)
	void shouldRejectInvalidRestWeight(
		@Nonnull InvalidWeight invalid, @Nonnull String catalog, @Nonnull RestTester tester
	) {
		final Map<String, Object> body = CollectionUtils.createHashMap(1);
		body.put("mutations", List.of(invalid.input()));
		final String response = tester.test(catalog).urlPathSuffix("/owner/schema").httpMethod(Request.METHOD_PUT)
			.requestBody(json(body)).executeAndExpectBadRequestAndThen().extract().asString();
		assertTrue(response.contains(invalid.place()), response);
	}

	/**
	 * Sends invalid protobuf messages directly so Java-side mutation validation cannot mask server validation.
	 *
	 * @param invalid decimal input and expected diagnostic
	 * @param catalog shared catalog containing the mutation prerequisites
	 * @param client real gRPC client builder
	 */
	@Tag(GRPC)
	@ParameterizedTest
	@MethodSource("invalidWeights")
	@UseDataSet(DATA_SET)
	void shouldRejectInvalidGrpcWeight(
		@Nonnull InvalidWeight invalid, @Nonnull String catalog, @Nonnull GrpcClientBuilder client
	) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub service = client.build(
			EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub session = client.build(
			EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		final GrpcEvitaSessionResponse opened = service.createReadWriteSession(
			GrpcEvitaSessionRequest.newBuilder().setCatalogName(catalog).build());
		SessionIdHolder.setSessionId(opened.getSessionId());
		try {
			final GrpcUpdateEntitySchemaRequest request = GrpcUpdateEntitySchemaRequest.newBuilder()
				.setSchemaMutation(GrpcModifyEntitySchemaMutation.newBuilder().setEntityType("owner")
					.addEntitySchemaMutations(invalid.grpc()))
				.build();
			final StatusRuntimeException refusal = assertThrows(
				StatusRuntimeException.class, () -> session.updateEntitySchema(request));
			assertEquals(Status.Code.INVALID_ARGUMENT, refusal.getStatus().getCode());
			assertTrue(refusal.getMessage().contains(invalid.place()), refusal.getMessage());
		} finally {
			try {
				session.close(GrpcCloseRequest.getDefaultInstance());
			} finally {
				SessionIdHolder.reset();
			}
		}
	}
}
