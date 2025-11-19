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

package io.evitadb.performance.externalApi.javaDriver.artificial;

import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.GrpcProviderRegistrar;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.externalApi.system.SystemProviderRegistrar;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import io.evitadb.performance.generators.TestDatasetGenerator;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import io.evitadb.utils.CertificateUtils;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;

/**
 * Base state class for {@link JavaDriverArtificialEntitiesBenchmark} benchmark.
 * See benchmark description on the methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class JavaDriverArtificialFullDatabaseBenchmarkState extends JavaDriverArtificialBenchmarkState
	implements EvitaCatalogReusableSetup, TestDatasetGenerator {
	/**
	 * Number of products stored in the database.
	 */
	public static final int PRODUCT_COUNT = 100_000;

	protected ExternalApiServer server;

	/**
	 * Method is invoked before each benchmark.
	 * Method creates bunch of brand, categories, price lists and stores that cen be referenced in products.
	 * Method also prepares 100.000 products in the database.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final ReadyReadEvita readyReadEvita = generateReadTestDataset(
			this.dataGenerator,
			PRODUCT_COUNT,
			this::shouldStartFromScratch,
			this::isCatalogAvailable,
			this::createEvitaInstanceFromExistingData,
			this::createEmptyEvitaInstance,
			getCatalogName(),
			this.generatedEntities,
			SEED,
			this.randomEntityPicker,
			this::processEntity,
			this::processCreatedEntityReference,
			this::createEntity,
			this::processSchema
		);
		this.evita = readyReadEvita.evita();
		this.productSchema = readyReadEvita.productSchema();

		// start grpc server and system api
		this.server = new ExternalApiServer(
			this.evita,
			ApiOptions.builder()
				.enable(SystemProvider.CODE, new SystemOptions(":" + SystemOptions.DEFAULT_SYSTEM_PORT))
				.enable(GrpcProvider.CODE, new GrpcOptions())
				.build(),
			List.of(new SystemProviderRegistrar(), new GrpcProviderRegistrar())
		);
		this.server.start();

		this.driver = new EvitaClient(
			EvitaClientConfiguration.builder()
				.port(GrpcOptions.DEFAULT_GRPC_PORT)
				.systemApiPort(SystemOptions.DEFAULT_SYSTEM_PORT)
				.mtlsEnabled(false)
				.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
				.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
				.build()
		);
	}

	/**
	 * Method closes evita database at the end of trial.
	 */
	@TearDown(Level.Trial)
	public void closeEvita() {
		this.driver.close();
		this.server.close();
		this.evita.close();
	}

	/**
	 * Descendants may store reference to the schema if they want.
	 */
	protected SealedEntitySchema processSchema(@Nonnull SealedEntitySchema schema) {
		// do nothing by default
		return schema;
	}

	/**
	 * Descendants may examine created entity if they want.
	 */
	protected void processEntity(@Nonnull SealedEntity entity) {
		// do nothing by default
	}

	/**
	 * Descendants may examine created entity if they want.
	 */
	protected void processCreatedEntityReference(@Nonnull EntityReferenceContract entity) {
		// do nothing by default
	}

}
