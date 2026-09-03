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
 * It is also the reader for the last shape that persisted range thresholds at **second** granularity, so it marks
 * every part it produces with {@link FilterIndexStoragePart#isSecondGranularityRangeThresholds()}. The rescale itself
 * cannot happen here: a range index whose axis is `PAGED` keeps its thresholds in {@code RangeIndexLeafPagePart}
 * records this serializer never sees, and the rescale must be applied to the declared `DateTimeRange` attributes only
 * — both of which the load path knows and this reader does not. See {@code AttributeIndexLoader#loadRangeIndex}.
 *
 * This serializer only reads - writes always go through the current {@link FilterIndexStoragePartSerializer}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated kept for backward compatibility; can be removed once no catalog written before value ids were
 *             introduced is still in use.
 */
@Deprecated(since = "2026.2", forRemoval = true)
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

		final FilterIndexStoragePart part = new FilterIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, payload.points(), payload.rangeIndex(),
			payload.indexedDecimalPlaces(),
			bucketMetadata.paged(), bucketMetadata.highWaterPageSequence(), bucketMetadata.leafPageSequences(),
			rangeMetadata.paged(), rangeMetadata.highWaterPageSequence(), rangeMetadata.leafPageSequences(),
			uniquePartId
		);
		// every FilterIndexStoragePart format older than the millisecond change persisted its range thresholds as
		// epoch SECONDS; mark the provenance so AttributeIndexLoader can rescale the ones that belong to a
		// `DateTimeRange` attribute (a threshold is an untyped long shared with every NumberRange subtype, so the
		// declared attribute type - not this flag alone - decides)
		part.setSecondGranularityRangeThresholds(true);
		return part;
	}

}
