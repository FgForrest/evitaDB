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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.structure.Entity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Optional;

/**
 * Common ancestor for contracts that either directly represent {@link EntityContract} or reference to it and may
 * contain reference to parent entities. We don't use sealed interface here because there are multiple implementations
 * of those interfaces but only these two aforementioned extending interfaces could extend from this one.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface EntityClassifierWithParent extends EntityClassifier {
	/**
	 * Special value for {@link EntityClassifierWithParent} that represents concealed entity. This constant is expected
	 * to be used for concealing parents that were not requested by the client, but in reality they do exist.
	 */
	EntityClassifierWithParent CONCEALED_ENTITY = new EntityClassifierWithParent() {
		@Serial private static final long serialVersionUID = -2322605230612089578L;

		@Nonnull
		@Override
		public Optional<EntityClassifierWithParent> getParentEntity() {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public String getType() {
			throw new UnsupportedOperationException();
		}

		@Nullable
		@Override
		public Integer getPrimaryKey() {
			throw new UnsupportedOperationException();
		}
	};

	/**
	 * Optional reference to {@link Entity#getParent()} of the referenced entity.
	 */
	@Nonnull
	Optional<EntityClassifierWithParent> getParentEntity();
}
