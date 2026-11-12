/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.invertedIndex.suppliers;

import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityFixture;

import javax.annotation.Nonnull;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Real whole-index and leaf-slice construction inputs for {@link HistogramBitmapSupplier}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramBitmapSupplierIdentityFixture implements IdentityFixture<HistogramBitmapSupplier> {
	/** Covers both constructors, ordered values, opaque foreign values and oversized sequences. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<HistogramBitmapSupplier>> modes() {
		final ValueToRecord[] buckets = {new ValueToRecordPrimitive(5, 1), new ValueToRecordPrimitive(10, 2)};
		final ValueToRecord[] changed = {new ValueToRecordPrimitive(7, 1), new ValueToRecordPrimitive(10, 2)};
		final ValueToRecord[] reversed = {buckets[1], buckets[0]};
		final ValueToRecord[] opaque = {new ValueToRecordPrimitive(Instant.EPOCH, 1)};
		final ValueToRecord[] manyBuckets = new ValueToRecord[TokenList.CAP + 1];
		Arrays.fill(manyBuckets, buckets[0]);
		final long[] manyIds = new long[TokenList.CAP + 1];
		for (int i = 0; i < manyIds.length; i++) {
			manyIds[i] = i + 1;
		}
		return Map.of(
			"whole index", new ConstructionMode<>(
				() -> new HistogramBitmapSupplier(1L, buckets),
				Map.of(
					"values", () -> new HistogramBitmapSupplier(1L, changed),
					"order", () -> new HistogramBitmapSupplier(1L, reversed),
					"index version", () -> new HistogramBitmapSupplier(2L, buckets)
				),
				Map.of("foreign value", () -> new HistogramBitmapSupplier(1L, opaque)),
				Map.of("many values", () -> new HistogramBitmapSupplier(1L, manyBuckets)),
				Map.of(), Set.of(), Set.of("transactionalIds"), Set.of()
			),
			"leaf slice", new ConstructionMode<>(
				() -> new HistogramBitmapSupplier(new long[]{1, 2}, buckets),
				Map.of(
					"values", () -> new HistogramBitmapSupplier(new long[]{1, 2}, changed),
					"order", () -> new HistogramBitmapSupplier(new long[]{1, 2}, reversed),
					"leaf version", () -> new HistogramBitmapSupplier(new long[]{1, 3}, buckets),
					"leaf count", () -> new HistogramBitmapSupplier(new long[]{1}, buckets)
				),
				Map.of("foreign value", () -> new HistogramBitmapSupplier(new long[]{1, 2}, opaque)),
				Map.of(
					"many values", () -> new HistogramBitmapSupplier(new long[]{1, 2}, manyBuckets),
					"many leaves", () -> new HistogramBitmapSupplier(manyIds, buckets)
				), Map.of(), Set.of(), Set.of(), Set.of()
			)
		);
	}
}
