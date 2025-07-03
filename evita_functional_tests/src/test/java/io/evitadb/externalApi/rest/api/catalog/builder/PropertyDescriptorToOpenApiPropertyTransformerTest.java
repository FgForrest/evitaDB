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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.rest.api.model.PropertyDataTypeDescriptorToOpenApiTypeTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.scalarFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

	private CatalogRestBuildingContext context;
	private PropertyDescriptorToOpenApiPropertyTransformer transformer;

	@BeforeEach
	void setUp() {
		this.context = mock(CatalogRestBuildingContext.class);
		this.transformer = new PropertyDescriptorToOpenApiPropertyTransformer(
			new PropertyDataTypeDescriptorToOpenApiTypeTransformer(this.context)
		);
	}

	@Test
	void shouldTransformPropertyWithScalar() {
		final OpenApiProperty property = this.transformer.apply(EntityDescriptor.PRIMARY_KEY).build();
		assertEquals(EntityDescriptor.PRIMARY_KEY.name(), property.getName());
		assertEquals(EntityDescriptor.PRIMARY_KEY.description(), property.getDescription());

		final OpenApiSimpleType type = property.getType();
		assertEquals(nonNull(scalarFrom(Integer.class)), type);
		verify(this.context, never()).registerType(any());
	}

	@Test
	void shouldTransformPropertyWithReference() {
		final OpenApiProperty property = this.transformer.apply(EntityDescriptor.PRICE_FOR_SALE).build();
		assertEquals(EntityDescriptor.PRICE_FOR_SALE.name(), property.getName());
		assertEquals(EntityDescriptor.PRICE_FOR_SALE.description(), property.getDescription());

		final OpenApiSimpleType type = property.getType();
		assertEquals(typeRefTo(PriceDescriptor.THIS.name()), type);
		verify(this.context, never()).registerType(any());
	}

	@Test
	void shouldTransformPropertyWithEnum() {
		final OpenApiProperty property = this.transformer.apply(ReferenceSchemaDescriptor.CARDINALITY).build();
		assertEquals(ReferenceSchemaDescriptor.CARDINALITY.name(), property.getName());
		assertEquals(ReferenceSchemaDescriptor.CARDINALITY.description(), property.getDescription());

		final OpenApiSimpleType type = property.getType();
		assertEquals(nonNull(typeRefTo(Cardinality.class.getSimpleName())), type);
		verify(this.context).registerCustomEnumIfAbsent(eq(enumFrom(Cardinality.class)));
	}
}
