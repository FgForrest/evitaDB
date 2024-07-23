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

package io.evitadb.api.requestResponse.data.annotation;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation is used to mark a field, getter method or record component as associated data of the entity. This
 * information, annotation attribute values and target field/method/component signature allows to generate
 * {@link AssociatedDataSchemaContract}.
 *
 * Type of the associated data ({@link AssociatedDataSchemaContract#getType()}) is derived from the type of the field, property
 * or component.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface AssociatedData {

	/**
	 * Name of the associated data. Use camel-case naming style.
	 * Propagates to {@link AssociatedDataSchemaContract#getName()}
	 * If left empty the name is taken from the name of the field / property / component.
	 */
	String name() default "";

	/**
	 * Description of the associated data. Use Markdown format.
	 * Propagates to {@link AssociatedDataSchemaContract#getDescription()}.
	 */
	String description() default "";

	/**
	 * Marks associated data as deprecated and allows to specify the reason for it. Use Markdown format.
	 * Propagates to {@link AssociatedDataSchemaContract#getDeprecationNotice()}.
	 */
	String deprecated() default "";

	/**
	 * Allows associated data of this name to be missing in the entities.
	 * Propagates to {@link AssociatedDataSchemaContract#isNullable()}.
	 */
	boolean nullable() default false;

	/**
	 * Sets associated data of this type to be locale sensitive. I.e. to separate values for different locales.
	 * Propagates to {@link AssociatedDataSchemaContract#isLocalized()}.
	 */
	boolean localized() default false;

}
