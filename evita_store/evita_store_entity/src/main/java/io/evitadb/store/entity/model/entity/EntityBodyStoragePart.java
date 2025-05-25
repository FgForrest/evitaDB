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
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.dataType.Scope;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.service.KeyCompressor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This container class represents single {@link Entity} and contains all data necessary to fetch other entity data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@EqualsAndHashCode(exclude = {"dirty", "initialRevision", "sizeInBytes"})
@ToString(exclude = {"dirty", "initialRevision"})
public class EntityBodyStoragePart implements EntityStoragePart {
	@Serial private static final long serialVersionUID = -4270700716957317392L;
	/**
	 * See {@link Entity#getPrimaryKey()}.
	 */
	@Getter private final int primaryKey;
	/**
	 * See {@link Entity#getAttributeLocales()}.
	 */
	@Nonnull private final Set<Locale> attributeLocales;
	/**
	 * Contains TRUE if the container was not stored yet.
	 */
	private final boolean initialRevision;
	/**
	 * Contains set of all associated data keys that are used in this entity.
	 */
	@Getter @Nonnull private final Set<AssociatedDataKey> associatedDataKeys;
	/**
	 * See {@link Entity#version()}.
	 */
	private int version;
	/**
	 * See {@link Entity#getScope()}.
	 */
	@Getter private Scope scope;
	/**
	 * See {@link Entity#getParent()}.
	 */
	@Getter @Nullable private Integer parent;
	/**
	 * See {@link Entity#getLocales()}.
	 */
	@Nonnull private Set<Locale> locales;
	/**
	 * Contains information about size of this container in bytes.
	 */
	private final int sizeInBytes;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter @Setter private boolean dirty;
	/**
	 * If set to TRUE the storage part should be removed.
	 */
	@Getter
	private boolean markedForRemoval;

	public EntityBodyStoragePart(int primaryKey) {
		this.primaryKey = primaryKey;
		this.scope = Scope.LIVE;
		this.locales = new LinkedHashSet<>();
		this.attributeLocales = new LinkedHashSet<>();
		this.associatedDataKeys = new LinkedHashSet<>();
		this.dirty = true;
		this.initialRevision = true;
		this.sizeInBytes = -1;
	}

	public EntityBodyStoragePart(
		int version,
		@Nonnull Integer primaryKey,
		@Nonnull Scope scope,
		@Nullable Integer parent,
		@Nonnull Set<Locale> locales,
		@Nonnull Set<Locale> attributeLocales,
		@Nonnull Set<AssociatedDataKey> associatedDataKeys,
		int sizeInBytes
	) {
		this.version = version;
		this.primaryKey = primaryKey;
		this.scope = scope;
		this.parent = parent;
		this.locales = locales;
		this.attributeLocales = attributeLocales;
		this.associatedDataKeys = associatedDataKeys;
		this.initialRevision = false;
		this.sizeInBytes = sizeInBytes;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) this.primaryKey;
	}

	@Override
	public boolean isNew() {
		return this.initialRevision;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.primaryKey;
	}

	@Override
	public boolean isEmpty() {
		return this.markedForRemoval;
	}

	@Nonnull
	@Override
	public OptionalInt sizeInBytes() {
		return this.sizeInBytes == -1 ? OptionalInt.empty() : OptionalInt.of(this.sizeInBytes);
	}

	/**
	 * Sets the scope of the entity to the provided value.
	 * If the new scope differs from the current one, marks the entity as dirty.
	 *
	 * @param newScope the new scope to be set for the entity, must be non-null
	 */
	public void setScope(@Nonnull Scope newScope) {
		if (this.scope != newScope) {
			this.scope = newScope;
			this.dirty = true;
		}
	}

	/**
	 * Sets the parent identifier for this entity.
	 * If the new parent is different from the current one,
	 * this method also marks the entity as dirty, indicating that
	 * it has been modified and needs to be persisted.
	 *
	 * @param parent the new parent identifier, which could be null to indicate no parent.
	 */
	public void setParent(@Nullable Integer parent) {
		if ((this.parent == null && parent != null) || (this.parent != null && !Objects.equals(this.parent, parent))) {
			this.parent = parent;
			this.dirty = true;
		}
	}

	/**
	 * Returns version of the entity for storing (incremented by one, if anything changed).
	 */
	public int getVersion() {
		return this.dirty ? this.version + 1 : this.version;
	}

	/**
	 * Returns the set of locales used in the entity.
	 *
	 * @return the set of locales
	 */
	@Nonnull
	public Set<Locale> getLocales() {
		return Collections.unmodifiableSet(this.locales);
	}

	/**
	 * Retrieves the set of locales used in attributes.
	 *
	 * @return the set of locales used in attributes
	 */
	@Nonnull
	public Set<Locale> getAttributeLocales() {
		return Collections.unmodifiableSet(this.attributeLocales);
	}

	/**
	 * Method registers new {@link Locale} used in attributes.
	 *
	 * @param locale to be added
	 * @return TRUE if the locales of the entity have been changed
	 */
	public boolean addAttributeLocale(@Nonnull Locale locale) {
		if (this.attributeLocales.add(locale)) {
			this.dirty = true;
			return this.recomputeLocales();
		} else {
			return false;
		}
	}

	/**
	 * Method removes information about certain {@link Locale} to be used in attributes.
	 *
	 * @param locale to be removed
	 * @return TRUE if the locales of the entity have been changed
	 */
	public boolean removeAttributeLocale(@Nonnull Locale locale) {
		if (this.attributeLocales.remove(locale)) {
			this.dirty = true;
			return this.recomputeLocales();
		} else {
			return false;
		}
	}

	/**
	 * Method registers new {@link AssociatedDataKey} referenced by this entity.
	 *
	 * @return TRUE if the locales of the entity have been changed
	 */
	public boolean addAssociatedDataKey(@Nonnull AssociatedDataKey associatedDataKey) {
		if (this.associatedDataKeys.add(associatedDataKey)) {
			// if associated data is localized - enrich the set of entity locales
			this.dirty = true;
			return ofNullable(associatedDataKey.locale())
				.map(it -> this.recomputeLocales())
				.orElse(false);
		} else {
			return false;
		}
	}

	/**
	 * Method unregisters {@link AssociatedDataKey} as being referenced by this entity.
	 *
	 * @return TRUE if the locales of the entity have been changed
	 */
	public boolean removeAssociatedDataKey(@Nonnull AssociatedDataKey associatedDataKey) {
		if (this.associatedDataKeys.remove(associatedDataKey)) {
			// if associated data is localized - recompute the set of entity locales
			this.dirty = true;
			return ofNullable(associatedDataKey.locale())
				.map(it -> this.recomputeLocales())
				.orElse(false);
		} else {
			return false;
		}
	}

	/**
	 * Marks this part to be removed.
	 */
	public void markForRemoval() {
		this.markedForRemoval = true;
	}

	/**
	 * Updates set of locales of all localized attributes of this entity.
	 */
	private boolean recomputeLocales() {
		final LinkedHashSet<Locale> recomputedLocales = Stream.concat(
				this.attributeLocales.stream(),
				this.associatedDataKeys.stream()
					.map(AssociatedDataKey::locale)
					.filter(Objects::nonNull)
			)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		if (!this.locales.equals(recomputedLocales)) {
			this.locales = recomputedLocales;
			this.dirty = true;
			return true;
		} else {
			return false;
		}
	}

}
