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

import io.evitadb.api.query.order.AttributeSetExact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.VirtualRowConsumer;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.AttributeExactSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.core.query.sort.reference.sorter.VirtualPartitionSortedRecords;
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

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link AttributeSetExact} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeSetExactTranslator implements OrderingConstraintTranslator<AttributeSetExact> {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull AttributeSetExact attributeSetExact, @Nonnull OrderByVisitor orderByVisitor) {
		final String attributeName = attributeSetExact.getAttributeName();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeSchemaContract attributeSchema = processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE);

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeContentAll());

		return Stream.of(
			new AttributeExactSorter(
				attributeName,
				Arrays.stream(attributeSetExact.getAttributeValues())
					.map(it -> EvitaDataTypes.toTargetType(it, attributeSchema.getPlainType()))
					.toArray(Serializable[]::new),
				getRecordsEqualTo(orderByVisitor, attributeSchema, "")
			)
		);
	}

	/**
	 * Returns the lookup translating an attribute value to the primary keys of the entities carrying it, from the only
	 * index (partition) the ordering sorts by: the sort index of the attribute for the indexed entities and for a
	 * materialized reference partition, the registry row for a virtual reference partition (see
	 * {@link VirtualPartitionSortedRecords#getRecordsEqualTo}) - `O(log n)` per value, never a walk of the registry's
	 * sort index of the key.
	 *
	 * @param orderByVisitor  the visitor of the planned query
	 * @param attributeSchema the attribute the sorter sorts along
	 * @param hint            appended to the message when there is not exactly one index (partition)
	 * @return the lookup
	 */
	@Nonnull
	static Function<Serializable, Bitmap> getRecordsEqualTo(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull String hint
	) {
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final ReferenceSchema referenceSchema = processingScope.referenceSchema();
		final Locale locale = orderByVisitor.getLocale();
		if (referenceSchema == null) {
			final EntityIndex[] indexForSort = orderByVisitor.getIndexesForSort();
			Assert.isTrue(
				indexForSort.length == 1,
				"Expected exactly one index for sorting, but " + indexForSort.length + " were found." + hint
			);
			return getRecordsEqualToOfIndex(indexForSort[0], null, attributeSchema, locale);
		} else {
			final ReferencePartition[] partitionsForSort = orderByVisitor.getPartitionsForSort();
			Assert.isTrue(
				partitionsForSort.length == 1,
				"Expected exactly one index for sorting, but " + partitionsForSort.length + " were found." + hint
			);
			return switch (partitionsForSort[0]) {
				case ReducedEntityIndex reducedEntityIndex ->
					getRecordsEqualToOfIndex(reducedEntityIndex, referenceSchema, attributeSchema, locale);
				case VirtualReferencePartition virtualPartition -> {
					// TODO JNO - remove at the end of #1615 (the non-vacuity probes of the read parity tests)
					orderByVisitor.recordVirtualRowsRead(1);
					orderByVisitor.recordVirtualRowsConsumed(VirtualRowConsumer.BLOCK_EXACT_SET_LOOKUP, 1);
					final AttributeIndexKey key = AttributeIndex.createAttributeKey(
						referenceSchema, attributeSchema, locale
					);
					yield value -> VirtualPartitionSortedRecords.getRecordsEqualTo(virtualPartition, key, value);
				}
			};
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

}
