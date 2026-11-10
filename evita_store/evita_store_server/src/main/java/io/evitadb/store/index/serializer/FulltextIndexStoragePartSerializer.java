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
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildStream;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ValueManifest;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart.FieldEntry;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * This {@link Serializer} implementation reads/writes {@link FulltextIndexStoragePart} - the root record of a
 * fulltext index - from/to binary format, read back positionally in this order: its identity, the field registry with
 * its analysis identities and pivots in field-id order, the dictionary's page-stream metadata, the word-phase table -
 * its size, then each fingerprint with its short id in ascending fingerprint order - and the surface dictionary's
 * page-stream metadata. Both dictionaries are always paged, so their metadata is written without the paged flag.
 *
 * A field is written as its kind by name - as the stream kind of a {@link FulltextLeafStreamKeySerializer} key is -
 * the reference name (`null` for every kind but a reference attribute), the name, analyzer name, index fingerprint,
 * words-phase identity, pivot, text expression
 * (`null` for a field whose values are the text), the retired flag and the keys of its length blocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextIndexStoragePartSerializer extends Serializer<FulltextIndexStoragePart> {

	/**
	 * The compressor the root's locale key is registered in, to compute the primary key on write.
	 */
	private final KeyCompressor keyCompressor;

	/**
	 * Creates the serializer for the root and its compressed locale key.
	 *
	 * @param keyCompressor compressor assigning the persisted locale and stream identities
	 */
	public FulltextIndexStoragePartSerializer(@Nonnull KeyCompressor keyCompressor) {
		this.keyCompressor = keyCompressor;
	}

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
			final ValueManifest manifest = field.referenceValues();
			output.writeBoolean(manifest != null);
			if (manifest != null) {
				output.writeVarInt(manifest.nextValueId(), true);
				for (final ChildStream stream : manifest.streams()) {
					PagedStreamMetadataSerializer.writeBody(output, stream.highWater(), stream.sequences());
				}
			}
		}

		PagedStreamMetadataSerializer.writeBody(
			output, part.getDictionaryHighWaterPageSequence(), part.getDictionaryPageSequences()
		);
		final Map<Long, Integer> phases = new TreeMap<>(part.getWordPhaseIds());
		output.writeVarInt(phases.size(), true);
		for (final Map.Entry<Long, Integer> phase : phases.entrySet()) {
			output.writeLong(phase.getKey());
			output.writeVarInt(phase.getValue(), true);
		}
		PagedStreamMetadataSerializer.writeBody(output,
			part.getSurfaceFormHighWaterPageSequence(), part.getSurfaceFormPageSequences());
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
			ValueManifest referenceValues = null;
			if (input.readBoolean()) {
				final int nextValueId = input.readVarInt(true);
				final ChildStream[] streams = new ChildStream[ChildKind.values().length];
				for (int child = 0; child < streams.length; child++) {
					final PagedStreamMetadata stream = PagedStreamMetadataSerializer.readBody(input);
					streams[child] = new ChildStream(stream.highWaterPageSequence(), stream.leafPageSequences());
				}
				referenceValues = new ValueManifest(nextValueId, streams);
			}
			fields[i] = new FieldEntry(key, analyzerName, indexFingerprint, wordPhaseId, lengthPivot,
				textExpression, retired, lengthBlocks, referenceValues);
		}

		final PagedStreamMetadata dictionary = PagedStreamMetadataSerializer.readBody(input);
		final int phaseCount = input.readVarInt(true);
		final Map<Long, Integer> phases = CollectionUtils.createHashMap(phaseCount);
		for (int i = 0; i < phaseCount; i++) {
			phases.put(input.readLong(), input.readVarInt(true));
		}
		final PagedStreamMetadata surface = PagedStreamMetadataSerializer.readBody(input);
		return new FulltextIndexStoragePart(
			entityIndexPrimaryKey, locale, fields,
			dictionary.highWaterPageSequence(), dictionary.leafPageSequences(), phases,
			surface.highWaterPageSequence(), surface.leafPageSequences(), storagePartPK
		);
	}

}
