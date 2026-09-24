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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This is the definition object for {@link Attributes} that is stored along with
 * {@link Entity}. Definition objects allow to describe the structure of the entity type so that
 * in any time everyone can consult complete structure of the entity type. Definition object is similar to Java reflection
 * process where you can also at any moment see which fields and methods are available for the class.
 *
 * Entity attributes allows defining set of data that are fetched in bulk along with the entity body.
 * Attributes may be indexed for fast filtering or can be used to sort along. Attributes are not automatically indexed
 * in order not to waste precious memory space for data that will never be used in search queries.
 *
 * Filtering in attributes is executed by using constraints like {@link And}, {@link Or}, {@link Not},
 * {@link AttributeEquals}, {@link AttributeContains} and many others. Sorting can be achieved with
 * {@link AttributeNatural} or others.
 *
 * Attributes are not recommended for bigger data as they are all loaded at once when {@link AttributeContent}
 * requirement is used. Large data that are occasionally used store in {@link AssociatedData}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AttributeSchemaContract extends NamedSchemaWithDeprecationContract, ConflictResolutionOverrideAwareSchemaContract {

	/**
	 * Representative flag marks the attribute as one of the most important attributes in the entity, or when used
	 * on reference level in the {@link ReferenceSchemaContract} it marks attributes distinguishing duplicated
	 * references to the same entity and is a key attribute for creating distinct indexes for such references.
	 *
	 * In overall, representative attributes should be used in developer tools along with the entity's primary key to
	 * describe the entity or reference to that entity. If the flag is used correctly, it can be very helpful to
	 * developers in quickly finding their way around the data. There should be very few representative attributes
	 * in the entity / reference type, and the ones with uniqueness significance are usually the best to choose.
	 */
	boolean isRepresentative();

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method returns true only if the attribute is unique in the default (i.e. {@link Scope#LIVE}) scope.
	 *
	 * @return true if attribute is unique in the default (i.e. {@link Scope#LIVE}) scope
	 */
	default boolean isUnique() {
		return isUniqueInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @return true if attribute is unique in any scope
	 */
	default boolean isUniqueInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isUniqueInScope);
	}

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @param scope to check attribute is unique in
	 * @return true if attribute is unique in particular scope
	 */
	boolean isUniqueInScope(@Nonnull Scope scope);

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #isUnique()} in that it is possible to have multiple entities with same value
	 * of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different locales.
	 *
	 * @return true if attribute is unique in the default (i.e. {@link Scope#LIVE}) Scope
	 */
	default boolean isUniqueWithinLocale() {
		return isUniqueWithinLocaleInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #isUnique()} in that it is possible to have multiple entities with same value
	 * of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different locales.
	 *
	 * @return true if attribute is unique in any scope
	 */
	default boolean isUniqueWithinLocaleInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isUniqueWithinLocaleInScope);
	}

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #isUnique()} in that it is possible to have multiple entities with same value
	 * of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different locales.
	 *
	 * @param scope to check attribute is unique in
	 * @return true if attribute is unique in particular scope
	 */
	boolean isUniqueWithinLocaleInScope(@Nonnull Scope scope);

	/**
	 * Returns type of uniqueness of the attribute. See {@link #isUnique()} and {@link #isUniqueWithinLocale()}.
	 *
	 * @return type of uniqueness for {@link Scope#DEFAULT_SCOPE} scope
	 */
	@Nonnull
	default AttributeUniquenessType getUniquenessType() {
		return getUniquenessType(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Returns type of uniqueness of the attribute for particular scope. See {@link #isUniqueInScope(Scope)} and
	 * {@link #isUniqueWithinLocaleInScope(Scope)}.
	 *
	 * @param scope to check attribute is unique in
	 * @return type of uniqueness for particular scope
	 */
	@Nonnull
	AttributeUniquenessType getUniquenessType(@Nonnull Scope scope);

	/**
	 * Retrieves a map associating each scope with its corresponding attribute uniqueness type.
	 *
	 * @return map where the keys are scopes and the values are their associated attribute uniqueness types
	 */
	@Nonnull
	Map<Scope, AttributeUniquenessType> getUniquenessTypeInScopes();

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * When attribute is filterable requirement {@link AttributeHistogram}
	 * can be used for this attribute.
	 *
	 * This method returns true only if the attribute is filterable in the default (i.e. {@link Scope#LIVE}) scope.
	 *
	 * @return true if attribute is filterable in the default (i.e. {@link Scope#LIVE}) scope
	 */
	default boolean isFilterable() {
		return isFilterableInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * When attribute is filterable requirement {@link AttributeHistogram}
	 * can be used for this attribute.
	 *
	 * @return true if attribute is filterable in any scope
	 */
	default boolean isFilterableInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isFilterableInScope);
	}

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * When attribute is filterable requirement {@link AttributeHistogram}
	 * can be used for this attribute.
	 *
	 * @param scope to check attribute is filterable in
	 * @return true if attribute is filterable in particular scope
	 */
	boolean isFilterableInScope(@Nonnull Scope scope);

	/**
	 * Retrieves the set of scopes in which filtering by this attribute is possible.
	 *
	 * @return set of scopes in which filtering by this attribute is possible
	 */
	@Nonnull
	Set<Scope> getFilterableInScopes();

	/**
	 * Whether this attribute has a **filter index** in the given scope - the structure an
	 * {@link AttributeFilterAccelerator} accelerates.
	 *
	 * Two declarations produce one: `filterable()` asks for the index directly, and `unique()` gets it implicitly,
	 * because a uniqueness guarantee is maintained by a lookup structure a filter can be served from - which is why
	 * a filter may reach a `unique()`-only attribute without it ever having been declared filterable. An accelerator
	 * therefore only needs *one* of the two, and this method is the single place that spells that rule out so the
	 * schema, the mutations and the engine cannot drift apart on it.
	 *
	 * @param scope the scope to check
	 * @return true when the attribute is filterable or unique in that scope
	 */
	default boolean hasFilterIndexInScope(@Nonnull Scope scope) {
		return isFilterableInScope(scope) || isUniqueInScope(scope);
	}

	/**
	 * The optional {@link AttributeFilterAccelerator accelerators} this attribute declares in the default (i.e.
	 * {@link Scope#DEFAULT_SCOPE}) scope.
	 *
	 * An **empty set is the norm** and means the attribute's filter index is plain - exactly what an attribute
	 * declared before this axis existed has. Each accelerator present in the set buys an additional acceleration at
	 * the price of additional memory and additional write-path work, so nothing is enabled implicitly.
	 *
	 * @return accelerators declared in the default scope, empty when the attribute declares none there
	 */
	@Nonnull
	default Set<AttributeFilterAccelerator> getAccelerators() {
		return getAcceleratorsInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * The optional {@link AttributeFilterAccelerator accelerators} this attribute declares in a particular scope - see
	 * {@link #getAccelerators()} for what an empty result means.
	 *
	 * The result is always empty for a scope the attribute has no {@link #hasFilterIndexInScope(Scope) filter index}
	 * in, because an accelerator without an index to accelerate is not a representable state.
	 *
	 * @param scope the scope to read the accelerators of
	 * @return accelerators declared in that scope, never null
	 */
	@Nonnull
	Set<AttributeFilterAccelerator> getAcceleratorsInScope(@Nonnull Scope scope);

	/**
	 * Retrieves the accelerators declared per scope. A scope declaring none may be absent from the map or map to an
	 * empty set - both readings are equivalent.
	 *
	 * @return map of scope to the accelerators maintained in it, never null
	 */
	@Nonnull
	Map<Scope, Set<AttributeFilterAccelerator>> getAcceleratorsInScopes();

	/**
	 * Collects the schema-consistency errors of this attribute, one message per problem, **without throwing**.
	 *
	 * The accumulating shape mirrors
	 * {@link io.evitadb.api.requestResponse.schema.dto.ReferenceSchema}'s own validation: a caller gathers the
	 * messages of every attribute and every reference and reports them together, so a user fixing a schema sees all
	 * of the problems at once rather than peeling them off one exception at a time.
	 *
	 * It takes **no arguments on purpose**. The only rule it enforces is self-contained on the attribute, so widening
	 * the signature to carry a catalog or an entity schema - as the reference-level validation must - would buy
	 * nothing and would tie every future caller to state it does not need.
	 *
	 * **This runs on an assembled schema only.** It is called after a batch of mutations has been applied, never
	 * between two of them, which is what lets it check a cross-field invariant that any single mutation is entitled
	 * to leave temporarily broken.
	 *
	 * @return one message per consistency problem, empty when the attribute is valid
	 */
	@Nonnull
	default Stream<String> validate() {
		Stream<String> errors = Stream.empty();
		// an accelerator speeds up a filter index, so it needs one to exist in its own scope - `filterable()` or
		// `unique()` in *that* scope, since either builds the index a filter is served from
		for (final Map.Entry<Scope, Set<AttributeFilterAccelerator>> entry : getAcceleratorsInScopes().entrySet()) {
			final Scope scope = entry.getKey();
			if (!entry.getValue().isEmpty() && !hasFilterIndexInScope(scope)) {
				errors = Stream.concat(
					errors,
					Stream.of(
						"Attribute `" + getName() + "` declares filter accelerators " + entry.getValue() +
							" in scope `" + scope + "`, but it has no filter index there! Filter accelerators speed " +
							"up an existing filter index - make the attribute filterable or unique in `" + scope +
							"`, or drop the accelerators."
					)
				);
			}
		}
		return errors;
	}

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the sortable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * @return true if attribute is sortable in any scope
	 */
	default boolean isSortableInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isSortableInScope);
	}

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the sortable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * This method returns true only if the attribute is sortable in the default (i.e. {@link Scope#LIVE}) scope.
	 *
	 * @return true if attribute is sortable in the default (i.e. {@link Scope#LIVE}) scope
	 */
	default boolean isSortable() {
		return isSortableInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the sortable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * @param scope to check attribute is sortable in
	 * @return true if attribute is sortable in particular scope
	 */
	boolean isSortableInScope(@Nonnull Scope scope);

	/**
	 * Retrieves the set of scopes in which sorting by this attribute is possible.
	 *
	 * @return set of scopes in which sorting by this attribute is possible
	 */
	@Nonnull
	Set<Scope> getSortableInScopes();

	/**
	 * When attribute is localized, it has to be ALWAYS used in connection with specific {@link java.util.Locale}.
	 */
	boolean isLocalized();

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity. When the attribute is also {@link #isLocalized() localized},
	 * the presence is enforced only when the entity is {@link EntityContract#getAllLocales() localized} to particular
	 * language (it means it has at least one attribute or associated data of particular locale).
	 */
	boolean isNullable();

	/**
	 * Type of the attribute. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()} or its array.
	 * The type is never a primitive type although Evita can work with those. Due to external APIs the values are always
	 * internally represented as wrapping types in order to avoid confusion.
	 */
	@Nonnull
	Class<? extends Serializable> getType();

	/**
	 * Returns attribute type that represents non-array type class. I.e. method just unwraps array types to plain ones.
	 */
	@Nonnull
	Class<? extends Serializable> getPlainType();

	/**
	 * Default value is used when the entity is created without this attribute specified. Default values allow to pass
	 * non-null checks even if no attributes of such name are specified. The default value is used when new entity is
	 * created and the attribute has no value defined.
	 *
	 * @see #isNullable()
	 */
	@Nullable
	Serializable getDefaultValue();

	/**
	 * Determines how many fractional places are important when entities are compared during filtering or sorting. It is
	 * significant to know that all values of this attribute will be converted to {@link java.lang.Integer}, so the attribute
	 * number must not ever exceed maximum limits of {@link java.lang.Integer} type when scaling the number by the power
	 * of ten using `indexedDecimalPlaces` as exponent.
	 */
	int getIndexedDecimalPlaces();

	/**
	 * Returns the locale-agnostic {@link AttributeKey} of this attribute.
	 *
	 * Implementations backed by an immutable schema are expected to return a shared, canonical instance - the write
	 * path creates these keys per attribute per entity only to compare them or to look values up by them, and the
	 * schema outlives every such use. Callers must therefore treat the returned key as shared and must not rely on
	 * its identity; all engine usages are equality-based.
	 *
	 * @return attribute key without a locale
	 */
	@Nonnull
	default AttributeKey getAttributeKey() {
		return new AttributeKey(getName());
	}

	/**
	 * Returns the {@link AttributeKey} of this attribute for the passed locale, or the locale-agnostic one when
	 * `locale` is null. See {@link #getAttributeKey()} for the sharing contract.
	 *
	 * @param locale locale of the requested key, null for the locale-agnostic one
	 * @return attribute key for the passed locale
	 */
	@Nonnull
	default AttributeKey getAttributeKey(@Nullable Locale locale) {
		return locale == null ? getAttributeKey() : new AttributeKey(getName(), locale);
	}

}
