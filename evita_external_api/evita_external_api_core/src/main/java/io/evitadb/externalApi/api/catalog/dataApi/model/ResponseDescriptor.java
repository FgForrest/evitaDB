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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Descriptor of {@link io.evitadb.api.requestResponse.EvitaResponse} for schema-based external APIs. It describes what response data are supported in API
 * for better field names maintainability.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ResponseDescriptor {

    ObjectDescriptor THIS = ObjectDescriptor.builder()
        .name("*Response")
        .description("""
            Evita response contains all results to single query. Results are divided to two parts - main results returned by
            `recordPage`/`recordStrip` and set of extra results retrieved by `extraResults`.
            """)
        .build();

    /**
     * Represents {@link PaginatedList} with {@link Page}.
     */
    PropertyDescriptor RECORD_PAGE = PropertyDescriptor.builder()
        .name("recordPage")
        .description("""
            Returns page of records according to pagination rules in input query.
            Either `page` or `strip` can be used, not both.
            """)
        // type is expected to be a `RecordPage` object
        .build();
    /**
     * Represents {@link StripList} with {@link Strip}
     */
    PropertyDescriptor RECORD_STRIP = PropertyDescriptor.builder()
        .name("recordStrip")
        .description("""
            Returns strip of records according to pagination rules in input query.
            Either `page` or `strip` can be used, not both.
            """)
        // type is expected to be a `RecordStrip` object
        .build();
    /**
     * Represents {@link EvitaResponse#getExtraResults()}
     */
    PropertyDescriptor EXTRA_RESULTS = PropertyDescriptor.builder()
        .name("extraResults")
        .description("""
            Returns map of requested extra results besides actual found records.
            """)
        // type is expected to be a `ExtraResults` object
        .build();

}
