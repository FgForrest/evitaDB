/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import javax.annotation.Nonnull;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one ordered operation in {@link Attribute#normalized()} for a schema inferred from a Java model.
 * The server resolves the stable step name against installed providers when adopting the schema. Annotation
 * parameters are Strings; typed parameter values can be supplied through the programmatic schema API.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface AnalysisStepDef {
	/**
	 * Identifies the installed analysis provider. The name is stored in the schema, independently of its Java class.
	 *
	 * @return stable provider name, for example `lowercase`
	 */
	@Nonnull
	String step();

	/**
	 * Supplies the provider's named String parameters. An empty array describes a parameterless operation.
	 *
	 * @return ordered parameter declarations with unique names
	 */
	@Nonnull
	StepParameter[] parameters() default {};
}
