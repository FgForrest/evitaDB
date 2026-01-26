/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.cache.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;

import java.math.BigDecimal;

/**
 * This {@link Serializer} implementation reads {@link FlattenedFormula} from binary format
 * that was written before the `relativeFrequency` field was added to {@link CacheableBucket}.
 *
 * This serializer handles backward compatibility for cached histogram data that was serialized
 * without the `relativeFrequency` field. When reading old data, it defaults `relativeFrequency`
 * to {@link BigDecimal#ZERO}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @deprecated This serializer is deprecated and should not be used for writing new data.
 */
@Deprecated(since = "2026.1", forRemoval = true)
public class FlattenedHistogramComputerSerializer_2026_1 extends AbstractFlattenedFormulaSerializer<FlattenedHistogramComputer> {

	@Override
	public void write(Kryo kryo, Output output, FlattenedHistogramComputer object) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public FlattenedHistogramComputer read(Kryo kryo, Input input, Class<? extends FlattenedHistogramComputer> type) {
		final long originalHash = input.readLong();
		final long transactionalIdHash = input.readLong();
		final long[] bitmapIds = readBitmapIds(input);
		final BigDecimal max = kryo.readObject(input, BigDecimal.class);
		final int bucketCount = input.readVarInt(true);
		final CacheableBucket[] buckets = new CacheableBucket[bucketCount];
		for (int i = 0; i < bucketCount; i++) {
			final int occurrences = input.readVarInt(true);
			final BigDecimal threshold = kryo.readObject(input, BigDecimal.class);
			// Old format doesn't have relativeFrequency - default to ZERO
			buckets[i] = new CacheableBucket(threshold, occurrences, BigDecimal.ZERO);
		}

		return new FlattenedHistogramComputer(
			originalHash, transactionalIdHash, bitmapIds,
			new CacheableHistogram(buckets, max)
		);
	}

}
