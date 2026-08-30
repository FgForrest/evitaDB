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

import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing the scope-specific optional accelerators an attribute's filter index maintains.
 * It is used to represent both input ({@link ScopedAttributeFilterAccelerators}) in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ScopedAttributeFilterAcceleratorsDescriptor extends ScopedDataDescriptor {

	PropertyDescriptor ACCELERATORS = PropertyDescriptor.builder()
		.name("accelerators")
		.description("""
			Optional accelerations the attribute's filter index maintains in the given scope. Each accelerator costs
			additional memory and additional write-path work, so none of them is implied by marking the attribute
			filterable or unique - only the ones listed here are maintained.

			An empty array means no acceleration in the scope, which is the default and what every attribute declared
			before this axis existed has.
			""")
		.type(nonNull(AttributeFilterAccelerator[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedAttributeFilterAccelerators")
		.description("""
			Represents combination of filter accelerators and the entity scope they should be maintained in.
			""")
		.staticProperties(List.of(SCOPE, ACCELERATORS))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedAttributeFilterAccelerators")
		.build();
}
