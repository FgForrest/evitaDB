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
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Variant of a category mapped as POJO class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.CATEGORY)
@Data
@RequiredArgsConstructor
public abstract class AbstractCategoryPojo implements Serializable {
	@Serial private static final long serialVersionUID = 8206410610826858550L;
	@PrimaryKeyRef private final int id;
	private final TestEntity entityType;
	@ParentEntity private final Integer parentId;
	@ParentEntity private final AbstractCategoryPojo parentEntity;
	@ParentEntity private final EntityReference parentEntityReference;
	@ParentEntity private final EntityClassifier parentEntityClassifier;
	@ParentEntity private final EntityClassifierWithParent parentEntityClassifierWithParent;
	@Attribute(name = DataGenerator.ATTRIBUTE_CODE) private final String code;
	@Attribute(name = DataGenerator.ATTRIBUTE_NAME) private final String name;
	@AttributeRef(DataGenerator.ATTRIBUTE_PRIORITY) private final Long priority;
	@AttributeRef(DataGenerator.ATTRIBUTE_VALIDITY) private final DateTimeRange validity;

	public AbstractCategoryPojo(int id, TestEntity entityType) {
		this.id = id;
		this.entityType = entityType;
		this.parentId = null;
		this.parentEntity = null;
		this.parentEntityReference = null;
		this.parentEntityClassifier = null;
		this.parentEntityClassifierWithParent = null;
		this.code = null;
		this.name = null;
		this.priority = null;
		this.validity = null;
	}

	public AbstractCategoryPojo(int id, TestEntity entityType, String code, String name) {
		this.id = id;
		this.entityType = entityType;
		this.code = code;
		this.name = name;
		this.parentId = null;
		this.parentEntity = null;
		this.parentEntityReference = null;
		this.parentEntityClassifier = null;
		this.parentEntityClassifierWithParent = null;
		this.priority = null;
		this.validity = null;
	}
}
