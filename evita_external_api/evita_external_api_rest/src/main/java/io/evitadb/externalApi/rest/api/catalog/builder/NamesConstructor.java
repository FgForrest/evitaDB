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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordStripDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.AttributeHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.OBJECT_TYPE_NAME_PART_DELIMITER;

/**
 * Used to construct names of entities and attributes and other objects. These names will be used as schema objects
 * in OpenAPI.<br/>
 * Attribute <strong>distinguishLocalizedData</strong> generally means that localized attributes (attributes with locale)
 * will be separated from non-localized attributes in output. This leads to different data structure and thus different
 * schemas. So schema with separated localized attributes will have special {@link #LOCALIZED_ENTITY_NAME_SUFFIX} suffix
 * in its name.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NamesConstructor {

	// todo lho remove symbol
	public static final String LOCALIZED_ENTITY_NAME_SUFFIX = "_Localized";

	@Nonnull
	public static String constructObjectName(@Nonnull String... part) {
		return String.join(OBJECT_TYPE_NAME_PART_DELIMITER, part);
	}

	// todo lho remove
//	@Nonnull
//	public static String constructEnumObjectName(@Nonnull EntitySchemaContract entitySchema, @Nonnull String enumName) {
//		return constructObjectName(
//			entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION),
//			enumName
//		);
//	}

	@Nonnull
	public static String constructEntityName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return EntityDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructAttributesObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return AttributesDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructAssociatedDataObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return AssociatedDataDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructReferenceObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean distinguishLocalizedData) {
		return ReferenceDescriptor.THIS.name(
			getLocalizedSuffix(distinguishLocalizedData),
			entitySchema,
			referenceSchema
		);
	}

	@Nonnull
	public static String constructReferencedEntityReferenceObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                                  @Nonnull CatalogSchemaContract catalogSchema) {
		return constructObjectName(
			entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION),
			referenceSchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION),
			referenceSchema.getReferencedEntityTypeNameVariant(
				TYPE_NAME_NAMING_CONVENTION,
				catalogSchema::getEntitySchemaOrThrowException
			)
		);
	}

	@Nonnull
	public static String constructReferenceAttributesObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema) {
		return AttributesDescriptor.THIS.name(entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructEntityFullResponseObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return ResponseDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructEntityDataChunkObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return DataChunkDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructRecordPageObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return RecordPageDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructRecordStripObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return RecordStripDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructExtraResultsObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return ExtraResultsDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructAttributeHistogramObjectName(@Nonnull EntitySchemaContract entitySchema) {
		return AttributeHistogramDescriptor.THIS.name(entitySchema);
	}

	@Nonnull
	public static String constructFacetGroupStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean distinguishLocalizedData) {
		return FacetGroupStatisticsDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructFacetStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean distinguishLocalizedData) {
		return FacetStatisticsDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructFacetSummaryObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return FacetSummaryDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructParentsObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return HierarchyParentsDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructSelfParentsOfEntityObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return ParentsOfEntityDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, entitySchema);
	}

	@Nonnull
	public static String constructParentsOfEntityObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean distinguishLocalizedData) {
		return ParentsOfEntityDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructParentsOfEntityReferencesObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                                  boolean distinguishLocalizedData) {
		return ParentsOfReferenceDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructHierarchyStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return HierarchyStatisticsDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema);
	}

	@Nonnull
	public static String constructSelfLevelInfoObjectName(@Nonnull EntitySchemaContract entitySchema, boolean distinguishLocalizedData) {
		return HierarchyStatisticsLevelInfoDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, entitySchema);
	}

	@Nonnull
	public static String constructLevelInfoObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean distinguishLocalizedData) {
		return HierarchyStatisticsLevelInfoDescriptor.THIS.name(getLocalizedSuffix(distinguishLocalizedData), entitySchema, referenceSchema);
	}

	@Nullable
	private static String getLocalizedSuffix(boolean distinguishLocalizedData) {
		return distinguishLocalizedData ? LOCALIZED_ENTITY_NAME_SUFFIX : null;
	}
}
