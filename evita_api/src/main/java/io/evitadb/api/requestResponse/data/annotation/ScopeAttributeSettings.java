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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation is used only within {@link @Attribute} annotation to define settings for the attribute in the particular
 * scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface ScopeAttributeSettings {

	/**
	 * Definition of the scope for which the settings are applied.
	 */
	Scope scope() default Scope.LIVE;

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

}
