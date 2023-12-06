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

package io.evitadb.api.requestResponse.data.annotation;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation is used to mark a field, getter method or record component as attribute of the entity. This
 * information, annotation attribute values and target field/method/component signature allows to generate
 * {@link AttributeSchemaContract}.
 *
 * Type of the attribute ({@link AttributeSchemaContract#getType()}) is derived from the type of the field, property
 * or component.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface Attribute {

	/**
	 * Name of the attribute. Use camel-case naming style.
	 * Propagates to {@link AttributeSchemaContract#getName()}
	 * If left empty the name is taken from the name of the field / property / component.
	 */
	String name() default "";

	/**
	 * Description of the attribute. Use Markdown format.
	 * Propagates to {@link AttributeSchemaContract#getDescription()}.
	 */
	String description() default "";

	/**
	 * Marks attribute as deprecated and allows to specify the reason for it. Use Markdown format.
	 * Propagates to {@link AttributeSchemaContract#getDeprecationNotice()}.
	 */
	String deprecated() default "";

	/**
	 * Makes this attribute the global one. Global attributes are specified on catalog level and their definition is
	 * shared among all entities that refer to attribute of such name.
	 * Propagates to {@link CatalogSchemaContract#getAttribute(String)}.
	 */
	boolean global() default false;

	/**
	 * Allows attribute of this name to be missing in the entities.
	 * Propagates to {@link AttributeSchemaContract#isNullable()}.
	 */
	boolean nullable() default false;

	/**
	 * Enforces attribute of this name to be unique among other entities of the same type. Also enables filtering by
	 * this attribute.
	 * Propagates to {@link AttributeSchemaContract#isUnique()}.
	 */
	AttributeUniquenessType unique() default AttributeUniquenessType.NOT_UNIQUE;

	/**
	 * Enforces attribute of this name to be unique among all entities in the same catalog.
	 * Propagates to {@link GlobalAttributeSchemaContract#isUniqueGlobally()}.
	 */
	GlobalAttributeUniquenessType uniqueGlobally() default GlobalAttributeUniquenessType.NOT_UNIQUE;

	/**
	 * Enables filtering by attribute of this name.
	 * Propagates to {@link AttributeSchemaContract#isFilterable()}.
	 */
	boolean filterable() default false;

	/**
	 * Enables ordering/sorting by attribute of this name.
	 * Propagates to {@link AttributeSchemaContract#isSortable()}.
	 */
	boolean sortable() default false;

	/**
	 * Sets attribute of this type to be locale sensitive. I.e. to separate values for different locales.
	 * Propagates to {@link AttributeSchemaContract#isLocalized()}.
	 */
	boolean localized() default false;

	/**
	 * If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	 * primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	 * affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	 * helpful to developers in quickly finding their way around the data. There should be very few representative
	 * attributes in the entity type, and the unique ones are usually the best to choose.
	 */
	boolean representative() default false;

	/**
	 * Sets the number of indexed decimal places. Makes sense only for attributes of type {@link java.math.BigDecimal}.
	 * Determines the count of decimal places that will be taken into an account when value is converted to int type
	 * that is used for filtering / sorting.
	 */
	int indexedDecimalPlaces() default 0;

}
