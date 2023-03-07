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

package io.evitadb.externalApi.rest.testSuite;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.RestProviderRegistrar;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.evitadb.externalApi.rest.testSuite.RESTTester.Request;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;

/**
 * Common ancestor for functional testing of REST API server. It sets up Evita instance, REST API server and
 * API tester.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
@Tag(FUNCTIONAL_TEST)
@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public abstract class RESTEndpointFunctionalTest {

	public static final String TYPENAME_FIELD = "__typename";

	private static ExternalApiServer server;
	private static RESTTester tester;

	@SneakyThrows
	@BeforeEach
	void setUp() {
		 tester = new RESTTester("https://" + InetAddress.getByName("localhost").getHostAddress() + ":5555/rest" + getEndpointPath());
	}

	@Nonnull
	protected abstract String getEndpointPath();

	@DataSet(REST_THOUSAND_PRODUCTS)
	List<SealedEntity> setUp(Evita evita) {
		TestDataGenerator.generateMockCatalogs(evita);
		final List<SealedEntity> entities = TestDataGenerator.generateMainCatalogEntities(evita);

		server = new ExternalApiServer(
			evita,
			new ApiOptions(null, new CertificateSettings.Builder().build(), Map.of(RestProvider.CODE, new RestConfig())),
			Collections.singleton(new RestProviderRegistrar())
		);
		server.start();

		return entities;
	}

	@AfterAll
	static void tearDown() {
		if (server != null) {
			server.close();
			server = null;
		}
	}

	/**
	 * Test single request to REST API.
	 */
	protected Request testRESTCall() {
		return tester.test();
	}
}
