/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model.header;

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.Locale;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Parameters for endpoints that fetch entities in some way.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface FetchEntityEndpointHeaderDescriptor {

	PropertyDescriptor LOCALE = PropertyDescriptor.builder()
		.name("locale")
		.description("""
            Parameter specifying desired locale of queried entity and its inner datasets
            """)
		.type(nullable(Locale.class))
		.build();

	PropertyDescriptor FETCH_ALL = PropertyDescriptor.builder()
		.name("fetchAll")
		.description("""
            Parameter specifying whether all data will be present in returned entity.
            """)
		.type(nullable(Boolean.class))
		.build();

	PropertyDescriptor ASSOCIATED_DATA_CONTENT = PropertyDescriptor.builder()
		.name("associatedDataContent")
		.description("""
            Parameter specifying whether associated data (and which of them) will be present in returned entity.
            """)
		.type(nullable(String[].class))
		.build();

	PropertyDescriptor ASSOCIATED_DATA_CONTENT_ALL = PropertyDescriptor.builder()
		.name("associatedDataContentAll")
		.description("""
            Parameter specifying whether ALL associated data will be present in returned entity.
            """)
		.type(nullable(Boolean.class))
		.build();

	PropertyDescriptor ATTRIBUTE_CONTENT = PropertyDescriptor.builder()
		.name("attributeContent")
		.description("""
            Parameter specifying whether attributes (and which of them) will be present in returned entity.
            """)
		.type(nullable(String[].class))
		.build();

	PropertyDescriptor ATTRIBUTE_CONTENT_ALL = PropertyDescriptor.builder()
		.name("attributeContentAll")
		.description("""
            Parameter specifying whether ALL attributes will be present in returned entity.
            """)
		.type(nullable(Boolean.class))
		.build();

	PropertyDescriptor DATA_IN_LOCALES = PropertyDescriptor.builder()
		.name("dataInLocales")
		.description("""
           Parameter specifying locales to get localized data.
            """)
		.type(nullable(Locale[].class))
		.build();

	PropertyDescriptor BODY_FETCH = PropertyDescriptor.builder()
		.name("bodyFetch")
		.description("""
           Parameter specifying whether entity body data will be present in returned entity. If any other content
           require is specified, this can be omitted as it is implicitly triggered.
            """)
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor PRICE_CONTENT = PropertyDescriptor.builder()
		.name("priceContent")
		.description("""
            Parameter specifying whether price data will be present in returned entity.
            """)
		.type(nullable(PriceContentMode.class))
		.build();
	PropertyDescriptor REFERENCE_CONTENT = PropertyDescriptor.builder()
		.name("referenceContent")
		.description("""
           Parameter specifying whether reference data (and which of them) will be present in returned entity.
            """)
		.type(nullable(String[].class))
		.build();
	PropertyDescriptor HIERARCHY_CONTENT = PropertyDescriptor.builder()
		.name("hierarchyContent")
		.description("""
           Parameter specifying whether hierarchy data will be present in returned entity.
            """)
		.type(nullable(Boolean.class))
		.build();

	PropertyDescriptor REFERENCE_CONTENT_ALL = PropertyDescriptor.builder()
		.name("referenceContentAll")
		.description("""
           Parameter specifying whether ALL reference data will be present in returned entity.
            """)
		.type(nullable(Boolean.class))
		.build();
}
