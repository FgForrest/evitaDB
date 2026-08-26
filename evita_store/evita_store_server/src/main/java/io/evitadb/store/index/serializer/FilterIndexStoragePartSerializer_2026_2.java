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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.index.serializer.FilterIndexPayloadSerializer.FilterIndexPayload;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads a {@link FilterIndexStoragePart} from the binary format shipped by
 * release 2026.2 - the shape that predates the shared value tree's value ids. That format ends with the shared filter
 * payload (inline buckets, optional inline range, frozen scale, both page-stream axes) and carries neither the value
 * id high-water mark nor the inline id column.
 *
 * The substitution for the absent section is "no value ids at all", which is what the 13-argument
 * {@link FilterIndexStoragePart} constructor produces: an index written before value ids existed belongs to a shared
 * value tree that carried none, and the tree it is loaded into carries none until a consumer registers.
 *
 * This serializer only reads - writes always go through the current {@link FilterIndexStoragePartSerializer}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated kept for backward compatibility; can be removed once no catalog written before value ids were
 *             introduced is still in use.
 */
@Deprecated(since = "2026.3", forRemoval = true)
@RequiredArgsConstructor
public class FilterIndexStoragePartSerializer_2026_2 extends Serializer<FilterIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, FilterIndexStoragePart filterIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Override
	public FilterIndexStoragePart read(Kryo kryo, Input input, Class<? extends FilterIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		final Class<?> attributeType = kryo.readClass(input).getType();

		// the pre-value-id record ends with the shared filter payload - there is no id section to read
		final FilterIndexPayload payload = FilterIndexPayloadSerializer.read(kryo, input);
		final PagedStreamMetadata bucketMetadata = payload.bucketMetadata();
		final PagedStreamMetadata rangeMetadata = payload.rangeMetadata();

		return new FilterIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, payload.points(), payload.rangeIndex(),
			payload.indexedDecimalPlaces(),
			bucketMetadata.paged(), bucketMetadata.highWaterPageSequence(), bucketMetadata.leafPageSequences(),
			rangeMetadata.paged(), rangeMetadata.highWaterPageSequence(), rangeMetadata.leafPageSequences(),
			uniquePartId
		);
	}

}
