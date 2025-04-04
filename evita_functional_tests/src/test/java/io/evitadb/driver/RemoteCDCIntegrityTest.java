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

package io.evitadb.driver;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying integrity of the remote CDC communication.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
@Tag(LONG_RUNNING_TEST)
@ExtendWith(EvitaParameterResolver.class)
public class RemoteCDCIntegrityTest {

	private static final String REMOTE_CDC_INTEGRITY_DATA_SET = "RemoteCdcIntegrityDataSet";

	@DataSet(value = REMOTE_CDC_INTEGRITY_DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static DataCarrier initDataSet(EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer()
			.getApiOptions();
		final HostDefinition grpcHost = apiOptions
			.getEndpointConfiguration(GrpcProvider.CODE)
			.getHost()[0];
		final HostDefinition systemHost = apiOptions
			.getEndpointConfiguration(SystemProvider.CODE)
			.getHost()[0];

		final String serverCertificates = evitaServer.getExternalApiServer().getApiOptions().certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");
		final EvitaClientConfiguration evitaClientConfiguration = EvitaClientConfiguration.builder()
			.host(grpcHost.hostAddress())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			.mtlsEnabled(false)
			.certificateFolderPath(clientCertificates)
			.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
			.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
			.build();

		final EvitaClient evitaClient = new EvitaClient(evitaClientConfiguration);
		return new DataCarrier(
			"evitaClient", evitaClient
		);
	}

	@Test
	@DisplayName("Client should receive ALL captures in correct order even when subscriber is much slower than publisher and most of the data must be buffered")
	@UseDataSet(REMOTE_CDC_INTEGRITY_DATA_SET)
	void shouldReceiveAllCapturesEvenWhenSubscriberIsSlowerThanPublisher(EvitaClient evitaClient) throws InterruptedException {
		final int CATALOGS_COUNT = 1000; // simulate a large amount of sudden data
		final int CLIENT_FAKE_WORK_TIME_FOR_SINGLE_CATALOG = 1000; // simulate a slow client

		final List<String> receivedCatalogs = new ArrayList<>(CATALOGS_COUNT);
		final CountDownLatch receivedCatalogsLatch = new CountDownLatch(CATALOGS_COUNT);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher = evitaClient.registerSystemChangeCapture(new ChangeSystemCaptureRequest(ChangeCaptureContent.HEADER));
		publisher.subscribe(new Subscriber<>() {

			private Subscription subscription;

			@Override
			public void onSubscribe(Subscription subscription) {
				this.subscription = subscription;
				this.subscription.request(1);
			}

			@Override
			public void onNext(ChangeSystemCapture item) {
				receivedCatalogs.add(item.catalog());
				receivedCatalogsLatch.countDown();
				log.info("Received catalog `{}` doing some fake difficult work...", item.catalog());
				try {
					// simulating slow client, which is not able to process items fast enough to keep up with the server
					Thread.sleep(CLIENT_FAKE_WORK_TIME_FOR_SINGLE_CATALOG);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				this.subscription.request(1);
			}

			@Override
			public void onError(Throwable throwable) {
				throw new RuntimeException(throwable);
			}

			@Override
			public void onComplete() {
				throw new RuntimeException("Should not be called! Publisher is not finite by design.");
			}
		});

		for (int i = 0; i < CATALOGS_COUNT; i++) {
			// quickly generates large amounts of data, simulating server which is sending data faster than client can process it
			evitaClient.defineCatalog("newCatalog" + i);
		}
		log.info("All catalogs are created. Waiting for client to catch up...");

		// waiting for the client subscriber to catch up
		assertTrue(receivedCatalogsLatch.await(
			(CATALOGS_COUNT * CLIENT_FAKE_WORK_TIME_FOR_SINGLE_CATALOG) + (CATALOGS_COUNT * 200 /* for potential overhead */),
			TimeUnit.MILLISECONDS
		));
		// validates that all received catalogs are in correct order
		for (int i = 0; i < CATALOGS_COUNT; i++) {
			assertEquals("newCatalog" + i, receivedCatalogs.get(i));
		}

		// we should clean up all created catalogs
		log.info("Cleaning up all catalogs...");
		for (int i = 0; i < CATALOGS_COUNT; i++) {
			evitaClient.deleteCatalogIfExists("newCatalog" + i);
		}
	}
}
