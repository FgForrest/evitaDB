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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValuePage;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextReferenceValueLeafPagePart;

import javax.annotation.Nonnull;

/** Explicit wire format for the closed string/integer/packed-long and scalar/bucket child payloads. */
public final class FulltextReferenceValueLeafPagePartSerializer
	extends AbstractLeafPagePartSerializer<FulltextReferenceValueLeafPagePart> {
	/** Creates the store-registered serializer. */
	public FulltextReferenceValueLeafPagePartSerializer() { }

	@Override
	protected int streamId(@Nonnull FulltextReferenceValueLeafPagePart part) {
		return part.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull FulltextReferenceValueLeafPagePart part) {
		return part.getPageSequence();
	}

	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull FulltextReferenceValueLeafPagePart part
	) {
		final ValuePage page = part.getPage();
		output.writeVarInt(part.getFieldId(), true);
		output.writeString(page.kind().name());
		output.writeVarInt(page.keys().length, true);
		for (int i = 0; i < page.keys().length; i++) {
			switch (page.kind()) {
				case RAW_VALUE_IDS, TERM_VALUE_IDS -> output.writeString((String) page.keys()[i]);
				case VALUE_TARGETS, TARGET_VALUES, TARGET_ROW_COUNTS, ENROLLED_OWNER_BLOCKS ->
					output.writeVarInt((Integer) page.keys()[i], true);
				case VALUE_ROW_COUNTS, OWNER_FORMS, OWNER_SETS -> output.writeLong((Long) page.keys()[i]);
			}
			if (page.kind().scalar()) {
				output.writeVarLong(page.scalars()[i], true);
			} else {
				final Bitmap records = page.records()[i];
				output.writeBoolean(records.size() == 1);
				if (records.size() == 1) {
					output.writeVarInt(records.getFirst(), true);
				} else {
					kryo.writeObject(output, new TransactionalBitmap(records));
				}
			}
		}
	}

	@Nonnull
	@Override
	protected FulltextReferenceValueLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final int fieldId = input.readVarInt(true);
		final ChildKind kind = ChildKind.valueOf(input.readString());
		final int count = input.readVarInt(true);
		final Object[] keys = new Object[count];
		final long[] scalars = new long[kind.scalar() ? count : 0];
		final Bitmap[] records = new Bitmap[kind.scalar() ? 0 : count];
		for (int i = 0; i < count; i++) {
			keys[i] = switch (kind) {
				case RAW_VALUE_IDS, TERM_VALUE_IDS -> input.readString();
				case VALUE_TARGETS, TARGET_VALUES, TARGET_ROW_COUNTS, ENROLLED_OWNER_BLOCKS -> input.readVarInt(true);
				case VALUE_ROW_COUNTS, OWNER_FORMS, OWNER_SETS -> input.readLong();
			};
			if (kind.scalar()) {
				scalars[i] = input.readVarLong(true);
			} else {
				records[i] = input.readBoolean() ? new BaseBitmap(input.readVarInt(true)) :
					kryo.readObject(input, TransactionalBitmap.class);
			}
		}
		return new FulltextReferenceValueLeafPagePart(streamId, fieldId,
			new ValuePage(pageSequence, kind, keys, scalars, records));
	}
}
