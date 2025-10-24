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

package io.evitadb.store.index;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.entity.serializer.PriceWithInternalIdsSerializer;
import io.evitadb.store.entity.serializer.PriceWithInternalIdsSerializer_2024_11;
import io.evitadb.store.index.serializer.*;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.*;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link StoragePart} implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class IndexStoragePartConfigurer implements Consumer<Kryo> {
	private final KeyCompressor keyCompressor;
	private static final int INDEX_BASE = 600;

	@Override
	public void accept(Kryo kryo) {
		int index = INDEX_BASE;

		kryo.register(
			CatalogIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new CatalogIndexStoragePartSerializer(this.keyCompressor), CatalogIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-1216381352203651969L, new CatalogIndexStoragePartSerializer_2024_11(this.keyCompressor)),
			index++
		);
		kryo.register(
			EntityIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new EntityIndexStoragePartSerializer(this.keyCompressor), EntityIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-6245538251957498672L, new EntityIndexStoragePartSerializer_2024_11(this.keyCompressor))
				.addBackwardCompatibleSerializer(5424554446828324138L, new EntityIndexStoragePartSerializer_2025_6(this.keyCompressor))
				.addBackwardCompatibleSerializer(6028764096012501468L, new EntityIndexStoragePartSerializer_2025_6(this.keyCompressor)),
			index++
		);
		kryo.register(
			UniqueIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new UniqueIndexStoragePartSerializer(this.keyCompressor), UniqueIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-4095785894036417656L, new UniqueIndexStoragePartSerializer_2025_5(this.keyCompressor)),
			index++
		);
		kryo.register(
			FilterIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new FilterIndexStoragePartSerializer(this.keyCompressor), FilterIndexStoragePart.class)
				.addBackwardCompatibleSerializer(6163295675316818632L, new FilterIndexStoragePartSerializer_2024_5(this.keyCompressor))
				.addBackwardCompatibleSerializer(-3363238752052021735L, new FilterIndexStoragePartSerializer_2025_5(this.keyCompressor)),
			index++
		);
		kryo.register(
			SortIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new SortIndexStoragePartSerializer(this.keyCompressor), SortIndexStoragePart.class)
				.addBackwardCompatibleSerializer(6163295675316818632L, new SortIndexStoragePartSerializer_2025_5(this.keyCompressor)),
			index++
		);
		kryo.register(
			ChainIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new ChainIndexStoragePartSerializer(this.keyCompressor), ChainIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-2563092938071912295L, new ChainIndexStoragePartSerializer_2025_5(this.keyCompressor)),
			index++
		);
		kryo.register(
			AttributeCardinalityIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new AttributeCardinalityIndexStoragePartSerializer(this.keyCompressor), AttributeCardinalityIndexStoragePart.class)
				.addBackwardCompatibleSerializer(6163295675316818632L, new AttributeCardinalityIndexStoragePartSerializer_2025_5(this.keyCompressor)),
			index++
		);

		kryo.register(EntityIndexType.class, new EnumNameSerializer<>(), index++);
		kryo.register(AttributeIndexType.class, new EnumNameSerializer<>(), index++);
		kryo.register(
			GlobalUniqueIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new GlobalUniqueIndexStoragePartSerializer(this.keyCompressor), GlobalUniqueIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-8158322083280466471L, new GlobalUniqueIndexStoragePartSerializer_2024_11(this.keyCompressor)),
			index++
		);
		kryo.register(TransactionalBitmap.class, new SerialVersionBasedSerializer<>(new TransactionalIntegerBitmapSerializer(), TransactionalBitmap.class), index++);

		kryo.register(InvertedIndex.class, new SerialVersionBasedSerializer<>(new InvertedIndexSerializer(), InvertedIndex.class), index++);
		kryo.register(ValueToRecordBitmap.class, new SerialVersionBasedSerializer<>(new ValueToRecordBitmapSerializer(), ValueToRecordBitmap.class), index++);

		kryo.register(RangeIndex.class, new SerialVersionBasedSerializer<>(new IntRangeIndexSerializer(), RangeIndex.class), index++);
		kryo.register(TransactionalRangePoint.class, new SerialVersionBasedSerializer<>(new TransactionalIntRangePointSerializer(), TransactionalRangePoint.class), index++);

		// skip index, it was used by removed AttributeCardinalityIndexSerializer
		index++;

		kryo.register(PriceListAndCurrencySuperIndexStoragePart.class, new SerialVersionBasedSerializer<>(new PriceListAndCurrencySuperIndexStoragePartSerializer(this.keyCompressor), PriceListAndCurrencySuperIndexStoragePart.class), index++);
		kryo.register(PriceListAndCurrencyRefIndexStoragePart.class, new SerialVersionBasedSerializer<>(new PriceListAndCurrencyRefIndexStoragePartSerializer(this.keyCompressor), PriceListAndCurrencyRefIndexStoragePart.class), index++);
		kryo.register(
			PriceWithInternalIds.class,
			new SerialVersionBasedSerializer<>(new PriceWithInternalIdsSerializer(this.keyCompressor), PriceWithInternalIds.class)
				.addBackwardCompatibleSerializer(5008194525461751557L, new PriceWithInternalIdsSerializer_2024_11(this.keyCompressor)),
			index++
		);

		kryo.register(HierarchyIndexStoragePart.class, new SerialVersionBasedSerializer<>(new HierarchyIndexStorgePartSerializer(), HierarchyIndexStoragePart.class), index++);

		kryo.register(FacetIndexStoragePart.class, new SerialVersionBasedSerializer<>(new FacetIndexStoragePartSerializer(), FacetIndexStoragePart.class), index++);
		kryo.register(ComparableLocale.class, new SerialVersionBasedSerializer<>(new ComparableLocaleSerializer(), ComparableLocale.class), index++);
		kryo.register(ComparableCurrency.class, new SerialVersionBasedSerializer<>(new ComparableCurrencySerializer(), ComparableCurrency.class), index++);
		kryo.register(Scope.class, new EnumNameSerializer<>(), index++);
		kryo.register(RepresentativeReferenceKey.class, new SerialVersionBasedSerializer<>(new RepresentativeReferenceKeySerializer(), RepresentativeReferenceKey.class), index++);

		kryo.register(ReferenceTypeCardinalityIndexStoragePart.class, new SerialVersionBasedSerializer<>(new ReferenceTypeCardinalityIndexStoragePartSerializer(this.keyCompressor), ReferenceTypeCardinalityIndexStoragePart.class), index++);

		Assert.isPremiseValid(index < 700, "Index count overflow.");
	}

}
