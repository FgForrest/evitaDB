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

package io.evitadb.store.entity.model.entity;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart.EntityAssociatedDataKey;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.model.RecordWithCompressedId;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

import static io.evitadb.utils.ComparatorUtils.compareLocale;

/**
 * This container class represents single {@link AssociatedDataValue} item of the {@link Entity}.
 * Each associated data is stored on disk separately since we assume that associated data will be pretty big.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "associatedDataKey")
public class AssociatedDataStoragePart implements EntityStoragePart, RecordWithCompressedId<EntityAssociatedDataKey> {
	@Serial private static final long serialVersionUID = -1368845012702768956L;

	/**
	 * Entity id that is necessary to compute unique part id on new container creation.
	 */
	@Getter private final Integer entityPrimaryKey;
	/**
	 * See {@link AssociatedDataValue#getKey()}.
	 */
	private final EntityAssociatedDataKey associatedDataKey;
	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter private Long uniquePartId;
	/**
	 * See {@link AssociatedDataValue#getValue()}.
	 */
	@Getter private AssociatedDataValue value;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;

	public AssociatedDataStoragePart(int entityPrimaryKey, @Nonnull AssociatedDataKey associatedDataKey) {
		this.uniquePartId = null;
		this.entityPrimaryKey = entityPrimaryKey;
		this.associatedDataKey = new EntityAssociatedDataKey(entityPrimaryKey, associatedDataKey.getAssociatedDataName(), associatedDataKey.getLocale());
	}

	public AssociatedDataStoragePart(long uniquePartId, int entityPrimaryKey, @Nonnull AssociatedDataValue associatedDataValue) {
		this.uniquePartId = uniquePartId;
		this.entityPrimaryKey = entityPrimaryKey;
		this.associatedDataKey = new EntityAssociatedDataKey(entityPrimaryKey, associatedDataValue.getKey().getAssociatedDataName(), associatedDataValue.getKey().getLocale());
		this.value = associatedDataValue;
	}

	/**
	 * Computes primary ID of this container that is a long consisting of two parts:
	 * - int entity primary key
	 * - int key assigned by {@link KeyCompressor} for its {@link AssociatedDataKey}
	 */
	public static long computeUniquePartId(@Nonnull KeyCompressor keyCompressor, @Nonnull EntityAssociatedDataKey key) {
		return NumberUtils.join(
			key.getEntityPrimaryKey(),
			keyCompressor.getId(
				new AssociatedDataKey(
					key.getAssociatedDataName(), key.getLocale()
				)
			)
		);
	}

	@Override
	public EntityAssociatedDataKey getStoragePartSourceKey() {
		return associatedDataKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		Assert.isTrue(this.uniquePartId == null, "Unique part id is already known!");
		Assert.notNull(entityPrimaryKey, "Entity primary key must be non-null!");
		this.uniquePartId = AssociatedDataStoragePart.computeUniquePartId(keyCompressor, associatedDataKey);
		return this.uniquePartId;
	}

	@Override
	public boolean isEmpty() {
		return value == null || value.isDropped();
	}

	/**
	 * Replaces existing value with different content.
	 */
	public void replaceAssociatedData(AssociatedDataValue newValue) {
		if ((this.value == null && newValue != null) || (this.value != null && this.value.differsFrom(newValue))) {
			this.value = newValue;
			this.dirty = true;
		}
	}

	/**
	 * This key is used to fully represent this {@link AssociatedDataStoragePart} in the persistent storage.
	 * It needs to contain all information that uniquely distinguishes this associated data key among associated data
	 * keys of other entities / languages / names.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	@Immutable
	@ThreadSafe
	@Data
	public static class EntityAssociatedDataKey implements Serializable, Comparable<EntityAssociatedDataKey> {
		@Serial private static final long serialVersionUID = -4323213680699873995L;

		private final int entityPrimaryKey;
		private final String associatedDataName;
		private final Locale locale;

		@Override
		public int compareTo(@Nonnull EntityAssociatedDataKey o) {
			final int primaryKeyComparison = Integer.compare(entityPrimaryKey, o.entityPrimaryKey);
			if (primaryKeyComparison == 0) {
				return compareLocale(
					locale, o.locale, () -> associatedDataName.compareTo(o.associatedDataName)
				);
			} else {
				return primaryKeyComparison;
			}
		}

	}

}
