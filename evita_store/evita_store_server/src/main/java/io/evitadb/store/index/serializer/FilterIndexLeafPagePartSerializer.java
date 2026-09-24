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
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes a {@link FilterIndexLeafPagePart} — one leaf page of a granular
 * FilterIndex bucket tree — from/to binary format. The `(streamId, pageSequence)` pair fully determines the
 * storage-part primary key (via `join`), so the key is recomputed on read rather than stored; only the identifying
 * pair and the leaf's buckets are written. The bucket-page frame is defined once in {@link BucketLeafPagePartSerializer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FilterIndexLeafPagePartSerializer extends BucketLeafPagePartSerializer<FilterIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull FilterIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull FilterIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Nonnull
	@Override
	protected ValueToRecord[] buckets(@Nonnull FilterIndexLeafPagePart page) {
		return page.getBuckets();
	}

	@Nonnull
	@Override
	protected FilterIndexLeafPagePart create(
		int streamId, int pageSequence, @Nonnull ValueToRecord[] buckets
	) {
		return new FilterIndexLeafPagePart(
			streamId, pageSequence, buckets, null, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

	/**
	 * Writes the shared bucket-page frame, then APPENDS the optional value id column. Appending is what keeps the
	 * pre-2026.3 payload a byte-exact prefix of this one, which is the whole reason
	 * {@link FilterIndexLeafPagePartSerializer_2026_2} can read an old record by simply stopping short of the tail.
	 *
	 * The ids are written as plain varints with no length of their own: there is exactly one per bucket, and the
	 * bucket count has already been written by the frame.
	 */
	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull FilterIndexLeafPagePart page
	) {
		super.writePayload(kryo, output, page);
		final int[] valueIds = page.getValueIds();
		output.writeBoolean(valueIds != null);
		if (valueIds != null) {
			for (final int valueId : valueIds) {
				output.writeVarInt(valueId, true);
			}
		}
	}

	/**
	 * Reads the shared bucket-page frame through the base class, then the appended value id column. The base class
	 * builds the page before the tail has been read, so an id-carrying page is rebuilt once with its column attached —
	 * one extra allocation per loaded page, which is the load path and not a hot one.
	 */
	@Nonnull
	@Override
	protected FilterIndexLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final FilterIndexLeafPagePart page = super.readPayload(kryo, input, streamId, pageSequence);
		if (!input.readBoolean()) {
			return page;
		}
		final ValueToRecord[] buckets = page.getBuckets();
		final int[] valueIds = new int[buckets.length];
		for (int i = 0; i < valueIds.length; i++) {
			valueIds[i] = input.readVarInt(true);
		}
		return new FilterIndexLeafPagePart(
			streamId, pageSequence, buckets, valueIds,
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
