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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.cardinality.CardinalityIndex.CardinalityKey;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePartDeprecated;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2024.11", forRemoval = true)
@RequiredArgsConstructor
public class EntityIndexStoragePartSerializer_2024_11 extends Serializer<EntityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, EntityIndexStoragePart entityIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public EntityIndexStoragePart read(Kryo kryo, Input input, Class<? extends EntityIndexStoragePart> type) {
		final int primaryKey = input.readVarInt(true);
		final int version = input.readVarInt(true);

		final EntityIndexType readEntityIndexType = kryo.readObject(input, EntityIndexType.class);
		final EntityIndexType entityIndexType = readEntityIndexType == EntityIndexType.REFERENCED_HIERARCHY_NODE ?
			EntityIndexType.REFERENCED_ENTITY : readEntityIndexType;
		final Serializable discriminator = input.readBoolean() ? (Serializable) kryo.readClassAndObject(input) : null;
		final EntityIndexKey entityIndexKey = discriminator == null ?
			new EntityIndexKey(entityIndexType) : new EntityIndexKey(entityIndexType, discriminator);

		final TransactionalBitmap entityIds = kryo.readObject(input, TransactionalBitmap.class);

		final int languageCount = input.readVarInt(true);
		final Map<Locale, TransactionalBitmap> entityIdsByLocale = createHashMap(languageCount);
		for (int i = 0; i < languageCount; i++) {
			final Locale locale = kryo.readObject(input, Locale.class);
			final TransactionalBitmap localeSpecificEntityIds = kryo.readObject(input, TransactionalBitmap.class);
			entityIdsByLocale.put(locale, localeSpecificEntityIds);
		}

		final int attributeIndexesCount = input.readVarInt(true);
		final Set<AttributeIndexStorageKey> attributeIndexes = createHashSet(attributeIndexesCount);
		for (int i = 0; i < attributeIndexesCount; i++) {
			final AttributeIndexType attributeIndexType = kryo.readObject(input, AttributeIndexType.class);
			final AttributeKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
			attributeIndexes.add(new AttributeIndexStorageKey(entityIndexKey, attributeIndexType, attributeKey));
		}

		final Integer internalPriceIdSequenceSeed = kryo.readObjectOrNull(input, Integer.class);

		final int priceIndexesCount = input.readVarInt(true);
		final Set<PriceIndexKey> priceIndexes = createHashSet(priceIndexesCount);
		for (int i = 0; i < priceIndexesCount; i++) {
			final CompressiblePriceKey priceKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
			final PriceInnerRecordHandling innerRecordHandling = PriceInnerRecordHandling.values()[input.readVarInt(true)];
			priceIndexes.add(
				new PriceIndexKey(priceKey.getPriceList(), priceKey.getCurrency(), innerRecordHandling)
			);
		}

		final boolean hierarchyIndex = input.readBoolean();

		final int facetIndexesCount = input.readVarInt(true);
		final Set<String> facetIndexes = createHashSet(facetIndexesCount);
		for (int i = 0; i < facetIndexesCount; i++) {
			final String entityType = this.keyCompressor.getKeyForId(input.readVarInt(true));
			facetIndexes.add(entityType);
		}

		final int primaryKeyCardinalityCount = input.readInt();
		final CardinalityIndex primaryKeyCardinality;
		if (primaryKeyCardinalityCount == -1) {
			primaryKeyCardinality = new CardinalityIndex(Integer.class, Map.of());
		} else {
			final Map<CardinalityKey, Integer> index = createHashMap(primaryKeyCardinalityCount);
			for (int i = 0; i < primaryKeyCardinalityCount; i++) {
				final int cardinalityPrimaryKey = input.readVarInt(false);
				index.put(
					new CardinalityKey(cardinalityPrimaryKey, cardinalityPrimaryKey),
					input.readVarInt(true)
				);
			}
			primaryKeyCardinality = new CardinalityIndex(Integer.class, index);
		}

		return new EntityIndexStoragePartDeprecated(
			primaryKey, version, entityIndexKey,
			entityIds, entityIdsByLocale,
			attributeIndexes,
			priceIndexes,
			hierarchyIndex, facetIndexes, primaryKeyCardinality,
			internalPriceIdSequenceSeed
		);
	}
}
