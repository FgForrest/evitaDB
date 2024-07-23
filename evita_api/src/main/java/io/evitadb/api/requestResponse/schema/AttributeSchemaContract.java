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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

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
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 * {@link AttributeSchema#getType() Type} of the unique attribute must implement {@link Comparable} interface.
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 */
	boolean isUnique();

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
	 */
	boolean isUniqueWithinLocale();

	/**
	 * Returns type of uniqueness of the attribute. See {@link #isUnique()} and {@link #isUniqueWithinLocale()}.
	 * @return type of uniqueness
	 */
	@Nonnull
	AttributeUniquenessType getUniquenessType();

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * When attribute is filterable requirement {@link AttributeHistogram}
	 * can be used for this attribute.
	 */
	boolean isFilterable();

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchema#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 */
	boolean isSortable();

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
