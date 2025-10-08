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

package io.evitadb.core.query.sort.attribute.translator;

import com.carrotsearch.hppc.IntIntHashMap;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.MergeModeDefinition;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.index.attribute.SortIndex.createCombinedComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createComparatorFor;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link AttributeNatural} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeNaturalTranslator
	implements OrderingConstraintTranslator<AttributeNatural>, ReferenceOrderingConstraintTranslator<AttributeNatural> {

	/**
	 * Combines a given attribute or compound name with the attribute names derived from the
	 * provided SortableAttributeCompoundSchemaContract. This method concatenates the initial
	 * attribute or compound name with all of the attribute names defined in the compound's
	 * attribute elements.
	 *
	 * @param attributeOrCompoundName the primary attribute or compound name to be combined.
	 * @param sacsc                   an instance of SortableAttributeCompoundSchemaContract containing attribute elements
	 *                                whose names should be included in the result.
	 * @return an array of strings containing the combined attribute or compound name and the names
	 * of all the attributes from the compound schema.
	 */
	@Nonnull
	private static String[] combineCompoundWithReferencedAttributes(
		@Nonnull String attributeOrCompoundName,
		@Nonnull SortableAttributeCompoundSchemaContract sacsc
	) {
		return Stream.concat(
				Stream.of(attributeOrCompoundName),
				sacsc.getAttributeElements().stream().map(AttributeElement::attributeName)
			)
			.toArray(String[]::new);
	}

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull AttributeNatural attributeNatural, @Nonnull OrderByVisitor orderByVisitor) {
		final String attributeOrCompoundName = attributeNatural.getAttributeName();
		final OrderDirection orderDirection = attributeNatural.getOrderDirection();
		final Locale locale = orderByVisitor.getLocale();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();

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

		// we need the schema in case of compound sorting to determine the source attribute properties
		final EntitySchemaContract entitySchema = attributeOrCompoundSchema instanceof SortableAttributeCompoundSchemaContract ?
			orderByVisitor.getSchema() : null;
		final ReferenceSchema referenceSchema = processingScope.referenceSchema();
		if (orderDirection == ASC) {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getAscendingOrderRecordsSupplier,
				ChainIndex::getAscendingOrderRecordsSupplier,
				entitySchema,
				referenceSchema,
				attributeOrCompoundSchema,
				indexesForSort,
				orderByVisitor, locale
			);
		} else {
			sortedRecordsSupplier = new AttributeSortedRecordsProviderSupplier(
				SortIndex::getDescendingOrderRecordsSupplier,
				ChainIndex::getDescendingOrderRecordsSupplier,
				entitySchema,
				referenceSchema,
				attributeOrCompoundSchema,
				indexesForSort,
				orderByVisitor, locale
			);
		}

		//noinspection rawtypes
		final Comparator valueComparator;
		final EntityComparator entityComparator;
		final MergeModeDefinition mergeModeDefinition = processingScope.mergeModeDefinition();
		final MergeMode mergeMode;
		if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema &&
			(Predecessor.class.equals(attributeSchema.getPlainType()) || ReferencedEntityPredecessor.class.equals(attributeSchema.getPlainType()))) {
			// we cannot use attribute comparator for predecessor attributes, we always need index here
			if (referenceSchema == null) {
				entityComparator = new PredecessorAttributeComparator(
					sortedRecordsSupplier
				);
			} else {
				entityComparator = new TraverseReferencePredecessorAttributeComparator(
					attributeOrCompoundName,
					attributeSchema.getPlainType(),
					referenceSchema,
					locale,
					orderDirection,
					sortedRecordsSupplier
				);
				Assert.isTrue(
					mergeModeDefinition == null || mergeModeDefinition.mergeMode() == MergeMode.APPEND_ALL || mergeModeDefinition.implicit(),
					attributeSchema.getPlainType() + " attribute `" + attributeOrCompoundName + "` " +
						"is not comparable one with another and must use `traverseBy` approach for sorting!"
				);
			}
			mergeMode = MergeMode.APPEND_ALL;
			valueComparator = null;
		} else if (attributeOrCompoundSchema instanceof SortableAttributeCompoundSchemaContract compoundSchemaContract) {
			final Comparator<ComparableArray> naturalComparator = createCombinedComparatorFor(
				locale,
				compoundSchemaContract.getAttributeElements()
					.stream()
					.map(attributeElement -> new ComparatorSource(
						processingScope.getAttributeSchema(attributeElement.attributeName()).getPlainType(),
						attributeElement.direction(),
						attributeElement.behaviour()
					))
					.toArray(ComparatorSource[]::new)
			);
			// the order direction is not resolved within createCombinedComparatorFor() method
			valueComparator = orderDirection == ASC ? naturalComparator : naturalComparator.reversed();
			mergeMode = mergeModeDefinition == null ? MergeMode.APPEND_FIRST : mergeModeDefinition.mergeMode();
			if (referenceSchema == null) {
				entityComparator = new CompoundAttributeComparator(
					compoundSchemaContract,
					locale,
					attributeName -> processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE),
					orderDirection
				);
			} else if (mergeMode == MergeMode.APPEND_FIRST) {
				entityComparator = new PickFirstReferenceCompoundAttributeComparator(
					compoundSchemaContract,
					referenceSchema,
					locale,
					attributeName -> processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE),
					orderDirection,
					() -> createReferenceSortedRecordsProviderPositionIndex(sortedRecordsSupplier)
				);
			} else {
				entityComparator = new TraverseReferenceCompoundAttributeComparator(
					compoundSchemaContract,
					referenceSchema,
					locale,
					attributeName -> processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE),
					orderDirection
				);
			}
		} else if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema) {
			valueComparator = createComparatorFor(
				locale,
				new ComparatorSource(
					attributeSchema.getPlainType(),
					orderDirection,
					OrderBehaviour.NULLS_LAST
				)
			);
			mergeMode = mergeModeDefinition == null ? MergeMode.APPEND_FIRST : mergeModeDefinition.mergeMode();
			if (referenceSchema == null) {
				entityComparator = new AttributeComparator(
					attributeOrCompoundName,
					attributeSchema.getPlainType(),
					locale,
					orderDirection
				);
			} else if (mergeMode == MergeMode.APPEND_FIRST) {
				entityComparator = new PickFirstReferenceAttributeComparator(
					attributeOrCompoundName,
					attributeSchema.getPlainType(),
					referenceSchema,
					locale,
					orderDirection,
					() -> createReferenceSortedRecordsProviderPositionIndex(sortedRecordsSupplier)
				);
			} else {
				entityComparator = new TraverseReferenceAttributeComparator(
					attributeOrCompoundName,
					attributeSchema.getPlainType(),
					referenceSchema,
					locale,
					orderDirection
				);
			}
		} else {
			throw new GenericEvitaInternalError("Unsupported attribute schema type: " + attributeOrCompoundSchema);
		}

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(
			attributeOrCompoundSchema instanceof SortableAttributeCompoundSchemaContract sacsc ?
				(referenceSchema == null ?
					attributeContent(combineCompoundWithReferencedAttributes(attributeOrCompoundName, sacsc)) :
					referenceContentWithAttributes(referenceSchema.getName(), combineCompoundWithReferencedAttributes(attributeOrCompoundName, sacsc))
				) :
				(referenceSchema == null ? attributeContent(attributeOrCompoundName) : referenceContentWithAttributes(referenceSchema.getName(), attributeOrCompoundName))
		);

		return Stream.of(
			new PrefetchedRecordsSorter(entityComparator),
			new PreSortedRecordsSorter(mergeMode, valueComparator, sortedRecordsSupplier)
		);
	}

	/**
	 * Creates a mapping between the primary keys of reference-sorted records and their indices
	 * in the order they are sorted. This method processes the sorted records provided by the
	 * {@link SortedRecordsProvider} suppliers, filtering out those that are implementations of
	 * {@link ReferenceSortedRecordsProvider}. For each instance, the primary key of its reference key
	 * is associated with its zero-based position in the sorted array. The mapping is returned as an
	 * {@link IntIntHashMap}.
	 *
	 * @param sortedRecordsSupplier a supplier that provides an array of {@link SortedRecordsProvider}
	 *                               instances, which may include {@link ReferenceSortedRecordsProvider}
	 *                               implementations containing reference-based sorting information.
	 * @return an {@link IntIntHashMap} that maps each reference primary key to its position in the
	 *         sorted array.
	 */
	@Nonnull
	private static IntIntHashMap createReferenceSortedRecordsProviderPositionIndex(
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier
	) {
		final int[] sortedReferencePks = Arrays.stream(sortedRecordsSupplier.get())
			.filter(ReferenceSortedRecordsProvider.class::isInstance)
			.map(ReferenceSortedRecordsProvider.class::cast)
			.mapToInt(it -> it.getReferenceKey().primaryKey())
			.toArray();
		final IntIntHashMap result = new IntIntHashMap();
		for (int i = 0; i < sortedReferencePks.length; i++) {
			int sortedReferencePk = sortedReferencePks[i];
			result.put(sortedReferencePk, i);
		}
		return result;
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
					orderByVisitor.getReferenceSchema(),
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getAscendingOrderRecordsSupplier,
					(ref1, ref2) -> ref1.primaryKey() == ref2.primaryKey(),
					(epk, referenceKey) -> epk
				);
			} else {
				comparator = new ReferencePredecessorComparator(
					orderByVisitor.getReferenceSchema(),
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
					orderByVisitor.getReferenceSchema(),
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getAscendingOrderRecordsSupplier,
					(ref1, ref2) -> true,
					(epk, referenceKey) -> referenceKey.primaryKey()
				);
			} else {
				comparator = new ReferencePredecessorComparator(
					orderByVisitor.getReferenceSchema(),
					attributeOrCompoundName,
					attributeSchema.isLocalized() ? locale : null,
					orderByVisitor,
					ChainIndex::getDescendingOrderRecordsSupplier,
					(ref1, ref2) -> true,
					(epk, referenceKey) -> referenceKey.primaryKey()
				);
			}
		} else if (attributeOrCompoundSchema instanceof SortableAttributeCompoundSchema compoundSchemaContract) {
			comparator = new ReferenceCompoundAttributeReferenceComparator(
				compoundSchemaContract,
				compoundSchemaContract.isLocalized(orderByVisitor::getAttributeSchema) ? locale : null,
				orderByVisitor::getAttributeSchema,
				orderDirection
			);
		} else if (attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema) {
			comparator = new ReferenceAttributeReferenceComparator(
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

	/**
	 * A specialized record that supplies an array of {@link SortedRecordsProvider} objects based on the
	 * configuration of attribute or compound schema and associated logic. Implements the {@link Supplier} interface
	 * to dynamically provide sorted record providers tailored to the given schema and indexing setup.
	 *
	 * This supplier evaluates the type of the schema provided and extracts the relevant {@link SortedRecordsProvider}
	 * instances from the target indexes for further operations such as sorting. It supports both plain attribute schemas
	 * and compound schemas, handling different index types such as chain indexes and sort indexes.
	 *
	 * The behavior is influenced by the specific schema type (e.g., {@link AttributeSchemaContract}), and it
	 * applies extraction and filtering logic to produce the sorted record providers. Additionally, it supports an
	 * optional locale parameter to target locale-specific indexes.
	 */
	private record AttributeSortedRecordsProviderSupplier(
		@Nonnull Function<SortIndex, SortedRecordsProvider> sortIndexExtractor,
		@Nonnull Function<ChainIndex, SortedRecordsProvider> chainIndexExtractor,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull NamedSchemaContract attributeOrCompoundSchema,
		@Nonnull EntityIndex[] targetIndex,
		@Nonnull OrderByVisitor orderByVisitor,
		@Nullable Locale locale
	) implements Supplier<SortedRecordsProvider[]> {
		private static final SortedRecordsProvider[] EMPTY_PROVIDERS = {SortedRecordsProvider.EMPTY};

		@Override
		public SortedRecordsProvider[] get() {
			final SortedRecordsProvider[] sortedRecordsProvider;
			if (this.attributeOrCompoundSchema instanceof AttributeSchemaContract attributeSchema) {
				if (Predecessor.class.equals(attributeSchema.getPlainType())) {
					sortedRecordsProvider = Arrays.stream(this.targetIndex)
						.map(it -> it.getChainIndex(this.referenceSchema, attributeSchema, this.locale))
						.filter(Objects::nonNull)
						.map(this.chainIndexExtractor)
						.toArray(SortedRecordsProvider[]::new);
				}  else if (ReferencedEntityPredecessor.class.equals(attributeSchema.getPlainType())) {
					sortedRecordsProvider = Arrays.stream(this.targetIndex)
						.map(it -> it.getChainIndex(this.referenceSchema, attributeSchema, this.locale))
						.filter(Objects::nonNull)
						.map(this.chainIndexExtractor)
						.toArray(SortedRecordsProvider[]::new);
				} else {
					sortedRecordsProvider = Arrays.stream(this.targetIndex)
						.map(it -> it.getSortIndex(this.referenceSchema, attributeSchema, this.locale))
						.filter(Objects::nonNull)
						.map(this.sortIndexExtractor)
						.toArray(SortedRecordsProvider[]::new);
				}
			} else if (this.attributeOrCompoundSchema instanceof SortableAttributeCompoundSchemaContract compoundSchema) {
				sortedRecordsProvider = Arrays.stream(this.targetIndex)
					.map(it -> it.getSortIndex(Objects.requireNonNull(this.entitySchema), this.referenceSchema, compoundSchema, this.locale))
					.filter(Objects::nonNull)
					.map(this.sortIndexExtractor)
					.toArray(SortedRecordsProvider[]::new);
			} else {
				throw new GenericEvitaInternalError(
					"Unsupported attribute schema type: " + this.attributeOrCompoundSchema.getClass()
				);
			}

			return sortedRecordsProvider.length == 0 ?
				EMPTY_PROVIDERS : sortedRecordsProvider;
		}
	}
}
