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
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This implementation of {@link CacheSupervisor} is used when caching is disabled entirely. It fulfills the interface
 * by doing nothing (easy life, isn't it?).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NoCacheSupervisor implements CacheSupervisor {
	public static final NoCacheSupervisor INSTANCE = new NoCacheSupervisor();

	@Nonnull
	@Override
	public <T extends Formula> T analyse(
		@Nullable EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull T filterFormula
	) {
		// just return the input without any modification
		return filterFormula;
	}

	@SuppressWarnings("ClassEscapesDefinedScope")
	@Nonnull
	@Override
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(
		@Nullable EvitaSessionContract evitaSession,
		@Nonnull String entityType,
		@Nonnull T extraResultComputer
	) {
		// just return the input without any modification
		return extraResultComputer;
	}

	@Override
	public void close() {
		// nothing to close
	}

}
