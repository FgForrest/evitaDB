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

import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing scope-specific indexed components of a reference.
 * It is used to represent both input ({@link ScopedReferenceIndexedComponents}) in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ScopedReferenceIndexedComponentsDescriptor extends ScopedDataDescriptor {

	PropertyDescriptor INDEXED_COMPONENTS = PropertyDescriptor.builder()
		.name("indexedComponents")
		.description("""
			Determines which parts of a reference relationship are indexed in the given scope.
			Controls whether the referenced entity itself, the referenced group entity, or both
			are maintained in the index for filtering and querying operations.
			""")
		.type(nonNull(ReferenceIndexedComponents[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedReferenceIndexedComponents")
		.description("""
			Represents combination of reference indexed components and entity scope
			it should be applied to.
			""")
		.staticProperties(List.of(SCOPE, INDEXED_COMPONENTS))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedReferenceIndexedComponents")
		.build();
}
