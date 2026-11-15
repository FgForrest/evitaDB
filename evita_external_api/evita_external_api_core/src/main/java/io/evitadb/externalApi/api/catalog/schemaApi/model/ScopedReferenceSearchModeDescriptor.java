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

import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/** Declares how referenced text participates in fulltext search in one scope. */
public interface ScopedReferenceSearchModeDescriptor extends ScopedDataDescriptor {
	/** Composition mode for this scope. */
	PropertyDescriptor MODE = PropertyDescriptor.builder()
		.name("mode")
		.description("""
			How text of the referenced entities participates in the owner's fulltext search in this scope:
			`COMPOSITION` counts the referenced entity's searchable text as the owner's own text.
			""")
		.type(nonNull(ReferenceSearchMode.class))
		.build();
	/** Scoped composition declaration output. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedReferenceSearchMode")
		.description("""
			Pair of an entity scope and the mode in which referenced text joins the owner's fulltext search there.
			""")
		.staticProperties(List.of(SCOPE, MODE))
		.build();
	/** Scoped composition declaration input. */
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedReferenceSearchMode")
		.build();
}
