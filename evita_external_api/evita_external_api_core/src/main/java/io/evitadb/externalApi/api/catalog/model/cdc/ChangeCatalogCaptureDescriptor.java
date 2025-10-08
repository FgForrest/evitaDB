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

package io.evitadb.externalApi.api.catalog.model.cdc;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.cdc.ChangeCaptureDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor interface for catalog-specific Change Data Capture (CDC) events. Extends the base
 * {@link ChangeCaptureDescriptor} with additional properties specific to catalog operations,
 * including area, entity type, and version information. This descriptor is used to define
 * the structure of CDC events that occur within a specific catalog context.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ChangeCatalogCaptureDescriptor extends ChangeCaptureDescriptor {

	PropertyDescriptor AREA = PropertyDescriptor.builder()
		.name("area")
		.description("""
			The area of the operation.
			""")
		.type(nonNull(CaptureArea.class))
		.build();
	PropertyDescriptor ENTITY_TYPE = PropertyDescriptor.builder()
		.name("entityType")
		.description("""
			The type of the entity or its schema that was affected by the operation
			(if the operation is executed on catalog schema this field is null).
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor ENTITY_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("entityPrimaryKey")
		.description("""
			The primary key of the entity that was affected by the operation
			(if the operation is executed on catalog schema this field is null).
			""")
		.type(nullable(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ChangeCatalogCapture")
		.description("""
			Record representing a catalog-specific CDC event that is sent to the subscriber if it matches to the request he made.
			""")
		.staticFields(List.of(VERSION, INDEX, AREA, ENTITY_TYPE, ENTITY_PRIMARY_KEY, OPERATION))
		.build();
}
