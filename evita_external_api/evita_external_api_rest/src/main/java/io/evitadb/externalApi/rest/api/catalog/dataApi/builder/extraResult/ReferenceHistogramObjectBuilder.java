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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.extraResult;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceHistogramDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityObjectName;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builds a per-referenced-entity-type `{EntityType}Histogram` concrete OpenAPI schema that extends
 * the shared `Histogram` interface ({@link HistogramDescriptor#THIS_INTERFACE}). One concrete schema
 * is built per unique `(referencedEntityType, localized)` combination and shared across all
 * references targeting the same target entity type.
 *
 * Mirrors the GraphQL {@code ReferenceHistogramObjectBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceHistogramObjectBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;

	/**
	 * Returns (and when absent, builds and registers) the concrete per-referenced-entity-type
	 * `{EntityType}Histogram` OpenAPI object reference. The localization flag is baked into the
	 * generated type name so localized and non-localized variants are cached independently.
	 */
	@Nonnull
	public OpenApiTypeReference getOrBuild(@Nonnull ReferenceSchemaContract referenceSchema, boolean localized) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final EntitySchemaContract referencedEntitySchema = resolveReferencedEntitySchema(referenceSchema);
		final String typeName = buildTypeName(referencedEntityType, localized);

		return this.buildingContext.getRegisteredType(typeName)
			.orElseGet(() -> buildAndRegister(typeName, referencedEntitySchema, localized));
	}

	@Nonnull
	private OpenApiTypeReference buildAndRegister(
		@Nonnull String typeName,
		@Nullable EntitySchemaContract referencedEntitySchema,
		boolean localized
	) {
		final OpenApiTypeReference referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema, localized);

		final OpenApiObject referenceHistogramObject = ReferenceHistogramDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(typeName)
			.property(ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY
				.to(this.propertyBuilderTransformer)
				.type(referencedEntityObject))
			.property(ReferenceHistogramDescriptor.MAX_REFERENCED_ENTITY
				.to(this.propertyBuilderTransformer)
				.type(referencedEntityObject))
			.build();

		return this.buildingContext.registerType(referenceHistogramObject);
	}

	@Nonnull
	private static String buildTypeName(@Nonnull String referencedEntityType, boolean localized) {
		// ObjectDescriptor.name(...) requires a parameter matching the wildcard, so we resolve it
		// directly and append the localization suffix to keep localized/non-localized variants
		// separated — same convention other REST types use.
		return ReferenceHistogramDescriptor.THIS.name(referencedEntityType + (localized ? "Localized" : ""));
	}

	@Nullable
	private EntitySchemaContract resolveReferencedEntitySchema(@Nonnull ReferenceSchemaContract referenceSchema) {
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return null;
		}
		return this.buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
	}

	@Nonnull
	private static OpenApiTypeReference buildReferencedEntityObject(
		@Nullable EntitySchemaContract referencedEntitySchema,
		boolean localized
	) {
		if (referencedEntitySchema != null) {
			return typeRefTo(constructEntityObjectName(referencedEntitySchema, localized));
		} else {
			return typeRefTo(EntityDescriptor.THIS_REFERENCE.name());
		}
	}
}
