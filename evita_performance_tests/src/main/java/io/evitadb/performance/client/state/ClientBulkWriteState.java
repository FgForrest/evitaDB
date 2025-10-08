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

package io.evitadb.performance.client.state;

import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.performance.client.ClientDataState;
import io.evitadb.test.builder.CopyExistingEntityBuilder;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.util.Collections;
import java.util.function.Function;

/**
 * Base state class for bulkInsertThroughput tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientBulkWriteState extends ClientDataState {

	/**
	 * Senesi entity type of product.
	 */
	public static final String PRODUCT_ENTITY_TYPE = "Product";
	/**
	 * Simple counter for measuring total product count inserted into the database.
	 */
	private int counter;
	/**
	 * Highest observed product primary key.
	 */
	private int pkPeek;

	/**
	 * Method is invoked before each benchmark iteration.
	 * Method creates bunch of brand, categories, price lists and stores that cen be referenced in products.
	 * Method also prepares infinite iterator for creating new products for insertion.
	 */
	@Setup(Level.Iteration)
	public void setUp() {
		// reset counter
		this.counter = 0;
		final String catalogName = getCatalogName();
		final String writeCatalogName = catalogName + "_bulkWrite";
		// prepare database
		this.evita = createEmptyEvitaInstance(writeCatalogName);
		// create reader instance
		/* TODO JNO - make this work again */
		/*
		final GenericSerializedCatalogReader reader = new GenericSerializedCatalogReader(
			entityType -> !PRODUCT_ENTITY_TYPE.equals(entityType)
		);
		 */
		// create bunch or entities for referencing in products
		this.evita.updateCatalog(
			writeCatalogName,
			session -> {
				/*
				reader.read(
					catalogName,
					getDataDirectory().resolve(catalogName),
					new EntityConsumer() {
						@Override
						public void setup(MutableCatalogEntityHeader header, EntitySchema schema) {
							session.defineSchema(schema);
						}

						@Override
						public boolean accept(EntitySchema schema, Entity entity) {
							session.upsertEntity(new CopyExistingEntityBuilder(entity));
							return true;
						}

						@Override
						public void close() {

						}
					}
				);
				 */
			}
		);
		// create read/write bulk session
		this.session = this.evita.createReadWriteSession(writeCatalogName);
		// create product iterator
		initProductIterator(CopyExistingEntityBuilder::new);
	}

	/**
	 * Method is called when benchmark iteration is finished, closes session and prints statistics.
	 */
	@TearDown(Level.Iteration)
	public void closeSession() {
		this.session.close();
		this.evita.close();
		System.out.println("\nInserted " + this.counter + " records in iteration.");
	}

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall() {
		if (this.productIterator.hasNext()) {
			this.product = this.productIterator.next();
			// keep track of already assigned primary keys (may have gaps, may be in random order)
			if (this.product.getPrimaryKeyOrThrowException() > this.pkPeek) {
				this.pkPeek = this.product.getPrimaryKeyOrThrowException();
			}
		} else {
			// when products are exhausted - start again from scratch
			initProductIterator(it -> new CopyExistingEntityBuilder(it, ++this.pkPeek));
			// initialize first product from the new round
			this.product = this.productIterator.next();
		}
		this.counter++;
	}

	/**
	 * Initialized product iterator.
	 */
	private void initProductIterator(Function<Entity, EntityBuilder> entityBuilderFactory) {
		/*
		final GenericSerializedCatalogReader reader = new GenericSerializedCatalogReader();
		this.productIterator = StreamSupport.stream(
				Spliterators.spliteratorUnknownSize(
					reader.read(getCatalogName(), getDataDirectory().resolve(getCatalogName()), PRODUCT_ENTITY_TYPE),
					Spliterator.DISTINCT & Spliterator.IMMUTABLE & Spliterator.NONNULL
				), false
			)
			// but assign new primary keys starting from highest observed primary key so far plus one
			.map(entityBuilderFactory)
			.iterator();*/
		this.productIterator = Collections.emptyIterator();
	}

}
