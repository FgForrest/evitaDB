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

import java.io.Serial;
import java.io.Serializable;

/**
 * Variant of a category mapped as Java record.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EntityRef(Entities.CATEGORY)
public record CategoryRecord(
	@PrimaryKeyRef int id,
	TestEntity entityType,
	@ParentEntity Integer parentId,
	@ParentEntity CategoryRecord parentEntity,
	@ParentEntity EntityReference parentEntityReference,
	@ParentEntity EntityClassifier parentEntityClassifier,
	@ParentEntity EntityClassifierWithParent parentEntityClassifierWithParent,
	@Attribute(name = DataGenerator.ATTRIBUTE_CODE) String code,
	@Attribute(name = DataGenerator.ATTRIBUTE_NAME) String name,
	@AttributeRef(DataGenerator.ATTRIBUTE_PRIORITY) Long priority,
	@AttributeRef(DataGenerator.ATTRIBUTE_VALIDITY) DateTimeRange validity
) implements Serializable {
	@Serial private static final long serialVersionUID = -4302021544221115836L;

	public CategoryRecord(int id, TestEntity entityType) {
		this(id, entityType, null, null, null, null, null, null, null, null, null);
	}

	public CategoryRecord(int id, TestEntity entityType, String code, String name) {
		this(id, entityType, null, null, null, null, null, code, name, null, null);
	}

	// You can add any additional methods or overrides as needed
}
