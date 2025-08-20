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

package io.evitadb.api.query.descriptor.annotation;

import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintDomain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint definition that describes concrete constraint. This definition when processed can be later used when
 * building some system based on these constraints (e.g. custom query language that can be translated to this original).
 * <p>
 * Such an annotated constraint must have also specified creator constructor using {@link Creator} and
 * its parameters with {@link Classifier}, {@link Value} and {@link Child}.
 * <p>
 * This data is then processed by {@link ConstraintDescriptorProvider}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConstraintDefinition {

	/**
	 * Base short name of constraint in defined type and property type categorization. Should specify name of condition,
	 * operation or something like that, that this constraint represent (e.g. "equals", "fetch").
	 * Its format must be in camelCase and may be suffixed in concrete creators with their suffixes, making full name of
	 * constraint.
	 */
	String name();

	/**
	 * Short description of what the constraint do. It is used as quick explanation not full documentation.
	 */
	String shortDescription();

	/**
	 * Relative link to user documentation where this constraint is described in more detail.
	 * E.g., "/documentation/query/filtering/price#price-in-price-lists" for constraint "PriceInPriceLists".
	 */
	String userDocsLink();

	/**
	 * Set of domains in which this constraint is supported in and can be used in when querying.
	 * Default is {@link ConstraintDomain#ENTITY}.
	 */
	ConstraintDomain[] supportedIn() default { ConstraintDomain.GENERIC };

	/**
	 * Defines value data types of target data this query can operate on.
	 */
	ConstraintSupportedValues supportedValues() default @ConstraintSupportedValues();
}
