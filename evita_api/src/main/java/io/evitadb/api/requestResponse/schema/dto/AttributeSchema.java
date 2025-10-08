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
	@Serial private static final long serialVersionUID = -4825670975814791474L;
	/**
	 * Human readable name of the attribute as defined by the schema author. See
	 * {@link io.evitadb.api.requestResponse.schema.NamedSchemaContract#getName()}.
	 */
	@Getter @Nonnull protected final String name;
	/**
	 * Precomputed name variants for multiple {@link NamingConvention naming conventions}. These are generated
	 * from {@link #name} and used wherever a specific convention (e.g. camelCase, snake_case) is required.
	 */
	@Getter @Nonnull protected final Map<NamingConvention, String> nameVariants;
	/**
	 * Default value used when the entity is created without explicitly providing this attribute. See
	 * {@link AttributeSchemaContract#getDefaultValue()} for behavior details and its relation to
	 * {@link AttributeSchemaContract#isNullable()}.
	 */
	@Getter @Nullable protected final Serializable defaultValue;
	/**
	 * Optional deprecation notice explaining why the attribute should no longer be used. When present, developer
	 * tooling can surface this information while still keeping the attribute operational. See
	 * {@link io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract#getDeprecationNotice()}.
	 */
	@Getter @Nullable protected final String deprecationNotice;
	/**
	 * Optional human readable description of the attribute purpose. Intended for developer tooling and schema
	 * documentation, has no effect on runtime behavior.
	 */
	@Getter @Nullable protected final String description;
	/**
	 * Flag specifying that the attribute is tied to a particular {@link java.util.Locale}. Localized attributes must
	 * always be used together with a locale. See {@link AttributeSchemaContract#isLocalized()}.
	 */
	@Getter protected final boolean localized;
	/**
	 * Flag specifying that the attribute value may be missing on entities. If false, upserts must provide a value.
	 * For localized attributes, presence is enforced only for locales the entity is localized to. See
	 * {@link AttributeSchemaContract#isNullable()}.
	 */
	@Getter protected final boolean nullable;
	/**
	 * Flag marking this attribute as representative. Representative attributes help identify entities or
	 * disambiguate duplicated references and may be used by developer tools. See
	 * {@link AttributeSchemaContract#isRepresentative()}.
	 */
	@Getter protected final boolean representative;
	/**
	 * Mapping of {@link Scope} to the attribute uniqueness semantics in that scope. See
	 * {@link AttributeSchemaContract#getUniquenessTypeInScopes()} and uniqueness helpers
	 * such as {@link AttributeSchemaContract#isUniqueInScope(Scope)} and
	 * {@link AttributeSchemaContract#isUniqueWithinLocaleInScope(Scope)}.
	 */
	@Getter protected final Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes;
	/**
	 * Set of scopes where the attribute is filterable. Filterable attributes consume index space and their type must
	 * implement {@link Comparable}. See {@link AttributeSchemaContract#getFilterableInScopes()} and
	 * {@link AttributeSchemaContract#isFilterableInScope(Scope)}.
	 */
	@Getter protected final Set<Scope> filterableInScopes;
	/**
	 * Set of scopes where the attribute is sortable. Sortable attributes consume index space and their type must
	 * implement {@link Comparable}. See {@link AttributeSchemaContract#getSortableInScopes()} and
	 * {@link AttributeSchemaContract#isSortableInScope(Scope)}.
	 */
	@Getter protected final Set<Scope> sortableInScopes;
	/**
	 * Declared attribute type. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()} or their arrays. The type
	 * is always a reference type (never a primitive) due to API contracts. See {@link AttributeSchemaContract#getType()}.
	 */
	@Getter @Nonnull protected final Class<? extends Serializable> type;
	/**
	 * Non-array variant of {@link #type}. If {@link #type} is an array, this holds its component type; otherwise it
	 * equals {@link #type}. See {@link AttributeSchemaContract#getPlainType()}.
	 */
	@Getter @Nonnull protected final Class<? extends Serializable> plainType;
	/**
	 * Number of fractional places important for indexing numeric values (especially {@link java.math.BigDecimal}).
	 * Values are scaled by 10^indexedDecimalPlaces and stored as integers, therefore the scaled value must fit into
	 * {@link Integer} range. See {@link AttributeSchemaContract#getIndexedDecimalPlaces()}.
	 */
	@Getter protected final int indexedDecimalPlaces;

	/**
	 * Converts an array of ScopedAttributeUniquenessType objects into an EnumMap linking Scope to AttributeUniquenessType.
	 * If the input array is null, it initializes the map with a default value of Scope.DEFAULT_SCOPE mapped to AttributeUniquenessType.NOT_UNIQUE.
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
			theUniquenessType.put(Scope.DEFAULT_SCOPE, AttributeUniquenessType.NOT_UNIQUE);
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
			localized, false, false,
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
		boolean representative,
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
			localized, nullable, representative,
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
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new AttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable, representative,
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
		boolean representative,
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
			localized, nullable, representative,
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
		boolean representative,
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
			localized, nullable, representative,
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
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new AttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable, representative,
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
		boolean representative,
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
			theMap.put(Scope.DEFAULT_SCOPE, AttributeUniquenessType.NOT_UNIQUE);
		} else {
			this.uniquenessTypeInScopes = CollectionUtils.toUnmodifiableMap(uniquenessTypeInScopes);
		}
		this.filterableInScopes = CollectionUtils.toUnmodifiableSet(filterableInScopes == null ? EnumSet.noneOf(Scope.class) : filterableInScopes);
		this.sortableInScopes = CollectionUtils.toUnmodifiableSet(sortableInScopes == null ? EnumSet.noneOf(Scope.class) : sortableInScopes);
		this.localized = localized;
		this.nullable = nullable;
		this.representative = representative;
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
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(Scope.DEFAULT_SCOPE);
		return attributeUniquenessType != null && attributeUniquenessType != AttributeUniquenessType.NOT_UNIQUE;
	}

	@Override
	public boolean isUniqueInScope(@Nonnull Scope scope) {
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(scope);
		return attributeUniquenessType != null && attributeUniquenessType != AttributeUniquenessType.NOT_UNIQUE;
	}

	@Override
	public boolean isUniqueWithinLocale() {
		return this.uniquenessTypeInScopes.get(Scope.DEFAULT_SCOPE) == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
	}

	@Override
	public boolean isUniqueWithinLocaleInScope(@Nonnull Scope scope) {
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(scope);
		return attributeUniquenessType == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
	}

	@Nonnull
	@Override
	public AttributeUniquenessType getUniquenessType(@Nonnull Scope scope) {
		return ofNullable(this.uniquenessTypeInScopes.get(scope)).orElse(AttributeUniquenessType.NOT_UNIQUE);
	}

	@Override
	public boolean isFilterableInScope(@Nonnull Scope scope) {
		return this.filterableInScopes.contains(scope);
	}

	@Override
	public boolean isSortableInScope(@Nonnull Scope scope) {
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
				this.representative,
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
				this.representative,
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
			", representative=" + this.representative +
			", type=" + this.type +
			", indexedDecimalPlaces=" + this.indexedDecimalPlaces +
			", defaultValue=" + this.defaultValue +
			'}';
	}
}
