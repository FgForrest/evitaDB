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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.HierarchicalPlacementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDataTypeDescriptorToOpenApiTypeTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.dto.OpenApiProperty;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.rest.api.dto.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.dto.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.dto.OpenApiScalar.scalarFrom;
import static io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference.typeRefTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link PropertyDescriptorToOpenApiPropertyTransformer}
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class PropertyDescriptorToOpenApiPropertyTransformerTest {

	private CatalogSchemaBuildingContext context;
	private PropertyDescriptorToOpenApiPropertyTransformer transformer;

	@BeforeEach
	void setUp() {
		context = mock(CatalogSchemaBuildingContext.class);
		transformer = new PropertyDescriptorToOpenApiPropertyTransformer(
			new PropertyDataTypeDescriptorToOpenApiTypeTransformer(context)
		);
	}

	@Test
	void shouldTransformPropertyWithScalar() {
		final OpenApiProperty property = transformer.apply(EntityDescriptor.PRIMARY_KEY).build();
		assertEquals(EntityDescriptor.PRIMARY_KEY.name(), property.getName());
		assertEquals(EntityDescriptor.PRIMARY_KEY.description(), property.getDescription());

		final OpenApiSimpleType type = property.getType();
		assertEquals(nonNull(scalarFrom(Integer.class)), type);
		verify(context, never()).registerType(any());
	}

	@Test
	void shouldTransformPropertyWithReference() {
		final OpenApiProperty property = transformer.apply(EntityDescriptor.HIERARCHICAL_PLACEMENT).build();
		assertEquals(EntityDescriptor.HIERARCHICAL_PLACEMENT.name(), property.getName());
		assertEquals(EntityDescriptor.HIERARCHICAL_PLACEMENT.description(), property.getDescription());

		final OpenApiSimpleType type = property.getType();
		assertEquals(typeRefTo(HierarchicalPlacementDescriptor.THIS.name()), type);
		verify(context, never()).registerType(any());
	}

	@Test
	void shouldTransformPropertyWithEnum() {
		final OpenApiProperty property = transformer.apply(ReferenceSchemaDescriptor.CARDINALITY).build();
		assertEquals(ReferenceSchemaDescriptor.CARDINALITY.name(), property.getName());
		assertEquals(ReferenceSchemaDescriptor.CARDINALITY.description(), property.getDescription());

		final OpenApiSimpleType type = property.getType();
		assertEquals(nonNull(typeRefTo(Cardinality.class.getSimpleName())), type);
		verify(context).registerCustomEnumIfAbsent(eq(enumFrom(Cardinality.class)));
	}
}