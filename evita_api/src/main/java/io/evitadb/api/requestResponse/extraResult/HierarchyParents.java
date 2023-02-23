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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.query.require.HierarchyParentsOfSelf;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This DTO class contains information about full parent paths of hierarchical entities the requested entity is referencing.
 * Information can is usually used when rendering breadcrumb path for the entity.
 *
 * Instance of this class is returned in {@link EvitaResponse#getExtraResult(Class)} when
 * {@link HierarchyParentsOfSelf} require query is used in the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@ThreadSafe
public class HierarchyParents implements EvitaResponseExtraResult {
	@Serial private static final long serialVersionUID = -1205579626554971896L;
	/**
	 * Contains direct parents of the hierarchical entity - i.e. when we need to resolve parents of the very same entity
	 * type we query for.
	 */
	private final ParentsByReference selfParents;
	/**
	 * Contains resolved parents information indexed by `referenceName`.
	 */
	private final Map<String, ParentsByReference> parentsIndex;

	/**
	 * Returns DTO that contains information of parents of queried entity type (self).
	 * When `category` entity is queried this method allows to return its parents in hierarchy tree.
	 */
	@Nullable
	public ParentsByReference ofSelf() {
		return selfParents;
	}

	/**
	 * Returns DTO that contains information of parents of particular referenced entity type.
	 * When `product` entity is related to hierarchical entity `category`, calling this method when requesting products
	 * will provide information about category paths for each product.
	 */
	@Nullable
	public ParentsByReference ofType(@Nonnull String referenceName) {
		return parentsIndex.get(referenceName);
	}

	/**
	 * Returns unmodifiable map of all parents.
	 */
	@Nonnull
	public Map<String, ParentsByReference> getParents() {
		return Collections.unmodifiableMap(parentsIndex);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		HierarchyParents hierarchyParents = (HierarchyParents) o;
		return Objects.equals(selfParents, hierarchyParents.selfParents) &&
			parentsIndex.equals(hierarchyParents.parentsIndex);
	}

	@Override
	public int hashCode() {
		return Objects.hash(selfParents, parentsIndex);
	}

	@Override
	public String toString() {
		return ofNullable(selfParents).map(it -> "Self parents: " + selfParents + "\n").orElse("") +
			parentsIndex.entrySet()
				.stream()
				.map(Object::toString)
				.collect(Collectors.joining("\n"));
	}

	/**
	 * This DTO contains information about ancestor paths for related hierarchy entity.
	 */
	public static class ParentsByReference implements Serializable {
		@Serial private static final long serialVersionUID = -2392137889006961711L;
		private static final Integer[] EMPTY_INTEGERS = new Integer[0];
		/**
		 * Contains the reference name to be used.
		 * The name is null when parents relate to the queried entity type (self).
		 */
		@Nullable
		private final String referenceName;
		/**
		 * Contains index of entity PKs (key) related to map of parent chains. There may be multiple chains if the entity
		 * is related to multiple hierarchical entities of the same type.
		 */
		@Nonnull
		private final Map<Integer, Map<Integer, EntityClassifier[]>> parentsForEntity;

		public ParentsByReference(@Nonnull Map<Integer, Map<Integer, EntityClassifier[]>> parentsForEntity) {
			this.referenceName = null;
			this.parentsForEntity = parentsForEntity;
		}

		public ParentsByReference(@Nullable String referenceName, @Nonnull Map<Integer, Map<Integer, EntityClassifier[]>> parentsForEntity) {
			this.referenceName = referenceName;
			this.parentsForEntity = parentsForEntity;
		}

		/**
		 * Returns entities/primary keys of the all parents of entity with passed primary key.
		 * If product `Red gloves (id=5)` is referencing category `Gloves (id=87)`, that has parent category `Winter (id=124)`,
		 * that also has parent category `Clothes (id=45)`, then calling `getParentsFor(5)` would return array of integers:
		 * [45, 124, 87]
		 *
		 * Response represents entire category hierarchy path from root down through its parent-child chain
		 * to the referenced entity.
		 *
		 * @throws IllegalArgumentException when the entity relates to two or more entities of this type (eg. is referencing
		 *                                  two or more categories in our example), if it's possible use method {@link #getParentsFor(int, int)} instead
		 */
		@Nullable
		public EntityClassifier[] getParentsFor(int primaryKey) throws IllegalArgumentException {
			final Map<Integer, EntityClassifier[]> result = parentsForEntity.get(primaryKey);
			if ((result == null) || result.isEmpty()) {
				return null;
			} else if (result.size() == 1) {
				return result.values().iterator().next();
			} else {
				throw new EvitaInvalidUsageException("There are " + result.size() + " relations for entity type " + referenceName + " with id " + primaryKey + "!");
			}
		}

		/**
		 * Returns primary keys of all referenced entities passed entity id is related to. This method is handy for using
		 * method {@link #getParentsFor(int, int)} when we need to find out which referenced id should be requested.
		 */
		@Nullable
		public Integer[] getReferencedEntityIds(int primaryKey) {
			return ofNullable(parentsForEntity.get(primaryKey))
				.map(it -> it.keySet().toArray(EMPTY_INTEGERS))
				.orElse(null);
		}

		/**
		 * Returns entities/primary keys of the all parents of entity with combination passed primary key and referenced entity primary key.
		 * If product `Red gloves (id=5)` is referencing category `Gloves (id=87)`, that has parent category `Winter (id=124)`,
		 * that also has parent category `Clothes (id=45)`, then calling `getParentsFor(5, 87)` would return array of integers:
		 * [45, 124, 87]
		 *
		 * Response represents entire category hierarchy path from root down through its parent-child chain
		 * to the referenced entity.
		 */
		@Nullable
		public EntityClassifier[] getParentsFor(int primaryKey, int referencedId) {
			return ofNullable(parentsForEntity.get(primaryKey))
				.map(it -> it.get(referencedId))
				.orElse(null);
		}

		/**
		 * Returns unmodifiable map of parents for this entity type
		 */
		@Nonnull
		public Map<Integer, Map<Integer, EntityClassifier[]>> getParents() {
			return Collections.unmodifiableMap(parentsForEntity);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ParentsByReference that = (ParentsByReference) o;
			return Objects.equals(referenceName, that.referenceName) &&
				parentsForEntity.size() == that.parentsForEntity.size() &&
				parentsForEntity
					.entrySet()
					.stream()
						.allMatch(it -> {
							final Map<Integer, ?> thatValue = that.parentsForEntity.get(it.getKey());
							if (thatValue == null) {
								return false;
							} else {
								return it.getValue().size() == thatValue.size() &&
									it.getValue().entrySet()
										.stream()
										.allMatch(
											item -> {
												final Object[] thatArray = (Object[]) thatValue.get(item.getKey());
												return Arrays.equals(item.getValue(), thatArray);
											}
										);
							}
						});
		}

		@Override
		public int hashCode() {
			return Objects.hash(referenceName, parentsForEntity);
		}

		@Override
		public String toString() {
			return "Parents of `" + referenceName + "` reference: " +
				parentsForEntity.entrySet()
					.stream()
					.map(it ->
						it.getKey() + ": " +
							it.getValue().entrySet()
								.stream()
								.map(x -> x.getKey() + "->" + Arrays.toString(x.getValue()))
								.collect(Collectors.joining(", "))
					)
					.collect(Collectors.joining("\n"));
		}

	}

}
