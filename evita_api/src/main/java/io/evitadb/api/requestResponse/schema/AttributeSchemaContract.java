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
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

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
public interface AttributeSchemaContract extends NamedSchemaWithDeprecationContract {

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
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
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
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
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
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * @param scope to check attribute is filterable in
	 * @return true if attribute is filterable in particular scope
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

}
