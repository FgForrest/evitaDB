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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityRecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityRecordStripDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferencePageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferenceStripDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.EntityFacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.DataChunkUnionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.HierarchyOfDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Used to construct names of entities and attributes and other objects. These names will be used as schema objects
 * in OpenAPI.<br/>
 * Suffix <strong>localized</strong> generally means that entity will contain only global attributes and attributes of
 * single locale merged into single structure. Otherwise, attribute are split into global and by locale.
 * This leads to different data structure and thus different
 * schemas. So schema with separated localized attributes will have special {@link #LOCALIZED_ENTITY_NAME_SUFFIX} suffix
 * in its name.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DataApiNamesConstructor {

	private static final String LOCALIZED_ENTITY_NAME_SUFFIX = "Localized";

	@Nonnull
	public static String constructEntityObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return EntityDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructEntityListRequestBodyObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return FetchEntityRequestDescriptor.THIS_LIST.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructEntityQueryRequestBodyObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return FetchEntityRequestDescriptor.THIS_QUERY.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructReferenceObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		return EntityReferenceDescriptor.THIS.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructReferencePageObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                      @Nonnull ReferenceSchemaContract referenceSchema,
	                                                      boolean localized) {
		return EntityReferencePageDescriptor.THIS_INTERFACE.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructReferenceStripObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                      @Nonnull ReferenceSchemaContract referenceSchema,
	                                                      boolean localized) {
		return EntityReferenceStripDescriptor.THIS_INTERFACE.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructEntityFullResponseObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return ResponseDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructEntityDataChunkAggregateObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return DataChunkUnionDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructRecordPageObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return EntityRecordPageDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructRecordStripObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return EntityRecordStripDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructExtraResultsObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return ExtraResultsDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructFacetGroupStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		return FacetGroupStatisticsDescriptor.THIS.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructFacetStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		return EntityFacetStatisticsDescriptor.THIS.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructFacetSummaryObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return FacetSummaryDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructHierarchyObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return HierarchyDescriptor.THIS.name(entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructHierarchyOfSelfObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return HierarchyOfDescriptor.THIS.name(entitySchema, entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructHierarchyOfReferenceObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		return HierarchyOfDescriptor.THIS.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructSelfLevelInfoObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return LevelInfoDescriptor.THIS.name(entitySchema, entitySchema, getLocalizedSuffix(localized));
	}

	@Nonnull
	public static String constructLevelInfoObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		return LevelInfoDescriptor.THIS.name(entitySchema, referenceSchema, getLocalizedSuffix(localized));
	}

	@Nullable
	private static String getLocalizedSuffix(boolean localized) {
		return localized ? LOCALIZED_ENTITY_NAME_SUFFIX : null;
	}
}
