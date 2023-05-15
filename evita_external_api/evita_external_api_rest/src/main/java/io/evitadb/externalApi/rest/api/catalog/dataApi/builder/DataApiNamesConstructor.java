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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordStripDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.DataChunkAggregateDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
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
		return EntityDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructEntityListRequestBodyObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return FetchEntityRequestDescriptor.THIS_LIST.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructEntityQueryRequestBodyObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return FetchEntityRequestDescriptor.THIS_QUERY.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructReferenceObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		return ReferenceDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructReferenceAttributesObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema,
	                                                            boolean localized) {
		return AttributesDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructEntityFullResponseObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return ResponseDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructEntityDataChunkAggregateObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return DataChunkAggregateDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructRecordPageObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return RecordPageDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructRecordStripObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return RecordStripDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructExtraResultsObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return ExtraResultsDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructFacetGroupStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		return FacetGroupStatisticsDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructFacetStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		return FacetStatisticsDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema, referenceSchema);
	}

	@Nonnull
	public static String constructFacetSummaryObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return FacetSummaryDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructHierarchyStatisticsObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return HierarchyDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema);
	}

	@Nonnull
	public static String constructSelfLevelInfoObjectName(@Nonnull EntitySchemaContract entitySchema, boolean localized) {
		return LevelInfoDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema, entitySchema);
	}

	@Nonnull
	public static String constructLevelInfoObjectName(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		return LevelInfoDescriptor.THIS.name(getLocalizedSuffix(localized), entitySchema, referenceSchema);
	}

	@Nullable
	private static String getLocalizedSuffix(boolean localized) {
		return localized ? LOCALIZED_ENTITY_NAME_SUFFIX : null;
	}
}
