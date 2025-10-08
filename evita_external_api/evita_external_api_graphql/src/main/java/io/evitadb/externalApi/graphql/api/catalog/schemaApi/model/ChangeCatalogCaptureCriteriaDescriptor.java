/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.model;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Static descriptor for {@link io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ChangeCatalogCaptureCriteriaDescriptor {

	PropertyDescriptor AREA = PropertyDescriptor.builder()
		.name("area")
		.description("The requested area of the capture (must be provided when the site is provided).")
		.type(nullable(CaptureArea.class))
		.build();
	PropertyDescriptor SCHEMA_SITE = PropertyDescriptor.builder()
		.name("schemaSite")
		.description("""
             The filter for the events to be sent, limits the amount of events sent to the subscriber
             to only those that are relevant to the site and area. The site is required when the `SCHEMA` area is provided.
             """)
		.type(nullableRef(SchemaSiteDescriptor.THIS))
		.build();
	PropertyDescriptor DATA_SITE = PropertyDescriptor.builder()
		.name("dataSite")
		.description("""
             The filter for the events to be sent, limits the amount of events sent to the subscriber
             to only those that are relevant to the site and area. The site is required when the `DATA` area is provided.
             """)
		.type(nullableRef(DataSiteDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ChangeCatalogCaptureCriteria")
		.description("""
			Record for the criteria of the capture request allowing to limit mutations to specific area of interest an its
            properties.             
			""")
		.staticFields(List.of(AREA, SCHEMA_SITE, DATA_SITE))
		.build();
}
