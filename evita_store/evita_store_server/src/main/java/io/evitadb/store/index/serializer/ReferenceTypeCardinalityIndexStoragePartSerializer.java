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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * This {@link Serializer} implementation reads/writes the root {@link ReferenceTypeCardinalityIndexStoragePart} from/to
 * binary format. A `paged` discriminator selects between the inline SINGLE shape (the composed-key → count columns ride
 * on the root) and the PAGED shape (only the page-stream metadata rides on the root; the columns live in separate
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart} records).
 * The `referencedEntityPrimaryKey → reduced-index-PK bitmap` companion map is written inline in BOTH shapes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceTypeCardinalityIndexStoragePartSerializer extends Serializer<ReferenceTypeCardinalityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, ReferenceTypeCardinalityIndexStoragePart storagePart) {
		output.writeInt(storagePart.getEntityIndexPrimaryKey());
		final long uniquePartId = ofNullable(storagePart.getStoragePartPK()).orElseGet(() -> storagePart.computeUniquePartIdAndSet(this.keyCompressor));
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(new ReferenceNameKey(storagePart.getReferenceName())), true);

		final boolean paged = storagePart.isPaged();
		output.writeBoolean(paged);
		if (paged) {
			PagedStreamMetadataSerializer.writeBody(
				output, storagePart.getHighWaterPageSequence(),
				Objects.requireNonNull(storagePart.getLeafPageSequences())
			);
		} else {
			final long[] keys = Objects.requireNonNull(storagePart.getKeys());
			final long[] payloads = Objects.requireNonNull(storagePart.getPayloads());
			output.writeVarInt(keys.length, true);
			for (int i = 0; i < keys.length; i++) {
				output.writeVarLong(keys[i], false);
				output.writeVarInt((int) payloads[i], false);
			}
		}

		final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex = storagePart.getReferencedPrimaryKeysIndex();
		output.writeVarInt(referencedPrimaryKeysIndex.size(), true);
		for (Entry<Integer, TransactionalBitmap> entry : referencedPrimaryKeysIndex.entrySet()) {
			output.writeVarInt(entry.getKey(), true);
			kryo.writeObject(output, entry.getValue());
		}
	}

	@Override
	public ReferenceTypeCardinalityIndexStoragePart read(Kryo kryo, Input input, Class<? extends ReferenceTypeCardinalityIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final ReferenceNameKey referenceNameKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final boolean paged = input.readBoolean();
		int highWaterPageSequence = 0;
		int[] leafPageSequences = null;
		long[] keys = null;
		long[] payloads = null;
		if (paged) {
			final PagedStreamMetadata metadata = PagedStreamMetadataSerializer.readBody(input);
			highWaterPageSequence = metadata.highWaterPageSequence();
			leafPageSequences = metadata.leafPageSequences();
		} else {
			final int cardinalityCount = input.readVarInt(true);
			keys = new long[cardinalityCount];
			payloads = new long[cardinalityCount];
			for (int i = 0; i < cardinalityCount; i++) {
				keys[i] = input.readVarLong(false);
				payloads[i] = input.readVarInt(false);
			}
		}

		final int referencedPrimaryKeysIndexSize = input.readVarInt(true);
		final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex = CollectionUtils.createHashMap(referencedPrimaryKeysIndexSize);
		for (int i = 0; i < referencedPrimaryKeysIndexSize; i++) {
			final int key = input.readVarInt(true);
			final TransactionalBitmap bitmap = kryo.readObject(input, TransactionalBitmap.class);
			referencedPrimaryKeysIndex.put(key, bitmap);
		}

		return paged
			? ReferenceTypeCardinalityIndexStoragePart.paged(
				entityIndexPrimaryKey, referenceNameKey.referenceName(),
				highWaterPageSequence, leafPageSequences, referencedPrimaryKeysIndex, uniquePartId
			)
			: new ReferenceTypeCardinalityIndexStoragePart(
				entityIndexPrimaryKey, referenceNameKey.referenceName(),
				keys, payloads, referencedPrimaryKeysIndex, uniquePartId
			);
	}

}
