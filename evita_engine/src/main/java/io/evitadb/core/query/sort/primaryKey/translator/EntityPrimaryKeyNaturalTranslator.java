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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.primaryKey.translator;

import io.evitadb.api.query.order.EntityPrimaryKeyNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.comparator.IntComparator;
import io.evitadb.comparator.IntComparator.IntAscendingComparator;
import io.evitadb.comparator.IntComparator.IntDescendingComparator;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.primaryKey.ReversedSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link EntityPrimaryKeyNatural} to {@link Sorter}.
 * It allows to sort entities based on their primary key or primary key of referenced entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityPrimaryKeyNaturalTranslator implements OrderingConstraintTranslator<EntityPrimaryKeyNatural> {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull EntityPrimaryKeyNatural entityPrimaryKeyNatural, @Nonnull OrderByVisitor orderByVisitor) {
		final OrderDirection orderDirection = entityPrimaryKeyNatural.getOrderDirection();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final ReferenceSchemaContract referenceSchema = processingScope.referenceSchema();
		if (referenceSchema == null) {
			return orderDirection == OrderDirection.DESC ? Stream.of(ReversedSorter.INSTANCE) : Stream.empty();
		} else {
			final EntityIndex[] entityIndices = processingScope.entityIndex();
			final TransactionalBitmap[] pkBitmaps = new TransactionalBitmap[entityIndices.length];
			if (orderDirection == OrderDirection.DESC) {
				for (int i = 0; i < entityIndices.length; i++) {
					pkBitmaps[i] = getAsTransactionalBitmap(
						entityIndices[entityIndices.length - i - 1].getAllPrimaryKeys()
					);
				}
			} else {
				for (int i = 0; i < entityIndices.length; i++) {
					pkBitmaps[i] = getAsTransactionalBitmap(
						entityIndices[i].getAllPrimaryKeys()
					);
				}
			}

			final String referenceSchemaName = referenceSchema.getName();
			return Stream.of(
				new PreSortedRecordsSorter(
					() -> Arrays.stream(pkBitmaps)
						.filter(Objects::nonNull)
						.map(
							bitmap -> new SortedRecordsSupplier(
								bitmap.getId(), bitmap.getArray(), createPositionArray(bitmap.size()), bitmap
							)
						)
						.toArray(SortedRecordsSupplier[]::new)
				),
				new PrefetchedRecordsSorter(
					new ReferencePrimaryKeyEntityComparator(
						referenceSchemaName,
						orderDirection == OrderDirection.DESC ?
							IntDescendingComparator.INSTANCE : IntAscendingComparator.INSTANCE
					)
				)
			);
		}
	}

	/**
	 * Returns the given bitmap as a {@link TransactionalBitmap} or null if the bitmap is empty.
	 *
	 * @param bitmap the bitmap to be converted
	 * @return the given bitmap as a {@link TransactionalBitmap} or null if the bitmap is empty
	 */
	@Nullable
	private static TransactionalBitmap getAsTransactionalBitmap(@Nonnull Bitmap bitmap) {
		if (bitmap instanceof TransactionalBitmap txBitmap) {
			return txBitmap;
		} else if (bitmap instanceof EmptyBitmap) {
			return null;
		} else {
			throw new EvitaInternalError(
				"Unexpected bitmap type: " + bitmap.getClass().getName(),
				"Unexpected bitmap type!"
			);
		}
	}

	/**
	 * Creates an array of positions from 0 to size - 1.
	 *
	 * @param size the size of the array
	 * @return an array of positions from 0 to size - 1
	 */
	private static int[] createPositionArray(int size) {
		final int[] result = new int[size];
		for (int i = 0; i < size; i++) {
			result[i] = i;
		}
		return result;
	}

	/**
	 * A comparator that compares entities based on the primary key of the referenced entity.
	 */
	@RequiredArgsConstructor
	private static class ReferencePrimaryKeyEntityComparator implements EntityComparator, Serializable {
		@Serial private static final long serialVersionUID = -7648386897749667944L;
		@Nonnull private final String referenceSchemaName;
		@Nonnull private final IntComparator comparator;
		private CompositeObjectArray<EntityContract> nonSortedEntities;

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return nonSortedEntities;
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			final Collection<ReferenceContract> o1References = o1.getReferences(referenceSchemaName);
			final Collection<ReferenceContract> o2References = o2.getReferences(referenceSchemaName);
			if (o1References.isEmpty() && o2References.isEmpty()) {
				this.nonSortedEntities = getOrCreatedNonSortedEntitiesCollector();
				this.nonSortedEntities.add(o1);
				this.nonSortedEntities.add(o2);
				return 0;
			} else if (o1References.isEmpty()) {
				this.nonSortedEntities = getOrCreatedNonSortedEntitiesCollector();
				this.nonSortedEntities.add(o1);
				return 1;
			} else if (o2References.isEmpty()) {
				this.nonSortedEntities = getOrCreatedNonSortedEntitiesCollector();
				this.nonSortedEntities.add(o2);
				return -1;
			} else {
				return comparator.compare(
					o1References.iterator().next().getReferencedPrimaryKey(),
					o2References.iterator().next().getReferencedPrimaryKey()
				);
			}
		}

		@Nonnull
		private CompositeObjectArray<EntityContract> getOrCreatedNonSortedEntitiesCollector() {
			return ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
		}
	}

}
