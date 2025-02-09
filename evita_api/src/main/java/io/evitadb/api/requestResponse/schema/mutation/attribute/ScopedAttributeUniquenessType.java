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

package io.evitadb.api.requestResponse.schema.mutation.attribute;


import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * The ScopedAttributeUniquenessType class encapsulates the relationship between an attribute's
 * uniqueness type and the scope in which this uniqueness characteristic is enforced.
 *
 * It makes use of two parameters:
 * - scope: Defines the context or domain (live or archived) where the attribute resides.
 * - uniquenessType: Determines the uniqueness enforcement (e.g., unique within the entire collection or specific locale).
 *
 * The combination of these parameters allows for scoped uniqueness checks within attribute schemas,
 * providing fine-grained control over attribute constraints based on the entity's scope.
 */
public record ScopedAttributeUniquenessType(
	@Nonnull Scope scope,
	@Nonnull AttributeUniquenessType uniquenessType
) implements Serializable {

	public ScopedAttributeUniquenessType {
		Assert.notNull(scope, "Scope must not be null");
		Assert.notNull(uniquenessType, "Uniqueness type must not be null");
	}

}
