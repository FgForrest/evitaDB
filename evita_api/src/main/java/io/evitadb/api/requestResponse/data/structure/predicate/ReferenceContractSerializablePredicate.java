/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * This predicate allows limiting number of references visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferenceContractSerializablePredicate implements SerializablePredicate<ReferenceContract> {
	public static final ReferenceContractSerializablePredicate DEFAULT_INSTANCE = new ReferenceContractSerializablePredicate(Collections.emptySet(), true, new ReferenceAttributeValueSerializablePredicate(null, Collections.emptySet()));
	@Serial private static final long serialVersionUID = -3182607338600238414L;

	/**
	 * Contains information about all reference names that has been fetched / requested for the entity.
	 */
	@Nonnull @Getter private final Set<String> referenceSet;
	/**
	 * Contains true if any of the references of the entity has been fetched / requested.
	 */
	@Getter private final boolean requiresEntityReferences;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(SealedEntity, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final ReferenceContractSerializablePredicate underlyingPredicate;
	/**
	 * Predicate for reference attributes.
	 */
	@Getter @Nonnull private final ReferenceAttributeValueSerializablePredicate attributePredicate;

	public ReferenceContractSerializablePredicate() {
		this.requiresEntityReferences = false;
		this.referenceSet = Collections.emptySet();
		this.underlyingPredicate = null;
		this.attributePredicate = new ReferenceAttributeValueSerializablePredicate();
	}

	public ReferenceContractSerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.requiresEntityReferences = evitaRequest.isRequiresEntityReferences();
		this.referenceSet = evitaRequest.getEntityReferenceSet();
		this.underlyingPredicate = null;
		this.attributePredicate = new ReferenceAttributeValueSerializablePredicate(evitaRequest);
	}

	public ReferenceContractSerializablePredicate(boolean requiresEntityReferences) {
		this.requiresEntityReferences = requiresEntityReferences;
		this.referenceSet = Collections.emptySet();
		this.underlyingPredicate = null;
		this.attributePredicate = new ReferenceAttributeValueSerializablePredicate(null, Collections.emptySet());
	}

	public ReferenceContractSerializablePredicate(@Nonnull EvitaRequest evitaRequest, @Nonnull ReferenceContractSerializablePredicate underlyingPredicate) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.requiresEntityReferences = evitaRequest.isRequiresEntityReferences();
		this.referenceSet = evitaRequest.getEntityReferenceSet();
		this.underlyingPredicate = underlyingPredicate;
		this.attributePredicate = new ReferenceAttributeValueSerializablePredicate(evitaRequest);
	}

	ReferenceContractSerializablePredicate(@Nonnull Set<String> referenceSet,
	                                       boolean requiresEntityReferences,
	                                       @Nonnull ReferenceAttributeValueSerializablePredicate attributePredicate) {
		this.referenceSet = referenceSet;
		this.requiresEntityReferences = requiresEntityReferences;
		this.underlyingPredicate = null;
		this.attributePredicate = attributePredicate;
	}

	@Override
	public boolean test(@Nonnull ReferenceContract reference) {
		if (requiresEntityReferences) {
			final String referencedEntityType = reference.getReferenceName();
			return reference.exists() &&
				(referenceSet.isEmpty() || referenceSet.contains(referencedEntityType));
		} else {
			return false;
		}
	}

	public ReferenceContractSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final Set<String> requiredReferencedEntities = combineReferencedEntities(evitaRequest);
		final boolean doesRequireEntityReferences = evitaRequest.isRequiresEntityReferences();
		final ReferenceAttributeValueSerializablePredicate richerCopyOfAttributePredicate = attributePredicate.createRicherCopyWith(evitaRequest);

		if ((this.requiresEntityReferences || !doesRequireEntityReferences) &&
			Objects.equals(this.referenceSet, requiredReferencedEntities) &&
			attributePredicate == richerCopyOfAttributePredicate) {
			return this;
		} else {
			return new ReferenceContractSerializablePredicate(
				requiredReferencedEntities,
				this.requiresEntityReferences || doesRequireEntityReferences,
				richerCopyOfAttributePredicate
			);
		}
	}

	@Nonnull
	private Set<String> combineReferencedEntities(@Nonnull EvitaRequest evitaRequest) {
		final Set<String> requiredReferences;
		final Set<String> newlyRequiredReferences = evitaRequest.getEntityReferenceSet();
		if (!this.requiresEntityReferences) {
			requiredReferences = newlyRequiredReferences;
		} else if (evitaRequest.isRequiresEntityReferences()) {
			requiredReferences = new HashSet<>(this.referenceSet.size() + newlyRequiredReferences.size());
			requiredReferences.addAll(this.referenceSet);
			requiredReferences.addAll(newlyRequiredReferences);
		} else {
			requiredReferences = this.referenceSet;
		}
		return requiredReferences;
	}
}
