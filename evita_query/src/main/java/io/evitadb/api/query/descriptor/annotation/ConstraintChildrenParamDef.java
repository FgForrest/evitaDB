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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;

import javax.annotation.Nonnull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint children parameter definition that marks concrete query
 * constructor's (one that is annotated with {@link ConstraintCreatorDef}) parameter as children parameter which contains
 * one or more children of same query type.
 * Currently, only one parameter in single creator can be marked with this annotation.
 * <p>
 * Additionally, allowed children types can be limited with either {@link #allowed()}
 * or {@link #forbidden()} which take query classes.
 * <p>
 * This data is then processed by {@link ConstraintDescriptorProvider}.
 *
 * @see ConstraintCreatorDef
 * @see ConstraintDef
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConstraintChildrenParamDef {

	/**
	 * If each child query can be passed only once in this list parameter.
	 */
	boolean uniqueChildren() default false;

	/**
	 * Set of allowed child constraints. Constraint not specified in this set will be forbidden.
	 */
	@Nonnull
	Class<? extends Constraint<?>>[] allowed() default {};

	/**
	 * Set of forbidden child constraints. All constraints are allowed except of these.
	 */
	@Nonnull
	Class<? extends Constraint<?>>[] forbidden() default {};
}
