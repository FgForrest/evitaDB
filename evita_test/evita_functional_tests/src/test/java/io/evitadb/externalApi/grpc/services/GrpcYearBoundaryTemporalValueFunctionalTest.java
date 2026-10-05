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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CertificateUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes and queries temporal values at the end of the gRPC `Timestamp` range through a real gRPC server and the
 * Java {@link EvitaClient}.
 *
 * The Java client puts a year-9999 moment on the wire as its raw instant - byte for byte what a client in another
 * language (a C# client sending `DateTime.MaxValue`, for instance) sends - so the client stands in for any gRPC client
 * here. A moment beyond the `Timestamp` range, `OffsetDateTime.MAX` / `LocalDateTime.MAX` included, leaves the client
 * as the last second of the range.
 *
 * The server takes the first and last second of the range for the JDK sentinels and clamps those to the edge of the
 * window of moments it can hold; every other moment is stored as it was sent. Reading a clamped value back sends the
 * edge of the `Timestamp` range again, which the client decodes as the sentinel - so the sentinel round-trips.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC temporal values at the year 9999 boundary")
@ExtendWith(EvitaParameterResolver.class)
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(DRIVER)
@Tag(ATTRIBUTE)
@Tag(DATA_TYPE)
class GrpcYearBoundaryTemporalValueFunctionalTest {
	private static final String DATA_SET = "GrpcYearBoundaryTemporalValueDataSet";
	private static final String ENTITY_TYPE = "temporalProbe";
	private static final String ATTRIBUTE_VALID_UNTIL = "validUntil";
	private static final String ATTRIBUTE_LOCAL_VALID_UNTIL = "localValidUntil";
	private static final String ATTRIBUTE_VALID_UNTIL_SERIES = "validUntilSeries";
	private static final String ATTRIBUTE_VALIDITY = "validity";
	private static final int BASELINE_PK = 1;
	private static final OffsetDateTime BASELINE_MOMENT = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * The last moment of the gRPC `Timestamp` range - what a C# client sends for `DateTime.MaxValue`.
	 */
	private static final OffsetDateTime LAST_GRPC_MOMENT =
		OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
	/**
	 * The first moment of the gRPC `Timestamp` range - what a C# client sends for `DateTime.MinValue`.
	 */
	private static final OffsetDateTime FIRST_GRPC_MOMENT = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * An ordinary moment in year 9999 that is not the end of the range.
	 */
	private static final OffsetDateTime MID_YEAR_9999 = OffsetDateTime.of(9999, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * Control - the last moment of year 9998.
	 */
	private static final OffsetDateTime LAST_MOMENT_OF_9998 =
		OffsetDateTime.of(9998, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
	/**
	 * What the server stores for the upper sentinel - the last whole millisecond it can hold, at the `-18:00` offset
	 * of `OffsetDateTime.MAX`. Written out independently of the production constants.
	 */
	private static final OffsetDateTime STORED_UPPER_SENTINEL = OffsetDateTime.ofInstant(
		Instant.ofEpochMilli((Long.MAX_VALUE / 1_000L - 1L) * 1_000L + 999L), ZoneOffset.MIN
	);

	@DataSet(
		value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false,
		destroyAfterClass = true
	)
	static DataCarrier initDataSet(EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0];
		final HostDefinition systemHost = apiOptions.getEndpointConfiguration(SystemProvider.CODE).getHost()[0];

		final String serverCertificates = apiOptions.certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");
		final EvitaClient evitaClient = new EvitaClient(
			EvitaClientConfiguration.builder()
				.host(grpcHost.hostAddress())
				.port(grpcHost.port())
				.systemApiPort(systemHost.port())
				.tls(
					ClientTlsOptions.builder()
						.mtlsEnabled(false)
						.certificateFolderPath(clientCertificates)
						.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
						.certificateKeyFileName(
							Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName())
						)
						.build()
				)
				.timeouts(ClientTimeoutOptions.builder().timeout(10, TimeUnit.MINUTES).build())
				.build()
		);

		evitaClient.defineCatalog(TEST_CATALOG);
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TYPE)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_VALID_UNTIL, OffsetDateTime.class, whichIs -> whichIs.filterable().nullable()
					)
					.withAttribute(
						ATTRIBUTE_LOCAL_VALID_UNTIL, LocalDateTime.class, whichIs -> whichIs.filterable().nullable()
					)
					.withAttribute(
						ATTRIBUTE_VALID_UNTIL_SERIES, OffsetDateTime[].class, whichIs -> whichIs.filterable().nullable()
					)
					.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
					.updateVia(session);
				session.createNewEntity(ENTITY_TYPE, BASELINE_PK)
					.setAttribute(ATTRIBUTE_VALID_UNTIL, BASELINE_MOMENT)
					.upsertVia(session);
			}
		);
		return new DataCarrier("evitaClient", evitaClient);
	}

	/**
	 * Upserts a new probe entity carrying a single attribute value through the gRPC client.
	 *
	 * @param evitaClient   the gRPC client
	 * @param primaryKey    the primary key of the probe entity
	 * @param attributeName the attribute to set
	 * @param value         the value to set
	 */
	private static void upsertThroughGrpc(
		@Nonnull EvitaClient evitaClient,
		int primaryKey,
		@Nonnull String attributeName,
		@Nonnull Serializable value
	) {
		evitaClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(ENTITY_TYPE, primaryKey)
					.setAttribute(attributeName, value)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Reads the attribute value back through the gRPC client.
	 *
	 * @param evitaClient   the gRPC client
	 * @param primaryKey    the primary key of the probe entity
	 * @param attributeName the attribute to read
	 * @return the value the client received
	 */
	@Nonnull
	private static Serializable readThroughGrpc(
		@Nonnull EvitaClient evitaClient,
		int primaryKey,
		@Nonnull String attributeName
	) {
		final SealedEntity entity = evitaClient.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, SealedEntity>) session ->
				session.getEntity(ENTITY_TYPE, primaryKey, attributeContentAll()).orElseThrow()
		);
		final Serializable value = entity.getAttribute(attributeName);
		assertNotNull(value, "Attribute `" + attributeName + "` of entity " + primaryKey + " was not read back.");
		return value;
	}

	/**
	 * Reads the attribute value as the server stored it, through the embedded instance behind the gRPC server.
	 *
	 * @param evita         the embedded instance
	 * @param primaryKey    the primary key of the probe entity
	 * @param attributeName the attribute to read
	 * @return the stored value
	 */
	@Nonnull
	private static Serializable readStored(@Nonnull Evita evita, int primaryKey, @Nonnull String attributeName) {
		final SealedEntity entity = evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, SealedEntity>) session ->
				session.getEntity(ENTITY_TYPE, primaryKey, attributeContentAll()).orElseThrow()
		);
		final Serializable value = entity.getAttribute(attributeName);
		assertNotNull(value, "Attribute `" + attributeName + "` of entity " + primaryKey + " was not stored.");
		return value;
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should store the last gRPC moment as the upper sentinel and read it back as one")
	void shouldAcceptUpsertOfLastGrpcMoment(Evita evita, EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 10, ATTRIBUTE_VALID_UNTIL, LAST_GRPC_MOMENT));
		assertEquals(STORED_UPPER_SENTINEL, readStored(evita, 10, ATTRIBUTE_VALID_UNTIL));
		assertEquals(OffsetDateTime.MAX, readThroughGrpc(evitaClient, 10, ATTRIBUTE_VALID_UNTIL));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should store the first gRPC moment as the lower sentinel and read it back as one")
	void shouldAcceptUpsertOfFirstGrpcMoment(EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 18, ATTRIBUTE_VALID_UNTIL, FIRST_GRPC_MOMENT));
		assertEquals(OffsetDateTime.MIN, readThroughGrpc(evitaClient, 18, ATTRIBUTE_VALID_UNTIL));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should accept an upsert of any moment in year 9999 and read it back unchanged")
	void shouldAcceptUpsertOfAnyMomentInYear9999(EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 11, ATTRIBUTE_VALID_UNTIL, MID_YEAR_9999));
		final OffsetDateTime readBack = assertInstanceOf(
			OffsetDateTime.class, readThroughGrpc(evitaClient, 11, ATTRIBUTE_VALID_UNTIL)
		);
		assertEquals(MID_YEAR_9999.toInstant(), readBack.toInstant(), "Read back `" + readBack + "`.");
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should store a year 9999 moment as the moment that was sent")
	void shouldStoreYear9999MomentAsTheMomentSent(Evita evita, EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 12, ATTRIBUTE_VALID_UNTIL, MID_YEAR_9999));
		final OffsetDateTime stored = assertInstanceOf(
			OffsetDateTime.class, readStored(evita, 12, ATTRIBUTE_VALID_UNTIL)
		);
		assertEquals(MID_YEAR_9999.toInstant(), stored.toInstant(), "Stored `" + stored + "`.");
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should round-trip OffsetDateTime.MAX through the Java client")
	void shouldAcceptUpsertOfOffsetDateTimeMaxThroughJavaClient(Evita evita, EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 13, ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX));
		assertEquals(STORED_UPPER_SENTINEL, readStored(evita, 13, ATTRIBUTE_VALID_UNTIL));
		assertEquals(OffsetDateTime.MAX, readThroughGrpc(evitaClient, 13, ATTRIBUTE_VALID_UNTIL));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should accept an upsert of a local date-time in year 9999 and read it back unchanged")
	void shouldAcceptLocalDateTimeUpsertInYear9999(EvitaClient evitaClient) {
		final LocalDateTime midYear9999 = MID_YEAR_9999.toLocalDateTime();
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 14, ATTRIBUTE_LOCAL_VALID_UNTIL, midYear9999));
		assertEquals(midYear9999, readThroughGrpc(evitaClient, 14, ATTRIBUTE_LOCAL_VALID_UNTIL));
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should accept an upsert of an array carrying a year 9999 element and read it back unchanged")
	void shouldAcceptArrayUpsertWithYear9999Element(EvitaClient evitaClient) {
		final OffsetDateTime[] series = {BASELINE_MOMENT, MID_YEAR_9999};
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 15, ATTRIBUTE_VALID_UNTIL_SERIES, series));
		final OffsetDateTime[] readBack = assertInstanceOf(
			OffsetDateTime[].class, readThroughGrpc(evitaClient, 15, ATTRIBUTE_VALID_UNTIL_SERIES)
		);
		assertArrayEquals(
			new Instant[]{BASELINE_MOMENT.toInstant(), MID_YEAR_9999.toInstant()},
			new Instant[]{readBack[0].toInstant(), readBack[1].toInstant()}
		);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should find the upper sentinel by an equality query sent through the Java client")
	void shouldFindUpperSentinelByEqualityThroughGrpc(EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 19, ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX));
		final List<SealedEntity> found = evitaClient.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.queryListOfSealedEntities(
				query(
					collection(ENTITY_TYPE),
					filterBy(
						and(
							entityPrimaryKeyInSet(19),
							attributeEquals(ATTRIBUTE_VALID_UNTIL, OffsetDateTime.MAX)
						)
					)
				)
			)
		);
		assertEquals(1, found.size());
		assertEquals(19, found.get(0).getPrimaryKey());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should accept a query constant in year 9999")
	void shouldAcceptQueryConstantInYear9999(EvitaClient evitaClient) {
		final List<SealedEntity> found = assertDoesNotThrow(
			() -> evitaClient.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.queryListOfSealedEntities(
					query(
						collection(ENTITY_TYPE),
						filterBy(
							and(
								entityPrimaryKeyInSet(BASELINE_PK),
								attributeLessThan(ATTRIBUTE_VALID_UNTIL, MID_YEAR_9999)
							)
						)
					)
				)
			)
		);
		assertEquals(1, found.size());
		assertEquals(BASELINE_PK, found.get(0).getPrimaryKey());
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should accept a date-time range bounded by OffsetDateTime.MAX and read it back")
	void shouldAcceptDateTimeRangeBoundedByOffsetDateTimeMax(EvitaClient evitaClient) {
		final DateTimeRange validity = DateTimeRange.between(BASELINE_MOMENT, OffsetDateTime.MAX);
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 16, ATTRIBUTE_VALIDITY, validity));
		final DateTimeRange readBack = assertInstanceOf(
			DateTimeRange.class, readThroughGrpc(evitaClient, 16, ATTRIBUTE_VALIDITY)
		);
		assertNotNull(readBack.getPreciseTo(), "Read back `" + readBack + "`.");
		assertTrue(readBack.getPreciseTo().getYear() >= 9999, "Read back `" + readBack + "`.");
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("should accept an upsert of the last moment of year 9998 and read it back unchanged")
	void shouldAcceptUpsertOfLastMomentOf9998(Evita evita, EvitaClient evitaClient) {
		assertDoesNotThrow(() -> upsertThroughGrpc(evitaClient, 17, ATTRIBUTE_VALID_UNTIL, LAST_MOMENT_OF_9998));
		assertEquals(
			LAST_MOMENT_OF_9998.toInstant(),
			assertInstanceOf(OffsetDateTime.class, readThroughGrpc(evitaClient, 17, ATTRIBUTE_VALID_UNTIL)).toInstant()
		);
		assertEquals(
			LAST_MOMENT_OF_9998.toInstant(),
			assertInstanceOf(OffsetDateTime.class, readStored(evita, 17, ATTRIBUTE_VALID_UNTIL)).toInstant()
		);
	}

}
