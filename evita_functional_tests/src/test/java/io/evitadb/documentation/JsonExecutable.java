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

package io.evitadb.documentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.documentation.evitaql.CustomJsonVisibilityChecker;
import io.evitadb.documentation.evitaql.EntityDocumentationJsonSerializer;

import java.io.Serial;

import static io.evitadb.documentation.evitaql.CustomJsonVisibilityChecker.allow;

/**
 * Base class for all executable classes that needs to have JSON serialization capabilities.
 */
public abstract class JsonExecutable {
    /**
     * Object mapper used to serialize unknown objects to JSON output.
     */
    protected final static ObjectMapper OBJECT_MAPPER;
    /**
     * Pretty printer used to format JSON output.
     */
    protected final static DefaultPrettyPrinter DEFAULT_PRETTY_PRINTER;

    static {
        OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        OBJECT_MAPPER.setVisibility(
                new CustomJsonVisibilityChecker(
                        allow(EntityClassifier.class),
                        allow(EntityClassifierWithParent.class),
                        allow(Hierarchy.class),
                        allow(Hierarchy.LevelInfo.class),
                        allow(PaginatedList.class),
                        allow(Histogram.class),
                        allow(AttributeHistogram.class),
                        allow(PriceHistogram.class),
                        allow(Bucket.class),
                        allow(StripList.class),
                        allow(QueryTelemetry.class)
                )
        );
        OBJECT_MAPPER.registerModule(new Jdk8Module());
        OBJECT_MAPPER.registerModule(
                new SimpleModule()
                        .addSerializer(EntityContract.class, new EntityDocumentationJsonSerializer()));

        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);
        OBJECT_MAPPER.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        OBJECT_MAPPER.enable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS);
        OBJECT_MAPPER.setConfig(OBJECT_MAPPER.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));

        DEFAULT_PRETTY_PRINTER = new CustomPrettyPrinter();
    }

    /**
     * Custom pretty printer that leaves space before colon and properly indents arrays and objects.
     */
    private static class CustomPrettyPrinter extends DefaultPrettyPrinter {
        @Serial
        private static final long serialVersionUID = 5382128008653605263L;
        private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", "\n");

        public CustomPrettyPrinter() {
            this.indentArraysWith(INDENTER);
            this.indentObjectsWith(INDENTER);
        }

        public CustomPrettyPrinter(CustomPrettyPrinter basePrettyPrinter) {
            super(basePrettyPrinter);
            this.indentArraysWith(INDENTER);
            this.indentObjectsWith(INDENTER);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new CustomPrettyPrinter(this);
        }

        @Override
        public DefaultPrettyPrinter withSeparators(Separators separators) {
            final DefaultPrettyPrinter instance = super.withSeparators(separators);
	        this._objectFieldValueSeparatorWithSpaces = separators.getObjectFieldValueSeparator() + " ";
            return instance;
        }
    }
}
