/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortIndex;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.api.query.order.OrderDirection.ASC;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link AttributeNatural} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeNaturalTranslator
	implements OrderingConstraintTranslator<AttributeNatural>, ReferenceOrderingConstraintTranslator<AttributeNatural> {

	@Nonnull
	@Override
	public Sorter createSorter(@Nonnull AttributeNatural attributeNatural, @Nonnull OrderByVisitor orderByVisitor) {
		/* TODO JNO - handle this */
		final String attributeName = attributeNatural.getAttributeName();
		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
		final Sorter lastUsedSorter = orderByVisitor.getLastUsedSorter();
		final Locale locale = orderByVisitor.getLocale();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeExtractor attributeSchemaEntityAccessor = processingScope.attributeEntityAccessor();

		final Supplier<SortedRecordsProvider> sortedRecordsSupplier;
		final EntityComparator comparator;
		if (orderDirection == ASC) {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getAscendingOrderRecordsSupplier,
				() -> processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE),
				orderByVisitor.getIndexForSort(),
				orderByVisitor, locale
			);
			//noinspection unchecked,rawtypes
			comparator = new AttributeComparator(
				attributeName, locale, attributeSchemaEntityAccessor,
				(o1, o2) -> ((Comparable) o1).compareTo(o2)
			);
		} else {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getDescendingOrderRecordsSupplier,
				() -> processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE),
				orderByVisitor.getIndexForSort(),
				orderByVisitor, locale
			);
			//noinspection unchecked,rawtypes
			comparator = new AttributeComparator(
				attributeName, locale, attributeSchemaEntityAccessor,
				(o1, o2) -> ((Comparable) o2).compareTo(o1)
			);
		}

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeSchemaEntityAccessor.getRequirements());
		final PreSortedRecordsSorter sorter = new PreSortedRecordsSorter(
			sortedRecordsSupplier, comparator
		);

		if (lastUsedSorter == null) {
			return sorter;
		} else {
			return lastUsedSorter.andThen(sorter);
		}
	}

	@Nonnull
	@Override
	public ReferenceComparator createComparator(@Nonnull AttributeNatural attributeNatural, @Nonnull ReferenceOrderByVisitor orderByVisitor) {
		/* TODO JNO - Handle this */
		final String attributeName = attributeNatural.getAttributeName();
		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
		final ReferenceComparator lastUsedComparator = orderByVisitor.getLastUsedComparator();
		final Locale locale = orderByVisitor.getLocale();

		final ReferenceComparator comparator;
		if (orderDirection == ASC) {
			//noinspection unchecked,rawtypes
			comparator = new ReferenceAttributeComparator(
				attributeName, locale, (o1, o2) -> ((Comparable) o1).compareTo(o2)
			);
		} else {
			//noinspection unchecked,rawtypes
			comparator = new ReferenceAttributeComparator(
				attributeName, locale, (o1, o2) -> ((Comparable) o2).compareTo(o1)
			);
		}

		if (lastUsedComparator == null) {
			return comparator;
		} else {
			return lastUsedComparator.andThen(comparator);
		}
	}

	private record AttributeSortedRecordsProviderSupplier(
		@Nonnull Function<SortIndex, SortedRecordsProvider> extractor,
		@Nonnull Supplier<AttributeSchemaContract> attributeSchemaSupplier,
		@Nonnull EntityIndex targetIndex,
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull Locale locale
	) implements Supplier<SortedRecordsProvider> {
		@Override
		public SortedRecordsProvider get() {
			final AttributeSchemaContract attributeSchema = attributeSchemaSupplier.get();
			final SortIndex sortIndex = targetIndex.getSortIndex(attributeSchema.getName(), locale);
			return sortIndex == null ? SortedRecordsProvider.EMPTY : extractor.apply(sortIndex);
		}
	}
}
