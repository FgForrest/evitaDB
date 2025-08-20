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

package io.evitadb.performance.artificial.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.performance.artificial.ArtificialBenchmarkState;
import io.evitadb.performance.artificial.ArtificialEntitiesBenchmark;
import io.evitadb.performance.setup.EvitaCatalogSetup;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base state class for {@link ArtificialEntitiesBenchmark#bulkInsertThroughput(ArtificialBulkWriteBenchmarkState, ArtificialBulkWriteState)}.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ArtificialBulkWriteBenchmarkState extends ArtificialBenchmarkState implements EvitaCatalogSetup {
	/**
	 * Simple counter for measuring total product count inserted into the database.
	 */
	@Getter private final AtomicInteger counter = new AtomicInteger(0);

	/**
	 * Method is invoked before each benchmark iteration.
	 * Method creates bunch of brand, categories, price lists and stores that cen be referenced in products.
	 * Method also prepares infinite iterator for creating new products for insertion.
	 */
	@Setup(Level.Iteration)
	public void setUp() {
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		// reset counter
		this.counter.set(0);
		final String catalogName = getCatalogName();
		// prepare database
		this.evita = createEmptyEvitaInstance(catalogName);
		// create bunch or entities for referencing in products
		this.evita.updateCatalog(
			catalogName,
			session -> {
				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleBrandSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(5)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleCategorySchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(10)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSamplePriceListSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(4)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleStoreSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(12)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.productSchema = this.dataGenerator.getSampleProductSchema(session);
			}
		);
		// create product iterator
		this.productIterator = getProductStream().iterator();
	}

	/**
	 * We need writable sessions here.
	 */
	@Override
	public EvitaSessionContract getSession() {
		return getSession(() -> this.evita.createReadWriteSession(getCatalogName()));
	}

	/**
	 * Returns name of the test catalog.
	 */
	protected String getCatalogName() {
		return TEST_CATALOG + "_bulkWrite";
	}

	/**
	 * Method is called when benchmark iteration is finished, closes session and prints statistics.
	 */
	@TearDown(Level.Iteration)
	public void closeEvita() {
		this.evita.close();
		System.out.println("\nInserted " + this.counter + " records in iteration.");
	}

}
