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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ExtraResultRequireConstraint;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.performance.client.ClientDataFullDatabaseState;
import io.evitadb.performance.generators.RandomQueryGenerator;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.utils.Assert;
import lombok.Data;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;

import static java.util.Optional.ofNullable;

/**
 * Base state class for syntheticTest tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientSyntheticTestState extends ClientDataFullDatabaseState
	implements RandomQueryGenerator {

	private static final int PRELOADED_QUERY_COUNT = 100_000;
	private final Object monitor = new Object();

	private Deque<Query> preloadedQueries = new ArrayDeque<>(64);
	private Path inputFolder;
	@Nullable private Kryo kryo;
	@Nullable private Input input;
	/**
	 * Query prepared for the measured invocation.
	 */
	@Nullable @Getter protected QueryWithExpectedType queryWithExpectedType;

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Iteration)
	public void prepareQueries() {
		this.inputFolder = getDataDirectory().resolve(getCatalogName() + "_queries/queries.kryo");
		try {
			this.input = new ByteBufferInput(new FileInputStream(this.inputFolder.toFile()), 8_192);
			this.kryo = KryoFactory.createKryo(QuerySerializationKryoConfigurer.INSTANCE);
			this.preloadedQueries = fetchNewQueries(PRELOADED_QUERY_COUNT);
		} catch (FileNotFoundException e) {
			throw new RuntimeException("Cannot access input folder: " + this.inputFolder);
		}
	}

	@TearDown(Level.Iteration)
	public void tearDown() {
		if (this.input != null) {
			this.input.close();
		}
		this.input = null;
		this.kryo = null;
	}

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall() {
		this.queryWithExpectedType = ofNullable(this.preloadedQueries.pollFirst()).map(QueryWithExpectedType::new).orElse(null);
		while (this.queryWithExpectedType == null) {
			this.preloadedQueries = fetchNewQueries(PRELOADED_QUERY_COUNT);
			this.queryWithExpectedType = new QueryWithExpectedType(this.preloadedQueries.pollFirst());
		}
	}

	private Deque<Query> fetchNewQueries(int queryCount) {
		final LinkedList<Query> fetchedQueries = new LinkedList<>();
		synchronized (this.monitor) {
			for (int i = 0; i < queryCount; i++) {
				Assert.isPremiseValid(this.kryo != null && this.input != null, "Kryo or input stream is not initialized properly.");
				try {
					if (!this.input.canReadInt()) {
						// reopen the same file again and read from start
						this.input.close();
						this.input = new ByteBufferInput(new FileInputStream(this.inputFolder.toFile()), 8_192);
					}
				} catch (FileNotFoundException e) {
					throw new RuntimeException("Cannot access input folder: " + this.inputFolder);
				}
				fetchedQueries.add(this.kryo.readObject(this.input, Query.class));
			}
		}
		return fetchedQueries;
	}

	@Data
	public static class QueryWithExpectedType {
		private final Query query;
		private final Class<? extends EntityClassifier> expectedResult;

		public QueryWithExpectedType(@Nullable Query query) {
			this.query = query;
			this.expectedResult = this.query != null && this.query.getRequire() != null && FinderVisitor.findConstraints(this.query.getRequire(), EntityContentRequire.class::isInstance, ExtraResultRequireConstraint.class::isInstance).isEmpty()
				? EntityReferenceContract.class : SealedEntity.class;
		}

	}

}
