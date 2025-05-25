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

package io.evitadb.core.cache.payload;

import io.evitadb.core.cache.CacheEden;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.response.ServerBinaryEntityDecorator;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * This adappter wraps a request for fetching single Evita entity. The wrapper handles the {@link TransactionalDataRelatedStructure}
 * interface and logic that is required by {@link CacheEden}. The wrapper has no other reason to exists and is thrown
 * away immediately after the entity is retrieved or put to the cache.
 *
 * TOBEDONE JNO - we need to handle discarding the entities that have been modified (i.e. their version is not valid for
 * current session - somehow track invalidation transactional id?)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class BinaryEntityComputationalObjectAdapter implements TransactionalDataRelatedStructure {
	/**
	 * Primary key of the entity that is requested for fetching.
	 */
	private final int entityPrimaryKey;
	/**
	 * The logic that executed the factual entity fetch from the persistent datastore. The supplier will never be called
	 * providing the entity is found in the cache.
	 */
	@Nonnull private final Supplier<ServerBinaryEntityDecorator> entityFetcher;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private final Long cost;

	public BinaryEntityComputationalObjectAdapter(
		int entityPrimaryKey,
		@Nonnull Supplier<ServerBinaryEntityDecorator> entityFetcher,
		int requirementCount,
		long minimalComplexityThreshold
	) {
		this.entityPrimaryKey = entityPrimaryKey;
		this.entityFetcher = entityFetcher;
		this.estimatedCost = Math.max(minimalComplexityThreshold, requirementCount * getOperationCost());
		this.cost = Math.max(minimalComplexityThreshold, requirementCount * getOperationCost());
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getHash() {
		return this.entityPrimaryKey;
	}

	@Override
	public long getTransactionalIdHash() {
		return this.entityPrimaryKey;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return new long[0];
	}

	@Override
	public long getEstimatedCost() {
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		return 148L;
	}

	@Override
	public long getCostToPerformanceRatio() {
		return getCost();
	}

	/**
	 * Method will fetch the factual entity from the persistent datastore.
	 */
	@Nullable
	public ServerBinaryEntityDecorator fetchEntity() {
		return this.entityFetcher.get();
	}

}
