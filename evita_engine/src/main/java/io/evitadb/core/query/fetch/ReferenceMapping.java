/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.fetch;


import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.collection.IntegerIntoBitmapCollector;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This DTO envelopes cached access to referenced entity group primary key to referenced entity primary keys mapping.
 * This mapping is used in {@link EntityNestedQueryComparator#setFilteredEntities(int[], int[], Function)} method
 * when the references are sorted first by group entity and then by referenced entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ReferenceMapping {
	/**
	 * This index contains mapping: referenceName -> groupId -> array of referencedEntityPrimaryKeys
	 */
	private final Map<String, Map<Integer, int[]>> referenceGroupToReferencedEntitiesIndex;
	/**
	 * This function is used for lazy computation of the `group -> array of referencedEntityPrimaryKeys` mapping
	 * when the mapping is not yet present in the index for certain reference name.
	 */
	private final Function<String, Map<Integer, int[]>> groupToReferencedEntityLazyRetriever;
	/**
	 * This index contains mapping: referenceKey -> groupId
	 */
	private final Map<ReferenceKey, GroupMapping> referenceReferencedEntitiesToGroupIndex;
	/**
	 * This set contains keys of all reference names for which the results in `referenceReferencedEntitiesToGroupIndex`
	 * are present.
	 */
	private final Set<String> referenceReferencedEntitiesToGroupCalculationIndex;
	/**
	 * This function is used for lazy computation of the `referenceKey -> groupId` mapping
	 * when the mapping is not yet present in the index for a certain reference name.
	 */
	private final BiConsumer<String, Map<ReferenceKey, GroupMapping>> referenceReferencedEntitiesToGroupLazyRetriever;

	public ReferenceMapping(int expectedSize, @Nonnull List<? extends SealedEntity> richEnoughEntities) {
		this.referenceGroupToReferencedEntitiesIndex = CollectionUtils.createHashMap(expectedSize);
		this.groupToReferencedEntityLazyRetriever = referenceName -> richEnoughEntities
			.stream()
			.flatMap(it -> it.getReferences(referenceName).stream())
			.filter(it -> it.getGroup().isPresent())
			.collect(
				Collectors.groupingBy(
					it -> it.getGroup().get().getPrimaryKey(),
					Collectors.mapping(
						ReferenceContract::getReferencedPrimaryKey,
						Collectors.collectingAndThen(
							IntegerIntoBitmapCollector.INSTANCE,
							Bitmap::getArray
						)
					)
				)
			);
		this.referenceReferencedEntitiesToGroupIndex = CollectionUtils.createHashMap(expectedSize * 5);
		this.referenceReferencedEntitiesToGroupCalculationIndex = new HashSet<>(5);
		this.referenceReferencedEntitiesToGroupLazyRetriever = (referenceName, container) -> {
			for (SealedEntity richEnoughEntity : richEnoughEntities) {
				// we can safely cast here, because we work only with server side entities
				final EntityDecorator entityDecorator = (EntityDecorator) richEnoughEntity;
				// we need to skip predicate, because named reference content is not considered a predicate
				for (ReferenceContract reference : entityDecorator.getReferencesWithoutCheckingPredicate(referenceName)) {
					if (reference.getGroup().isPresent()) {
						container.compute(
							reference.getReferenceKey(),
							(referenceKey, existingValue) -> {
								final int epk = richEnoughEntity.getPrimaryKeyOrThrowException();
								final int groupPrimaryKey = reference.getGroup()
									.map(EntityClassifier::getPrimaryKeyOrThrowException)
									.orElseThrow();
								if (existingValue == null) {
									return new GroupMapping(epk, groupPrimaryKey, expectedSize);
								} else {
									existingValue.addMapping(epk, groupPrimaryKey);
									return existingValue;
								}
							}
						);
					}
				}
			}
		};
	}

	/**
	 * Retrieves the group identifier for a given reference name and referenced primary key.
	 * If the group mapping for the specified reference name has not been computed yet, it is lazily computed
	 * using the corresponding loader. This method relies on an internal index to fetch the group identifier.
	 *
	 * @param referenceName        the name of the reference for which the group identifier is being retrieved; must not be null
	 * @param referencedPrimaryKey the primary key of the referenced entity; must not be null
	 * @return the group identifier for the provided reference name and primary key, or {@code null} if no group mapping exists
	 */
	@Nullable
	public IntStream getGroup(
		int entityPrimaryKey, @Nonnull String referenceName, @Nonnull Integer referencedPrimaryKey) {
		if (!this.referenceReferencedEntitiesToGroupCalculationIndex.contains(referenceName)) {
			this.referenceReferencedEntitiesToGroupLazyRetriever.accept(
				referenceName, this.referenceReferencedEntitiesToGroupIndex
			);
			this.referenceReferencedEntitiesToGroupCalculationIndex.add(referenceName);
		}
		final GroupMapping groupMapping = this.referenceReferencedEntitiesToGroupIndex.get(
			new ReferenceKey(referenceName, referencedPrimaryKey));
		return groupMapping == null ? IntStream.empty() : groupMapping.getGroupId(entityPrimaryKey);
	}

	/**
	 * Returns (and lazily computes) an array of referenced entity primary keys for passed `groupEntityPrimaryKey` of
	 * group entity.
	 *
	 * @param referenceName         name of the reference
	 * @param groupEntityPrimaryKey primary key of the group entity
	 * @return array of referenced entity primary keys
	 */
	@Nonnull
	public int[] getReferencedEntityPrimaryKeys(@Nonnull String referenceName, int groupEntityPrimaryKey) {
		final Map<Integer, int[]> mapping = this.referenceGroupToReferencedEntitiesIndex.computeIfAbsent(
			referenceName, this.groupToReferencedEntityLazyRetriever);
		final int[] referencedEntityPrimaryKeys = mapping.get(groupEntityPrimaryKey);
		return referencedEntityPrimaryKeys == null ? ArrayUtils.EMPTY_INT_ARRAY : referencedEntityPrimaryKeys;
	}

}
