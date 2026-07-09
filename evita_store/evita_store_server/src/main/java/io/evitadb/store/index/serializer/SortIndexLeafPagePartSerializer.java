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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes a {@link SortIndexLeafPagePart} — one leaf page of a granular
 * OWNER-mode sort index value tree — from/to binary format. The `(streamId, pageSequence)` frame is owned by
 * {@link AbstractLeafPagePartSerializer}; this payload adds the comparator-base length and the leaf's buckets.
 *
 * Unlike {@link FilterIndexLeafPagePartSerializer}, the bucket VALUE is NOT written self-describingly via
 * {@link Kryo#writeClassAndObject}: a compound owner stores a {@code ComparableArray}, which is registered NOWHERE in
 * Kryo, so {@code writeClassAndObject} would garble it. The value is instead unwrapped component-by-component via the same
 * {@link SortIndexStoragePartSerializer#writeComparableValue} / {@link SortIndexStoragePartSerializer#readComparableValue}
 * convention the monolithic root serializer uses, driven by the page's `comparatorBaseLength` (Kryo serializers are
 * stateless, so the length must travel with the page).
 *
 * A leading discriminator byte per bucket picks the record-set wire shape, mirroring
 * {@link BucketLeafPagePartSerializer}: a compact single-record {@link ValueToRecordPrimitive} writes its record id as
 * a bare varint (no bitmap framing); a multi-record {@link ValueToRecordBitmap} writes its record-set exactly as
 * {@link ValueToRecordBitmapSerializer} writes it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SortIndexLeafPagePartSerializer extends AbstractLeafPagePartSerializer<SortIndexLeafPagePart> {

	/**
	 * Discriminator byte marking a wire-encoded {@link ValueToRecordPrimitive} bucket.
	 */
	private static final byte BUCKET_KIND_PRIMITIVE = 0;
	/**
	 * Discriminator byte marking a wire-encoded {@link ValueToRecordBitmap} bucket.
	 */
	private static final byte BUCKET_KIND_BITMAP = 1;

	@Override
	protected int streamId(@Nonnull SortIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull SortIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull SortIndexLeafPagePart page) {
		final int comparatorBaseLength = page.getComparatorBaseLength();
		output.writeVarInt(comparatorBaseLength, true);

		final ValueToRecord[] buckets = page.getBuckets();
		output.writeVarInt(buckets.length, true);
		for (final ValueToRecord bucket : buckets) {
			if (bucket instanceof final ValueToRecordPrimitive primitive) {
				output.writeByte(BUCKET_KIND_PRIMITIVE);
				// unwrap the (possibly compound) sort value component-by-component, then write the bare record id
				SortIndexStoragePartSerializer.writeComparableValue(kryo, output, primitive.getValue(), comparatorBaseLength);
				output.writeVarInt(primitive.getRecordId(), true);
			} else {
				output.writeByte(BUCKET_KIND_BITMAP);
				final ValueToRecordBitmap bitmapBucket = (ValueToRecordBitmap) bucket;
				SortIndexStoragePartSerializer.writeComparableValue(kryo, output, bitmapBucket.getValue(), comparatorBaseLength);
				kryo.writeObject(output, bitmapBucket.getRecordIds());
			}
		}
	}

	@Nonnull
	@Override
	protected SortIndexLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final int comparatorBaseLength = input.readVarInt(true);

		final int bucketCount = input.readVarInt(true);
		final ValueToRecord[] buckets = new ValueToRecord[bucketCount];
		for (int i = 0; i < bucketCount; i++) {
			final byte bucketKind = input.readByte();
			final Serializable value = SortIndexStoragePartSerializer.readComparableValue(kryo, input, comparatorBaseLength);
			if (bucketKind == BUCKET_KIND_PRIMITIVE) {
				final int recordId = input.readVarInt(true);
				buckets[i] = new ValueToRecordPrimitive(value, recordId);
			} else {
				final TransactionalBitmap recordIds = kryo.readObject(input, TransactionalBitmap.class);
				buckets[i] = new ValueToRecordBitmap(value, recordIds);
			}
		}

		return new SortIndexLeafPagePart(
			streamId, pageSequence, buckets, comparatorBaseLength,
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
