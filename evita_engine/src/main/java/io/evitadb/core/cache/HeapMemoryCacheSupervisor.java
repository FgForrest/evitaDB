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

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.response.ServerBinaryEntityDecorator;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.core.query.sort.Sorter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static java.util.Optional.ofNullable;

/**
 * This class contains the full logic documented in {@link CacheSupervisor}. It delegates its logic to two additional
 * classes:
 *
 * - {@link CacheAnteroom} the place for all costly formulas that haven't yet been placed in the cache
 * - {@link CacheEden} the place for all already cached formulas
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see CacheSupervisor for more information
 */
public class HeapMemoryCacheSupervisor implements CacheSupervisor {
	/**
	 * Anteroom with cache adepts.
	 */
	private final CacheAnteroom cacheAnteroom;
	/**
	 * Eden cache.
	 */
	private final CacheEden cacheEden;
	/**
	 * Task that reevaluates the cache contents.
	 */
	private final DelayedAsyncTask reevaluationTask;

	public HeapMemoryCacheSupervisor(@Nonnull CacheOptions cacheOptions, @Nonnull Scheduler scheduler) {
		this.cacheEden = new CacheEden(
			cacheOptions.cacheSizeInBytes(),
			cacheOptions.minimalUsageThreshold(),
			cacheOptions.minimalComplexityThreshold(),
			scheduler
		);
		this.cacheAnteroom = new CacheAnteroom(
			cacheOptions.anteroomRecordCount(),
			cacheOptions.minimalComplexityThreshold(),
			this.cacheEden, scheduler
		);
		// initialize function that will frequently evaluate contents of the cache, discard unused entries and introduce
		// new ones from the CacheAnteroom
		this.reevaluationTask = new DelayedAsyncTask(
			null,
			"Eden cache timed reevaluation",
			scheduler,
			() -> {
				this.cacheAnteroom.evaluateAssociatesSynchronouslyIfNoAdeptsWait();
				// plan next reevaluation in standard interval
				return 0L;
			},
			cacheOptions.reevaluateEachSeconds(),
			TimeUnit.SECONDS
		);
		this.reevaluationTask.schedule();
	}

	@Nonnull
	@Override
	public <T extends Formula> T analyse(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull T filterFormula
	) {
		// we use cache only for Evita read only sessions, write session might already contain client specific modifications
		// that effectively exclude the formula caches from being used
		if (evitaSession.isReadOnly()) {
			//noinspection unchecked
			return (T) FormulaCacheVisitor.analyse(evitaSession, entityType, filterFormula, this.cacheAnteroom);
		} else {
			return filterFormula;
		}
	}

	@SuppressWarnings("ClassEscapesDefinedScope")
	@Nonnull
	@Override
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull T extraResultComputer
	) {
		// we use cache only for Evita read only sessions, write session might already contain client specific modifications
		// that effectively exclude the formula caches from being used
		if (evitaSession.isReadOnly()) {
			//noinspection unchecked
			return (EvitaResponseExtraResultComputer<U>) this.cacheAnteroom.register(
				evitaSession, entityType, extraResultComputer
			);
		} else {
			return extraResultComputer;
		}
	}

	@Nonnull
	@Override
	public Sorter analyse(
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull CacheableSorter cacheableSorter
	) {
		// we use cache only for Evita read only sessions, write session might already contain client specific modifications
		// that effectively exclude the formula caches from being used
		if (evitaSession.isReadOnly()) {
			return this.cacheAnteroom.register(
				evitaSession, entityType, cacheableSorter
			);
		} else {
			return cacheableSorter;
		}
	}

	@Nonnull
	@Override
	public Optional<ServerEntityDecorator> analyse(
		@Nonnull EvitaSessionContract evitaSession,
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull OffsetDateTime offsetDateTime,
		@Nullable EntityFetch entityRequirement,
		@Nonnull Supplier<ServerEntityDecorator> entityFetcher,
		@Nonnull UnaryOperator<ServerEntityDecorator> enricher
	) {
		// we use cache only for Evita read only sessions, write session might already contain client specific modifications
		// that effectively exclude the formula caches from being used
		if (evitaSession.isReadOnly()) {
			return ofNullable(
				this.cacheAnteroom.register(
					evitaSession, primaryKey, entityType, offsetDateTime,
					entityRequirement, entityFetcher, enricher
				)
			);
		} else {
			return ofNullable(entityFetcher.get());
		}
	}

	@Nonnull
	@Override
	public Optional<ServerBinaryEntityDecorator> analyse(
		@Nonnull EvitaSessionContract evitaSession,
		int primaryKey,
		@Nonnull String entityType,
		@Nullable EntityFetch entityRequirement,
		@Nonnull Supplier<ServerBinaryEntityDecorator> entityFetcher,
		@Nonnull UnaryOperator<ServerBinaryEntityDecorator> enricher
	) {
		// we use cache only for Evita read only sessions, write session might already contain client specific modifications
		// that effectively exclude the formula caches from being used
		if (evitaSession.isReadOnly()) {
			return ofNullable(
				this.cacheAnteroom.register(
					evitaSession, primaryKey, entityType, entityRequirement, entityFetcher
				)
			);
		} else {
			return ofNullable(entityFetcher.get());
		}
	}

}
