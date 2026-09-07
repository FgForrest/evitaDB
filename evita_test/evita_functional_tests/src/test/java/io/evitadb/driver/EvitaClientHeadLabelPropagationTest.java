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

package io.evitadb.driver;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.generated.GrpcQueryRequest;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CertificateUtils;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static java.util.Objects.requireNonNull;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gRPC transport carries a query as an EvitaQL string plus its extracted parameters — a head constraint that
 * does not make it into that string never reaches the server, and the query is then untraceable in the traffic
 * recording, in the spans and in the metrics that the label exists to feed.
 *
 * Two independent client-side sites drop it. `EvitaClientSession#assertRequestMakesSenseAndEntityTypeIsPresent`
 * rebuilds a head that carries no `collection` from scratch, and `PrettyPrintingVisitor#traverse(Query)` prints
 * `Query#getCollection()` rather than `Query#getHead()`. These tests read what the driver actually put on the wire,
 * so each site is observed where it is reachable rather than where it is written.
 *
 * See issue #1507.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaClient — head label propagation")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(QUERY)
@ExtendWith(EvitaParameterResolver.class)
class EvitaClientHeadLabelPropagationTest implements TestConstants, EvitaTestSupport {
	private static final String DATA_SET_HEAD_LABEL_PROPAGATION = "clientHeadLabelPropagation";
	private static final String LABEL_NAME = "rest_method";
	private static final String LABEL_VALUE = "CartController.updateCartByOperation";
	private static final String SECOND_LABEL_NAME = "page-url";
	private static final String SECOND_LABEL_VALUE = "/cart";
	/**
	 * Primary key deliberately absent from the dataset — the queries below exist to be *sent*, not to match, and an
	 * empty result page keeps the assertion about the wire and nothing else.
	 */
	private static final int MISSING_PRIMARY_KEY = 999_999;

	/**
	 * Model class whose {@link EntityRef} annotation supplies the entity type. It is what makes a query with no
	 * `collection` in its head a legitimate caller shape: the entity type comes from the expected result type
	 * instead, which is exactly the shape the driver's rebuild throws the rest of the head away for.
	 */
	@EntityRef(Entities.PRODUCT)
	public interface ProductHandle extends EntityClassifier {
	}

	/**
	 * Builds a minimal catalog holding a single product. The catalog stays in the WARM_UP state — queries by primary
	 * key work there and need no transaction, keeping the reproduction focused on what the driver serializes.
	 */
	@DataSet(value = DATA_SET_HEAD_LABEL_PROPAGATION, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static EvitaClient initDataSet(EvitaServer evitaServer) {
		final EvitaClient setupClient = new EvitaClient(clientConfiguration(evitaServer));
		setupClient.defineCatalog(TEST_CATALOG);
		setupClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 1).upsertVia(session);
			}
		);
		return setupClient;
	}

	@Test
	@UseDataSet(DATA_SET_HEAD_LABEL_PROPAGATION)
	@DisplayName("should send a head label of a query whose head carries no collection")
	void shouldSendHeadLabelWhenHeadCarriesNoCollection(EvitaServer evitaServer) {
		final GrpcQueryRequest sentRequest = captureQueryRequest(
			evitaServer,
			session -> session.queryList(
				query(
					label(LABEL_NAME, LABEL_VALUE),
					filterBy(entityPrimaryKeyInSet(MISSING_PRIMARY_KEY)),
					require(entityFetch())
				),
				ProductHandle.class
			)
		);

		// the entity type taken from the expected result type must still be there, exactly once ...
		assertSingleCollectionOnTheWire(sentRequest);
		// ... and the label must have been merged into the head rather than replaced by it
		assertLabelOnTheWire(sentRequest);
	}

	@Test
	@UseDataSet(DATA_SET_HEAD_LABEL_PROPAGATION)
	@DisplayName("should send a head label of a query whose head also carries a collection")
	void shouldSendHeadLabelWhenHeadAlsoCarriesCollection(EvitaServer evitaServer) {
		final GrpcQueryRequest sentRequest = captureQueryRequest(
			evitaServer,
			session -> session.queryList(
				query(
					head(
						collection(Entities.PRODUCT),
						label(LABEL_NAME, LABEL_VALUE)
					),
					filterBy(entityPrimaryKeyInSet(MISSING_PRIMARY_KEY))
				),
				EntityReference.class
			)
		);

		assertSingleCollectionOnTheWire(sentRequest);
		assertLabelOnTheWire(sentRequest);
	}

	@Test
	@UseDataSet(DATA_SET_HEAD_LABEL_PROPAGATION)
	@DisplayName("should derive the collection when the query carries no head at all")
	void shouldDeriveCollectionWhenQueryCarriesNoHead(EvitaServer evitaServer) {
		final GrpcQueryRequest sentRequest = captureQueryRequest(
			evitaServer,
			session -> session.queryList(
				query(
					filterBy(entityPrimaryKeyInSet(MISSING_PRIMARY_KEY)),
					require(entityFetch())
				),
				ProductHandle.class
			)
		);

		assertSingleCollectionOnTheWire(sentRequest);
		assertBareCollectionHeadOnTheWire(sentRequest);
	}

	@Test
	@UseDataSet(DATA_SET_HEAD_LABEL_PROPAGATION)
	@DisplayName("should keep every label of a collection-free head container while deriving the collection")
	void shouldKeepEveryLabelOfCollectionFreeHeadContainer(EvitaServer evitaServer) {
		final GrpcQueryRequest sentRequest = captureQueryRequest(
			evitaServer,
			session -> session.queryList(
				query(
					head(
						label(LABEL_NAME, LABEL_VALUE),
						label(SECOND_LABEL_NAME, SECOND_LABEL_VALUE)
					),
					filterBy(entityPrimaryKeyInSet(MISSING_PRIMARY_KEY)),
					require(entityFetch())
				),
				ProductHandle.class
			)
		);

		assertSingleCollectionOnTheWire(sentRequest);
		assertLabelOnTheWire(sentRequest, LABEL_NAME, LABEL_VALUE);
		assertLabelOnTheWire(sentRequest, SECOND_LABEL_NAME, SECOND_LABEL_VALUE);
	}

	/**
	 * Guards the opposite mistake: a query whose head is nothing but a `collection` must keep going on the wire in
	 * the shape every deployed server already parses, without a gratuitous `head(...)` wrapper.
	 */
	@Test
	@UseDataSet(DATA_SET_HEAD_LABEL_PROPAGATION)
	@DisplayName("should send a bare collection head unwrapped")
	void shouldSendBareCollectionHeadUnwrapped(EvitaServer evitaServer) {
		final GrpcQueryRequest sentRequest = captureQueryRequest(
			evitaServer,
			session -> session.queryList(
				query(
					collection(Entities.PRODUCT),
					filterBy(entityPrimaryKeyInSet(MISSING_PRIMARY_KEY))
				),
				EntityReference.class
			)
		);

		assertSingleCollectionOnTheWire(sentRequest);
		assertBareCollectionHeadOnTheWire(sentRequest);
	}

	/**
	 * Asserts both halves of a label on the wire: the `label(...)` constraint in the query string, and the name and
	 * value among the extracted positional parameters. Asserting only the string would pass on a query that prints
	 * the placeholders but loses the values.
	 */
	private static void assertLabelOnTheWire(@Nonnull GrpcQueryRequest sentRequest) {
		assertLabelOnTheWire(sentRequest, LABEL_NAME, LABEL_VALUE);
	}

	private static void assertLabelOnTheWire(
		@Nonnull GrpcQueryRequest sentRequest,
		@Nonnull String labelName,
		@Nonnull String labelValue
	) {
		final Query sentQuery = parseWireForm(sentRequest);
		final List<Label> labels = QueryUtils.findConstraints(
			requireNonNull(sentQuery.getHead(), () -> "the head never reached the wire: " + sentRequest.getQuery()),
			Label.class
		);
		assertTrue(
			labels.stream().anyMatch(
				it -> labelName.equals(it.getLabelName()) && labelValue.equals(it.getLabelValue())
			),
			() -> "the head label `" + labelName + "` was dropped by the driver: " + sentRequest.getQuery()
		);
	}

	/**
	 * Asserts the head on the wire is a bare `collection`, not wrapped in a `head(...)` container - the shape every
	 * already-deployed server parses, and the one a fix that over-wraps would break.
	 */
	private static void assertBareCollectionHeadOnTheWire(@Nonnull GrpcQueryRequest sentRequest) {
		assertInstanceOf(
			Collection.class,
			parseWireForm(sentRequest).getHead(),
			() -> "a head with nothing but a collection must not be wrapped: " + sentRequest.getQuery()
		);
	}

	/**
	 * Parses the captured wire form back with its positional parameters and returns the resulting query.
	 *
	 * Parsing rather than matching substrings is what the server itself does, so this doubles as proof that the
	 * driver emitted something the server can actually read - a head printed as siblings of `filterBy` would be
	 * rejected here by `EvitaQLQueryVisitor#findHeadConstraint`.
	 */
	@Nonnull
	private static Query parseWireForm(@Nonnull GrpcQueryRequest sentRequest) {
		return DefaultQueryParser.getInstance().parseQuery(
			sentRequest.getQuery(),
			QueryConverter.convertQueryParamsList(sentRequest.getPositionalQueryParamsList())
		);
	}

	/**
	 * Asserts the wire carries exactly one `collection` constraint, naming the product collection. Counting rather
	 * than merely finding one is what catches a merge that prepends a collection to a header that already names one.
	 */
	private static void assertSingleCollectionOnTheWire(@Nonnull GrpcQueryRequest sentRequest) {
		final Query sentQuery = parseWireForm(sentRequest);
		final List<Collection> collections = QueryUtils.findConstraints(
			requireNonNull(sentQuery.getHead(), () -> "the head never reached the wire: " + sentRequest.getQuery()),
			Collection.class
		);
		assertEquals(
			1, collections.size(),
			() -> "expected exactly one collection on the wire: " + sentRequest.getQuery()
		);
		assertEquals(Entities.PRODUCT, collections.get(0).getEntityType());
	}

	/**
	 * Runs `queryInvocation` through a freshly built client whose channel is intercepted, and returns the
	 * {@link GrpcQueryRequest} the driver actually sent.
	 */
	@Nonnull
	private static GrpcQueryRequest captureQueryRequest(
		@Nonnull EvitaServer evitaServer,
		@Nonnull Consumer<EvitaSessionContract> queryInvocation
	) {
		final QueryRequestCapturingInterceptor capture = new QueryRequestCapturingInterceptor();
		try (final EvitaClient client = new EvitaClient(
			clientConfiguration(evitaServer),
			(Consumer<GrpcClientBuilder>) builder -> builder.intercept(capture)
		)) {
			client.queryCatalog(TEST_CATALOG, queryInvocation);
		}
		final GrpcQueryRequest sentRequest = capture.lastRequest();
		assertNotNull(sentRequest, "the driver never sent a GrpcQueryRequest");
		return sentRequest;
	}

	/**
	 * Builds an {@link EvitaClientConfiguration} pointing at the running server's gRPC endpoint.
	 */
	private static EvitaClientConfiguration clientConfiguration(@Nonnull EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0];
		final HostDefinition systemHost = apiOptions.getEndpointConfiguration(SystemProvider.CODE).getHost()[0];

		final String serverCertificates = apiOptions.certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");

		return EvitaClientConfiguration
			.builder()
			.host(grpcHost.hostAddress())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			.tls(
				ClientTlsOptions.builder()
					.mtlsEnabled(false)
					.certificateFolderPath(clientCertificates)
					.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
					.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
					.build()
			)
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(10, TimeUnit.MINUTES)
					.build()
			)
			.build();
	}

	/**
	 * gRPC {@link ClientInterceptor} that records the last {@link GrpcQueryRequest} handed to the transport. Reading
	 * the outbound message is the only faithful observation point — everything downstream of the driver's own
	 * serialization already sees the head that survived it.
	 */
	private static final class QueryRequestCapturingInterceptor implements ClientInterceptor {
		private final AtomicReference<GrpcQueryRequest> lastRequest = new AtomicReference<>();

		@Nullable
		GrpcQueryRequest lastRequest() {
			return this.lastRequest.get();
		}

		@Override
		public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
			MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next
		) {
			return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
				@Override
				public void sendMessage(ReqT message) {
					if (message instanceof GrpcQueryRequest queryRequest) {
						QueryRequestCapturingInterceptor.this.lastRequest.set(queryRequest);
					}
					super.sendMessage(message);
				}
			};
		}
	}

}
