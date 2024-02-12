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

package io.evitadb.core.cache.payload;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.cache.CacheEden;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
@RequiredArgsConstructor
public class EntityComputationalObjectAdapter implements TransactionalDataRelatedStructure {
	/**
	 * Primary key of the entity that is requested for fetching.
	 */
	private final int entityPrimaryKey;
	/**
	 * Function allowing to fetch current version of the {@link EvitaSessionContract} for cached entity.
	 */
	@Nonnull private final Supplier<EntitySchemaContract> entitySchemaFetcher;
	/**
	 * The logic that executed the factual entity fetch from the persistent datastore. The supplier will never be called
	 * providing the entity is found in the cache.
	 */
	@Nonnull private final Supplier<EntityDecorator> entityFetcher;
	/**
	 * The logic that is executed if the entity found in the cache. This logic must check if the entity is rich enough
	 * to satisfy the input request and if not lazy-fetch additional containers so the entity is complete enough.
	 */
	@Nonnull private final UnaryOperator<EntityDecorator> entityEnricher;
	/**
	 * Copy of the {@link EvitaRequest#getAlignedNow()} that was actual when entity was fetched from the database.
	 */
	@Getter private final OffsetDateTime alignedNow;
	/**
	 * Contains the count of requested {@link io.evitadb.api.query.require.EntityContentRequire} that control how many
	 * containers would have to be fetched from the persistent storage in order to supply rich enough entity for
	 * the current request. This affects the "cost" of the entity fetch that needs to be examined when determining which
	 * entities are worth to stay in the cache.
	 */
	private final int requirementCount;
	/**
	 * Contains minimal threshold of the {@link Formula#getEstimatedCost(CalculationContext)}  that formula needs to exceed in order to
	 * become a cache adept, that may be potentially moved to {@link CacheEden}.
	 */
	private final long minimalComplexityThreshold;

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return entityPrimaryKey;
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		return entityPrimaryKey;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return new long[0];
	}

	@Override
	public long getEstimatedCost(@Nonnull CalculationContext calculationContext) {
		if (calculationContext.visit(CalculationType.ESTIMATED_COST, this)) {
			return Math.max(minimalComplexityThreshold, requirementCount * getOperationCost());
		} else {
			return 0L;
		}
	}

	@Override
	public long getCost(@Nonnull CalculationContext calculationContext) {
		if (calculationContext.visit(CalculationType.COST, this)) {
			return Math.max(minimalComplexityThreshold, requirementCount * getOperationCost());
		} else {
			return 0L;
		}
	}

	@Override
	public long getOperationCost() {
		return 148L;
	}

	@Override
	public long getCostToPerformanceRatio(@Nonnull CalculationContext calculationContext) {
		return getCost(calculationContext);
	}

	/**
	 * Method returns current entity schema for the entity.
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema() {
		return Objects.requireNonNull(entitySchemaFetcher.get());
	}

	/**
	 * Method will fetch the factual entity from the persistent datastore.
	 */
	@Nullable
	public EntityDecorator fetchEntity() {
		return entityFetcher.get();
	}

	/**
	 * Method will check if the entity is rich enough to satisfy the input request and if not lazy-fetch additional
	 * containers so the entity is complete enough.
	 */
	@Nonnull
	public EntityDecorator enrichEntity(@Nonnull EntityDecorator entity) {
		return entityEnricher.apply(entity);
	}

}
