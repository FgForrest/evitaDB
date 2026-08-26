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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.index.serializer.FilterIndexPayloadSerializer.FilterIndexPayload;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
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
		output.writeVarInt(this.keyCompressor.getId(filterIndex.getAttributeIndexKey()), true);
		kryo.writeClass(output, filterIndex.getAttributeType());

		// the shared filter payload: inline buckets, optional inline range, frozen scale and the two independent bucket /
		// range page-stream metadata axes. Appended after the legacy fields so the SINGLE shape stays a superset of the
		// prior layout. Shared verbatim with HistogramIndexStoragePartSerializer, which embeds an OwnerFilterIndex.
		FilterIndexPayloadSerializer.write(
			kryo, output,
			filterIndex.getHistogramPoints(), filterIndex.getRangeIndex(), filterIndex.getIndexedDecimalPlaces(),
			filterIndex.isPaged(), filterIndex.getHighWaterPageSequence(), filterIndex.getLeafPageSequences(),
			filterIndex.isRangePaged(), filterIndex.getRangeHighWaterPageSequence(),
			filterIndex.getRangeLeafPageSequences()
		);

		// the value id section is APPENDED after the shared payload, so the pre-2026.3 record stays a byte-exact
		// prefix of this one and FilterIndexStoragePartSerializer_2026_2 can read it by stopping short of the tail.
		// It is written here rather than inside FilterIndexPayloadSerializer because that payload is shared verbatim
		// with HistogramIndexStoragePartSerializer, whose index never carries value ids.
		output.writeVarInt(filterIndex.getNextValueId(), true);
		final int[] inlineValueIds = filterIndex.getInlineValueIds();
		output.writeBoolean(inlineValueIds != null);
		if (inlineValueIds != null) {
			// one id per inline bucket - the bucket count already rode the shared payload, so no length is needed
			for (final int valueId : inlineValueIds) {
				output.writeVarInt(valueId, true);
			}
		}
	}

	@Override
	public FilterIndexStoragePart read(Kryo kryo, Input input, Class<? extends FilterIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		final Class<?> attributeType = kryo.readClass(input).getType();

		// the shared filter payload tail (buckets + optional range + frozen scale + both page-stream metadata axes); the
		// page-stream ids are recomputed at load from the sub-index identity, not read here
		final FilterIndexPayload payload = FilterIndexPayloadSerializer.read(kryo, input);
		final PagedStreamMetadata bucketMetadata = payload.bucketMetadata();
		final PagedStreamMetadata rangeMetadata = payload.rangeMetadata();

		final int nextValueId = input.readVarInt(true);
		final int[] inlineValueIds;
		if (input.readBoolean()) {
			inlineValueIds = new int[payload.points().length];
			for (int i = 0; i < inlineValueIds.length; i++) {
				inlineValueIds[i] = input.readVarInt(true);
			}
		} else {
			inlineValueIds = null;
		}

		return new FilterIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, payload.points(), payload.rangeIndex(),
			payload.indexedDecimalPlaces(),
			bucketMetadata.paged(), bucketMetadata.highWaterPageSequence(), bucketMetadata.leafPageSequences(),
			rangeMetadata.paged(), rangeMetadata.highWaterPageSequence(), rangeMetadata.leafPageSequences(),
			nextValueId, inlineValueIds, uniquePartId
		);
	}

}
