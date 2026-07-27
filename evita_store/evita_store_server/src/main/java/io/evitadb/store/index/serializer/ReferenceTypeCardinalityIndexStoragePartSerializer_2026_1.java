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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Backward-compatible {@link Serializer} that reads the released 2025.x–2026.1 binary format of
 * {@link ReferenceTypeCardinalityIndexStoragePart} (every catalog up to and including 2026.1 shares its
 * `serialVersionUID`).
 *
 * The released format always wrote the composed-key → cardinality count map INLINE on the root, as `count` followed by
 * `(writeVarLong key, writeVarInt value)*`, then the `referencedEntityPrimaryKey → reduced-index-PK bitmap` companion
 * map. The current format prefixes the value section with a `PAGED`/`SINGLE` discriminator boolean (so large indexes can
 * page their cardinality columns into separate leaf-page records). This serializer reads the legacy inline layout into
 * the current `(keys, payloads)` columns so old catalogs keep loading; the loaded part is always a `SINGLE` root (the
 * released format never paged).
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class ReferenceTypeCardinalityIndexStoragePartSerializer_2026_1 extends Serializer<ReferenceTypeCardinalityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, ReferenceTypeCardinalityIndexStoragePart storagePart) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public ReferenceTypeCardinalityIndexStoragePart read(Kryo kryo, Input input, Class<? extends ReferenceTypeCardinalityIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final ReferenceNameKey referenceNameKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		// the released format wrote the cardinality map inline (no PAGED/SINGLE discriminator) as
		// (varLong key, varInt value)* — read it straight into the current SINGLE (keys, payloads) columns
		final int cardinalityCount = input.readVarInt(true);
		final long[] keys = new long[cardinalityCount];
		final long[] payloads = new long[cardinalityCount];
		for (int i = 0; i < cardinalityCount; i++) {
			keys[i] = input.readVarLong(false);
			payloads[i] = input.readVarInt(false);
		}

		final int referencedPrimaryKeysIndexSize = input.readVarInt(true);
		final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex = createHashMap(referencedPrimaryKeysIndexSize);
		for (int i = 0; i < referencedPrimaryKeysIndexSize; i++) {
			final int key = input.readVarInt(true);
			final TransactionalBitmap bitmap = kryo.readObject(input, TransactionalBitmap.class);
			referencedPrimaryKeysIndex.put(key, bitmap);
		}

		return new ReferenceTypeCardinalityIndexStoragePart(
			entityIndexPrimaryKey, referenceNameKey.referenceName(),
			keys, payloads, referencedPrimaryKeysIndex, uniquePartId
		);
	}

}
