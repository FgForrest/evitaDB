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

import io.evitadb.dataType.EvitaDataTypes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines value data types of target data this query can operate on.
 * One can specify either specific array of classes through {@link #supportedTypes()} or specify that
 * all data types are supported through {@link #allTypesSupported()} (all supported by {@link EvitaDataTypes}.
 * Also, target data may be arrays, thus query can specify if it supports target arrays through {@link #arraysSupported()}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConstraintSupportedValues {

	/**
	 * Set of value data types of target data this query can operate on. Cannot be primitive types.
	 */
	Class<?>[] supportedTypes() default {};

	/**
	 * Specifies that all data types supported by Evita will be supported. During processing this is resolved to
	 * {@link EvitaDataTypes#getSupportedDataTypes()}.
	 */
	boolean allTypesSupported() default false;

	/**
	 * Flag stating that those {@link #supportedTypes()} can be in arrays in queried data.
	 */
	boolean arraysSupported() default false;
}
