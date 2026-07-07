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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.store.index.serializer.FilterIndexPayloadSerializer.FilterIndexPayload;
import io.evitadb.store.index.serializer.HistogramIdentitySerializer.HistogramIdentity;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

/**
 * Kryo {@link Serializer} for the {@link HistogramIndexStoragePart} root. Serializes the bucketed filter data — inline
 * for a `SINGLE`-shaped axis, or the page-stream metadata (high-water + ordered leaf-page list) for a `PAGED`-shaped
 * bucket / range axis — mirroring {@link FilterIndexStoragePartSerializer}. The cardinality data is NOT written here: it
 * lives in the sibling {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class HistogramIndexStoragePartSerializer extends Serializer<HistogramIndexStoragePart> {

	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, HistogramIndexStoragePart part) {
		HistogramIdentitySerializer.write(kryo, output, part, this.keyCompressor);
		kryo.writeClass(output, part.getValueType());

		// the shared filter payload: inline buckets, optional inline range, frozen scale and the two independent bucket /
		// range page-stream metadata axes. Identical to FilterIndexStoragePartSerializer — the histogram root embeds an
		// OwnerFilterIndex and persists its exact shape.
		FilterIndexPayloadSerializer.write(
			kryo, output,
			part.getHistogramPoints(), part.getRangeIndex(), part.getIndexedDecimalPlaces(),
			part.isPaged(), part.getHighWaterPageSequence(), part.getLeafPageSequences(),
			part.isRangePaged(), part.getRangeHighWaterPageSequence(), part.getRangeLeafPageSequences()
		);
	}

	@SuppressWarnings("unchecked")
	@Override
	public HistogramIndexStoragePart read(Kryo kryo, Input input, Class<? extends HistogramIndexStoragePart> type) {
		final HistogramIdentity identity = HistogramIdentitySerializer.read(kryo, input);
		final Class<? extends Serializable> valueType =
			(Class<? extends Serializable>) kryo.readClass(input).getType();

		// the shared filter payload tail (buckets + optional range + frozen scale + both page-stream metadata axes)
		final FilterIndexPayload payload = FilterIndexPayloadSerializer.read(kryo, input);
		final PagedStreamMetadata bucketMetadata = payload.bucketMetadata();
		final PagedStreamMetadata rangeMetadata = payload.rangeMetadata();

		return new HistogramIndexStoragePart(
			identity.entityIndexPrimaryKey(), identity.histogramName(), identity.locale(), valueType,
			payload.points(), payload.rangeIndex(), payload.indexedDecimalPlaces(),
			bucketMetadata.paged(), bucketMetadata.highWaterPageSequence(), bucketMetadata.leafPageSequences(),
			rangeMetadata.paged(), rangeMetadata.highWaterPageSequence(), rangeMetadata.leafPageSequences(),
			identity.uniquePartId()
		);
	}

}
