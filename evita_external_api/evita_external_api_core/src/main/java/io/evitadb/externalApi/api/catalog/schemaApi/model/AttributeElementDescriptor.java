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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of {@link io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface AttributeElementDescriptor {

	PropertyDescriptor ATTRIBUTE_NAME = PropertyDescriptor.builder()
		.name("attributeName")
		.description("""
			Name of the existing attribute in the same schema.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor DIRECTION = PropertyDescriptor.builder()
		.name("direction")
		.description("""
			Direction of the sorting.
			""")
		.type(nonNull(OrderDirection.class))
		.build();
	PropertyDescriptor BEHAVIOUR = PropertyDescriptor.builder()
		.name("behaviour")
		.description("""
			Behaviour of the null values.
			""")
		.type(nonNull(OrderBehaviour.class))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("InputAttributeElement")
		.description("""
			Attribute element is a part of the sortable compound. It defines the attribute name, the direction of the
			sorting and the behaviour of the null values. The attribute name refers to the existing attribute defined in the
			schema.
			""")
		.staticProperty(ATTRIBUTE_NAME)
		.staticProperty(DIRECTION)
		.staticProperty(BEHAVIOUR)
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AttributeElement")
		.description("""
			Attribute element is a part of the sortable compound. It defines the attribute name, the direction of the
			sorting and the behaviour of the null values. The attribute name refers to the existing attribute defined in the
			schema.
			""")
		.staticProperty(ATTRIBUTE_NAME)
		.staticProperty(DIRECTION)
		.staticProperty(BEHAVIOUR)
		.build();
}
