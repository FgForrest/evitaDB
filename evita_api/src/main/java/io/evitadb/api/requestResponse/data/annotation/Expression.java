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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation representing a value expression. Used within other annotations
 * (e.g. {@link Histogram}) to define computed values.
 *
 * Check the `documentation/user/en/query/expression-language.md` for possible expressions. Typical example is
 * <pre>
 *     $referencedEntity.attributes['basicUnitValue'] ?? 0.0
 * </pre>
 *
 * Empty string (default) means no expression is defined.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Expression {

	/**
	 * Value expression string. Supports
	 * `referencedEntity.attributes['name']` syntax and fallback values
	 * via `!'default'`.
	 */
	String value() default "";

}
