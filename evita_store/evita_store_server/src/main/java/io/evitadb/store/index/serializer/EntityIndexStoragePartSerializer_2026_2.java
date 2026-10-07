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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Backward-compatible {@link Serializer} that reads the 2026.2 binary format of {@link EntityIndex} data.
 *
 * The 2026.2 format ends with the histogram section. The current serializer appends the fulltext index section after
 * it, so this one reads the legacy layout and constructs an {@link EntityIndexStoragePart} with an empty set of
 * fulltext indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.3", forRemoval = true)
@RequiredArgsConstructor
public class EntityIndexStoragePartSerializer_2026_2 extends Serializer<EntityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, EntityIndexStoragePart entityIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
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

		final int attributeIndexesCount = input.readVarInt(true);
		final Set<AttributeIndexStorageKey> attributeIndexes = createHashSet(attributeIndexesCount);
		for (int i = 0; i < attributeIndexesCount; i++) {
			final AttributeIndexType attributeIndexType = kryo.readObject(input, AttributeIndexType.class);
			final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
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
			final ReferenceNameKey key = this.keyCompressor.getKeyForId(input.readVarInt(true));
			facetIndexes.add(key.referenceName());
		}

		final int histogramIndexesCount = input.readVarInt(true);
		final Set<HistogramIndexStorageKey> histogramIndexes = createHashSet(histogramIndexesCount);
		for (int i = 0; i < histogramIndexesCount; i++) {
			final String histogramName = input.readString();
			final Locale locale = kryo.readObjectOrNull(input, Locale.class);
			histogramIndexes.add(new HistogramIndexStorageKey(
				entityIndexKey, histogramName, locale
			));
		}

		return new EntityIndexStoragePart(
			primaryKey, version, entityIndexKey,
			attributeIndexes,
			priceIndexes,
			hierarchyIndex, facetIndexes,
			histogramIndexes,
			Set.of()
		);
	}
}
