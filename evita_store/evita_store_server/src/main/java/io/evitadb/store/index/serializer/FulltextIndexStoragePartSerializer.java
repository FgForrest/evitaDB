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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart.FieldEntry;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

/**
 * This {@link Serializer} implementation reads/writes {@link FulltextIndexStoragePart} - the root record of a
 * fulltext index - from/to binary format: its identity, the analyzer and default pivot, the field registry in
 * field-id order and the dictionary's page-stream metadata. The dictionary is always paged, so the metadata is
 * written without the paged flag.
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
	public void write(Kryo kryo, Output output, FulltextIndexStoragePart part) {
		output.writeInt(part.getEntityIndexPrimaryKey());
		output.writeVarLong(part.computeUniquePartIdAndSet(this.keyCompressor), true);
		kryo.writeObject(output, part.getLocale());
		//TODO JNO change it at the end of #258
		output.writeString(part.getAnalyzerName());
		output.writeDouble(part.getDefaultLengthPivot());

		final FieldEntry[] fields = part.getFields();
		output.writeVarInt(fields.length, true);
		for (final FieldEntry field : fields) {
			output.writeString(field.name());
			//TODO JNO change it at the end of #258
			output.writeDouble(field.lengthPivot());
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

	@Override
	public FulltextIndexStoragePart read(Kryo kryo, Input input, Class<? extends FulltextIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long storagePartPK = input.readVarLong(true);
		final Locale locale = kryo.readObject(input, Locale.class);
		final String analyzerName = input.readString();
		final double defaultLengthPivot = input.readDouble();

		final FieldEntry[] fields = new FieldEntry[input.readVarInt(true)];
		for (int i = 0; i < fields.length; i++) {
			final String name = input.readString();
			final double lengthPivot = input.readDouble();
			final int[] lengthBlocks = new int[input.readVarInt(true)];
			for (int j = 0; j < lengthBlocks.length; j++) {
				lengthBlocks[j] = input.readVarInt(true);
			}
			fields[i] = new FieldEntry(name, lengthPivot, lengthBlocks);
		}

		final PagedStreamMetadata dictionary = PagedStreamMetadataSerializer.readBody(input);
		return new FulltextIndexStoragePart(
			entityIndexPrimaryKey, locale, analyzerName, defaultLengthPivot, fields,
			dictionary.highWaterPageSequence(), dictionary.leafPageSequences(), storagePartPK
		);
	}

}
