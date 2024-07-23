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

package io.evitadb.api.exception;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Exception is thrown when {@link HierarchyWithinRoot} or {@link HierarchyWithin} targets an entity that is not marked
 * as {@link EntitySchemaContract#isWithHierarchy()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityIsNotHierarchicalException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2138081433947529964L;
	@Getter private final String referenceName;
	@Getter private final String entityType;

	public EntityIsNotHierarchicalException(@Nonnull String entityType) {
		super(
			"Entity `" + entityType + "` is not hierarchical!"
		);
		this.referenceName = null;
		this.entityType = entityType;
	}

	public EntityIsNotHierarchicalException(@Nullable String referenceName, @Nonnull String entityType) {
		super(
			referenceName == null ?
				"Entity `" + entityType + "` targeted by query within hierarchy is not hierarchical!" :
				"Entity `" + entityType + "` targeted by query within hierarchy through reference `" + referenceName + "` is not hierarchical!"
		);
		this.referenceName = referenceName;
		this.entityType = entityType;
	}
}
