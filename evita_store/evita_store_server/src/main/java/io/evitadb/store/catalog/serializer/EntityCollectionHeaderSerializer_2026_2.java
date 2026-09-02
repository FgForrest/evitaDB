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

package io.evitadb.store.catalog.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.header.PersistentStorageHeader;
import io.evitadb.store.shared.model.FileLocation;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads {@link EntityCollectionFileHeader} in the layout that shipped up to and including 2026.2 - everything the
 * current {@link EntityCollectionHeaderSerializer} writes *except* the trailing `lastModifiedMillis`, which 2026.3
 * appended. Headers in that layout carry no timestamp at all, so they are reconstructed with
 * {@link EntityCollectionFileHeader#NOT_STAMPED} and the statistics layer reports the collection's last-modified time
 * as unknown until its next flush stamps it.
 *
 * Registered against the pre-bump `serialVersionUID` `-2149051526452828365L`, which was the value at `release_2026-1`
 * and `release_2026-2` alike - the class had not been bumped since 2024.12, so that single value covers every catalog
 * written in between.
 *
 * Read-only, like every snapshot serializer: {@link #write} throws.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class EntityCollectionHeaderSerializer_2026_2
	extends AbstractPersistentStorageHeaderSerializer<EntityCollectionFileHeader> {

	@Override
	public void write(Kryo kryo, Output output, EntityCollectionFileHeader object) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public EntityCollectionFileHeader read(Kryo kryo, Input input, Class<? extends EntityCollectionFileHeader> type) {
		final String entityType = input.readString();
		final int entityTypePrimaryKey = input.readVarInt(true);
		final int entityTypeFileIndex = input.readVarInt(true);
		final long version = input.readVarLong(true);
		final int lastPrimaryKey = input.readVarInt(true);
		final int lastEntityIndexPrimaryKey = input.readVarInt(true);
		final int lastInternalPriceId = input.readVarInt(true);
		final int entityCount = input.readVarInt(true);
		final double activeRecordShare = input.readDouble();
		final FileLocation fileOffsetIndexLocation = new FileLocation(
			input.readVarLong(true),
			input.readVarInt(true)
		);
		final DeserializedKeys deserializedKeys = deserializeKeysAndPeak(input, kryo);

		final Integer globalIndexKey = kryo.readObjectOrNull(input, Integer.class);
		final List<Integer> entityIndexIds = deserializeEntityIndexIds(input);
		// the stream ends here in this layout - the current serializer appends `lastModifiedMillis` past this point

		return new EntityCollectionFileHeader(
			entityType,
			entityTypePrimaryKey,
			entityTypeFileIndex,
			entityCount,
			lastPrimaryKey,
			lastEntityIndexPrimaryKey,
			lastInternalPriceId,
			activeRecordShare,
			new PersistentStorageHeader(
				version, fileOffsetIndexLocation, deserializedKeys.keys(), deserializedKeys.peakId()
			),
			globalIndexKey,
			entityIndexIds,
			EntityCollectionFileHeader.NOT_STAMPED
		);
	}

	@Nonnull
	private static List<Integer> deserializeEntityIndexIds(@Nonnull Input input) {
		final int entityIndexCount = input.readVarInt(true);
		return Arrays.stream(input.readInts(entityIndexCount, true))
			.boxed()
			.collect(Collectors.toList());
	}

}
