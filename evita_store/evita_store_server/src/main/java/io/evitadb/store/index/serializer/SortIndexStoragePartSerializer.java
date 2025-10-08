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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
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
		final Long uniquePartId = sortIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(sortIndex.getAttributeIndexKey()), true);

		final ComparatorSource[] comparatorBase = sortIndex.getComparatorBase();
		output.writeVarInt(comparatorBase.length, true);
		for (final ComparatorSource comparatorSource : comparatorBase) {
			kryo.writeClass(output, comparatorSource.type());
			kryo.writeObject(output, comparatorSource.orderDirection());
			kryo.writeObject(output, comparatorSource.orderBehaviour());
		}

		final int[] sortedRecords = sortIndex.getSortedRecords();
		output.writeVarInt(sortedRecords.length, true);
		output.writeInts(sortedRecords, 0, sortedRecords.length);

		final Serializable[] sortedRecordValues = sortIndex.getSortedRecordsValues();
		output.writeVarInt(sortedRecordValues.length, true);

		if (comparatorBase.length == 1) {
			for (Serializable sortedRecordValue : sortedRecordValues) {
				kryo.writeObject(output, sortedRecordValue);
			}
		} else {
			for (Serializable sortedRecordValue : sortedRecordValues) {
				final ComparableArray comparableArray = (ComparableArray) sortedRecordValue;
				for (int i = 0; i < comparatorBase.length; i++) {
					kryo.writeObjectOrNull(output, comparableArray.array()[i], comparatorBase[i].type());
				}
			}
		}

		final Map<Serializable, Integer> cardinalities = sortIndex.getValueCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (Entry<Serializable, Integer> entry : cardinalities.entrySet()) {
			if (comparatorBase.length == 1) {
				kryo.writeObject(output, entry.getKey());
			} else {
				final ComparableArray comparableArray = (ComparableArray) entry.getKey();
				for (int i = 0; i < comparatorBase.length; i++) {
					kryo.writeObjectOrNull(output, comparableArray.array()[i], comparatorBase[i].type());
				}
			}
			output.writeVarInt(entry.getValue(), true);
		}
	}

	@Override
	public SortIndexStoragePart read(Kryo kryo, Input input, Class<? extends SortIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final int comparatorBaseLength = input.readVarInt(true);
		final ComparatorSource[] comparatorBase = new ComparatorSource[comparatorBaseLength];
		for (int i = 0; i < comparatorBaseLength; i++) {
			comparatorBase[i] = new ComparatorSource(
				kryo.readClass(input).getType(),
				kryo.readObject(input, OrderDirection.class),
				kryo.readObject(input, OrderBehaviour.class)
			);
		}

		final int sortedRecordCount = input.readVarInt(true);
		final int[] sortedRecords = input.readInts(sortedRecordCount);

		final int sortedValuesCount = input.readVarInt(true);
		final Serializable[] sortedRecordValues = new Serializable[sortedValuesCount];
		for (int i = 0; i < sortedValuesCount; i++) {
			if (comparatorBaseLength == 1) {
				//noinspection unchecked
				sortedRecordValues[i] = (Serializable) kryo.readObject(input, comparatorBase[0].type());
			} else {
				final Serializable[] comparableArray = new Serializable[comparatorBaseLength];
				for (int j = 0; j < comparatorBase.length; j++) {
					//noinspection unchecked
					comparableArray[j] = (Serializable) kryo.readObjectOrNull(input, comparatorBase[j].type());
				}
				sortedRecordValues[i] = new ComparableArray(comparableArray);
			}
		}

		final int cardinalityCount = input.readVarInt(true);
		final Map<Serializable, Integer> cardinalities = createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value;
			if (comparatorBaseLength == 1) {
				//noinspection unchecked
				value = (Serializable) kryo.readObject(input, comparatorBase[0].type());
			} else {
				final Serializable[] comparableArray = new Serializable[comparatorBaseLength];
				for (int j = 0; j < comparatorBase.length; j++) {
					//noinspection unchecked
					comparableArray[j] = (Serializable) kryo.readObjectOrNull(input, comparatorBase[j].type());
				}
				value = new ComparableArray(comparableArray);
			}
			cardinalities.put(
				value, input.readVarInt(true)
			);
		}

		return new SortIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, comparatorBase, sortedRecords, sortedRecordValues, cardinalities, uniquePartId
		);
	}

}
