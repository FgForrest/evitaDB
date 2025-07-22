/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.PersistentStorageHeader;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityCollectionHeader} from/to binary format.
 * TOBEDONE #538 - Remove this class in the future.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2024.3", forRemoval = true)
public class EntityCollectionHeaderSerializer_2024_5 extends AbstractPersistentStorageHeaderSerializer<EntityCollectionHeader> {

	@Override
	public void write(Kryo kryo, Output output, EntityCollectionHeader object) {
		output.writeString(object.entityType());
		output.writeVarInt(object.entityTypePrimaryKey(), true);
		output.writeVarInt(object.entityTypeFileIndex(), true);
		output.writeVarLong(object.version(), true);
		output.writeVarInt(object.lastPrimaryKey(), true);
		output.writeVarInt(object.lastEntityIndexPrimaryKey(), true);
		output.writeVarInt(object.recordCount(), true);

		final FileLocation fileOffsetIndexLocation = object.fileLocation();
		output.writeVarLong(fileOffsetIndexLocation.startingPosition(), true);
		output.writeVarInt(fileOffsetIndexLocation.recordLength(), true);

		serializeKeys(object.compressedKeys(), output, kryo);
		kryo.writeObjectOrNull(output, object.globalEntityIndexId(), Integer.class);
		serializeEntityIndexIds(output, object);
	}

	@Override
	public EntityCollectionHeader read(Kryo kryo, Input input, Class<? extends EntityCollectionHeader> type) {
		final String entityType = input.readString();
		final int entityTypePrimaryKey = input.readVarInt(true);
		final int entityTypeFileIndex = input.readVarInt(true);
		final long version = input.readVarLong(true);
		final int lastPrimaryKey = input.readVarInt(true);
		final int lastEntityIndexPrimaryKey = input.readVarInt(true);
		final int entityCount = input.readVarInt(true);
		final FileLocation fileOffsetIndexLocation = new FileLocation(
				input.readVarLong(true),
				input.readVarInt(true)
			);
		final Map<Integer, Object> keys = deserializeKeys(input, kryo);

		final Integer globalIndexKey = kryo.readObjectOrNull(input, Integer.class);
		final List<Integer> entityIndexIds = deserializeEntityIndexIds(input);

		return new EntityCollectionHeader(
			entityType,
			entityTypePrimaryKey,
			entityTypeFileIndex,
			entityCount,
			lastPrimaryKey,
			lastEntityIndexPrimaryKey,
			-1,
			1.0,
			new PersistentStorageHeader(version, fileOffsetIndexLocation, keys),
			globalIndexKey,
			entityIndexIds
		);
	}

	private static void serializeEntityIndexIds(@Nonnull Output output, @Nonnull EntityCollectionHeader catalogEntityHeader) {
		final int entityIndexCount = catalogEntityHeader.usedEntityIndexIds().size();
		output.writeVarInt(entityIndexCount, true);
		output.writeInts(
			catalogEntityHeader.usedEntityIndexIds()
				.stream()
				.mapToInt(it -> it)
				.toArray(),
			0,
			entityIndexCount,
			true
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
