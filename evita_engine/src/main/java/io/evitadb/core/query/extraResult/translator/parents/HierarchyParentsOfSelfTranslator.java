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

package io.evitadb.core.query.extraResult.translator.parents;

import io.evitadb.api.query.require.HierarchyParentsOfSelf;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.parents.producer.HierarchyParentsProducer;
import io.evitadb.utils.Assert;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyParentsOfSelf} to {@link HierarchyParentsProducer}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(List)} method.
 *
 * This translator interoperates with {@link HierarchyParentsOfReferenceTranslator} and shares/reuses same {@link HierarchyParentsProducer}
 * instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyParentsOfSelfTranslator implements RequireConstraintTranslator<HierarchyParentsOfSelf>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(HierarchyParentsOfSelf parentConstraint, ExtraResultPlanningVisitor extraResultPlanner) {
		// verify that requested entityType is hierarchical
		final EntitySchemaContract entitySchema = extraResultPlanner.getSchema();
		Assert.isTrue(entitySchema.isWithHierarchy(), "Entity schema for `" + entitySchema.getName() + "` doesn't allow hierarchy!");

		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final Set<String> entityReferenceSet = evitaRequest.getEntityReferenceSet();
		final String requestEntityType = evitaRequest.getEntityTypeOrThrowException("parents");
		final BiFunction<Integer, String, int[]> referenceFetcher = (entityPrimaryKey, referencedEntityType) ->
			extraResultPlanner.getReferencesStorageContainer(requestEntityType, entityPrimaryKey).getReferencedIds(referencedEntityType);

		// find existing ParentsProducer for potential reuse
		final HierarchyParentsProducer existingHierarchyParentsProducer = extraResultPlanner.findExistingProducer(HierarchyParentsProducer.class);
		if (existingHierarchyParentsProducer == null) {
			// if no ParentsProducer exists yet - create new one
			return new HierarchyParentsProducer(
				extraResultPlanner.getQueryContext(),
				entityType -> evitaRequest.isRequiresEntityReferences() && entityReferenceSet.isEmpty() || entityReferenceSet.contains(entityType),
				false,
				referenceFetcher,
				extraResultPlanner.getGlobalEntityIndex(entitySchema.getName()),
				parentConstraint.getEntityRequirement()
			);
		} else {
			// otherwise, just add another computational lambda
			existingHierarchyParentsProducer.addRequestedParents(
				extraResultPlanner.getGlobalEntityIndex(entitySchema.getName()),
				parentConstraint.getEntityRequirement()
			);
			return existingHierarchyParentsProducer;
		}
	}

}
