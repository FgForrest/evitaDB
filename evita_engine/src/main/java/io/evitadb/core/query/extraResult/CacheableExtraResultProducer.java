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

package io.evitadb.core.query.extraResult;

import javax.annotation.Nonnull;

/**
 * Implementation of this interface declares that it allows caching of its partial results. This interface exists only
 * for debugging purposes. In case {@link io.evitadb.api.query.require.DebugMode#VERIFY_POSSIBLE_CACHING_TREES} is
 * part of the query requirements, then the query planner will verify that all producers in the query plan produces
 * consistent results both when the results are not cached and when they are cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface CacheableExtraResultProducer extends ExtraResultProducer {

	/**
	 * Creates clone of this producer instance with exchanged {@link ExtraResultCacheAccessor} implementation.
	 */
	@Nonnull
	ExtraResultProducer cloneInstance(@Nonnull ExtraResultCacheAccessor cacheAccessor);

}
