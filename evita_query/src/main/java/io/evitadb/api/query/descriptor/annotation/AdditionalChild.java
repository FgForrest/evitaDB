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
import io.evitadb.api.query.descriptor.ConstraintType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint additional children parameter definition that marks concrete constraint
 * constructor's (one that is annotated with {@link Creator}) parameter as additional child parameter which contains
 * one or more children of different constraint type.
 * Currently, only one parameter in single creator can be marked with this annotation for certain constraint type. However,
 * there may be multiple {@link AdditionalChild} marker parameters if each have different {@link ConstraintType} which
 * is not same as parent type.
 * <p>
 * Currently, supported types are kind of limited to what makes currently sense which enabled us some great simplifications
 * in building and resolving constraint trees.
 * The type has following requirements:
 * <ul>
 *     <li>must be a separate {@link io.evitadb.api.query.ConstraintContainer}</li>
 *     <li>must be of a {@link io.evitadb.api.query.descriptor.ConstraintPropertyType#GENERIC} property type</li>
 *     <li>must have only one parameter which must be a direct child parameter</li>
 * </ul>
 * </p>
 * <p>
 * <p>
 * This data is then processed by {@link ConstraintDescriptorProvider}.
 *
 * @see Creator
 * @see ConstraintDefinition
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdditionalChild {

	/**
	 * Specifies domain of child constraints. By default, domain of parent constraint (usually resolved from its
	 * {@link io.evitadb.api.query.descriptor.ConstraintPropertyType}) is passed to children. However, custom domain
	 * can be specified for children using this parameter.
	 * <p>
	 * <b>Note: </b>that not all combinations of parent constraint domain and custom children are possible. E.g. if custom domain
	 * uses references underneath, the parent constraint domain must use references as well, because otherwise
	 * there is no way how to specify the targeted reference. One exception is the {@link ConstraintDomain#HIERARCHY}
	 * which can be used on hierarchical collection without reference.
	 */
	ConstraintDomain domain() default ConstraintDomain.DEFAULT;
}
