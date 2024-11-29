/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
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
		final String attributeOrCompoundName = attributeNatural.getAttributeName();
		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
		final Locale locale = orderByVisitor.getLocale();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeExtractor attributeSchemaEntityAccessor = processingScope.attributeEntityAccessor();

		final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
		final EntityIndex[] indexesForSort = orderByVisitor.getIndexesForSort();
		final NamedSchemaContract attributeOrCompoundSchema = processingScope.getAttributeSchemaOrSortableAttributeCompound(attributeOrCompoundName);

		if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema && attributeSchema.isLocalized()) {
			Assert.notNull(
				orderByVisitor.getLocale(),
				"Cannot sort by localized attribute `" + attributeOrCompoundName + "` without locale specified in `entityLocaleEquals` filtering constraint!"
			);
		} else if (attributeOrCompoundSchema instanceof SortableAttributeCompoundSchemaContract compoundSchemaContract) {
			for (AttributeElement attributeElement : compoundSchemaContract.getAttributeElements()) {
				if (processingScope.getAttributeSchema(attributeElement.attributeName()).isLocalized()) {
					Assert.notNull(
						orderByVisitor.getLocale(),
						"Cannot sort by localized attribute `" + attributeOrCompoundName + "` without locale specified in `entityLocaleEquals` filtering constraint!"
					);
					break;
				}
			}
		}

		if (orderDirection == ASC) {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getAscendingOrderRecordsSupplier,
				ChainIndex::getAscendingOrderRecordsSupplier,
				attributeOrCompoundSchema,
				indexesForSort,
				orderByVisitor, locale
			);
		} else {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getDescendingOrderRecordsSupplier,
				ChainIndex::getDescendingOrderRecordsSupplier,
				attributeOrCompoundSchema,
				indexesForSort,
				orderByVisitor, locale
			);
		}

		final EntityComparator entityComparator;
		if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema &&
			(Predecessor.class.equals(attributeSchema.getPlainType()) || ReferencedEntityPredecessor.class.equals(attributeSchema.getPlainType()))) {
			// we cannot use attribute comparator for predecessor attributes, we always need index here
			entityComparator = new PredecessorAttributeComparator(sortedRecordsSupplier);
		} else if (attributeOrCompoundSchema instanceof SortableAttributeCompoundSchemaContract compoundSchemaContract) {
			entityComparator = new CompoundAttributeComparator(
				compoundSchemaContract, locale,
				attributeName -> processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE),
				attributeSchemaEntityAccessor,
				orderDirection
			);
		} else if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema) {
			entityComparator = new AttributeComparator(
				attributeOrCompoundName, attributeSchema.getPlainType(), locale, attributeSchemaEntityAccessor, orderDirection
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported attribute schema type: " + attributeOrCompoundSchema);
		}

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeSchemaEntityAccessor.getRequirements());

		return Stream.of(
			new PrefetchedRecordsSorter(entityComparator),
			new PreSortedRecordsSorter(sortedRecordsSupplier)
		);
	}

	@Override
	public void createComparator(@Nonnull AttributeNatural attributeNatural, @Nonnull ReferenceOrderByVisitor orderByVisitor) {
		final String attributeOrCompoundName = attributeNatural.getAttributeName();
		final NamedSchemaContract attributeOrCompoundSchema = orderByVisitor.getAttributeSchemaOrSortableAttributeCompound(
			attributeOrCompoundName
		);

		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
		final Locale locale = orderByVisitor.getLocale();

		final ReferenceComparator comparator;
		if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema &&
			attributeSchema.getPlainType().equals(Predecessor.class)
		) {
			if (orderDirection == ASC) {
				comparator = new ReferencePredecessorComparator(
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getAscendingOrderRecordsSupplier,
					(ref1, ref2) -> ref1.primaryKey() == ref2.primaryKey(),
					(epk, referenceKey) -> epk
				);
			} else {
				comparator = new ReferencePredecessorComparator(
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getDescendingOrderRecordsSupplier,
					(ref1, ref2) -> ref1.primaryKey() == ref2.primaryKey(),
					(epk, referenceKey) -> epk
				);
			}
		} else if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema &&
			attributeSchema.getPlainType().equals(ReferencedEntityPredecessor.class)
		) {
			if (orderDirection == ASC) {
				comparator = new ReferencePredecessorComparator(
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getAscendingOrderRecordsSupplier,
					(ref1, ref2) -> true,
					(epk, referenceKey) -> referenceKey.primaryKey()
				);
			} else {
				comparator = new ReferencePredecessorComparator(
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getDescendingOrderRecordsSupplier,
					(ref1, ref2) -> true,
					(epk, referenceKey) -> referenceKey.primaryKey()
				);
			}
		} else if (attributeOrCompoundSchema instanceof SortableAttributeCompoundSchema compoundSchemaContract) {
			comparator = new ReferenceCompoundAttributeComparator(
				compoundSchemaContract,
				compoundSchemaContract.isLocalized(orderByVisitor::getAttributeSchema) ? locale : null,
				orderByVisitor::getAttributeSchema,
				orderDirection
			);
		} else if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema) {
			comparator = new ReferenceAttributeComparator(
				attributeOrCompoundName,
				attributeSchema.getPlainType(),
				attributeSchema.isLocalized() ? locale : null,
				orderDirection
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported attribute schema type: " + attributeOrCompoundSchema);
		}

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementsToPrefetch(attributeContent(attributeOrCompoundSchema.getName()));

		orderByVisitor.addComparator(comparator);
	}

	private record AttributeSortedRecordsProviderSupplier(
		@Nonnull Function<SortIndex, SortedRecordsProvider> sortIndexExtractor,
		@Nonnull Function<ChainIndex, SortedRecordsProvider> chainIndexExtractor,
		@Nonnull NamedSchemaContract attributeOrCompoundSchema,
		@Nonnull EntityIndex[] targetIndex,
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull Locale locale
		) implements Supplier<SortedRecordsProvider[]> {
		private static final SortedRecordsProvider[] EMPTY_PROVIDERS = {SortedRecordsProvider.EMPTY};

		@Override
		public SortedRecordsProvider[] get() {
			final SortedRecordsProvider[] sortedRecordsProvider;
			if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchemaContract &&
				Predecessor.class.equals(attributeSchemaContract.getPlainType())) {
				sortedRecordsProvider = Arrays.stream(targetIndex)
					.map(it -> it.getChainIndex(attributeOrCompoundSchema.getName(), locale))
					.filter(Objects::nonNull)
					.map(chainIndexExtractor)
					.toArray(SortedRecordsProvider[]::new);
			} else if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchemaContract &&
				ReferencedEntityPredecessor.class.equals(attributeSchemaContract.getPlainType())) {
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
