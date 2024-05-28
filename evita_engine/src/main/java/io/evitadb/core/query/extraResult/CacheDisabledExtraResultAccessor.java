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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult;

import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This implementation returns the input computer without any caching.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class CacheDisabledExtraResultAccessor implements ExtraResultCacheAccessor {
	public static final CacheDisabledExtraResultAccessor INSTANCE = new CacheDisabledExtraResultAccessor();

	@Nonnull
	@Override
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(@Nonnull String entityType, @Nonnull T computer) {
		return computer;
	}

}
