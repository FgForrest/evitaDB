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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.utils.NumberUtils;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Backward-compatible {@link Serializer} that reads the released 2025.1–2026.1 binary format of
 * {@link GlobalUniqueIndex} data (every catalog up to and including 2026.1 shares its `serialVersionUID`).
 *
 * The released format always wrote the unique value map INLINE on the root, storing the logical
 * `(entityType, primaryKey, locale)` tuple as three separate ints per entry (entity type as a positive var-int,
 * primary key and locale as fixed ints). The current format folds that tuple into a single packed `long` payload
 * (see {@link GlobalUniqueIndex} `packTuple`) and prefixes the value section with a `PAGED`/`SINGLE` discriminator
 * boolean. This serializer reads the legacy inline layout and packs each tuple into the current payload column so old
 * catalogs keep loading; the loaded part is always a `SINGLE` root (the released format never paged).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class GlobalUniqueIndexStoragePartSerializer_2026_1 extends Serializer<GlobalUniqueIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, GlobalUniqueIndexStoragePart uniqueIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public GlobalUniqueIndexStoragePart read(Kryo kryo, Input input, Class<? extends GlobalUniqueIndexStoragePart> type) {
		final long uniquePartId = input.readVarLong(true);
		final Scope scope = kryo.readObject(input, Scope.class);
		final AttributeKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = kryo.readClass(input).getType();

		// the released format stored the value map inline as (key, entityType, primaryKey, locale) tuples; fold each
		// tuple into the current packed `long` payload (layout locale:16 | entityType:16 | pk:32, NO_LOCALE(-1) biased
		// to 0) so the reconstructed part matches the columns the runtime serializer now writes
		final int uniqueValueCount = input.readVarInt(true);
		final Serializable[] values = new Serializable[uniqueValueCount];
		final long[] payloads = new long[uniqueValueCount];
		for (int i = 0; i < uniqueValueCount; i++) {
			values[i] = kryo.readObject(input, attributeType);
			final int entityType = input.readVarInt(true);
			final int primaryKey = input.readInt();
			final int locale = input.readInt();
			payloads[i] = NumberUtils.pack(locale + 1, entityType, primaryKey);
		}

		final int localeCount = input.readVarInt(true);
		final Map<Integer, Locale> localeIndex = createHashMap(localeCount);
		for (int i = 0; i < localeCount; i++) {
			localeIndex.put(
				input.readVarInt(true),
				kryo.readObject(input, Locale.class)
			);
		}

		return new GlobalUniqueIndexStoragePart(
			scope, attributeKey, attributeType, values, payloads, localeIndex, uniquePartId
		);
	}

}
