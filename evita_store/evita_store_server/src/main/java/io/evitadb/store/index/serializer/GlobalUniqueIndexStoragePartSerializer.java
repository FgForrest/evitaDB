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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link GlobalUniqueIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class GlobalUniqueIndexStoragePartSerializer extends Serializer<GlobalUniqueIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, GlobalUniqueIndexStoragePart uniqueIndex) {
		final Long uniquePartId = uniqueIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		kryo.writeObject(output, uniqueIndex.getScope());
		output.writeVarInt(this.keyCompressor.getId(uniqueIndex.getAttributeKey()), true);
		final Class<?> plainType = uniqueIndex.getType().isArray() ? uniqueIndex.getType().getComponentType() : uniqueIndex.getType();
		kryo.writeClass(output, plainType);

		// the PAGED/SINGLE discriminator: a PAGED root keeps the value→tuple data in GlobalUniqueIndexLeafPagePart leaf
		// pages and carries only the page metadata here; a SINGLE root carries the whole value map inline as before. The
		// locale map is written INLINE on the root in both shapes.
		final boolean paged = uniqueIndex.isPaged();
		output.writeBoolean(paged);
		if (paged) {
			output.writeVarInt(uniqueIndex.getHighWaterPageSequence(), true);
			final int[] leafPageSequences = uniqueIndex.getLeafPageSequences();
			output.writeVarInt(leafPageSequences.length, true);
			for (final int leafPageSequence : leafPageSequences) {
				output.writeVarInt(leafPageSequence, true);
			}
		} else {
			final Serializable[] values = Objects.requireNonNull(
				uniqueIndex.getValues(), "A SINGLE global unique part must carry the inline value column!"
			);
			final long[] payloads = Objects.requireNonNull(
				uniqueIndex.getPayloads(), "A SINGLE global unique part must carry the inline payload column!"
			);
			output.writeVarInt(values.length, true);
			for (int i = 0; i < values.length; i++) {
				kryo.writeObject(output, values[i]);
				output.writeLong(payloads[i]);
			}
		}

		final Map<Integer, Locale> localeIndex = uniqueIndex.getLocaleIndex();
		output.writeVarInt(localeIndex.size(), true);
		for (Entry<Integer, Locale> entry : localeIndex.entrySet()) {
			output.writeVarInt(entry.getKey(), true);
			kryo.writeObject(output, entry.getValue());
		}
	}

	@Override
	public GlobalUniqueIndexStoragePart read(Kryo kryo, Input input, Class<? extends GlobalUniqueIndexStoragePart> type) {
		final long uniquePartId = input.readVarLong(true);
		final Scope scope = kryo.readObject(input, Scope.class);
		final AttributeKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = kryo.readClass(input).getType();

		final boolean paged = input.readBoolean();
		final int highWaterPageSequence;
		final int[] leafPageSequences;
		final Serializable[] values;
		final long[] payloads;
		if (paged) {
			highWaterPageSequence = input.readVarInt(true);
			final int leafPageCount = input.readVarInt(true);
			leafPageSequences = new int[leafPageCount];
			for (int i = 0; i < leafPageCount; i++) {
				leafPageSequences[i] = input.readVarInt(true);
			}
			values = null;
			payloads = null;
		} else {
			highWaterPageSequence = -1;
			leafPageSequences = ArrayUtils.EMPTY_INT_ARRAY;
			final int uniqueValueCount = input.readVarInt(true);
			values = new Serializable[uniqueValueCount];
			payloads = new long[uniqueValueCount];
			for (int i = 0; i < uniqueValueCount; i++) {
				values[i] = kryo.readObject(input, attributeType);
				payloads[i] = input.readLong();
			}
		}

		final int localeCount = input.readVarInt(true);
		final Map<Integer, Locale> localeIndex = createHashMap(localeCount);
		for (int i = 0; i < localeCount; i++) {
			localeIndex.put(
				input.readVarInt(true),
				kryo.readObject(input, Locale.class)
			);
		}

		return paged
			? GlobalUniqueIndexStoragePart.paged(
				scope, attributeKey, attributeType, highWaterPageSequence, leafPageSequences, localeIndex, uniquePartId
			)
			: new GlobalUniqueIndexStoragePart(
				scope, attributeKey, attributeType, values, payloads, localeIndex, uniquePartId
			);
	}

}
