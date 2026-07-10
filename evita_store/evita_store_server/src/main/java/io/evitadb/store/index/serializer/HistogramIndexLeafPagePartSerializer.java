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

import com.esotericsoftware.kryo.Serializer;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes a {@link HistogramIndexLeafPagePart} — one leaf page of a granular
 * histogram bucket tree — from/to binary format. The `(streamId, pageSequence)` pair fully determines the storage-part
 * primary key (via `pack`), so the key is recomputed on read rather than stored; only the identifying pair and the
 * leaf's buckets are written. The bucket-page frame is defined once in {@link BucketLeafPagePartSerializer}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramIndexLeafPagePartSerializer extends BucketLeafPagePartSerializer<HistogramIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull HistogramIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull HistogramIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Nonnull
	@Override
	protected ValueToRecord[] buckets(@Nonnull HistogramIndexLeafPagePart page) {
		return page.getBuckets();
	}

	@Nonnull
	@Override
	protected HistogramIndexLeafPagePart create(
		int streamId, int pageSequence, @Nonnull ValueToRecord[] buckets
	) {
		return new HistogramIndexLeafPagePart(
			streamId, pageSequence, buckets, HistogramIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
