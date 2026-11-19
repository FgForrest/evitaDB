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

package io.evitadb.externalApi.lab;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.tester.LabApiTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.LAB;
import static io.evitadb.test.TestTags.SECURITY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the embedded lab fixture and its shared tester connect using the documented TLS default.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@Tag(LAB)
@Tag(SECURITY)
@DisplayName("Lab default TLS connection")
class LabTlsFunctionalTest {
	/** Creates a minimal server fixture with the lab endpoint enabled through its host-only constructor. */
	@DataSet(value = "labTls", openWebApi = LabProvider.CODE)
	DataCarrier setUp(@Nonnull Evita evita) {
		return new DataCarrier("serverName", evita.getConfiguration().name());
	}

	/** Verifies the lab redirect over HTTPS and uses the shared HTTPS tester to request a missing route. */
	@Test
	@UseDataSet("labTls")
	@DisplayName("Should connect to lab using HTTPS with default embedded options")
	void shouldConnectToLabUsingDefaultTls(@Nonnull EvitaServer server, @Nonnull LabApiTester tester) {
		assertEquals(
			TlsMode.FORCE_TLS,
			server.getExternalApiServer().getApiOptions().getEndpointConfiguration(LabProvider.CODE).getTlsMode(),
			"TLS mode of the lab created by the host-only embedded options constructor"
		);
		try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
			final String baseUrl = server.getExternalApiServer().getApiOptions()
				.getEndpointConfiguration(LabProvider.CODE).getBaseUrls()[0];
			final AggregatedHttpResponse response = WebClient.builder(baseUrl).factory(factory).build()
				.blocking().get("/");
			assertEquals(303, response.status().code());
			assertNotNull(response.headers().get("location"));
			assertTrue(response.headers().get("location").contains("evitalab-server-name="));
		}
		tester.test("missing-route").get("").executeAndExpectNotFoundAndThen();
	}
}
