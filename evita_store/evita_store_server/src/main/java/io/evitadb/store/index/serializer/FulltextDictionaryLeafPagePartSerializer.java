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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPagePart;

import javax.annotation.Nonnull;

/**
 * This serializer reads/writes {@link FulltextDictionaryLeafPagePart} - one leaf page of a fulltext index's term
 * dictionary. After the `(streamId, pageSequence)` frame come the buckets in the shared bucket encoding of
 * {@link BucketLeafPagePartSerializer}, then the impacts of every bucket's postings, one byte each.
 *
 * The impacts carry no length of their own: a bucket's impact count is its posting count, which the reader already
 * knows from the bucket.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextDictionaryLeafPagePartSerializer
	extends AbstractLeafPagePartSerializer<FulltextDictionaryLeafPagePart> {

	@Override
	protected int streamId(@Nonnull FulltextDictionaryLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull FulltextDictionaryLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull FulltextDictionaryLeafPagePart page
	) {
		BucketLeafPagePartSerializer.writeBuckets(kryo, output, page.getBuckets());
		for (final byte[] bucketImpacts : page.getImpacts()) {
			output.writeBytes(bucketImpacts);
		}
	}

	@Nonnull
	@Override
	protected FulltextDictionaryLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final ValueToRecord[] buckets = BucketLeafPagePartSerializer.readBuckets(kryo, input);
		final byte[][] impacts = new byte[buckets.length][];
		for (int i = 0; i < buckets.length; i++) {
			impacts[i] = input.readBytes(buckets[i].getRecordIds().size());
		}
		return new FulltextDictionaryLeafPagePart(
			streamId, pageSequence, buckets, impacts, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
