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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link SortIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class SortIndexStoragePartSerializer extends Serializer<SortIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, SortIndexStoragePart sortIndex) {
		output.writeInt(sortIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = sortIndex.getUniquePartId();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(keyCompressor.getId(sortIndex.getAttributeKey()), true);
		kryo.writeClass(output, sortIndex.getType());

		final int[] sortedRecords = sortIndex.getSortedRecords();
		output.writeVarInt(sortedRecords.length, true);
		output.writeInts(sortedRecords, 0, sortedRecords.length);

		final Comparable<?>[] sortedRecordValues = sortIndex.getSortedRecordsValues();
		output.writeVarInt(sortedRecordValues.length, true);
		for (Comparable<?> sortedRecordValue : sortedRecordValues) {
			kryo.writeObject(output, sortedRecordValue);
		}

		final Map<? extends Comparable<?>, Integer> cardinalities = sortIndex.getValueCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (Entry<? extends Comparable<?>, Integer> entry : cardinalities.entrySet()) {
			kryo.writeObject(output, entry.getKey());
			output.writeVarInt(entry.getValue(), true);
		}
	}

	@Override
	public SortIndexStoragePart read(Kryo kryo, Input input, Class<? extends SortIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeKey attributeKey = keyCompressor.getKeyForId(input.readVarInt(true));

		@SuppressWarnings("unchecked") final Class<? extends Comparable<?>> attributeType = kryo.readClass(input).getType();

		final int sortedRecordCount = input.readVarInt(true);
		final int[] sortedRecords = input.readInts(sortedRecordCount);

		final int sortedValuesCount = input.readVarInt(true);
		final Comparable<?>[] sortedRecordValues = new Comparable[sortedValuesCount];
		for (int i = 0; i < sortedValuesCount; i++) {
			sortedRecordValues[i] = kryo.readObject(input, attributeType);
		}

		final int cardinalityCount = input.readVarInt(true);
		final Map<Comparable<?>, Integer> cardinalities = createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Comparable<?> value = kryo.readObject(input, attributeType);
			cardinalities.put(
				value, input.readVarInt(true)
			);
		}

		return new SortIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, sortedRecords, sortedRecordValues, cardinalities, uniquePartId
		);
	}

}
