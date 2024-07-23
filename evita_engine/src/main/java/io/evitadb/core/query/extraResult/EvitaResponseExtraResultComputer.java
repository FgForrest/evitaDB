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

package io.evitadb.core.query.extraResult;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;

import javax.annotation.Nonnull;

/**
 * Interface must be implemented by classes that provide "computational" logic for {@link EvitaResponseExtraResult}
 * objects and that can be subject to caching logic. This interface represents minimal "contract" for them, that is
 * implemented by "cached" counterpart of the original computer with memoized result. The original computer must
 * implement accompanying interface {@link CacheableEvitaResponseExtraResultComputer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EvitaResponseExtraResultComputer<T> {

	/**
	 * Method returns object that is either {@link EvitaResponseExtraResult} or its part.
	 */
	@Nonnull
	T compute();

}
