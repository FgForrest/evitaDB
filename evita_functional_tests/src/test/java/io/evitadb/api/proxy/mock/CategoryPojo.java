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

package io.evitadb.api.proxy.mock;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.DateTimeRange;

/**
 * Concrete class of a category mapped as POJO class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CategoryPojo extends AbstractCategoryPojo {

	public CategoryPojo(int id, TestEntity entityType, Integer parentId, AbstractCategoryPojo parentEntity, EntityReference parentEntityReference, EntityClassifier parentEntityClassifier, EntityClassifierWithParent parentEntityClassifierWithParent, String code, String name, Long priority, DateTimeRange validity) {
		super(id, entityType, parentId, parentEntity, parentEntityReference, parentEntityClassifier, parentEntityClassifierWithParent, code, name, priority, validity);
	}

	public CategoryPojo(int id, TestEntity entityType) {
		super(id, entityType);
	}

	public CategoryPojo(int id, TestEntity entityType, String code, String name) {
		super(id, entityType, code, name);
	}

}
