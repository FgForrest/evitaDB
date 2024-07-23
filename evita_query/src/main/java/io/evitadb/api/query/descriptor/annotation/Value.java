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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint value parameter definition that marks concrete query
 * constructor's (one that is annotated with {@link Creator}) parameter as value parameter which should
 * be some primitive data type such as {@link String}, not another query.
 * Multiple parameters can be marked with this annotation.
 * <p>
 * Such an annotated parameter must  have generic data type (in case where concrete data type can be discovered in
 * some other way, e.g. schema) or data type supported {@link io.evitadb.api.dataType.EvitaDataTypes}.
 * <p>
 * This data is then processed by {@link ConstraintDescriptorProvider}.
 *
 * @see Creator
 * @see ConstraintDefinition
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Value {

	/**
	 * If true, value is required to be plain type of original one (e.g. if original type is integer range, this query
	 * requires integer to be passed)
	 */
	boolean requiresPlainType() default false;
}
