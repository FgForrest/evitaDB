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

import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.dataType.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation is used to specify a sortable attribute compound on an entity class. All the attributes defined in the
 * compound must exist on the entity class. The order of the attributes in the compound defines the sorting order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Repeatable(SortableAttributeCompounds.class)
public @interface SortableAttributeCompound {

	/**
	 * Name of the compound. Use camel-case naming style.
	 */
	String name();

	/**
	 * Description of the sortable attribute compound. Use Markdown format.
	 * Propagates to {@link SortableAttributeCompoundSchemaContract#getDescription()}.
	 */
	String description() default "";

	/**
	 * Marks sortable attribute compound as deprecated and allows to specify the reason for it. Use Markdown format.
	 * Propagates to {@link SortableAttributeCompoundSchemaContract#getDeprecationNotice()}.
	 */
	String deprecated() default "";

	/**
	 * Allows to define different settings for different scopes. If not specified, the general settings apply only to
	 * the {@link Scope#LIVE} and in the {@link Scope#ARCHIVED} the sortable attribute compound is not indexed whatsoever (not filterable,
	 * not sortable, not unique). If scope settings are specified for {@link Scope#LIVE}, the general settings are
	 * ignored completely.
	 */
	Scope[] scope() default { Scope.LIVE };

	/**
	 * At least two attribute sources must be defined to create a compound. The order of the annotations in the array
	 * define the sorting order (primary, secondary, tertiary, ...).
	 */
	AttributeSource[] attributeElements();

}
