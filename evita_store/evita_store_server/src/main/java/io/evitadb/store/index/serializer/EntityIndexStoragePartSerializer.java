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
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.cardinality.CardinalityIndex.CardinalityKey;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class EntityIndexStoragePartSerializer extends Serializer<EntityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	/**
	 * Writes the provided {@link CardinalityIndex} to the output stream.
	 *
	 * - When the index is `null`, writes `-1` to mark absence.
	 * - Otherwise writes the number of entries and then pairs of `(recordId, cardinality)`.
	 *
	 * @param output           target output
	 * @param cardinalityIndex index to serialize (may be `null`)
	 */
	private static void writeCardinalityIndex(
		@Nonnull final Output output,
		@Nullable final CardinalityIndex cardinalityIndex
	) {
		// write marker for null index to preserve backward compatibility
		if (cardinalityIndex == null) {
			output.writeInt(-1);
			return;
		}
		final Map<CardinalityKey, Integer> cardinalities = cardinalityIndex.getCardinalities();
		output.writeInt(cardinalities.size());
		for (Entry<CardinalityKey, Integer> entry : cardinalities.entrySet()) {
			output.writeVarInt(entry.getKey().recordId(), false);
			output.writeVarInt(entry.getValue(), true);
		}
	}

	/**
	 * Reads {@link CardinalityIndex} serialized by {@link #writeCardinalityIndex(Output, CardinalityIndex)}.
	 *
	 * @param input input stream
	 * @return reconstructed index or `null` when it was not present
	 */
	@Nullable
	private static CardinalityIndex readCardinalityIndex(@Nonnull final Input input) {
		final int count = input.readInt();
		if (count == -1) {
			return null;
		}
		final Map<CardinalityKey, Integer> index = createHashMap(count);
		for (int i = 0; i < count; i++) {
			final int cardinalityPrimaryKey = input.readVarInt(false);
			index.put(
				new CardinalityKey(cardinalityPrimaryKey, cardinalityPrimaryKey),
				input.readVarInt(true)
			);
		}
		return new CardinalityIndex(Integer.class, index);
	}

	/**
	 * Writes the referenced primary keys index map to the output stream.
	 *
	 * - When the map is `null`, writes `-1` to mark absence.
	 * - Otherwise writes the size and then pairs of `(referencedPk, originalPk)`.
	 *
	 * @param output                     target output
	 * @param referencedPrimaryKeysIndex map to serialize (may be `null`)
	 */
	private static void writeReferencedPrimaryKeysIndex(
		@Nonnull final Output output,
		@Nonnull final Kryo kryo,
		@Nullable final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex
	) {
		if (referencedPrimaryKeysIndex == null) {
			output.writeInt(-1);
			return;
		}
		output.writeInt(referencedPrimaryKeysIndex.size());
		for (Entry<Integer, TransactionalBitmap> entry : referencedPrimaryKeysIndex.entrySet()) {
			output.writeVarInt(entry.getKey(), false);
			kryo.writeObject(output, entry.getValue());
		}
	}

	/**
	 * Reads map serialized by {@link #writeReferencedPrimaryKeysIndex(Output, Kryo, Map)}.
	 *
	 * @param input input stream
	 * @return reconstructed map (never `null`; may be empty)
	 */
	@Nonnull
	private static Map<Integer, TransactionalBitmap> readReferencedPrimaryKeysIndex(@Nonnull final Input input, @Nonnull final Kryo kryo) {
		final int count = input.readInt();
		if (count == -1) {
			return Collections.emptyMap();
		}
		final Map<Integer, TransactionalBitmap> map = createHashMap(count);
		for (int i = 0; i < count; i++) {
			map.put(
				input.readVarInt(false),
				kryo.readObject(input, TransactionalBitmap.class)
			);
		}
		return map;
	}

	@Override
	public void write(Kryo kryo, Output output, EntityIndexStoragePart entityIndex) {
		output.writeVarInt(entityIndex.getPrimaryKey(), true);
		output.writeVarInt(entityIndex.getVersion(), true);

		final EntityIndexKey entityIndexKey = entityIndex.getEntityIndexKey();
		kryo.writeObject(output, entityIndexKey.type());
		kryo.writeObject(output, entityIndexKey.scope());
		if (entityIndexKey.discriminator() == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			kryo.writeClassAndObject(output, entityIndexKey.discriminator());
		}

		final Bitmap entityIds = entityIndex.getEntityIds();
		kryo.writeObject(output, entityIds);

		final Map<Locale, ? extends Bitmap> entitiesIdsByLanguage = entityIndex.getEntityIdsByLanguage();
		output.writeVarInt(entitiesIdsByLanguage.size(), true);
		for (Entry<Locale, ? extends Bitmap> entry : entitiesIdsByLanguage.entrySet()) {
			kryo.writeObject(output, entry.getKey());
			kryo.writeObject(output, entry.getValue());
		}

		final Set<AttributeIndexStorageKey> attributeIndexes = entityIndex.getAttributeIndexes();
		output.writeVarInt(attributeIndexes.size(), true);
		for (AttributeIndexStorageKey attributeIndexKey : attributeIndexes) {
			kryo.writeObject(output, attributeIndexKey.indexType());
			output.writeVarInt(this.keyCompressor.getId(attributeIndexKey.attribute()), true);
		}

		final Set<PriceIndexKey> priceIndexes = entityIndex.getPriceIndexes();
		output.writeVarInt(priceIndexes.size(), true);
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final CompressiblePriceKey cpk = new CompressiblePriceKey(
				priceIndexKey.getPriceList(), priceIndexKey.getCurrency());
			output.writeVarInt(this.keyCompressor.getId(cpk), true);
			output.writeVarInt(priceIndexKey.getRecordHandling().ordinal(), true);
		}

		output.writeBoolean(entityIndex.isHierarchyIndex());

		final Set<String> facetIndexes = entityIndex.getFacetIndexes();
		output.writeVarInt(facetIndexes.size(), true);
		for (String referencedEntity : facetIndexes) {
			output.writeVarInt(this.keyCompressor.getId(referencedEntity), true);
		}

		EntityIndexStoragePartSerializer.writeCardinalityIndex(output, entityIndex.getIndexPrimaryKeyCardinality());
		EntityIndexStoragePartSerializer.writeReferencedPrimaryKeysIndex(output, kryo, entityIndex.getReferencedPrimaryKeysIndex());
	}

	@Override
	public EntityIndexStoragePart read(Kryo kryo, Input input, Class<? extends EntityIndexStoragePart> type) {
		final int primaryKey = input.readVarInt(true);
		final int version = input.readVarInt(true);

		final EntityIndexType entityIndexType = kryo.readObject(input, EntityIndexType.class);
		final Scope entityIndexScope = kryo.readObject(input, Scope.class);
		final Serializable discriminator = input.readBoolean() ? (Serializable) kryo.readClassAndObject(input) : null;
		final EntityIndexKey entityIndexKey = discriminator == null ?
			new EntityIndexKey(entityIndexType, entityIndexScope, null) :
			new EntityIndexKey(entityIndexType, entityIndexScope, discriminator);

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

		final int priceIndexesCount = input.readVarInt(true);
		final Set<PriceIndexKey> priceIndexes = createHashSet(priceIndexesCount);
		for (int i = 0; i < priceIndexesCount; i++) {
			final CompressiblePriceKey priceKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
			final PriceInnerRecordHandling innerRecordHandling = PriceInnerRecordHandling.values()[input.readVarInt(
				true)];
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

		final CardinalityIndex primaryKeyCardinality = readCardinalityIndex(input);
		final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex = readReferencedPrimaryKeysIndex(input, kryo);

		return new EntityIndexStoragePart(
			primaryKey, version, entityIndexKey,
			entityIds, entityIdsByLocale,
			attributeIndexes,
			priceIndexes,
			hierarchyIndex, facetIndexes,
			primaryKeyCardinality,
			referencedPrimaryKeysIndex
		);
	}
}
