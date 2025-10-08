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

package io.evitadb.core.query.algebra;

import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.index.bitmap.Bitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * AbstractCacheableFormula is abstract ancestor for all {@link Formula} that produce int based bitmaps as their
 * result and that are suitable for caching. We need to stick to primitive types in order to perform fast. That's why
 * we need to maintain this type of formula.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class AbstractCacheableFormula extends AbstractFormula implements CacheableFormula {
	protected final Consumer<CacheableFormula> computationCallback;

	protected AbstractCacheableFormula(@Nullable Consumer<CacheableFormula> computationCallback) {
		this.computationCallback = computationCallback;
	}

	protected AbstractCacheableFormula(@Nullable Bitmap memoizedResult, @Nullable Consumer<CacheableFormula> computationCallback) {
		this.memoizedResult = memoizedResult;
		this.computationCallback = computationCallback;
	}

	@Nonnull
	@Override
	public Bitmap compute() {
		if (this.memoizedResult == null) {
			this.memoizedResult = computeInternal();
			ofNullable(this.computationCallback).ifPresent(it -> it.accept(this));
		}
		return this.memoizedResult;
	}

	@Override
	public FlattenedFormula toSerializableFormula(long formulaHash, @Nonnull LongHashFunction hashFunction) {
		// by this time computation result should be already memoized
		return new FlattenedFormula(
			formulaHash,
			getTransactionalIdHash(),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			compute()
		);
	}

	@Override
	public int getSerializableFormulaSizeEstimate() {
		return FlattenedFormula.estimateSize(
			gatherTransactionalIds(),
			compute()
		);
	}
}
