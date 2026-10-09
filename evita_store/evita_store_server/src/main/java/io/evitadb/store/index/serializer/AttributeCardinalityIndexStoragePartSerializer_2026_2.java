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


package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Reads an {@link AttributeCardinalityIndex} written before its counter keys were canonicalized.
 *
 * Parts of this vintage store each key's value with `kryo.writeObject` and therefore read it back as the
 * index's DECLARED {@link AttributeCardinalityIndex#getValueType()} — the value is not self-describing. That
 * was sound only while the stored key was always an instance of the declared type, which is exactly what
 * stopped being true when the counter began keying on the normalized value (a `BigDecimal`-typed index now
 * holds a scaled `Integer`, an `OffsetDateTime`-typed one an `Instant`). The current serializer therefore
 * writes the concrete class alongside each value, mirroring `HistogramCardinalityStoragePartSerializer`, and
 * this reader survives only to parse what the older writer left behind.
 *
 * The keys it returns are RAW, un-normalized values. They are re-keyed by the storage-protocol 6 → 7
 * migration, which owns the attribute schema and therefore the scale the normalizer needs; this reader
 * deliberately does not attempt it, having no access to either.
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.3", forRemoval = true)
@RequiredArgsConstructor
public class AttributeCardinalityIndexStoragePartSerializer_2026_2 extends Serializer<AttributeCardinalityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, AttributeCardinalityIndexStoragePart storagePart) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public AttributeCardinalityIndexStoragePart read(Kryo kryo, Input input, Class<? extends AttributeCardinalityIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		@SuppressWarnings("unchecked") final Class<? extends Serializable> valueType = kryo.readClass(input).getType();
		final int cardinalityCount = input.readVarInt(true);
		final Map<AttributeCardinalityKey, Integer> cardinalities = CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value = kryo.readObject(input, valueType);
			final int recordId = input.readVarInt(false);
			final int cardinality = input.readVarInt(true);
			cardinalities.put(new AttributeCardinalityKey(recordId, value), cardinality);
		}
		final AttributeCardinalityIndex cardinalityIndex = new AttributeCardinalityIndex(valueType, cardinalities);
		return new AttributeCardinalityIndexStoragePart(entityIndexPrimaryKey, attributeKey, cardinalityIndex, uniquePartId);
	}

}
