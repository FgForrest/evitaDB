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

package io.evitadb.api.proxy;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * This interface is implemented by all proxy types that wrap a sealed entity reference and provide access to
 * an instance of the {@link ReferenceContract} trapped in the proxy state object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SealedEntityReferenceProxy extends EvitaProxy, ReferencedEntityBuilderProvider {

	/**
	 * Returns entity classifier of the underlying sealed entity.
	 * @return entity classifier
	 */
	@Nonnull
	EntityClassifier getEntityClassifier();

	/**
	 * Returns the underlying sealed entity reference that is wrapped into a requested proxy type.
	 * @return the underlying sealed entity reference
	 */
	@Nonnull
	ReferenceContract getReference();

	/**
	 * Returns the reference builder on internally wrapped entity {@link #getReference()} or creates new.
	 *
	 * @return the reference builder
	 */
	@Nonnull
	ReferenceBuilder getReferenceBuilder();

	/**
	 * Returns the reference builder that is created on demand by calling mutation method on internally wrapped entity
	 * {@link #getReference()}.
	 *
	 * @return the reference builder
	 */
	@Nonnull
	Optional<ReferenceBuilder> getReferenceBuilderIfPresent();

	/**
	 * Method is called when the wrapped entity is upserted and thus the changes in the {@link #getReferenceBuilder()}
	 * should be marked as persisted.
	 */
	void notifyBuilderUpserted();

}
