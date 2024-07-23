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
 * Constraint classifier parameter definition that marks concrete query
 * constructor's (one that is annotated with {@link Creator}) parameter as classifier.
 * Currently, only one parameter in single creator can be marked with this annotation.
 * <p>
 * Such an annotated parameter must have supported classifier type by Evita, e.g. {@link String} and specifies usually
 * entity type or attribute name.
 * If dynamic classifier cannot be used, alternative is to specify {@link Creator#implicitClassifier()}.
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
public @interface Classifier {
}
