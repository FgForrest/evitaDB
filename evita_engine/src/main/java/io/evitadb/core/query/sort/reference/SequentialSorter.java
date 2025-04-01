/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.sort.reference;


import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.ReducedEntityIndex;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SequentialSorter implements Sorter {
	private final ReducedEntityIndex[][] atomicBlocks;
	private final Sorter delegate;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;

	public SequentialSorter(@Nonnull ReducedEntityIndex[][] atomicBlocks, @Nonnull Sorter delegate) {
		this.atomicBlocks = atomicBlocks;
		this.delegate = delegate;
		this.unknownRecordIdsSorter = null;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new SequentialSorter(this.atomicBlocks, this.delegate.cloneInstance(), this.unknownRecordIdsSorter);
	}

	@Nonnull
	@Override
	public Sorter andThen(@Nonnull Sorter sorterForUnknownRecords) {
		return new SequentialSorter(this.atomicBlocks, this.delegate, sorterForUnknownRecords);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return this.unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		int skipped,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Sorter theSorter = ConditionalSorter.getFirstApplicableSorter(queryContext, this.delegate);
		if (theSorter == null) {
			return peak;
		} else {
			int resultPeak = peak;
			final SkippingRecordConsumer theSkippedRecordsConsumer = new SkippingRecordConsumer(skippedRecordsConsumer);
			for (ReducedEntityIndex[] atomicBlock : this.atomicBlocks) {
				// TODO JNO - handle comparators
				final Formula limitedInput = FormulaFactory.or(
					Arrays.stream(atomicBlock)
						.map(it -> FormulaFactory.and(input, it.getAllPrimaryKeysFormula()))
						.toArray(Formula[]::new)
				);
				resultPeak = theSorter.sortAndSlice(
					queryContext,
					/* TODO JNO - tady asi nějak odstraňovat ty, co už byly? */
					limitedInput,
					startIndex,
					endIndex,
					result,
					resultPeak,
					theSkippedRecordsConsumer.getCounter(),
					theSkippedRecordsConsumer
				);
				// we don't need to continue, we've reached the requested number of records
				if (theSkippedRecordsConsumer.getCounter() + resultPeak >= endIndex) {
					break;
				}
			}
			return resultPeak;
		}
	}
}
