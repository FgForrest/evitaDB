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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.*;

/** Describes the declared SynonymGroup value without exposing compiled analysis policies. */
public interface SynonymGroupDescriptor {
	/** Words in this synonym group. */
	PropertyDescriptor WORDS = PropertyDescriptor.builder()
		.name("words")
		.description("Words in this synonym group.")
		.type(nonNull(String[].class))
		.build();
	/** Words a query treats as equal. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SynonymGroup")
		.description("Words a query treats as equal: a query word of the group also finds the other words of it.")
		.staticProperties(List.of(WORDS))
		.build();
	/** Input form of a synonym group; the converter refuses a group without `words`. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputSynonymGroup")
		.staticProperty(PropertyDescriptor.from(WORDS).type(nullable(String[].class)).build())
		.build();
}
