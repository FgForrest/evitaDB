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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.referential.nosql.model;

import lombok.Data;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

import static org.springframework.data.elasticsearch.annotations.FieldType.*;

@Data
public class Facet {

    @Field(type = FieldType.Integer)
    private Integer facetId;

    @Field(type = Text)
    private String entityType;

    @Field(type = FieldType.Boolean)
    private boolean indexed;

    @Field(type = Nested, includeInParent = true)
    private List<FacetGroup> facetGroups;

    @Field(type = Nested, includeInParent = true)
    private List<Attribute> attributes;

    @Field(type = Nested, includeInParent = true)
    private LowerEntity entity;

    @Data
    public static class FacetGroup {

        @Field(type = FieldType.Integer)
        private Integer primaryKey;

        @Field(type = Text)
        private String entityType;

        @Field(type = Nested, includeInParent = true)
        private LowerEntity entity;
    }

    @Data
    public static class Attribute {

        @Field(type = Text)
        private String parentCatalog;

        @Field(type = Keyword)
        private String path;

    }
}
