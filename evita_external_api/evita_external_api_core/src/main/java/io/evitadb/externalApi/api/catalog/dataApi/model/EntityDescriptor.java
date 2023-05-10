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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;
import java.util.Locale;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of {@link EntityContract} for schema-based external APIs. It describes what entity data are supported in API
 * for better field names maintainability.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface EntityDescriptor {

    default PropertyDescriptor primaryKey() {
        return PropertyDescriptor.builder()
            .name("primaryKey")
            .description("""
            Unique Integer positive number representing the entity. Can be used for fast lookup for
            entity (entities). Primary key must be unique within the same entity type.
            """)
            .type(nonNull(Integer.class))
            .build();
    }

    PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
        .name("primaryKey")
        .description("""
            Unique Integer positive number representing the entity. Can be used for fast lookup for
            entity (entities). Primary key must be unique within the same entity type.
            """)
        .type(nonNull(Integer.class))
        .build();
    PropertyDescriptor TYPE = PropertyDescriptor.builder()
        .name("type")
        .description("""
            Type of entity.
            Entity type is main sharding key - all data of entities with same type are stored in separated collections. Within the
            entity type entity is uniquely represented by primary key.
            """)
        .type(nonNull(String.class))
        .build();
    PropertyDescriptor LOCALES = PropertyDescriptor.builder()
        .name("locales")
        .description("""
            Contains set of requested locales for this particular entity.
            """)
        .type(nonNull(Locale[].class))
        .build();
    PropertyDescriptor ALL_LOCALES = PropertyDescriptor.builder()
        .name("allLocales")
        .description("""
            Contains set of all locales that were used for localized attributes or associated data of
            this particular entity.
            """)
        .type(nonNull(Locale[].class))
        .build();
    PropertyDescriptor ATTRIBUTES = PropertyDescriptor.builder()
        .name("attributes")
        .description("""
            Attributes allows defining set of data that are fetched in bulk along with the entity body.
            Attributes may be used for fast filtering or can be used to sort along.
            """)
        // type is expected to be a map with attribute names as keys and attribute values as values
        .build();
    PropertyDescriptor ASSOCIATED_DATA = PropertyDescriptor.builder()
        .name("associatedData")
        .description("""
            Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
            along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
            in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
            binary arrays representing entire files (i.e. pictures, documents).
            """)
        // type is expected to be a map with associated data names as keys and associated data values as values
        .build();
    PropertyDescriptor PRICE_FOR_SALE = PropertyDescriptor.builder()
        .name("priceForSale")
        .description("""
            Price for which the entity should be sold. This method can be used only when appropriate
            price related constraints are present so that `currency` and `priceList` priority can be extracted from the query.
            The moment is either extracted from the query as well (if present) or current date and time is used.
            """)
        .type(nullableRef(PriceDescriptor.THIS))
        .build();
    PropertyDescriptor PRICE = PropertyDescriptor.builder()
        .name("price")
        .description("""
            Single price corresponding to defined arguments picked up from set of all `prices`.
            If more than one price is found, the valid one is picked. Validity is check based on query, if desired
            validity is not specified in query, current time is used. 
            """)
        .type(nullableRef(PriceDescriptor.THIS))
        .build();
    PropertyDescriptor PRICES = PropertyDescriptor.builder()
        .name("prices")
        .description("""
            Prices allows defining set of prices of entity for complex filtering and ordering.
            """)
        .type(nonNullListRef(PriceDescriptor.THIS))
        .build();
    PropertyDescriptor PRICE_INNER_RECORD_HANDLING = PropertyDescriptor.builder()
        .name("priceInnerRecordHandling")
        .description("""
            Price inner record handling controls how prices that share same `inner entity id` will behave during filtering and sorting.
            """)
        .type(nonNull(PriceInnerRecordHandling.class))
        .build();

    ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.builder()
        .name("Entity")
        .description("""
            Generic the most basic entity.
            Common ancestor for all specific entities which correspond to specific collections.
            """)
        .staticFields(List.of(PRIMARY_KEY, TYPE))
        .build();
    ObjectDescriptor THIS_ENTITY_REFERENCE = ObjectDescriptor.extend(THIS_INTERFACE)
        .name("EntityReference")
        .description("""
            Object holding reference to an entity.
            """)
        .build();
    ObjectDescriptor THIS = ObjectDescriptor.extend(THIS_INTERFACE)
        .name("*")
        .build();
}
