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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;


/**
 * Internal implementation of {@link AttributeSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public sealed class AttributeSchema implements AttributeSchemaContract permits EntityAttributeSchema, GlobalAttributeSchema {
	@Serial private static final long serialVersionUID = -4646684142378649904L;
	@Getter @Nonnull protected final String name;
	@Getter @Nonnull protected final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable protected final Serializable defaultValue;
	@Getter @Nullable protected final String deprecationNotice;
	@Getter @Nullable protected final String description;
	@Getter protected final boolean localized;
	@Getter protected final boolean nullable;
	@Getter protected final Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes;
	@Getter protected final Set<Scope> filterableInScopes;
	@Getter protected final Set<Scope> sortableInScopes;
	@Getter @Nonnull protected final Class<? extends Serializable> type;
	@Getter @Nonnull protected final Class<? extends Serializable> plainType;
	@Getter protected final int indexedDecimalPlaces;

	/**
	 * Converts an array of ScopedAttributeUniquenessType objects into an EnumMap linking Scope to AttributeUniquenessType.
	 * If the input array is null, it initializes the map with a default value of Scope.LIVE mapped to AttributeUniquenessType.NOT_UNIQUE.
	 *
	 * @param uniqueInScopes An array of ScopedAttributeUniquenessType to be converted. Can be null.
	 * @return An EnumMap where each Scope is associated with its corresponding AttributeUniquenessType.
	 */
	@Nonnull
	public static EnumMap<Scope, AttributeUniquenessType> toUniquenessEnumMap(@Nullable ScopedAttributeUniquenessType[] uniqueInScopes) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = new EnumMap<>(Scope.class);
		if (uniqueInScopes != null) {
			for (ScopedAttributeUniquenessType uniqueInScope : uniqueInScopes) {
				theUniquenessType.put(uniqueInScope.scope(), uniqueInScope.uniquenessType());
			}
		} else {
			theUniquenessType.put(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE);
		}
		return theUniquenessType;
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static AttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		boolean localized
	) {
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			toUniquenessEnumMap(null),
			EnumSet.noneOf(Scope.class),
			EnumSet.noneOf(Scope.class),
			localized, false,
			type, null,
			0
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static <T extends Serializable> AttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		if ((!theFilterableInScopes.isEmpty() || !theSortableInScopes.isEmpty()) && BigDecimal.class.equals(type)) {
			throw new EvitaInvalidUsageException(
				"IndexedDecimalPlaces must be specified for attributes of type BigDecimal (attribute: " + name + ")!"
			);
		}
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable,
			type, defaultValue,
			0
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static <T extends Serializable> AttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		if ((!theFilterableInScopes.isEmpty() || !theSortableInScopes.isEmpty()) && BigDecimal.class.equals(type)) {
			throw new EvitaInvalidUsageException(
				"IndexedDecimalPlaces must be specified for attributes of type BigDecimal (attribute: " + name + ")!"
			);
		}

		return new AttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static <T extends Serializable> AttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			uniquenessTypeInScopes,
			filterableInScopes,
			sortableInScopes,
			localized, nullable,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static <T extends Serializable> AttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		return new AttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			uniquenessTypeInScopes,
			filterableInScopes,
			sortableInScopes,
			localized, nullable,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static <T extends Serializable> AttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		if ((!theFilterableInScopes.isEmpty() || !theSortableInScopes.isEmpty()) && BigDecimal.class.equals(type)) {
			throw new EvitaInvalidUsageException(
				"IndexedDecimalPlaces must be specified for attributes of type BigDecimal (attribute: " + name + ")!"
			);
		}

		return new AttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	<T extends Serializable> AttributeSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		this.name = name;
		this.nameVariants = CollectionUtils.toUnmodifiableMap(nameVariants);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		if (uniquenessTypeInScopes == null || uniquenessTypeInScopes.isEmpty()) {
			final EnumMap<Scope, AttributeUniquenessType> theMap = new EnumMap<>(Scope.class);
			this.uniquenessTypeInScopes = Collections.unmodifiableMap(theMap);
			theMap.put(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE);
		} else {
			this.uniquenessTypeInScopes = CollectionUtils.toUnmodifiableMap(uniquenessTypeInScopes);
		}
		this.filterableInScopes = CollectionUtils.toUnmodifiableSet(filterableInScopes == null ? EnumSet.noneOf(Scope.class) : filterableInScopes);
		this.sortableInScopes = CollectionUtils.toUnmodifiableSet(sortableInScopes == null ? EnumSet.noneOf(Scope.class) : sortableInScopes);
		this.localized = localized;
		this.nullable = nullable;
		this.type = EvitaDataTypes.toWrappedForm(type);
		//noinspection unchecked
		this.plainType = (Class<? extends Serializable>) (this.type.isArray() ? this.type.getComponentType() : this.type);
		this.defaultValue = EvitaDataTypes.toTargetType(defaultValue, this.plainType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
	}

	@Override
	@Nonnull
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	@Override
	public boolean isUnique() {
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(Scope.LIVE);
		return attributeUniquenessType != null && attributeUniquenessType != AttributeUniquenessType.NOT_UNIQUE;
	}

	@Override
	public boolean isUnique(@Nonnull Scope scope) {
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(scope);
		return attributeUniquenessType != null && attributeUniquenessType != AttributeUniquenessType.NOT_UNIQUE;
	}

	@Override
	public boolean isUniqueWithinLocale() {
		return this.uniquenessTypeInScopes.get(Scope.LIVE) == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
	}

	@Override
	public boolean isUniqueWithinLocale(@Nonnull Scope scope) {
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(scope);
		return attributeUniquenessType == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
	}

	@Nonnull
	@Override
	public AttributeUniquenessType getUniquenessType(@Nonnull Scope scope) {
		return ofNullable(this.uniquenessTypeInScopes.get(scope)).orElse(AttributeUniquenessType.NOT_UNIQUE);
	}

	@Override
	public boolean isFilterable(@Nonnull Scope scope) {
		return this.filterableInScopes.contains(scope);
	}

	@Override
	public boolean isSortable(@Nonnull Scope scope) {
		return this.sortableInScopes.contains(scope);
	}

	/**
	 * Inverts the type of the attribute schema between Predecessor and ReferencedEntityPredecessor.
	 * Throws GenericEvitaInternalError if the type cannot be inverted.
	 *
	 * @return A new instance of AttributeSchemaContract with the inverted type.
	 */
	@Nonnull
	public AttributeSchemaContract withInvertedType() {
		if (Predecessor.class.equals(this.plainType)) {
			return new AttributeSchema(
				this.name,
				this.nameVariants,
				this.description,
				this.deprecationNotice,
				this.uniquenessTypeInScopes,
				this.filterableInScopes,
				this.sortableInScopes,
				this.localized,
				this.nullable,
				ReferencedEntityPredecessor.class,
				null,
				this.indexedDecimalPlaces
			);
		} else if (ReferencedEntityPredecessor.class.equals(this.plainType)) {
			return new AttributeSchema(
				this.name,
				this.nameVariants,
				this.description,
				this.deprecationNotice,
				this.uniquenessTypeInScopes,
				this.filterableInScopes,
				this.sortableInScopes,
				this.localized,
				this.nullable,
				Predecessor.class,
				null,
				this.indexedDecimalPlaces
			);
		} else {
			throw new GenericEvitaInternalError(
				"Type `" + this.type + "` cannot be inverted!"
			);
		}
	}

	@Override
	public String toString() {
		return "AttributeSchema{" +
			"name='" + this.name + '\'' + (this.deprecationNotice == null ? "" : " (deprecated)") +
			", unique=(" + (this.uniquenessTypeInScopes.entrySet().stream().map(it -> it.getKey() + ": " + it.getValue().name())) + ")" +
			", filterable=" + (this.filterableInScopes.isEmpty() ? "no" : "(in scopes: " + this.filterableInScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + ")") +
			", sortable=" + (this.sortableInScopes.isEmpty() ? "no" : "(in scopes: " + this.sortableInScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + ")") +
			", localized=" + this.localized +
			", nullable=" + this.nullable +
			", type=" + this.type +
			", indexedDecimalPlaces=" + this.indexedDecimalPlaces +
			", defaultValue=" + this.defaultValue +
			'}';
	}
}
