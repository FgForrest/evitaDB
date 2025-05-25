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

package io.evitadb.store.cache;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredOutRecords;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPrices;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesAndFilteredOutRecords;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.store.cache.serializer.FlattenedFormulaSerializer;
import io.evitadb.store.cache.serializer.FlattenedFormulaWithFilteredOutRecordsSerializer;
import io.evitadb.store.cache.serializer.FlattenedFormulaWithFilteredPricesAndFilteredOutRecordsSerializer;
import io.evitadb.store.cache.serializer.FlattenedFormulaWithFilteredPricesSerializer;
import io.evitadb.store.cache.serializer.FlattenedHistogramComputerSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link CacheableFormula}.
 */
@RequiredArgsConstructor
class CachedRecordKryoConfigurer implements Consumer<Kryo> {
	private static final int CACHE_BASE = 200;
	private final Supplier<GlobalEntityIndex> globalEntityIndexAccessor;

	@Override
	public void accept(Kryo kryo) {
		kryo.register(FlattenedFormula.class, new SerialVersionBasedSerializer<>(new FlattenedFormulaSerializer(), FlattenedFormula.class), CACHE_BASE);
		kryo.register(FlattenedFormulaWithFilteredOutRecords.class, new SerialVersionBasedSerializer<>(new FlattenedFormulaWithFilteredOutRecordsSerializer(), FlattenedFormulaWithFilteredOutRecords.class), 201);
		kryo.register(FlattenedFormulaWithFilteredPrices.class, new SerialVersionBasedSerializer<>(new FlattenedFormulaWithFilteredPricesSerializer(this.globalEntityIndexAccessor), FlattenedFormulaWithFilteredPrices.class), 202);
		kryo.register(FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.class, new SerialVersionBasedSerializer<>(new FlattenedFormulaWithFilteredPricesAndFilteredOutRecordsSerializer(this.globalEntityIndexAccessor), FlattenedFormulaWithFilteredPricesAndFilteredOutRecords.class), 203);
		kryo.register(FlattenedHistogramComputer.class, new SerialVersionBasedSerializer<>(new FlattenedHistogramComputerSerializer(), FlattenedHistogramComputer.class), 204);
	}

}
