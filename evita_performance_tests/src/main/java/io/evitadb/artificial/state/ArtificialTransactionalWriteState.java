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

package io.evitadb.artificial.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.test.Entities;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.artificial.state.ArtificialTransactionalWriteBenchmarkState.INITIAL_COUNT_OF_PRODUCTS;

/**
 * Base state class for {@link io.evitadb.artificial.ArtificialEntitiesBenchmark#transactionalUpsertThroughput(ArtificialTransactionalWriteBenchmarkState, ArtificialTransactionalWriteState)}.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ArtificialTransactionalWriteState extends AbstractArtificialState {

	/**
	 * Prepared product with randomized content ready to be upserted to DB.
	 */
	@Getter protected EntityBuilder product;

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall(ArtificialTransactionalWriteBenchmarkState benchmarkState) {
		final EvitaSessionContract session = benchmarkState.getSession();
		session.openTransaction();
		// there is 50% on update instead of insert
		if (benchmarkState.getRandom().nextBoolean()) {
			final SealedEntity existingEntity = session.getEntity(
				Entities.PRODUCT,
				benchmarkState.getRandom().nextInt(INITIAL_COUNT_OF_PRODUCTS + benchmarkState.getInsertCounter().get()) + 1,
				entityFetchAllContent()
			).orElseThrow();
			this.product = benchmarkState.getModificationFunction().apply(existingEntity);
			benchmarkState.getUpdateCounter().incrementAndGet();
		} else {
			this.product = benchmarkState.getProductIterator().next();
			benchmarkState.getInsertCounter().incrementAndGet();
		}
	}

	@TearDown(Level.Invocation)
	public void finishCall(ArtificialTransactionalWriteBenchmarkState benchmarkState) {
		benchmarkState.getSession().closeTransaction();
	}

}
