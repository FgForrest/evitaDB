/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.dataType.Scope;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation is used only within {@link @Reference} annotation to define settings for the references in the particular
 * scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface ScopeReferenceSettings {

	/**
	 * Definition of the scope for which the settings are applied.
	 */
	Scope scope() default Scope.LIVE;

	/**
	 * Enables filtering / sorting by attributes of reference of this name.
	 * Propagates to {@link ReferenceSchemaContract#getReferenceIndexType(Scope)}.
	 */
	ReferenceIndexType indexed() default ReferenceIndexType.NONE;

	/**
	 * Enables facet computation for reference of this name.
	 * Propagates to {@link ReferenceSchemaContract#isFacetedInScope(Scope)}.
	 */
	boolean faceted() default false;

	/**
	 * Defines a condition to further narrow down the facet scope.
	 * This is only evaluated if {@link #faceted()} is set to true.
	 */
	Expression facetedPartially() default @Expression;

	/**
	 * Enables histogram (bucketed) computation for reference of this name
	 * in this scope.
	 */
	Histogram bucketed() default @Histogram;

	/**
	 * Defines a condition to further narrow down the histogram scope.
	 * This is only evaluated if {@link #bucketed()} is set up.
	 */
	Expression bucketedPartially() default @Expression;

}
