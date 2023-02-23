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

package io.evitadb.store.cache;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SerializationService;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.function.Supplier;

/**
 * This class takes care of (de)serialization {@link CachePayloadHeader} from and to binary format.
 * Currently, simple implementation that keeps single kryo instance with all necessary classes registered. Implementation
 * is not thread safe.
 *
 * Deserializers that work with price needs to have access to the original {@link CacheableFormula} in order to find
 * proper {@link PriceRecord} for serialized price ids. Complete price records are not serialized
 * to save memory.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
public class CachedRecordSerializationService implements SerializationService<CachePayloadHeader> {
	private final Kryo kryo;

	public CachedRecordSerializationService(@Nonnull Supplier<GlobalEntityIndex> globalEntityIndexAccessor) {
		this.kryo = KryoFactory.createKryo(
			new CachedRecordKryoConfigurer(globalEntityIndexAccessor)
		);
	}

	@Override
	public void serialize(@Nonnull CachePayloadHeader theObject, @Nonnull Output output) {
		this.kryo.writeClassAndObject(output, theObject);
	}

	@Override
	public CachePayloadHeader deserialize(@Nonnull Input input) {
		return (CachePayloadHeader) this.kryo.readClassAndObject(input);
	}

}
