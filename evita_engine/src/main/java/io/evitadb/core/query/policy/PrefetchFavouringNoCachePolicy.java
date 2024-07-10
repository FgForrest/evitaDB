/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.policy;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This implementation returns the input computer without any caching.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class PrefetchFavouringNoCachePolicy implements PlanningPolicy {
	public static final PrefetchFavouringNoCachePolicy INSTANCE = new PrefetchFavouringNoCachePolicy();

	@Nonnull
	@Override
	public PrefetchPolicy getPrefetchPolicy() {
		return PrefetchPolicy.ALLOW;
	}

	@Override
	public long estimatePrefetchCost(
		int prefetchedEntityCount,
		@Nonnull EntityFetchRequire requirements,
		boolean preferredByUser
	) {
		// if prefetch is possible, try to always use it
		return Long.MIN_VALUE;
	}

	@Nonnull
	@Override
	public Formula analyse(
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Formula formula
	) {
		// no cache
		return formula;
	}

	@Nonnull
	@Override
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull T computer
	) {
		// no cache
		return computer;
	}

}
