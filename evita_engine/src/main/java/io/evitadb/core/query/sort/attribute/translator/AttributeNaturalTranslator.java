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
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.SortIndex;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
	public Stream<Sorter> createSorter(@Nonnull AttributeNatural attributeNatural, @Nonnull OrderByVisitor orderByVisitor) {
		final String attributeName = attributeNatural.getAttributeName();
		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
		final Locale locale = orderByVisitor.getLocale();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeExtractor attributeSchemaEntityAccessor = processingScope.attributeEntityAccessor();

		final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
		final EntityIndex<?>[] indexesForSort = orderByVisitor.getIndexesForSort();
		final NamedSchemaContract attributeOrCompoundSchema = processingScope.getAttributeSchemaOrSortableAttributeCompound(attributeName);

		final Comparator<Comparable<?>> comparator;
		if (orderDirection == ASC) {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getAscendingOrderRecordsSupplier,
				ChainIndex::getAscendingOrderRecordsSupplier,
				attributeOrCompoundSchema,
				indexesForSort,
				orderByVisitor, locale
			);
			//noinspection unchecked,rawtypes
			comparator = (o1, o2) -> ((Comparable) o1).compareTo(o2);
		} else {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getDescendingOrderRecordsSupplier,
				ChainIndex::getDescendingOrderRecordsSupplier,
				attributeOrCompoundSchema,
				indexesForSort,
				orderByVisitor, locale
			);
			//noinspection unchecked,rawtypes
			comparator = (o1, o2) -> ((Comparable) o2).compareTo(o1);
		}

		final EntityComparator entityComparator;
		if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema &&
			Predecessor.class.equals(attributeSchema.getPlainType())) {
			// we cannot use attribute comparator for predecessor attributes, we always need index here
			entityComparator = new PredecessorAttributeComparator(sortedRecordsSupplier);
		} else {
			entityComparator = new AttributeComparator(
				attributeName, locale, attributeSchemaEntityAccessor, comparator
			);
		}

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeSchemaEntityAccessor.getRequirements());

		final PreSortedRecordsSorter preSortedRecordsSorter = new PreSortedRecordsSorter(
			processingScope.entityType(), sortedRecordsSupplier
		);

		return Stream.of(
				new PrefetchedRecordsSorter(entityComparator),
				preSortedRecordsSorter
			);
	}

	@Override
	public void createComparator(@Nonnull AttributeNatural attributeNatural, @Nonnull ReferenceOrderByVisitor orderByVisitor) {
		final String attributeName = attributeNatural.getAttributeName();
		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
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

		orderByVisitor.addComparator(comparator);
	}

	private record AttributeSortedRecordsProviderSupplier(
		@Nonnull Function<SortIndex, SortedRecordsProvider> sortIndexExtractor,
		@Nonnull Function<ChainIndex, SortedRecordsProvider> chainIndexExtractor,
		@Nonnull NamedSchemaContract attributeOrCompoundSchema,
		@Nonnull EntityIndex<?>[] targetIndex,
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull Locale locale
	) implements Supplier<SortedRecordsProvider[]> {
		private static final SortedRecordsProvider[] EMPTY_PROVIDERS = {SortedRecordsProvider.EMPTY};

		@Override
		public SortedRecordsProvider[] get() {
			final SortedRecordsProvider[] sortedRecordsProvider;
			if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchemaContract && Predecessor.class.equals(attributeSchemaContract.getPlainType())) {
				sortedRecordsProvider = Arrays.stream(targetIndex)
					.map(it -> it.getChainIndex(attributeOrCompoundSchema.getName(), locale))
					.filter(Objects::nonNull)
					.map(chainIndexExtractor)
					.toArray(SortedRecordsProvider[]::new);
			} else {
				sortedRecordsProvider = Arrays.stream(targetIndex)
					.map(it -> it.getSortIndex(attributeOrCompoundSchema.getName(), locale))
					.filter(Objects::nonNull)
					.map(sortIndexExtractor)
					.toArray(SortedRecordsProvider[]::new);
			}

			return sortedRecordsProvider.length == 0 ?
				EMPTY_PROVIDERS : sortedRecordsProvider;
		}
	}
}
