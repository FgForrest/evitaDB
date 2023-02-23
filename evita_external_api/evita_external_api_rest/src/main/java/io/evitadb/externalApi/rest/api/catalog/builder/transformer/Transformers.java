/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.builder.transformer;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Class for static initialization of transformers.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Transformers {
	public static final EndpointDescriptorToOpenApiSchemaTransformer STATIC_ENDPOINT_TRANSFORMER = new EndpointDescriptorToOpenApiSchemaTransformer();
	public static final PropertyDataTypeDescriptorToOpenApiSchemaTransformer PROPERTY_DATA_TYPE_TRANSFORMER = new PropertyDataTypeDescriptorToOpenApiSchemaTransformer();
	public static final PropertyDescriptorToOpenApiSchemaTransformer PROPERTY_TRANSFORMER = new PropertyDescriptorToOpenApiSchemaTransformer(PROPERTY_DATA_TYPE_TRANSFORMER);
	public static final ObjectDescriptorToOpenApiSchemaTransformer OBJECT_TRANSFORMER = new ObjectDescriptorToOpenApiSchemaTransformer(PROPERTY_TRANSFORMER);
//	public static final DataTypeDescriptorToOpenApiSchemaTransformer DATA_TYPE_TRANSFORMER = new DataTypeDescriptorToOpenApiSchemaTransformer();
}
