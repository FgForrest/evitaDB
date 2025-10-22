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

package io.evitadb.externalApi.graphql.api.system.model;

import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.ChangeCatalogCaptureCriteriaDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor for arguments of {@link SystemRootDescriptor#ON_CATALOG_CHANGE} subscription.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface OnCatalogChangeCaptureSubscriptionHeaderDescriptor {

	PropertyDescriptor CATALOG_NAME = PropertyDescriptor.builder()
		.name("catalogName")
		.description("""
			Specifies source catalog for the changes stream.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor SINCE_VERSION = PropertyDescriptor.builder()
		.name("sinceVersion")
		.description("""
			Specifies the initial capture point (catalog version) for the CDC stream, if not specified
			it is assumed to begin at the most recent / oldest available version.
			""")
		.type(nullable(Long.class))
		.build();
	PropertyDescriptor SINCE_INDEX = PropertyDescriptor.builder()
		.name("sinceIndex")
		.description("""
			Specifies the initial capture point for the CDC stream, it is optional and can be used
			to specify continuation point within an enclosing block of events.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor CRITERIA = PropertyDescriptor.builder()
		.name("criteria")
		.description("""
			The criteria of the capture, if not specified - all changes are captured, if multiple are specified
			matching any of them is sufficient (OR).
			""")
		.type(nullableListRef(ChangeCatalogCaptureCriteriaDescriptor.THIS))
		.build();
}
