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
 * Names one String parameter within an {@link AnalysisStepDef} annotation.
 * The schema analyzer preserves the value verbatim; the installed server provider validates its meaning.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface StepParameter {
	/**
	 * Identifies a parameter accepted by the selected analysis provider.
	 *
	 * @return nonblank, case-sensitive parameter name
	 */
	@Nonnull
	String name();

	/**
	 * Supplies the literal String value passed to the provider. No locale conversion or parsing occurs here.
	 *
	 * @return provider parameter value
	 */
	@Nonnull
	String value();
}
