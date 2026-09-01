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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * Internal implementation of {@link AttributeSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public sealed class AttributeSchema implements AttributeSchemaContract
	permits EntityAttributeSchema, GlobalAttributeSchema {
	@Serial private static final long serialVersionUID = -4825670975814791472L;
	/**
	 * Per-item override of the conflict resolution granularity applied to this attribute. It never carries `null`;
	 * the default is {@link ConflictResolutionOverride#INHERITED}. See
	 * {@link AttributeSchemaContract#getConflictResolutionOverride()} for the meaning of the individual values and
	 * how the meaning depends on the attribute location (entity attribute vs. reference attribute).
	 */
	@Getter @Nonnull protected final ConflictResolutionOverride conflictResolutionOverride;
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
	 * Set of scopes where the attribute is filterable. Filterable attributes consume index space and their type must
	 * implement {@link Comparable}. See {@link AttributeSchemaContract#getFilterableInScopes()} and
	 * {@link AttributeSchemaContract#isFilterableInScope(Scope)}.
	 */
	@Getter protected final Set<Scope> filterableInScopes;
	/**
	 * Optional accelerations the attribute's filter index maintains, per scope. Scopes declaring no accelerator are
	 * **absent** from the map rather than mapped to an empty set, so that an attribute declaring none is `equals` to
	 * one declared with an explicitly empty accelerator list. Every key necessarily has a filter index - it is either
	 * in {@link #filterableInScopes} or unique per {@link #uniquenessTypeInScopes}, and the constructor refuses any
	 * other combination. See {@link AttributeSchemaContract#getAcceleratorsInScope(Scope)}.
	 */
	@Getter protected final Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes;
	/**
	 * Number of fractional places important for indexing numeric values (especially {@link java.math.BigDecimal}).
	 * Values are scaled by 10^indexedDecimalPlaces and stored as integers, therefore the scaled value must fit into
	 * {@link Integer} range. See {@link AttributeSchemaContract#getIndexedDecimalPlaces()}.
	 */
	@Getter protected final int indexedDecimalPlaces;
	/**
	 * Flag specifying that the attribute is tied to a particular {@link java.util.Locale}. Localized attributes must
	 * always be used together with a locale. See {@link AttributeSchemaContract#isLocalized()}.
	 */
	@Getter protected final boolean localized;
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
	 * Flag specifying that the attribute value may be missing on entities. If false, upserts must provide a value.
	 * For localized attributes, presence is enforced only for locales the entity is localized to. See
	 * {@link AttributeSchemaContract#isNullable()}.
	 */
	@Getter protected final boolean nullable;
	/**
	 * Non-array variant of {@link #type}. If {@link #type} is an array, this holds its component type; otherwise it
	 * equals {@link #type}. See {@link AttributeSchemaContract#getPlainType()}.
	 */
	@Getter @Nonnull protected final Class<? extends Serializable> plainType;
	/**
	 * Flag marking this attribute as representative. Representative attributes help identify entities or
	 * disambiguate duplicated references and may be used by developer tools. See
	 * {@link AttributeSchemaContract#isRepresentative()}.
	 */
	@Getter protected final boolean representative;
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
	 * Mapping of {@link Scope} to the attribute uniqueness semantics in that scope. See
	 * {@link AttributeSchemaContract#getUniquenessTypeInScopes()} and uniqueness helpers
	 * such as {@link AttributeSchemaContract#isUniqueInScope(Scope)} and
	 * {@link AttributeSchemaContract#isUniqueWithinLocaleInScope(Scope)}.
	 */
	@Getter protected final Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes;
	/**
	 * Canonical locale-agnostic {@link AttributeKey} of this attribute. The write path derives such a key from the
	 * schema per attribute per entity only to compare it or to look a value up by it, and then discards it - since
	 * the schema is immutable and outlives all of those uses, the key is created once here instead.
	 */
	@EqualsAndHashCode.Exclude @Nonnull private final AttributeKey attributeKey;
	/**
	 * Canonical localized {@link AttributeKey} instances of this attribute, keyed by locale and filled in on demand.
	 * The locale domain is bounded by the locales of the entities using this schema, so the map stays small. It is
	 * `null` for non-localized attributes, which never look a localized key up repeatedly.
	 */
	@EqualsAndHashCode.Exclude @Nullable private final Map<Locale, AttributeKey> localizedAttributeKeys;

	/**
	 * Converts an array of ScopedAttributeUniquenessType objects into an EnumMap linking Scope to AttributeUniquenessType.
	 * If the input array is null, it initializes the map with a default value of Scope.DEFAULT_SCOPE mapped to AttributeUniquenessType.NOT_UNIQUE.
	 *
	 * @param uniqueInScopes An array of ScopedAttributeUniquenessType to be converted. Can be null.
	 * @return An EnumMap where each Scope is associated with its corresponding AttributeUniquenessType.
	 */
	@Nonnull
	public static EnumMap<Scope, AttributeUniquenessType> toUniquenessEnumMap(
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes
	) {
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
	 * Converts the scoped carriers a mutation transports into the per-scope map the schema keeps. A `null` array and
	 * an empty one are the same thing - no accelerator anywhere - because the field is optional on the wire and an old
	 * client simply never sends it.
	 *
	 * @param acceleratorsInScopes carriers to convert, may be null
	 * @return map of scope to the accelerators declared in it, never null and never containing an empty value
	 */
	@Nonnull
	public static EnumMap<Scope, Set<AttributeFilterAccelerator>> toAcceleratorsEnumMap(
		@Nullable ScopedAttributeFilterAccelerators[] acceleratorsInScopes
	) {
		final EnumMap<Scope, Set<AttributeFilterAccelerator>> theAccelerators = new EnumMap<>(Scope.class);
		if (acceleratorsInScopes != null) {
			for (final ScopedAttributeFilterAccelerators scopedAccelerators : acceleratorsInScopes) {
				final AttributeFilterAccelerator[] accelerators = scopedAccelerators.accelerators();
				if (accelerators.length == 0) {
					// an empty carrier means "filterable, no acceleration" - that is the absence of an entry, not an
					// entry holding an empty set, so that it stays `equals` to a plain `filterable()` declaration
					continue;
				}
				final EnumSet<AttributeFilterAccelerator> theSet = EnumSet.noneOf(AttributeFilterAccelerator.class);
				Collections.addAll(theSet, accelerators);
				theAccelerators.merge(
					scopedAccelerators.scope(), theSet,
					(existing, added) -> {
						existing.addAll(added);
						return existing;
					}
				);
			}
		}
		return theAccelerators;
	}

	/**
	 * The inverse of {@link #toAcceleratorsEnumMap(ScopedAttributeFilterAccelerators[])} - turns the schema's per-scope
	 * map back into the carriers a mutation or an external API transports. Used wherever an existing schema has to be
	 * re-expressed as a mutation (schema diffing, gRPC/REST/GraphQL conversion).
	 *
	 * @param acceleratorsInScopes the schema's per-scope accelerators
	 * @return one carrier per scope that declares at least one accelerator, in {@link Scope} declaration order
	 */
	@Nonnull
	public static ScopedAttributeFilterAccelerators[] toAcceleratorsArray(
		@Nullable Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes
	) {
		if (acceleratorsInScopes == null || acceleratorsInScopes.isEmpty()) {
			return ScopedAttributeFilterAccelerators.EMPTY;
		}
		final ScopedAttributeFilterAccelerators[] result =
			new ScopedAttributeFilterAccelerators[acceleratorsInScopes.size()];
		int index = 0;
		for (final Scope scope : Scope.values()) {
			final Set<AttributeFilterAccelerator> accelerators = acceleratorsInScopes.get(scope);
			if (accelerators == null || accelerators.isEmpty()) {
				continue;
			}
			result[index++] = new ScopedAttributeFilterAccelerators(
				scope, accelerators.toArray(AttributeFilterAccelerator[]::new)
			);
		}
		return index == result.length ? result : Arrays.copyOf(result, index);
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
		boolean localized,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			toUniquenessEnumMap(null),
			EnumSet.noneOf(Scope.class),
			null,
			EnumSet.noneOf(Scope.class),
			localized, false, false,
			type, null,
			0,
			conflictResolutionOverride
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
		@Nullable ScopedAttributeFilterAccelerators[] acceleratorsInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
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
			toAcceleratorsEnumMap(acceleratorsInScopes),
			theSortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			0,
			conflictResolutionOverride
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
		@Nullable ScopedAttributeFilterAccelerators[] acceleratorsInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new AttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			toAcceleratorsEnumMap(acceleratorsInScopes),
			theSortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces,
			conflictResolutionOverride
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
		@Nullable Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			uniquenessTypeInScopes,
			filterableInScopes,
			acceleratorsInScopes,
			sortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces,
			conflictResolutionOverride
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
		@Nullable Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		return new AttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			uniquenessTypeInScopes,
			filterableInScopes,
			acceleratorsInScopes,
			sortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces,
			conflictResolutionOverride
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
		@Nullable ScopedAttributeFilterAccelerators[] acceleratorsInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new AttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			toAcceleratorsEnumMap(acceleratorsInScopes),
			theSortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces,
			conflictResolutionOverride
		);
	}

	<T extends Serializable> AttributeSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniquenessTypeInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		this.name = name;
		this.nameVariants = CollectionUtils.toUnmodifiableMap(nameVariants);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		if (uniquenessTypeInScopes == null || uniquenessTypeInScopes.isEmpty()) {
			final EnumMap<Scope, AttributeUniquenessType> theMap = new EnumMap<>(Scope.class);
			theMap.put(Scope.DEFAULT_SCOPE, AttributeUniquenessType.NOT_UNIQUE);
			this.uniquenessTypeInScopes = Collections.unmodifiableMap(theMap);
		} else {
			this.uniquenessTypeInScopes = CollectionUtils.toUnmodifiableMap(uniquenessTypeInScopes);
		}
		this.filterableInScopes = CollectionUtils.toUnmodifiableSet(
			filterableInScopes == null ? EnumSet.noneOf(Scope.class) : filterableInScopes
		);
		this.acceleratorsInScopes = normalizeAccelerators(
			name, EvitaDataTypes.toWrappedForm(type), acceleratorsInScopes
		);
		this.sortableInScopes = CollectionUtils.toUnmodifiableSet(
			sortableInScopes == null ? EnumSet.noneOf(Scope.class) : sortableInScopes
		);
		this.localized = localized;
		this.nullable = nullable;
		this.representative = representative;
		this.type = EvitaDataTypes.toWrappedForm(type);
		//noinspection unchecked
		this.plainType = (Class<? extends Serializable>) (
			this.type.isArray() ? this.type.getComponentType() : this.type
		);
		this.defaultValue = EvitaDataTypes.toTargetType(defaultValue, this.plainType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.conflictResolutionOverride = conflictResolutionOverride;
		this.attributeKey = new AttributeKey(this.name);
		this.localizedAttributeKeys = localized ? new ConcurrentHashMap<>(8) : null;
	}

	@Nonnull
	@Override
	public AttributeKey getAttributeKey() {
		return this.attributeKey;
	}

	@Nonnull
	@Override
	public AttributeKey getAttributeKey(@Nullable Locale locale) {
		if (locale == null) {
			return this.attributeKey;
		}
		final Map<Locale, AttributeKey> theLocalizedKeys = this.localizedAttributeKeys;
		if (theLocalizedKeys == null) {
			// non-localized attribute - a localized key of it is never requested repeatedly, caching would not pay off
			return new AttributeKey(this.name, locale);
		}
		final AttributeKey cachedKey = theLocalizedKeys.get(locale);
		if (cachedKey != null) {
			return cachedKey;
		}
		// deliberately not computeIfAbsent - it would allocate a capturing lambda on every call, which is exactly
		// the allocation this cache exists to remove
		final AttributeKey newKey = new AttributeKey(this.name, locale);
		final AttributeKey concurrentlyStoredKey = theLocalizedKeys.putIfAbsent(locale, newKey);
		return concurrentlyStoredKey == null ? newKey : concurrentlyStoredKey;
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
		return this.uniquenessTypeInScopes.get(Scope.DEFAULT_SCOPE) ==
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
	}

	@Override
	public boolean isUniqueWithinLocaleInScope(@Nonnull Scope scope) {
		final AttributeUniquenessType attributeUniquenessType = this.uniquenessTypeInScopes.get(scope);
		return attributeUniquenessType == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
	}

	@Nonnull
	@Override
	public AttributeUniquenessType getUniquenessType(@Nonnull Scope scope) {
		// plain null-check instead of Optional wrapping - this accessor is called per attribute on the write path
		final AttributeUniquenessType uniquenessType = this.uniquenessTypeInScopes.get(scope);
		return uniquenessType == null ? AttributeUniquenessType.NOT_UNIQUE : uniquenessType;
	}

	@Override
	public boolean isFilterableInScope(@Nonnull Scope scope) {
		return this.filterableInScopes.contains(scope);
	}

	@Nonnull
	@Override
	public Set<AttributeFilterAccelerator> getAcceleratorsInScope(@Nonnull Scope scope) {
		// plain null-check instead of Optional wrapping - this accessor is consulted per attribute while planning
		final Set<AttributeFilterAccelerator> accelerators = this.acceleratorsInScopes.get(scope);
		return accelerators == null ? Collections.emptySet() : accelerators;
	}

	/**
	 * Refuses an accelerator the attribute's declared type cannot support.
	 *
	 * The mutations validate this too, with far more actionable messages - this is the **last line of defence for the
	 * very same rule**, making the invariant hold for every construction path, including a schema rebuilt directly
	 * through `_internalBuild(...)` by a type-changing mutation, a Kryo reader, or an external-API converter. Without
	 * it the rule is only as strong as the set of mutations that happen to remember to check. It therefore throws
	 * {@link InvalidSchemaMutationException} - the type the mutations throw - so that one `catch` covers the rule
	 * however it was reached.
	 *
	 * @param name         attribute name, used only to make the error message locatable
	 * @param type         the attribute's declared type, already in wrapped form
	 * @param accelerators the accelerators declared for one scope
	 * @throws InvalidSchemaMutationException when an accelerator is not applicable to the type
	 */
	private static void verifyAcceleratorsApplicableToType(
		@Nonnull String name,
		@Nonnull Class<?> type,
		@Nonnull Set<AttributeFilterAccelerator> accelerators
	) {
		final Class<?> plainType = type.isArray() ? type.getComponentType() : type;
		for (final AttributeFilterAccelerator accelerator : accelerators) {
			// a switch *expression* rather than a statement: it is the expression form that the compiler requires to
			// be exhaustive, so a future accelerator added without teaching this method which type carries it is
			// a compile error here - a switch statement would have let it through silently
			final Class<?> requiredType = switch (accelerator) {
				case SUBSTRING_SEARCH -> String.class;
			};
			if (!requiredType.equals(plainType)) {
				throw new InvalidSchemaMutationException(
					"Attribute `" + name + "` declares filter accelerator `" + accelerator +
						"`, which is only supported on attributes of type `" + requiredType.getSimpleName() +
						"` or `" + requiredType.getSimpleName() + "[]` - but its type is `" + type.getName() + "`!"
				);
			}
		}
	}

	/**
	 * Drops scopes declaring no accelerator, so that the field's *no empty values* invariant holds for every instance
	 * however it was constructed, and refuses an accelerator the declared type cannot support.
	 *
	 * **The "every accelerator has a filter index in its scope" rule is deliberately NOT enforced here.** That is a
	 * cross-field invariant of an *assembled* schema, and this DTO is also what the builder materializes after each
	 * individual mutation - so it legitimately represents intermediate states in which a filterability change has not
	 * been applied yet. Enforcing it here made the order of the builder calls significant, and worse, made it depend
	 * on how mutation *combination* happened to reorder them. Every other cross-field invariant of an attribute
	 * schema - sortable-versus-array, unique-versus-filterable, the `Comparable` requirement - is enforced in
	 * {@link io.evitadb.api.requestResponse.schema.builder.AbstractAttributeSchemaBuilder}'s `validate` on the final
	 * schema for exactly this reason, and the accelerator rule now sits with them. The create mutations check it at
	 * construction time as well, because they carry the whole attribute in a single payload.
	 *
	 * The type rule below stays, because it is a property of the value itself rather than of the surrounding fields:
	 * no intermediate state can make `SUBSTRING_SEARCH` legal on an `Integer`.
	 *
	 * @param name                 attribute name, used only to make the error message locatable
	 * @param type                 the attribute's declared type, already in wrapped form
	 * @param acceleratorsInScopes the declared accelerators, may be null
	 * @return an unmodifiable, normalized per-scope accelerator map, never null
	 * @throws InvalidSchemaMutationException when an accelerator is not applicable to the declared type
	 */
	@Nonnull
	private static Map<Scope, Set<AttributeFilterAccelerator>> normalizeAccelerators(
		@Nonnull String name,
		@Nonnull Class<?> type,
		@Nullable Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes
	) {
		if (acceleratorsInScopes == null || acceleratorsInScopes.isEmpty()) {
			return Collections.emptyMap();
		}
		final EnumMap<Scope, Set<AttributeFilterAccelerator>> normalized = new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, Set<AttributeFilterAccelerator>> entry : acceleratorsInScopes.entrySet()) {
			final Set<AttributeFilterAccelerator> accelerators = entry.getValue();
			if (accelerators == null || accelerators.isEmpty()) {
				continue;
			}
			verifyAcceleratorsApplicableToType(name, type, accelerators);
			normalized.put(entry.getKey(), CollectionUtils.toUnmodifiableSet(EnumSet.copyOf(accelerators)));
		}
		return normalized.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(normalized);
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
				this.acceleratorsInScopes,
				this.sortableInScopes,
				this.localized,
				this.nullable,
				this.representative,
				ReferencedEntityPredecessor.class,
				null,
				this.indexedDecimalPlaces,
				this.conflictResolutionOverride
			);
		} else if (ReferencedEntityPredecessor.class.equals(this.plainType)) {
			return new AttributeSchema(
				this.name,
				this.nameVariants,
				this.description,
				this.deprecationNotice,
				this.uniquenessTypeInScopes,
				this.filterableInScopes,
				this.acceleratorsInScopes,
				this.sortableInScopes,
				this.localized,
				this.nullable,
				this.representative,
				Predecessor.class,
				null,
				this.indexedDecimalPlaces,
				this.conflictResolutionOverride
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
			", unique=(" + join(this.uniquenessTypeInScopes) + ")" +
			", filterable=" +
			(this.filterableInScopes.isEmpty() ? "no" : "(in scopes: " + join(this.filterableInScopes) + ")") +
			(this.acceleratorsInScopes.isEmpty() ?
				"" : ", accelerators=(" + joinAccelerators(this.acceleratorsInScopes) + ")") +
			", sortable=" +
			(this.sortableInScopes.isEmpty() ? "no" : "(in scopes: " + join(this.sortableInScopes) + ")") +
			", localized=" + this.localized +
			", nullable=" + this.nullable +
			", representative=" + this.representative +
			", type=" + this.type +
			", indexedDecimalPlaces=" + this.indexedDecimalPlaces +
			", defaultValue=" + this.defaultValue +
			", conflictResolutionOverride=" + this.conflictResolutionOverride +
			'}';
	}

	/**
	 * Joins the entries of the provided map into a single, comma-separated string.
	 * Each map entry is formatted as "key: value", where the key is a {@code Scope}
	 * and the value is an {@code AttributeUniquenessType}.
	 *
	 * @param scopes A non-null map linking {@code Scope} instances to {@code AttributeUniquenessType} values.
	 *               Each map entry will contribute "key: value" to the resulting string.
	 * @return A non-null, comma-separated string representing the entries of the map.
	 */
	@Nonnull
	protected static String join(@Nonnull Map<Scope, ? extends Enum<?>> scopes) {
		return scopes.entrySet().stream()
			.map(it -> it.getKey() + ": " + it.getValue().name())
			.collect(Collectors.joining(", "));
	}

	/**
	 * Joins the names of the provided {@code Scope} instances into a single comma-separated string.
	 *
	 * @param scopes A non-null set of {@code Scope} instances to be joined. Each {@code Scope}'s name will be used in the output string.
	 * @return A non-null, comma-separated string containing the names of the provided {@code Scope} instances.
	 */
	@Nonnull
	protected static String join(@Nonnull Set<Scope> scopes) {
		return scopes.stream().map(Enum::name).collect(Collectors.joining(", "));
	}

	/**
	 * Renders the per-scope accelerators as `SCOPE: ACC_A, ACC_B; SCOPE: ...` for {@link #toString()}.
	 *
	 * @param acceleratorsInScopes the per-scope accelerators, never null
	 * @return a non-null, human readable rendering of the map
	 */
	@Nonnull
	protected static String joinAccelerators(
		@Nonnull Map<Scope, Set<AttributeFilterAccelerator>> acceleratorsInScopes
	) {
		return acceleratorsInScopes.entrySet().stream()
			.map(it -> it.getKey() + ": " + it.getValue().stream().map(Enum::name).collect(Collectors.joining(", ")))
			.collect(Collectors.joining("; "));
	}
}
