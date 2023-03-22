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

package io.evitadb.externalApi.grpc.services;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestChannelCreator;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

/**
 * This test defines shared dataset method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ExtendWith(DbInstanceParameterResolver.class)
public abstract class EvitaGrpcAbstractTest {
	protected static final String THOUSAND_PRODUCTS = "ThousandProducts";
	protected static ExternalApiServer EXTERNAL_API_SERVER;
	protected static ManagedChannel CHANNEL;

	@DataSet(THOUSAND_PRODUCTS)
	List<SealedEntity> setUp(Evita evita) {
		final ExternalApiServer externalApiServer = new ExternalApiServer(
			evita,
			ApiOptions.builder()
				.enable(GrpcProvider.CODE)
				.enable(SystemProvider.CODE)
				.build()
		);

		// open the API on configured ports
		externalApiServer.start();

		EXTERNAL_API_SERVER = externalApiServer;
		CHANNEL = TestChannelCreator.getChannel(new ClientSessionInterceptor(), externalApiServer);

		return new TestDataProvider().generateEntities(evita);
	}

	@OnDataSetTearDown(THOUSAND_PRODUCTS)
	void onDataSetTearDown() {
		CHANNEL.shutdown();
		EXTERNAL_API_SERVER.close();
	}

	@AfterEach
	public void afterEach() {
		SessionIdHolder.reset();
	}

}
