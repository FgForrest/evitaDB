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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link FilterIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class FilterIndexStoragePartSerializer extends Serializer<FilterIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, FilterIndexStoragePart filterIndex) {
		output.writeInt(filterIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = filterIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(keyCompressor.getId(filterIndex.getAttributeKey()), true);

		kryo.writeObject(output, filterIndex.getHistogram());
		final boolean rangeIndex = filterIndex.getRangeIndex() != null;
		output.writeBoolean(rangeIndex);
		if (rangeIndex) {
			kryo.writeObject(output, filterIndex.getRangeIndex());
		}
	}

	@Override
	public FilterIndexStoragePart read(Kryo kryo, Input input, Class<? extends FilterIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeKey attributeKey = keyCompressor.getKeyForId(input.readVarInt(true));

		final InvertedIndex<?> histogramIndex = kryo.readObject(input, InvertedIndex.class);
		final boolean hasRangeIndex = input.readBoolean();
		if (hasRangeIndex) {
			final RangeIndex intRangeIndex = kryo.readObject(input, RangeIndex.class);
			return new FilterIndexStoragePart(entityIndexPrimaryKey, attributeKey, histogramIndex, intRangeIndex, uniquePartId);
		} else {
			return new FilterIndexStoragePart(entityIndexPrimaryKey, attributeKey, histogramIndex, null, uniquePartId);
		}
	}

}
