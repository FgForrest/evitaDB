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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import lombok.RequiredArgsConstructor;

import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes the {@link EntityIdsStoragePart} sibling record
 * — the entity-id superset bitmap and the per-locale bitmaps evicted out of
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart} — from/to
 * binary format. The bitmap + locale-map codec mirrors the one previously embedded in
 * `EntityIndexStoragePartSerializer`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class EntityIdsStoragePartSerializer extends Serializer<EntityIdsStoragePart> {
	@SuppressWarnings("unused")
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, EntityIdsStoragePart entityIds) {
		output.writeVarInt(entityIds.getPrimaryKey(), true);
		output.writeVarInt(entityIds.getVersion(), true);

		kryo.writeObject(output, entityIds.getEntityIds());

		final Map<Locale, ? extends Bitmap> entityIdsByLanguage = entityIds.getEntityIdsByLanguage();
		output.writeVarInt(entityIdsByLanguage.size(), true);
		for (Entry<Locale, ? extends Bitmap> entry : entityIdsByLanguage.entrySet()) {
			kryo.writeObject(output, entry.getKey());
			kryo.writeObject(output, entry.getValue());
		}
	}

	@Override
	public EntityIdsStoragePart read(Kryo kryo, Input input, Class<? extends EntityIdsStoragePart> type) {
		final int primaryKey = input.readVarInt(true);
		final int version = input.readVarInt(true);

		final TransactionalBitmap entityIds = kryo.readObject(input, TransactionalBitmap.class);

		final int languageCount = input.readVarInt(true);
		final Map<Locale, TransactionalBitmap> entityIdsByLocale = createHashMap(languageCount);
		for (int i = 0; i < languageCount; i++) {
			final Locale locale = kryo.readObject(input, Locale.class);
			final TransactionalBitmap localeSpecificEntityIds = kryo.readObject(input, TransactionalBitmap.class);
			entityIdsByLocale.put(locale, localeSpecificEntityIds);
		}

		return new EntityIdsStoragePart(primaryKey, version, entityIds, entityIdsByLocale);
	}
}
