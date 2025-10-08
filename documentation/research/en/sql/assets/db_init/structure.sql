/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

/*
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

DROP TABLE IF EXISTS t_associatedData;
DROP TABLE IF EXISTS t_attribute;
DROP TABLE IF EXISTS t_facet;
DROP TABLE IF EXISTS t_facetGroup;
DROP TABLE IF EXISTS t_price;
DROP TABLE IF EXISTS t_entity;

CREATE TABLE t_entity
(
    id                                bigserial    NOT NULL,
    primaryKey                        bigint       NOT NULL,
    "type"                            varchar(128) NOT NULL,
    parentEntityPrimaryKey            bigint,
    siblingsOrder                     bigint,
    priceInnerEntityReferenceHandling varchar(64),
    CONSTRAINT cnpk_entity PRIMARY KEY (id),
    CONSTRAINT cnun_entity_typePrimaryKey UNIQUE ("type", primaryKey),
    CONSTRAINT cnfk_entity_parentEntity FOREIGN KEY ("type", parentEntityPrimaryKey) REFERENCES t_entity ("type", primaryKey) ON DELETE SET NULL ON UPDATE CASCADE
);

CREATE TABLE t_associatedData
(
    id       bigserial    NOT NULL,
    entityId bigint       NOT NULL,
    name     varchar(128) NOT NULL,
    locale   char(3),
    data     json,
    CONSTRAINT cnpk_associatedData PRIMARY KEY (id),
    CONSTRAINT cnfk_associatedData_entity FOREIGN KEY (entityId) REFERENCES t_entity (id) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE t_attribute
(
    id                  bigserial    NOT NULL,
    entityId            bigint       NOT NULL,
    name                varchar(128) NOT NULL,
    filterable          boolean DEFAULT false,
    sortable            boolean DEFAULT false,
    locale              char(3),
    stringValue         varchar(512),
    intValue            bigint,
    dateValue           timestamp,
    floatValuePrecision bigint,
    floatValueScale     bigint,
    rangeValue          int8range,
    CONSTRAINT cnpk_attribute PRIMARY KEY (id),
    CONSTRAINT cnfk_attribute_entity FOREIGN KEY (entityId) REFERENCES t_entity (id) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE t_facetGroup
(
    id         bigserial    NOT NULL,
    primaryKey bigint       NOT NULL,
    "type"     varchar(128) NOT NULL,
    CONSTRAINT cnpk_facetGroup PRIMARY KEY (id)
);

CREATE TABLE t_facet
(
    id                         bigserial    NOT NULL,
    facetGroupId               bigint       NOT NULL,
    entityId                   bigint       NOT NULL,
    referencedEntityPrimaryKey bigint       NOT NULL,
    referencedEntityType       varchar(128) NOT NULL,
    indexed                    boolean DEFAULT false,
    attributes                 json,
    CONSTRAINT cnpk_facet PRIMARY KEY (id),
    CONSTRAINT cnfk_facet_facetGroup FOREIGN KEY (facetGroupId) REFERENCES t_facetGroup (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT cnfk_facet_entity FOREIGN KEY (entityId) REFERENCES t_entity (id) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE t_price
(
    id                    bigserial     NOT NULL,
    entityId              bigint        NOT NULL,
    primaryKey            bigint        NOT NULL,
    priceWithoutVAT       numeric(9, 2) NOT NULL,
    priceWithVAT          numeric(9, 2) NOT NULL,
    vat                   numeric(4, 2) NOT NULL,
    innerEntityPrimaryKey bigint        NOT NULL,
    currency              char(3)       NOT NULL,
    priceList             bigint        NOT NULL,
    priority              bigint        NOT NULL,
    validRange            tsrange,
    indexed               boolean DEFAULT false,
    CONSTRAINT cnpk_price PRIMARY KEY (id),
    CONSTRAINT cnfk_price_entity FOREIGN KEY (entityId) REFERENCES t_entity (id) ON DELETE CASCADE ON UPDATE CASCADE
);
