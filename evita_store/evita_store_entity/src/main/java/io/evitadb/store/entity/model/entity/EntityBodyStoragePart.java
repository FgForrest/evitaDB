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
import io.evitadb.api.requestResponse.data.structure.Entity;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
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
@EqualsAndHashCode(exclude = {"dirty", "initialRevision"})
@ToString(exclude = {"dirty", "initialRevision"})
public class EntityBodyStoragePart implements EntityStoragePart {
	@Serial private static final long serialVersionUID = 34998825794290379L;
	/**
	 * See {@link Entity#getPrimaryKey()}.
	 */
	@Getter private final int primaryKey;
	/**
	 * See {@link Entity#getAttributeLocales()}.
	 */
	@Getter @Nonnull private final Set<Locale> attributeLocales;
	/**
	 * Contains TRUE if the container was not stored yet.
	 */
	private final boolean initialRevision;
	/**
	 * See {@link Entity#version()}.
	 */
	private int version;
	/**
	 * See {@link Entity#getParent()}.
	 */
	@Getter @Nullable private Integer parent;
	/**
	 * See {@link Entity#getLocales()}.
	 */
	@Getter @Nonnull private Set<Locale> locales;
	/**
	 * Contains set of all associated data keys that are used in this entity.
	 */
	@Getter @Nonnull private final Set<AssociatedDataKey> associatedDataKeys;
	/**
	 * Contains true if anything changed in this container.
	 */
	@Getter @Setter private boolean dirty;
	/**
	 * If set to TRUE the storage part should be removed.
	 */
	@Getter
	private boolean markedForRemoval;
	/**
	 * If set to TRUE the consistenci of the {@link #initialRevision} was successfully performed.
	 */
	@Getter @Setter
	private boolean validated;

	public EntityBodyStoragePart(int primaryKey) {
		this.primaryKey = primaryKey;
		this.locales = new LinkedHashSet<>();
		this.attributeLocales = new LinkedHashSet<>();
		this.associatedDataKeys = new LinkedHashSet<>();
		this.dirty = true;
		this.initialRevision = true;
	}

	public EntityBodyStoragePart(int version, @Nonnull Integer primaryKey, @Nonnull Integer parent, @Nonnull Set<Locale> locales, @Nonnull Set<Locale> attributeLocales, @Nonnull Set<AssociatedDataKey> associatedDataKeys) {
		this.version = version;
		this.primaryKey = primaryKey;
		this.parent = parent;
		this.locales = locales;
		this.attributeLocales = attributeLocales;
		this.associatedDataKeys = associatedDataKeys;
		this.initialRevision = false;
	}

	@Nullable
	@Override
	public Long getUniquePartId() {
		return (long) primaryKey;
	}

	@Override
	public boolean isNew() {
		return this.initialRevision;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return primaryKey;
	}

	@Override
	public boolean isEmpty() {
		return markedForRemoval;
	}

	/**
	 * Updates hierarchical placement of the entity.
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
		return dirty ? version + 1 : version;
	}

	/**
	 * Method registers new {@link Locale} used in attributes.
	 *
	 * @param locale to be added
	 * @return TRUE if information is actually added
	 */
	public OperationResult addAttributeLocale(@Nonnull Locale locale) {
		if (this.attributeLocales.add(locale)) {
			this.dirty = true;
			return new OperationResult(true, this.recomputeLocales());
		} else {
			return new OperationResult(false, false);
		}
	}

	/**
	 * Method removes information about certain {@link Locale} to be used in attributes.
	 *
	 * @param locale to be removed
	 * @return TRUE if information is actually removed
	 */
	public OperationResult removeAttributeLocale(@Nonnull Locale locale) {
		if (this.attributeLocales.remove(locale)) {
			this.dirty = true;
			return new OperationResult(true, this.recomputeLocales());
		} else {
			return new OperationResult(false, false);
		}
	}

	/**
	 * Method registers new {@link AssociatedDataKey} referenced by this entity.
	 *
	 * @return TRUE if the key was added
	 */
	public OperationResult addAssociatedDataKey(@Nonnull AssociatedDataKey associatedDataKey) {
		if (this.associatedDataKeys.add(associatedDataKey)) {
			// if associated data is localized - enrich the set of entity locales
			final boolean localesChanged = ofNullable(associatedDataKey.locale())
				.map(it -> this.recomputeLocales())
				.orElse(false);
			this.dirty = true;
			return new OperationResult(true, localesChanged);
		} else {
			return new OperationResult(false, false);
		}
	}

	/**
	 * Method unregisters {@link AssociatedDataKey} as being referenced by this entity.
	 *
	 * @return TRUE if the key was removed
	 */
	public OperationResult removeAssociatedDataKey(@Nonnull AssociatedDataKey associatedDataKey) {
		if (this.associatedDataKeys.remove(associatedDataKey)) {
			// if associated data is localized - recompute the set of entity locales
			final boolean localesChanged = ofNullable(associatedDataKey.locale())
				.map(it -> this.recomputeLocales())
				.orElse(false);
			this.dirty = true;
			return new OperationResult(true, localesChanged);
		} else {
			return new OperationResult(false, false);
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
		final Set<Locale> recomputedLocales = Stream.concat(
				attributeLocales.stream(),
				associatedDataKeys.stream()
					.map(AssociatedDataKey::locale)
					.filter(Objects::nonNull)
			)
			.collect(Collectors.toSet());

		if (!this.locales.equals(recomputedLocales)) {
			this.locales = recomputedLocales;
			this.dirty = true;
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Tuple containing the result of the operation.
	 *
	 * @param operationChangedData         TRUE if the internal data has been affected
	 * @param operationChangedSetOfLocales TRUE if the operation also recomputed the set of entity languages
	 */
	public record OperationResult(
		boolean operationChangedData,
		boolean operationChangedSetOfLocales
	) {
	}

	;
}
