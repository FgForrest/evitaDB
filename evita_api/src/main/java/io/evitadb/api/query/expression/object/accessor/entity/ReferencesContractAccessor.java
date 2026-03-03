/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression.object.accessor.entity;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;

/**
 * Implementation of {@link ObjectElementAccessor} for {@link ReferencesContract} objects.
 * Enables bracket-access expressions in EvitaEL to retrieve references by reference name,
 * e.g. `entity.references['brand']`.
 *
 * The accessor resolves references through {@link ReferencesContract#getReferences(String)}
 * and returns:
 *
 * - a single {@link ReferenceContract} when exactly one reference matches,
 * - an unmodifiable {@link java.util.List} of {@link ReferenceContract} when multiple
 *   references match,
 * - `null` when no references match the given name.
 *
 * @see ObjectElementAccessor
 * @see ReferenceContractAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ReferencesContractAccessor implements ObjectElementAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { ReferencesContract.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String elementName
	) throws ExpressionEvaluationException {
		if (!(object instanceof ReferencesContract referencesContract)) {
			throw new ExpressionEvaluationException(
				"Cannot access element by name on object of type `" + object.getClass().getName() + "`. Expected ReferencesContract.",
				"Cannot access element."
			);
		}

		final Cardinality referenceCardinality = referencesContract
			.getSchema()
			.getReference(elementName)
			.map(ReferenceSchemaContract::getCardinality)
			.orElseThrow(
				() -> new ExpressionEvaluationException(
					"Reference schema `" + elementName + "` not found.",
					"Cannot found reference `" + elementName + "`."
				)
			);

		final Collection<ReferenceContract> references;
		try {
			references = referencesContract.getReferences(elementName);
		} catch (ContextMissingException | ReferenceNotFoundException e) {
			throw new ExpressionEvaluationException(
				"Cannot access reference `" + elementName + "` on ReferencesContract: " + e.getMessage(),
				"Cannot found reference `" + elementName + "`.",
				e
			);
		}

		if (referenceCardinality == Cardinality.ZERO_OR_ONE) {
			if (references.isEmpty()) {
				return null;
			} else {
				return references.iterator().next();
			}
		} else if (referenceCardinality == Cardinality.EXACTLY_ONE) {
			return references.iterator().next();
		} else if (referenceCardinality == Cardinality.ZERO_OR_MORE || referenceCardinality == Cardinality.ONE_OR_MORE) {
			return (Serializable) references;
		} else {
			throw new ExpressionEvaluationException(
				"Reference cardinality `" + referenceCardinality + "` is not supported.",
				"Unexpected internal error occurred while accessing references."
			);
		}
	}
}
