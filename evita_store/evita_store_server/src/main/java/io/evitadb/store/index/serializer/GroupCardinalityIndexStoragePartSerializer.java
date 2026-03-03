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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GroupCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Map.Entry;

import static java.util.Optional.ofNullable;

/**
 * This {@link Serializer} implementation reads/writes {@link GroupCardinalityIndexStoragePart} from/to binary format.
 *
 * Serialization format:
 * 1. Entity index PK (int)
 * 2. Unique part ID (varLong) — encodes entity index PK and compressed reference name ID via
 *    {@link NumberUtils#join(int, int)}, so the reference name is derived from it on read
 * 3. PK cardinalities count (varInt), then for each: entityPK (varInt) + count (varInt)
 * 4. Referenced PKs index count (varInt), then for each: referencedPK (varInt) + TransactionalBitmap (Kryo object)
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class GroupCardinalityIndexStoragePartSerializer extends Serializer<GroupCardinalityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, GroupCardinalityIndexStoragePart storagePart) {
		output.writeInt(storagePart.getEntityIndexPrimaryKey());
		final long uniquePartId = ofNullable(storagePart.getStoragePartPK())
			.orElseGet(() -> storagePart.computeUniquePartIdAndSet(this.keyCompressor));
		output.writeVarLong(uniquePartId, true);

		// write PK cardinalities
		final Map<Integer, Integer> pkCardinalities = storagePart.getPkCardinalities();
		output.writeVarInt(pkCardinalities.size(), true);
		for (Entry<Integer, Integer> entry : pkCardinalities.entrySet()) {
			output.writeVarInt(entry.getKey(), false);
			output.writeVarInt(entry.getValue(), true);
		}

		// write referenced primary keys index
		final Map<Integer, TransactionalBitmap> referencedPKsIndex = storagePart.getReferencedPrimaryKeysIndex();
		output.writeVarInt(referencedPKsIndex.size(), true);
		for (Entry<Integer, TransactionalBitmap> entry : referencedPKsIndex.entrySet()) {
			output.writeVarInt(entry.getKey(), false);
			kryo.writeObject(output, entry.getValue());
		}
	}

	@Override
	public GroupCardinalityIndexStoragePart read(
		Kryo kryo, Input input, Class<? extends GroupCardinalityIndexStoragePart> type
	) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		// derive compressed reference name ID from the uniquePartId (low 32 bits)
		final ReferenceNameKey referenceNameKey = this.keyCompressor.getKeyForId(
			NumberUtils.split(uniquePartId)[1]
		);

		// read PK cardinalities
		final int cardinalityCount = input.readVarInt(true);
		final Map<Integer, Integer> pkCardinalities = CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final int entityPK = input.readVarInt(false);
			final int count = input.readVarInt(true);
			pkCardinalities.put(entityPK, count);
		}

		// read referenced primary keys index
		final int referencedPKsSize = input.readVarInt(true);
		final Map<Integer, TransactionalBitmap> referencedPKsIndex = CollectionUtils.createHashMap(referencedPKsSize);
		for (int i = 0; i < referencedPKsSize; i++) {
			final int referencedPK = input.readVarInt(false);
			final TransactionalBitmap bitmap = kryo.readObject(input, TransactionalBitmap.class);
			referencedPKsIndex.put(referencedPK, bitmap);
		}

		return new GroupCardinalityIndexStoragePart(
			entityIndexPrimaryKey,
			referenceNameKey.referenceName(),
			pkCardinalities,
			referencedPKsIndex,
			uniquePartId
		);
	}

}
