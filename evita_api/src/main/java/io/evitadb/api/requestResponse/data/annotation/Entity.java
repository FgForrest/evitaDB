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

package io.evitadb.api.requestResponse.data.annotation;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation is used to mark a class as entity. This information, annotation attribute values and target class
 * signature allows to generate {@link EntitySchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Entity {

	/**
	 * Name of the attribute. Use camel-case naming style.
	 * Propagates to {@link EntitySchemaContract#getName()}
	 * If left empty the name is taken from the name of the simple class name.
	 */
	String name() default "";

	/**
	 * Description of the entity. Use Markdown format.
	 * Propagates to {@link EntitySchemaContract#getDescription()}.
	 */
	String description() default "";

	/**
	 * Marks entity as deprecated and allows to specify the reason for it. Use Markdown format.
	 * Propagates to {@link EntitySchemaContract#getDeprecationNotice()}.
	 */
	String deprecated() default "";

	/**
	 * Names all locales that are allowed for localized attributes / associated data of entities of this type.
	 * Propagates to {@link EntitySchemaContract#getLocales()}.
	 *
	 * Please use IETF BCP 47 language tag string.
	 * @see java.util.Locale#forLanguageTag(String)
	 */
	String[] allowedLocales() default {};

	/**
	 * Names all modes that are allowed for schema evolution (by default free evolution is allowed).
	 */
	EvolutionMode[] allowedEvolution() default {
		EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
		EvolutionMode.ADDING_ATTRIBUTES,
		EvolutionMode.ADDING_ASSOCIATED_DATA,
		EvolutionMode.ADDING_HIERARCHY,
		EvolutionMode.ADDING_LOCALES,
		EvolutionMode.ADDING_CURRENCIES,
		EvolutionMode.ADDING_REFERENCES,
		EvolutionMode.ADDING_PRICES
	};

}
