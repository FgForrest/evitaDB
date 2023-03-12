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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model;


import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of request parameters for APIs. It describes what particular request parameters.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
// todo lho this is wrong
public interface ParamDescriptor {

    PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
        .name("primaryKey")
        .description("""
            Parameter specifying desired primary key of queried entity
            """)
        .type(nullable(Integer.class))
        .build();

    PropertyDescriptor LOCALE = PropertyDescriptor.builder()
        .name("locale")
        .description("""
            Parameter specifying desired locale of queried entity and its inner datasets
            """)
        .type(nullable(Locale.class))
        .build();

    PropertyDescriptor REQUIRED_LOCALE = PropertyDescriptor.builder()
        .name("locale")
        .description("""
            Parameter specifying desired locale of queried entity and its inner datasets
            """)
        .type(nonNull(Locale.class))
        .build();

    PropertyDescriptor PRICE_IN_CURRENCY = PropertyDescriptor.builder()
        .name("priceInCurrency")
        .description("""
            Parameter specifying desired price list of price for sale of queried entity
            """)
        .type(nullable(Currency.class))
        .build();

    PropertyDescriptor PRICE_IN_PRICE_LISTS = PropertyDescriptor.builder()
        .name("priceInPriceLists")
        .description("""
            Parameter specifying desired price list(s) of price for sale of queried entity
            """)
        .type(nullable(String[].class))
        .build();

    PropertyDescriptor PRICE_VALID_IN = PropertyDescriptor.builder()
        .name("priceValidIn")
        .description("""
            Parameter specifying desired validity of price for sale of queried entity. The date time is resolved to
            `now` by evitaDB. If both `priceValidInNow` and `priceValidIn` parameters are specified `priceValidIn` is used.
            """)
        .type(nullable(OffsetDateTime.class))
        .build();

    PropertyDescriptor PRICE_VALID_NOW = PropertyDescriptor.builder()
        .name("priceValidNow")
        .description("""
            Parameter specifying desired validity of price for sale of queried entity. The date time is resolved to
            `now` by evitaDB. If both `priceValidNow` and `priceValidIn` parameters are specified `priceValidIn` is used.
            """)
        .type(nullable(Boolean.class))
        .build();

    PropertyDescriptor LIMIT = PropertyDescriptor.builder()
        .name("limit")
        .description("""
            Limits number of returned entities.
            """)
        .type(nullable(Integer.class))
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
        .type(nullable(Boolean.class))
        .build();
    PropertyDescriptor REFERENCE_CONTENT = PropertyDescriptor.builder()
        .name("referenceContent")
        .description("""
           Parameter specifying whether reference data (and which of them) will be present in returned entity.
            """)
        .type(nullable(String[].class))
        .build();

    PropertyDescriptor REFERENCE_CONTENT_ALL = PropertyDescriptor.builder()
        .name("referenceContentAll")
        .description("""
           Parameter specifying whether ALL reference data will be present in returned entity.
            """)
        .type(nullable(Boolean.class))
        .build();

    PropertyDescriptor ENTITY_COUNT = PropertyDescriptor.builder()
        .name("entityCount")
        .description("""
           Parameter specifying whether count of entities within single collection should be returned in response.
            """)
        .type(nullable(Boolean.class))
        .build();

    PropertyDescriptor ENTITY_TYPE = PropertyDescriptor.builder()
        .name("entityType")
        .description("""
            Type of entity collection.
            """)
        .type(nonNull(String.class))
        .build();
}
