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

package io.evitadb.performance.artificial;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.performance.generators.TestDatasetGenerator;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import io.evitadb.test.TestConstants;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Base state class for {@link ArtificialEntitiesBenchmark} benchmark.
 * See benchmark description on the methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ArtificialFullDatabaseBenchmarkState extends ArtificialBenchmarkState
	implements EvitaCatalogReusableSetup, TestDatasetGenerator {
	/**
	 * Number of products stored in the database.
	 */
	public static final int PRODUCT_COUNT = 100_000;

	/**
	 * Method is invoked before each benchmark.
	 * Method creates bunch of brand, categories, price lists and stores that cen be referenced in products.
	 * Method also prepares 100.000 products in the database.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final ReadyReadEvita readyReadEvita = generateReadTestDataset(
			dataGenerator,
			PRODUCT_COUNT,
			this::shouldStartFromScratch,
			this::isCatalogAvailable,
			this::createEvitaInstanceFromExistingData,
			this::createEmptyEvitaInstance,
			getCatalogName(),
			generatedEntities,
			SEED,
			randomEntityPicker,
			this::processEntity,
			this::processCreatedEntityReference,
			this::createEntity,
			this::processSchema
		);
		this.evita = readyReadEvita.evita();
		this.productSchema = readyReadEvita.productSchema();
	}

	/**
	 * Method closes evita database at the end of trial.
	 */
	@TearDown(Level.Trial)
	public void closeEvita() {
		this.evita.close();
	}

	/**
	 * Returns name of the test catalog.
	 */
	protected String getCatalogName() {
		return TestConstants.TEST_CATALOG;
	}

	/**
	 * Descendants may store reference to the schema if they want.
	 */
	protected SealedEntitySchema processSchema(SealedEntitySchema schema) {
		// do nothing by default
		return schema;
	}

	/**
	 * Descendants may examine created entity if they want.
	 */
	protected void processEntity(SealedEntity entity) {
		// do nothing by default
	}

	/**
	 * Descendants may examine created entity if they want.
	 */
	protected void processCreatedEntityReference(EntityReference entity) {
		// do nothing by default
	}

}
