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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.externalApi.exception.ExternalApiInternalError;

import javax.annotation.Nonnull;

/**
 * Specifies how to get data from reference.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record ReferenceDataLocator(
	@Nonnull EntityTypePointer entityTypePointer,
	@Nonnull String referenceName
) implements AbstractReferenceDataLocator {

	public ReferenceDataLocator {
		if (!(entityTypePointer instanceof ManagedEntityTypePointer)) {
			throw new ExternalApiInternalError("Facets can be accessed only on managed entity type.");
		}
	}

	@Nonnull
	@Override
	public ConstraintDomain targetDomain() {
		return ConstraintDomain.REFERENCE;
	}
}
