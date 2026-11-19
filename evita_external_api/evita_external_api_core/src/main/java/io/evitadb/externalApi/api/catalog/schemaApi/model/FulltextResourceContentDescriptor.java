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

import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.*;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.*;

/** Describes the declared FulltextResourceContent value without exposing compiled analysis policies. */
public interface FulltextResourceContentDescriptor {
	/** Kind of the declared resource. */
	PropertyDescriptor KIND = PropertyDescriptor.builder()
		.name("kind")
		.description("Kind of the declared resource.")
		.type(nonNull(FulltextResourceContent.FulltextResourceKind.class))
		.build();
	/** Words of a word list; a synonym list neither has nor accepts them. */
	PropertyDescriptor WORDS = PropertyDescriptor.builder()
		.name("words")
		.description("Words of a word list in declaration order; a synonym list neither has nor accepts them.")
		.type(nullable(String[].class))
		.build();
	/** Groups of a synonym list; a word list neither has nor accepts them. */
	PropertyDescriptor GROUPS = PropertyDescriptor.builder()
		.name("groups")
		.description("Groups of a synonym list in declaration order; a word list neither has nor accepts them.")
		.type(nullableListRef(SynonymGroupDescriptor.THIS))
		.build();
	/** Content of a resource, shaped by its kind. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("FulltextResourceContent")
		.description("Content of a resource: the words of a word list or the groups of a synonym list, by `kind`.")
		.staticProperties(List.of(KIND, WORDS, GROUPS))
		.build();
	/**
	 * Input form of resource content. `kind` is required by the converter, the field of the other kind is refused,
	 * and an omitted field of the declared kind is read as an empty list.
	 */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputFulltextResourceContent")
		.description("""
			Content of a resource. Send `kind` and only the field of that kind: `words` for a word list, `groups` for
			a synonym list.
			""")
		.staticProperty(PropertyDescriptor.from(KIND)
			.type(nullable(FulltextResourceContent.FulltextResourceKind.class)).build())
		.staticProperty(PropertyDescriptor.from(WORDS).type(nullable(String[].class)).build())
		.staticProperty(PropertyDescriptor.from(GROUPS)
			.type(nullableListRef(SynonymGroupDescriptor.THIS_INPUT)).build())
		.build();
}
