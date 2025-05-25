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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * This implementation combines both {@link ResolvedFilteredPriceRecords} and {@link LazyEvaluatedEntityPriceRecords}
 * implementation preferring the price records from the resolved price records first.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CombinedPriceRecords implements FilteredPriceRecords {
	@Serial private static final long serialVersionUID = -9121190638019933649L;
	@Getter private final NonResolvedFilteredPriceRecords nonResolvedFilteredPriceRecords;
	@Getter private final LazyEvaluatedEntityPriceRecords lazyEvaluatedEntityPriceRecords;
	@Nullable @Getter private ResolvedFilteredPriceRecords resolvedFilteredPriceRecords;

	public CombinedPriceRecords(
		@Nonnull ResolvedFilteredPriceRecords resolvedFilteredPriceRecords,
		@Nonnull LazyEvaluatedEntityPriceRecords lazyEvaluatedEntityPriceRecords
	) {
		this.nonResolvedFilteredPriceRecords = null;
		this.resolvedFilteredPriceRecords = resolvedFilteredPriceRecords;
		this.lazyEvaluatedEntityPriceRecords = lazyEvaluatedEntityPriceRecords;
	}

	public CombinedPriceRecords(
		@Nonnull NonResolvedFilteredPriceRecords nonResolvedFilteredPriceRecords,
		@Nonnull LazyEvaluatedEntityPriceRecords lazyEvaluatedEntityPriceRecords
	) {
		this.nonResolvedFilteredPriceRecords = nonResolvedFilteredPriceRecords;
		this.resolvedFilteredPriceRecords = null;
		this.lazyEvaluatedEntityPriceRecords = lazyEvaluatedEntityPriceRecords;
	}

	@Nonnull
	@Override
	public PriceRecordLookup getPriceRecordsLookup() {
		if (this.nonResolvedFilteredPriceRecords != null && this.resolvedFilteredPriceRecords == null) {
			this.resolvedFilteredPriceRecords = this.nonResolvedFilteredPriceRecords.toResolvedFilteredPriceRecords();
		}
		return new PriceRecordIterator(
			Objects.requireNonNull(this.resolvedFilteredPriceRecords).getPriceRecordsLookup(),
			this.lazyEvaluatedEntityPriceRecords.getPriceRecordsLookup()
		);
	}

	/**
	 * Implementation of {@link PriceRecordLookup} that provides prices first from the {@link #resolvedFilteredPriceRecords}
	 * and if not found from {@link #lazyEvaluatedEntityPriceRecords}.
	 */
	@ThreadSafe
	@RequiredArgsConstructor
	public static class PriceRecordIterator implements PriceRecordLookup {
		private final ResolvedFilteredPriceRecords.PriceRecordIterator resolvedPriceRecordsLookup;
		private final LazyEvaluatedEntityPriceRecords.PriceRecordIterator lazyPriceRecordsLookup;

		@Override
		public boolean forEachPriceOfEntity(int entityPk, int lastExpectedEntity, @Nonnull Consumer<PriceRecordContract> priceConsumer) {
			if (this.resolvedPriceRecordsLookup.forEachPriceOfEntity(entityPk, lastExpectedEntity, priceConsumer)) {
				return true;
			} else {
				return this.lazyPriceRecordsLookup.forEachPriceOfEntity(entityPk, lastExpectedEntity, priceConsumer);
			}
		}

	}

}
