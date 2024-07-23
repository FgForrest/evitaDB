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

select e.id, p.id, p.pricewithvat, p.priority, p.currency, p.pricelist
from t_entity e
inner join (
    select distinct on (p.entityid) p.entityid,
                                    p.id,
                                    p.currency,
                                    p.pricewithvat,
                                    p.pricelist,
                                    p.priority,
                                    p.validrange
    from t_price as p
    where (p.pricelist = 1 or p.pricelist = 2 or p.pricelist = 3)
      and p.currency = 'usd'
      and (p.validrange @> '2020-12-01 00:00:00'::timestamp or p.validrange is null)
    order by p.entityid, p.priority desc, p.pricewithvat
) p on p.entityid = e.id
where e.type = 'product'
  and p.pricewithvat between 3000 and 3300;
