/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.*;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.shared.model.PriceWithInternalIds;
import io.evitadb.store.entity.serializer.EnumNameSerializer;
import io.evitadb.store.entity.serializer.PriceWithInternalIdsSerializer;
import io.evitadb.store.entity.serializer.PriceWithInternalIdsSerializer_2024_11;
import io.evitadb.store.entity.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.index.serializer.*;
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
				.addBackwardCompatibleSerializer(6028764096012501468L, new EntityIndexStoragePartSerializer_2025_6(this.keyCompressor))
				.addBackwardCompatibleSerializer(-5960890423106351315L, new EntityIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(
			UniqueIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new UniqueIndexStoragePartSerializer(this.keyCompressor), UniqueIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-4095785894036417656L, new UniqueIndexStoragePartSerializer_2025_5(this.keyCompressor))
				.addBackwardCompatibleSerializer(-3921198859032670410L, new UniqueIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(
			FilterIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new FilterIndexStoragePartSerializer(this.keyCompressor), FilterIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-3363238752052021735L, new FilterIndexStoragePartSerializer_2025_5(this.keyCompressor))
				.addBackwardCompatibleSerializer(942367579256351640L, new FilterIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(
			SortIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new SortIndexStoragePartSerializer(this.keyCompressor), SortIndexStoragePart.class)
				.addBackwardCompatibleSerializer(6163295675316818632L, new SortIndexStoragePartSerializer_2025_5(this.keyCompressor))
				.addBackwardCompatibleSerializer(-7076092972784353868L, new SortIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(
			ChainIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new ChainIndexStoragePartSerializer(this.keyCompressor), ChainIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-2563092938071912295L, new ChainIndexStoragePartSerializer_2025_5(this.keyCompressor))
				.addBackwardCompatibleSerializer(8894604958733971199L, new ChainIndexStoragePartSerializer_2026_1(this.keyCompressor)),
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
				.addBackwardCompatibleSerializer(-7216725334566367295L, new GlobalUniqueIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(TransactionalBitmap.class, new SerialVersionBasedSerializer<>(new TransactionalIntegerBitmapSerializer(), TransactionalBitmap.class), index++);

		kryo.register(InvertedIndex.class, new SerialVersionBasedSerializer<>(new InvertedIndexSerializer(), InvertedIndex.class), index++);
		kryo.register(ValueToRecordBitmap.class, new SerialVersionBasedSerializer<>(new ValueToRecordBitmapSerializer(), ValueToRecordBitmap.class), index++);

		kryo.register(RangeIndex.class, new SerialVersionBasedSerializer<>(new IntRangeIndexSerializer(), RangeIndex.class), index++);
		kryo.register(TransactionalRangePoint.class, new SerialVersionBasedSerializer<>(new TransactionalIntRangePointSerializer(), TransactionalRangePoint.class), index++);

		// skip index, it was used by removed AttributeCardinalityIndexSerializer
		index++;

		kryo.register(
			PriceListAndCurrencySuperIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new PriceListAndCurrencySuperIndexStoragePartSerializer(this.keyCompressor), PriceListAndCurrencySuperIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-7553613939380658772L, new PriceListAndCurrencySuperIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(
			PriceListAndCurrencyRefIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new PriceListAndCurrencyRefIndexStoragePartSerializer(this.keyCompressor), PriceListAndCurrencyRefIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-1687563151524978160L, new PriceListAndCurrencyRefIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(
			PriceWithInternalIds.class,
			new SerialVersionBasedSerializer<>(new PriceWithInternalIdsSerializer(this.keyCompressor), PriceWithInternalIds.class)
				.addBackwardCompatibleSerializer(5008194525461751557L, new PriceWithInternalIdsSerializer_2024_11(this.keyCompressor)),
			index++
		);

		kryo.register(
			HierarchyIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new HierarchyIndexStoragePartSerializer(), HierarchyIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-3223754922135567923L, new HierarchyIndexStoragePartSerializer_2026_1()),
			index++
		);

		kryo.register(
			FacetIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new FacetIndexStoragePartSerializer(), FacetIndexStoragePart.class)
				.addBackwardCompatibleSerializer(-2348533783771242845L, new FacetIndexStoragePartSerializer_2026_1()),
			index++
		);
		kryo.register(ComparableLocale.class, new SerialVersionBasedSerializer<>(new ComparableLocaleSerializer(), ComparableLocale.class), index++);
		kryo.register(ComparableCurrency.class, new SerialVersionBasedSerializer<>(new ComparableCurrencySerializer(), ComparableCurrency.class), index++);
		kryo.register(Scope.class, new EnumNameSerializer<>(), index++);
		kryo.register(RepresentativeReferenceKey.class, new SerialVersionBasedSerializer<>(new RepresentativeReferenceKeySerializer(), RepresentativeReferenceKey.class), index++);

		kryo.register(
			ReferenceTypeCardinalityIndexStoragePart.class,
			new SerialVersionBasedSerializer<>(new ReferenceTypeCardinalityIndexStoragePartSerializer(this.keyCompressor), ReferenceTypeCardinalityIndexStoragePart.class)
				.addBackwardCompatibleSerializer(8276690113370094734L, new ReferenceTypeCardinalityIndexStoragePartSerializer_2026_1(this.keyCompressor)),
			index++
		);
		kryo.register(GroupCardinalityIndexStoragePart.class, new SerialVersionBasedSerializer<>(new GroupCardinalityIndexStoragePartSerializer(this.keyCompressor), GroupCardinalityIndexStoragePart.class), index++);
		kryo.register(
			HistogramIndexStoragePart.class,
			// the reference bucketed-histogram feature is unreleased (absent from every release branch), so no released
			// catalog carries a histogram part — there is nothing to be backward-compatible with. The UID bump on
			// HistogramIndexStoragePart makes any stale unreleased-dev catalog fail loud (and be regenerated) rather than
			// mis-read the added frozen-scale field.
			new SerialVersionBasedSerializer<>(new HistogramIndexStoragePartSerializer(this.keyCompressor), HistogramIndexStoragePart.class),
			index++
		);

		// the granular FilterIndex leaf-page record — a brand-new record type with no backward-compatible reader.
		// Appended last to keep the preceding registration ids stable.
		kryo.register(
			FilterIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new FilterIndexLeafPagePartSerializer(), FilterIndexLeafPagePart.class),
			index++
		);

		// the granular FilterIndex range leaf-page record — a brand-new record type with no backward-compatible reader.
		kryo.register(
			RangeIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new RangeIndexLeafPagePartSerializer(), RangeIndexLeafPagePart.class),
			index++
		);

		// the entity-id bitmaps evicted out of EntityIndexStoragePart — a brand-new record type with no
		// backward-compatible reader (legacy catalogs keep the bitmaps inline on the manifest instead).
		kryo.register(
			EntityIdsStoragePart.class,
			new SerialVersionBasedSerializer<>(new EntityIdsStoragePartSerializer(this.keyCompressor), EntityIdsStoragePart.class),
			index++
		);

		// the granular super-price-index leaf-page record — a brand-new record type with no backward-compatible reader
		// (legacy catalogs keep the price records inline on the monolithic super-index part instead). Appended last to
		// keep the preceding registration ids stable.
		kryo.register(
			PriceListAndCurrencySuperIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new PriceListAndCurrencySuperIndexLeafPagePartSerializer(), PriceListAndCurrencySuperIndexLeafPagePart.class),
			index++
		);

		// the granular standalone (OWNER) unique-index leaf-page record — a brand-new record type with no
		// backward-compatible reader (legacy catalogs keep the value map inline on the monolithic unique part instead).
		// Appended last to keep the preceding registration ids stable.
		kryo.register(
			UniqueIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new UniqueIndexLeafPagePartSerializer(), UniqueIndexLeafPagePart.class),
			index++
		);

		// the granular catalog-level (GLOBAL) unique-index leaf-page record — a brand-new record type with no
		// backward-compatible reader (legacy catalogs keep the value map inline on the monolithic global-unique part
		// instead). Appended last to keep the preceding registration ids stable.
		kryo.register(
			GlobalUniqueIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new GlobalUniqueIndexLeafPagePartSerializer(), GlobalUniqueIndexLeafPagePart.class),
			index++
		);

		// the granular reference-type-cardinality leaf-page record — a brand-new record type with no backward-compatible
		// reader (legacy catalogs keep the cardinality map inline on the monolithic root part instead). Appended last to
		// keep the preceding registration ids stable.
		kryo.register(
			ReferenceTypeCardinalityIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new ReferenceTypeCardinalityIndexLeafPagePartSerializer(), ReferenceTypeCardinalityIndexLeafPagePart.class),
			index++
		);

		// the granular OWNER-mode sort-index value-tree leaf-page record — a brand-new record type with no
		// backward-compatible reader (legacy / small owner catalogs keep the flat sortedRecords + value columns inline on
		// the monolithic root part instead). Appended last to keep the preceding registration ids stable.
		kryo.register(
			SortIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new SortIndexLeafPagePartSerializer(), SortIndexLeafPagePart.class),
			index++
		);

		// the granular ChainIndex value-tree leaf-page record — a brand-new record type with no backward-compatible
		// reader (legacy / small chain catalogs keep the chain runs + element states inline on the monolithic root part
		// instead). Appended last to keep the preceding registration ids stable.
		kryo.register(
			ChainIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new ChainIndexLeafPagePartSerializer(), ChainIndexLeafPagePart.class),
			index++
		);

		// the granular histogram bucket value-tree leaf-page record — a brand-new record type with no
		// backward-compatible reader (the bucketed-histogram feature is unreleased). Appended last to keep the preceding
		// registration ids stable.
		kryo.register(
			HistogramIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new HistogramIndexLeafPagePartSerializer(), HistogramIndexLeafPagePart.class),
			index++
		);

		// the granular histogram range-tree leaf-page record — a brand-new record type with no backward-compatible
		// reader. Appended last to keep the preceding registration ids stable.
		kryo.register(
			HistogramRangeIndexLeafPagePart.class,
			new SerialVersionBasedSerializer<>(new HistogramRangeIndexLeafPagePartSerializer(), HistogramRangeIndexLeafPagePart.class),
			index++
		);

		// the histogram cardinality index evicted out of HistogramIndexStoragePart into its own sibling record — a
		// brand-new record type with no backward-compatible reader (the histogram feature is unreleased). Appended last
		// to keep the preceding registration ids stable.
		kryo.register(
			HistogramCardinalityStoragePart.class,
			new SerialVersionBasedSerializer<>(new HistogramCardinalityStoragePartSerializer(this.keyCompressor), HistogramCardinalityStoragePart.class),
			index++
		);

		Assert.isPremiseValid(index < 700, "Index count overflow.");
	}

}
