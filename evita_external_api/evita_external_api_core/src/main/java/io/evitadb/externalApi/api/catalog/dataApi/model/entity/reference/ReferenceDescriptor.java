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

package io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents a generic version of {@link io.evitadb.api.requestResponse.data.ReferenceContract} that can be used as base
 * type for any reference.
 *
 * Note: this descriptor has a static structure.
 *
 * @see ReferenceWithReferencedEntityDescriptor
 * @see ReferenceWithGroupDescriptor
 * @see ReferenceWithAttributesDescriptor
 * @see EntityReferenceDescriptor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ReferenceDescriptor {

	PropertyDescriptor REFERENCED_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("referencedPrimaryKey")
		.description("""
            Returns primary key of the referenced (internal or external) entity.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.builder()
		.name("Reference")
		.description("Represents a reference to another entity.")
		.staticProperty(REFERENCED_PRIMARY_KEY)
		.build();
}
