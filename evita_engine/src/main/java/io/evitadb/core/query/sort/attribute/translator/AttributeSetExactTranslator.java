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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.order.AttributeSetExact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.AttributeExactSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.reference.sorter.VirtualPartitionSortedRecords;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link AttributeSetExact} to {@link Sorter}.
 * A traverse exact ordering reads only the current block of partitions, choosing each owner's first valued row
 * before looking up listed values. Other orderings retain the single-index contract of {@link #getRecordsEqualTo}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeSetExactTranslator implements OrderingConstraintTranslator<AttributeSetExact> {

	/**
	 * Plans a block-aware exact sorter for a traverse, retaining the single-index lookup elsewhere.
	 * Chain attributes are refused before values are converted or either execution route is prepared.
	 *
	 * @param attributeSetExact the exact value ordering
	 * @param orderByVisitor    the ordering context
	 * @return the exact sorter, or no sorter when the single-index route has no partitions
	 * @throws EvitaInvalidUsageException when the attribute is a {@link Predecessor} or
	 *                                    {@link ReferencedEntityPredecessor} chain, which orders by predecessor links
	 *                                    and has no values to list
	 */
	@Nonnull
	@Override
	public Stream<Sorter> createSorter(
		@Nonnull AttributeSetExact attributeSetExact,
		@Nonnull OrderByVisitor orderByVisitor
	) {
		final String attributeName = attributeSetExact.getAttributeName();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeSchemaContract attributeSchema = processingScope.getAttributeSchema(
			attributeName, AttributeTrait.SORTABLE
		);
		final Class<? extends Serializable> attributeType = attributeSchema.getPlainType();
		if (Predecessor.class.equals(attributeType) || ReferencedEntityPredecessor.class.equals(attributeType)) {
			throw new EvitaInvalidUsageException(
				"Constraint `attributeSetExact` cannot order chain attribute `" + attributeName + "` of type `" +
					attributeType.getName() + "`: a chain orders by predecessor links, so its values cannot be " +
					"listed. Use `attributeNatural` to order by the chain instead; inside `referenceProperty` it " +
					"needs `traverseByEntityProperty` or no explicit traversal, not `pickFirstByEntityProperty`."
			);
		}

		final Serializable[] exactOrder = Arrays.stream(attributeSetExact.getAttributeValues())
			.map(it -> EvitaDataTypes.toTargetType(it, attributeType))
			.toArray(Serializable[]::new);
		final ReferenceSchema referenceSchema = processingScope.referenceSchema();
		if (
			referenceSchema != null && processingScope.mergeModeDefinition() != null &&
				processingScope.mergeModeDefinition().mergeMode() == MergeMode.APPEND_ALL
		) {
			// fetch the sorted value and the attributes needed to choose among duplicate reference rows
			final String[] attributes = Stream.concat(
				Stream.of(attributeName),
				referenceSchema.getRepresentativeAttributeDefinition().getAttributeNames().stream()
			).toArray(String[]::new);
			orderByVisitor.addRequirementToPrefetch(
				referenceContentWithAttributes(referenceSchema.getName(), attributes)
			);
			return Stream.of(
				new AttributeExactSorter(
					attributeName, exactOrder, referenceSchema, attributeSchema, orderByVisitor.getLocale(),
					orderByVisitor.getPartitionsForSort()
				)
			);
		}

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeContentAll());

		final RecordsEqualTo recordsEqualTo = getRecordsEqualTo(orderByVisitor, attributeSchema, "");
		if (recordsEqualTo == null) {
			return Stream.empty();
		}
		return Stream.of(
			new AttributeExactSorter(
				attributeName,
				exactOrder,
				recordsEqualTo.lookup(),
				recordsEqualTo.scope()
			)
		);
	}

	/**
	 * Returns the single-index lookup used outside exact traversal ordering and by `attributeSetInFilter`.
	 * It translates an attribute value to the primary keys of the entities carrying it: the attribute sort index for
	 * indexed entities and a materialized reference partition, the registry row for a virtual reference partition (see
	 * {@link VirtualPartitionSortedRecords#getRecordsEqualTo}) - `O(log n)` per value, never a walk of the registry's
	 * sort index of the key.
	 *
	 * @param orderByVisitor  the visitor of the planned query
	 * @param attributeSchema the attribute the sorter sorts along
	 * @param hint            appended to the message when there is not exactly one index (partition)
	 * @return the lookup and its scope, or `null` when the ordered scope has no index or partition
	 */
	@Nullable
	static RecordsEqualTo getRecordsEqualTo(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull String hint
	) {
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final ReferenceSchema referenceSchema = processingScope.referenceSchema();
		final Locale locale = orderByVisitor.getLocale();
		if (referenceSchema == null) {
			final EntityIndex[] indexForSort = orderByVisitor.getIndexesForSort();
			if (indexForSort.length == 0) {
				return null;
			}
			Assert.isTrue(
				indexForSort.length == 1,
				"Expected exactly one index for sorting, but " + indexForSort.length + " were found." + hint
			);
			return new RecordsEqualTo(
				indexForSort[0].getIndexKey().scope(),
				getRecordsEqualToOfIndex(indexForSort[0], null, attributeSchema, locale)
			);
		} else {
			final ReferencePartition[] partitionsForSort = orderByVisitor.getPartitionsForSort();
			if (partitionsForSort.length == 0) {
				return null;
			}
			Assert.isTrue(
				partitionsForSort.length == 1,
				"Expected exactly one index for sorting, but " + partitionsForSort.length + " were found." + hint
			);
			final Function<Serializable, Bitmap> lookup = switch (partitionsForSort[0]) {
				case ReducedEntityIndex reducedEntityIndex ->
					getRecordsEqualToOfIndex(reducedEntityIndex, referenceSchema, attributeSchema, locale);
				case VirtualReferencePartition virtualPartition -> {
					final AttributeIndexKey key = AttributeIndex.createAttributeKey(
						referenceSchema, attributeSchema, locale
					);
					yield value -> VirtualPartitionSortedRecords.getRecordsEqualTo(virtualPartition, key, value);
				}
			};
			return new RecordsEqualTo(partitionsForSort[0].getIndexKey().scope(), lookup);
		}
	}

	/**
	 * Returns the lookup of the sort index of the attribute the index keeps.
	 *
	 * @param index           the index of the sorted entities or a materialized reference partition
	 * @param referenceSchema the reference the attribute belongs to, `null` for an entity attribute
	 * @param attributeSchema the attribute the sorter sorts along
	 * @param locale          the locale of the ordering
	 * @return the lookup
	 */
	@Nonnull
	private static Function<Serializable, Bitmap> getRecordsEqualToOfIndex(
		@Nonnull EntityIndex index,
		@Nullable ReferenceSchema referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		final SortIndex sortIndex = index.getSortIndex(referenceSchema, attributeSchema, locale);
		// a lambda rather than a method reference keeps a missing sort index failing where it always failed - when
		// the sorter reads it
		return value -> sortIndex.getRecordsEqualTo(value);
	}

	/**
	 * The value lookup of the only index or partition an exact ordering sorts by, with its scope so the prefetch
	 * comparator admits exactly the same entities.
	 *
	 * @param scope  the scope of the sort data
	 * @param lookup translates a value to the entities carrying it
	 */
	record RecordsEqualTo(@Nonnull Scope scope, @Nonnull Function<Serializable, Bitmap> lookup) {
	}

}
