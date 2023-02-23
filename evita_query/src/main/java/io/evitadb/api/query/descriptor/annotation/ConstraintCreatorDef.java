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

package io.evitadb.api.query.descriptor.annotation;

import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint creator definition that marks concrete query's (one that is annotated with {@link ConstraintDef})
 * constructor as constructor for creating this query. Multiple constructors may be marked, however each must have
 * unique suffix and the one without suffix is considered default one. Also, combination of query name and suffix
 * must be unique across all constraints of same type and property type.
 * <p>
 * Such an annotated constructor must have all of its parameters annotated with {@link ConstraintClassifierParamDef},
 * {@link ConstraintValueParamDef} or {@link ConstraintChildrenParamDef}.
 * <p>
 * This data is then processed by {@link ConstraintDescriptorProvider}.
 *
 * @see ConstraintDef
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Target(ElementType.CONSTRUCTOR)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConstraintCreatorDef {

	/**
	 * If query has more creator constructors, each must have unique suffix across creators of that query.
	 * Creator with no suffix is considered default.
	 * Also, combination of query name and suffix
	 * must be unique across all constraints of same type and property type.
	 * Its format must be in camelCase and when joined with query name, first letter is capitalized.
	 */
	String suffix() default "";

	/**
	 * Implicit classifier if query needs a classifier that cannot be passed as parameter via
	 * {@link ConstraintClassifierParamDef}.
	 * <p>
	 * <b>Note:</b> both implicit classifier and classifier parameter cannot be specified at the same time. Also, only
	 * one type of implicit classifier can be specified.
	 */
	boolean silentImplicitClassifier() default false;

	/**
	 * Implicit classifier if query needs a fixed classifier that cannot be passed as parameter via
	 * {@link ConstraintClassifierParamDef} or resolved by system automatically.
	 * <p>
	 * <b>Note:</b> both implicit classifier and classifier parameter cannot be specified at the same time. Also, only
	 * 	one type of implicit classifier can be specified.
	 */
	String implicitClassifier() default "";
}
