/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of object containing all name variants of certain object (usually schema).
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface NameVariantsDescriptor {

	PropertyDescriptor CAMEL_CASE = PropertyDescriptor.builder()
		.name("camelCase")
		.description("""
			[Camel case variant](https://en.wikipedia.org/wiki/Camel_case)
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor PASCAL_CASE = PropertyDescriptor.builder()
		.name("pascalCase")
		.description("""
			[Pascal case variant](https://www.theserverside.com/definition/Pascal-case)
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor SNAKE_CASE = PropertyDescriptor.builder()
		.name("snakeCase")
		.description("""
			[Snake case variant](https://en.wikipedia.org/wiki/Snake_case)
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor UPPER_SNAKE_CASE = PropertyDescriptor.builder()
		.name("upperSnakeCase")
		.description("""
			[Capitalized snake case variant](https://en.wikipedia.org/wiki/Snake_case)
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor KEBAB_CASE = PropertyDescriptor.builder()
		.name("kebabCase")
		.description("""
			[Kebab case variant](https://en.wikipedia.org/wiki/Letter_case#Kebab_case)
			""")
		.type(nonNull(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("NameVariants")
		.description("""
			Contains all name variants of parent object.
			""")
		.staticProperties(List.of(
			CAMEL_CASE,
			PASCAL_CASE,
			SNAKE_CASE,
			UPPER_SNAKE_CASE,
			KEBAB_CASE
		))
		.build();
}
