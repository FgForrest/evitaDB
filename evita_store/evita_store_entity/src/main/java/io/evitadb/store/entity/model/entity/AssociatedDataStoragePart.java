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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static io.evitadb.utils.ComparatorUtils.compareLocale;

/**
 * This container class represents single {@link AssociatedDataValue} item of the {@link Entity}.
 * Each associated data is stored on disk separately since we assume that associated data will be pretty big.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "associatedDataKey")
@EqualsAndHashCode(exclude = {"dirty", "sizeInBytes"})
public class AssociatedDataStoragePart implements EntityStoragePart, RecordWithCompressedId<EntityAssociatedDataKey> {
	@Serial private static final long serialVersionUID = -1368845012702768956L;

	/**
	 * Entity id that is necessary to compute unique part id on new container creation.
	 */
	@Getter private final Integer entityPrimaryKey;
	/**
	 * See {@link AssociatedDataValue#key()}.
	 */
	@Getter private final EntityAssociatedDataKey associatedDataKey;
	/**
	 * Contains information about size of this container in bytes.
	 */
	private final int sizeInBytes;
	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter @Nullable private Long storagePartPK;
	/**
	 * See {@link AssociatedDataValue#value()}.
	 */
	@Getter private AssociatedDataValue value;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;

	/**
	 * Computes primary ID of this container that is a long consisting of two parts:
	 * - int entity primary key
	 * - int key assigned by {@link KeyCompressor} for its {@link AssociatedDataKey}
	 */
	@Nonnull
	public static OptionalLong computeUniquePartId(@Nonnull KeyCompressor keyCompressor, @Nonnull EntityAssociatedDataKey key) {
		final OptionalInt id = keyCompressor.getIdIfExists(
			new AssociatedDataKey(
				key.associatedDataName(), key.locale()
			)
		);
		if (id.isPresent()) {
			return OptionalLong.of(
				NumberUtils.join(
					key.entityPrimaryKey(),
					id.getAsInt()
				)
			);
		} else {
			return OptionalLong.empty();
		}
	}

	public AssociatedDataStoragePart(int entityPrimaryKey, @Nonnull AssociatedDataKey associatedDataKey) {
		this.storagePartPK = null;
		this.entityPrimaryKey = entityPrimaryKey;
		this.associatedDataKey = new EntityAssociatedDataKey(entityPrimaryKey, associatedDataKey.associatedDataName(), associatedDataKey.locale());
		this.sizeInBytes = -1;
	}

	public AssociatedDataStoragePart(long storagePartPK, int entityPrimaryKey, @Nonnull AssociatedDataValue associatedDataValue, int sizeInBytes) {
		this.storagePartPK = storagePartPK;
		this.entityPrimaryKey = entityPrimaryKey;
		this.associatedDataKey = new EntityAssociatedDataKey(entityPrimaryKey, associatedDataValue.key().associatedDataName(), associatedDataValue.key().locale());
		this.value = associatedDataValue;
		this.sizeInBytes = sizeInBytes;
	}

	@Override
	public EntityAssociatedDataKey getStoragePartSourceKey() {
		return this.associatedDataKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		Assert.isTrue(this.storagePartPK == null, "Unique part id is already known!");
		Assert.notNull(this.entityPrimaryKey, "Entity primary key must be non-null!");
		this.storagePartPK = NumberUtils.join(
			this.associatedDataKey.entityPrimaryKey(),
			keyCompressor.getId(
				new AssociatedDataKey(
					this.associatedDataKey.associatedDataName(),
					this.associatedDataKey.locale()
				)
			)
		);
		return this.storagePartPK;
	}

	@Override
	public boolean isEmpty() {
		return this.value == null || this.value.dropped();
	}

	@Nonnull
	@Override
	public OptionalInt sizeInBytes() {
		return this.sizeInBytes == -1 ? OptionalInt.empty() : OptionalInt.of(this.sizeInBytes);
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
	public record EntityAssociatedDataKey(
		int entityPrimaryKey,
		@Nonnull String associatedDataName,
		@Nullable Locale locale
	) implements Serializable, Comparable<EntityAssociatedDataKey> {
		@Serial private static final long serialVersionUID = -4323213680699873995L;

		@Override
		public int compareTo(@Nonnull EntityAssociatedDataKey o) {
			final int primaryKeyComparison = Integer.compare(this.entityPrimaryKey, o.entityPrimaryKey);
			if (primaryKeyComparison == 0) {
				return compareLocale(
					this.locale, o.locale, () -> this.associatedDataName.compareTo(o.associatedDataName)
				);
			} else {
				return primaryKeyComparison;
			}
		}

	}

}
