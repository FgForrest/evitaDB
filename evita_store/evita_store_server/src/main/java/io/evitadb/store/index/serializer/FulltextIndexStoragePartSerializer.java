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
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextFieldKey.FieldKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart.FieldEntry;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import lombok.RequiredArgsConstructor;

import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes {@link FulltextIndexStoragePart} - the root record of a
 * fulltext index - from/to binary format: its identity, the field registry with its analysis identities and pivots in
 * field-id order and the dictionary's page-stream metadata. The dictionary is always paged, so the metadata is
 * written without the paged flag.
 *
 * A field is written as its kind by name - as the stream kind of a {@link FulltextLeafStreamKeySerializer} key is -
 * the reference name (`null` for every kind but a reference attribute), the name, analyzer name, index fingerprint,
 * words-phase identity, pivot, text expression
 * (`null` for a field whose values are the text), the retired flag and the keys of its length blocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class FulltextIndexStoragePartSerializer extends Serializer<FulltextIndexStoragePart> {

	/**
	 * The compressor the root's locale key is registered in, to compute the primary key on write.
	 */
	private final KeyCompressor keyCompressor;

	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull FulltextIndexStoragePart part) {
		output.writeInt(part.getEntityIndexPrimaryKey());
		output.writeVarLong(part.computeUniquePartIdAndSet(this.keyCompressor), true);
		kryo.writeObject(output, part.getLocale());

		final FieldEntry[] fields = part.getFields();
		output.writeVarInt(fields.length, true);
		for (final FieldEntry field : fields) {
			final FulltextFieldKey key = field.key();
			output.writeString(key.kind().name());
			output.writeString(key.referenceName());
			output.writeString(key.name());
			output.writeString(field.analyzerName());
			output.writeLong(field.indexFingerprint());
			output.writeLong(field.wordPhaseId());
			output.writeDouble(field.lengthPivot());
			output.writeString(field.textExpression());
			output.writeBoolean(field.retired());
			final int[] lengthBlocks = field.lengthBlocks();
			output.writeVarInt(lengthBlocks.length, true);
			for (final int lengthBlock : lengthBlocks) {
				output.writeVarInt(lengthBlock, true);
			}
		}

		PagedStreamMetadataSerializer.writeBody(
			output, part.getDictionaryHighWaterPageSequence(), part.getDictionaryPageSequences()
		);
	}

	@Nonnull
	@Override
	public FulltextIndexStoragePart read(
		@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends FulltextIndexStoragePart> type
	) {
		final int entityIndexPrimaryKey = input.readInt();
		final long storagePartPK = input.readVarLong(true);
		final Locale locale = kryo.readObject(input, Locale.class);

		final FieldEntry[] fields = new FieldEntry[input.readVarInt(true)];
		for (int i = 0; i < fields.length; i++) {
			final FieldKind kind = FieldKind.valueOf(input.readString());
			final String referenceName = input.readString();
			final FulltextFieldKey key = new FulltextFieldKey(kind, referenceName, input.readString());
			final String analyzerName = input.readString();
			final long indexFingerprint = input.readLong();
			final long wordPhaseId = input.readLong();
			final double lengthPivot = input.readDouble();
			final String textExpression = input.readString();
			final boolean retired = input.readBoolean();
			final int[] lengthBlocks = new int[input.readVarInt(true)];
			for (int j = 0; j < lengthBlocks.length; j++) {
				lengthBlocks[j] = input.readVarInt(true);
			}
			fields[i] = new FieldEntry(key, analyzerName, indexFingerprint, wordPhaseId, lengthPivot,
				textExpression, retired, lengthBlocks);
		}

		final PagedStreamMetadata dictionary = PagedStreamMetadataSerializer.readBody(input);
		return new FulltextIndexStoragePart(
			entityIndexPrimaryKey, locale, fields,
			dictionary.highWaterPageSequence(), dictionary.leafPageSequences(), storagePartPK
		);
	}

}
