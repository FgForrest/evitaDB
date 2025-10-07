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
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link SortIndex} from/to binary format.
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2025.5", forRemoval = true)
@RequiredArgsConstructor
public class SortIndexStoragePartSerializer_2025_5 extends Serializer<SortIndexStoragePart>
	implements AttributeKeyToAttributeKeyIndexBridge {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, SortIndexStoragePart sortIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public SortIndexStoragePart read(Kryo kryo, Input input, Class<? extends SortIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = getAttributeIndexKey(input, this.keyCompressor);

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
			entityIndexPrimaryKey, attributeKey, comparatorBase, sortedRecords, sortedRecordValues, cardinalities, uniquePartId
		);
	}

}
