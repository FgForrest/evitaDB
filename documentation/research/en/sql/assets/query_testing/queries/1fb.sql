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

select e.id, a.rangevalue, a.id
from (select distinct on (e.id) e.id, e.type
      from t_entity e,
           t_facet f
      where f.entityid = e.id
        and e.type = 'product'
        and f.referencedentityprimarykey in (
          with recursive hier(primaryKey, parentEntityPrimaryKey) as (
              select primaryKey, parententityprimarykey
              from t_entity
              where "type" = 'category'
                and primarykey = 266
              union all
              select e.primaryKey, e.parentEntityPrimaryKey
              from t_entity e,
                   hier pe
              where pe.primarykey = e.parentEntityPrimaryKey
                and e.type = 'category'
          )
          select primaryKey
          from hier
      )) e,
     t_attribute a
where a.entityid = e.id
  and a.name = 'validity'
  and a.rangevalue @> 1000::int8;
