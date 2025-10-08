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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.store.entity.model.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.store.exception.CompressionKeyUnknownException;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.model.RecordWithCompressedId;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ComparatorUtils;
import io.evitadb.utils.NumberUtils;
import lombok.Data;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.UnaryOperator;

/**
 * This container class represents set of {@link AttributeValue} item of the {@link Entity}.
 * Each entity has single container for:
 *
 * - non-localized (i.e. shared / global) attributes
 * - localized attributes for each language
 *
 * When entity is fetched in certain language - two containers are loaded from persistent storage - one for global attributes
 * and second for localized attributes in requested language.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@EqualsAndHashCode(exclude = {"dirty", "sizeInBytes"})
@ToString(of = "attributeSetKey")
public class AttributesStoragePart implements EntityStoragePart, RecordWithCompressedId<EntityAttributesSetKey> {
	private static final AttributeValue[] EMPTY_ATTRIBUTE_VALUES = new AttributeValue[0];
	@Serial private static final long serialVersionUID = 5114335323100299202L;

	/**
	 * Entity id that is necessary to compute unique part id on new container creation.
	 */
	@Getter private final Integer entityPrimaryKey;
	/**
	 * Contains key for attribute set lookup.
	 */
	@Getter private final EntityAttributesSetKey attributeSetKey;
	/**
	 * Contains information about size of this container in bytes.
	 */
	private final int sizeInBytes;
	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Nullable @Getter private Long storagePartPK;
	/**
	 * See {@link Attributes#getAttributeValues()}. Attributes are sorted in ascending order according to {@link AttributeKey}.
	 */
	@Getter private AttributeValue[] attributes = EMPTY_ATTRIBUTE_VALUES;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter private boolean dirty;

	/**
	 * Computes primary ID of this container that is a long consisting of two parts:
	 * - int entity primary key
	 * - int key assigned by {@link KeyCompressor} for its {@link AttributesSetKey}
	 *
	 * @throws CompressionKeyUnknownException when key is not recognized by {@link KeyCompressor}
	 */
	@Nonnull
	public static OptionalLong computeUniquePartId(@Nonnull KeyCompressor keyCompressor, @Nonnull EntityAttributesSetKey attributeSetKey) throws CompressionKeyUnknownException {
		final OptionalInt id = keyCompressor.getIdIfExists(
			new AttributesSetKey(attributeSetKey.locale())
		);
		if (id.isPresent()) {
			return OptionalLong.of(
				NumberUtils.join(
					attributeSetKey.entityPrimaryKey(),
					id.getAsInt()
				)
			);
		} else {
			return OptionalLong.empty();
		}
	}

	public AttributesStoragePart(int entityPrimaryKey) {
		this.storagePartPK = null;
		this.entityPrimaryKey = entityPrimaryKey;
		this.attributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, null);
		this.sizeInBytes = -1;
	}

	public AttributesStoragePart(int entityPrimaryKey, Locale locale) {
		this.storagePartPK = null;
		this.entityPrimaryKey = entityPrimaryKey;
		this.attributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, locale);
		this.sizeInBytes = -1;
	}

	public AttributesStoragePart(long storagePartPK, int entityPrimaryKey, @Nonnull Locale locale, @Nonnull AttributeValue[] attributes, int sizeInBytes) {
		this.storagePartPK = storagePartPK;
		this.entityPrimaryKey = entityPrimaryKey;
		this.attributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, locale);
		this.attributes = attributes;
		this.sizeInBytes = sizeInBytes;
	}

	@Override
	public EntityAttributesSetKey getStoragePartSourceKey() {
		return this.attributeSetKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		Assert.isTrue(this.storagePartPK == null, "Unique part id is already known!");
		Assert.notNull(this.entityPrimaryKey, "Entity primary key must be non null!");
		this.storagePartPK = NumberUtils.join(
			this.attributeSetKey.entityPrimaryKey(),
			keyCompressor.getId(
				new AttributesSetKey(this.attributeSetKey.locale())
			)
		);
		return this.storagePartPK;
	}

	/**
	 * Returns locale of the attribute set.
	 *
	 * @return NULL for global attributes
	 */
	@Nullable
	public Locale getLocale() {
		return this.attributeSetKey.locale();
	}

	/**
	 * Adds new or replaces existing attribute with new {@link AttributeValue}.
	 */
	@Nullable
	public AttributeValue findAttribute(@Nonnull AttributeKey attributeKey) {
		final int index = Arrays.binarySearch(
			this.attributes,
			AttributeValue.createEmptyComparableAttributeValue(attributeKey)
		);
		return index < 0 ? null : this.attributes[index];
	}

	/**
	 * Adds new or replaces existing attribute with new {@link AttributeValue}.
	 */
	public void upsertAttribute(
		@Nonnull AttributeKey attributeKey,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull UnaryOperator<AttributeValue> mutator
	) {
		this.attributes = ArrayUtils.insertRecordIntoOrderedArray(
			AttributeValue.createEmptyComparableAttributeValue(attributeKey),
			attributeValue -> {
				final AttributeValue mutatedAttribute = mutator.apply(attributeValue);
				final Serializable valueAlignedToSchema = EvitaDataTypes.toTargetType(mutatedAttribute.value(), attributeDefinition.getType(), attributeDefinition.getIndexedDecimalPlaces());
				final AttributeValue attributeToUpsert = valueAlignedToSchema == mutatedAttribute.value() || valueAlignedToSchema == null ?
					mutatedAttribute : new AttributeValue(mutatedAttribute, valueAlignedToSchema);
				if (attributeValue == null || attributeValue.differsFrom(mutatedAttribute)) {
					this.dirty = true;
					return attributeToUpsert;
				} else {
					return attributeValue;
				}
			},
			this.attributes
		);
	}

	@Override
	public boolean isEmpty() {
		return this.attributes.length == 0 || Arrays.stream(this.attributes).noneMatch(Droppable::exists);
	}

	@Nonnull
	@Override
	public OptionalInt sizeInBytes() {
		return this.sizeInBytes == -1 ? OptionalInt.empty() : OptionalInt.of(this.sizeInBytes);
	}

	/**
	 * This key is used to fully represent this {@link AttributesStoragePart} in the persistent storage. It needs to
	 * contain all information that uniquely distinguishes this attribute set key among attribute set keys of other
	 * entities / languages / names.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	@Immutable
	@ThreadSafe
	public record EntityAttributesSetKey(
		int entityPrimaryKey,
		@Nullable Locale locale
	) implements Serializable, Comparable<EntityAttributesSetKey> {
		@Serial private static final long serialVersionUID = -5227300829540800269L;

		@Override
		public int compareTo(@Nonnull EntityAttributesSetKey o) {
			return ComparatorUtils.compareLocale(this.locale, o.locale, () -> Integer.compare(this.entityPrimaryKey, o.entityPrimaryKey));
		}

	}

	/**
	 * This key is registered in {@link KeyCompressor} to retrieve id that is part of the {@link AttributesStoragePart#getStoragePartPK()}.
	 * Key can be shared among attribute sets of different entities, but is single for all global attributes and single
	 * for attribute sets in certain language. Together with entityPrimaryKey composes part id unique among all other
	 * attribute set part types.
	 */
	@Data
	@Immutable
	@ThreadSafe
	public static class AttributesSetKey implements Serializable, Comparable<AttributesSetKey> {
		@Serial private static final long serialVersionUID = 5780158824881799432L;
		private final Locale locale;

		@Override
		public int compareTo(@Nonnull AttributesSetKey o) {
			return ComparatorUtils.compareLocale(this.locale, o.locale, () -> 0);
		}

	}
}
