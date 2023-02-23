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

import io.evitadb.api.exception.TargetEntityIsNotHierarchicalException;
import io.evitadb.api.query.require.HierarchyParentsOfReference;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
 * This implementation of {@link RequireConstraintTranslator} converts {@link HierarchyParentsOfReference} to {@link HierarchyParentsProducer}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(List)} method.
 *
 * This translator interoperates with {@link HierarchyParentsOfSelfTranslator} and shares/reuses same {@link HierarchyParentsProducer} instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyParentsOfReferenceTranslator implements RequireConstraintTranslator<HierarchyParentsOfReference>, SelfTraversingTranslator {

	@Override
	public ExtraResultProducer apply(HierarchyParentsOfReference parentConstraint, ExtraResultPlanningVisitor extraResultPlanner) {
		final EvitaRequest evitaRequest = extraResultPlanner.getEvitaRequest();
		final Set<String> entityReferenceSet = evitaRequest.getEntityReferenceSet();
		final String requestedEntityType = evitaRequest.getEntityTypeOrThrowException("parents");
		final BiFunction<Integer, String, int[]> referenceFetcher = (entityPrimaryKey, referenceName) ->
			extraResultPlanner.getReferencesStorageContainer(requestedEntityType, entityPrimaryKey).getReferencedIds(referenceName);

		// find existing ParentsProducer for potential reuse
		HierarchyParentsProducer hierarchyParentsProducer = extraResultPlanner.findExistingProducer(HierarchyParentsProducer.class);
		// for each requested reference name
		final String[] referenceNames = parentConstraint.getReferenceNames();
		for (String referenceName : referenceNames) {
			// target hierarchy type is either passed in query, or is the queried entity itself
			final ReferenceSchemaContract referenceSchema = extraResultPlanner.getSchema().getReferenceOrThrowException(referenceName);
			final String entityType = referenceSchema.getReferencedEntityType();

			// verify that requested entityType is hierarchical
			final EntitySchemaContract entitySchema = extraResultPlanner.getSchema(entityType);
			Assert.isTrue(
				entitySchema.isWithHierarchy(),
				() -> new TargetEntityIsNotHierarchicalException(referenceName, entityType));

			if (hierarchyParentsProducer == null) {
				// if no ParentsProducer exists yet - create new one
				hierarchyParentsProducer = new HierarchyParentsProducer(
					extraResultPlanner.getQueryContext(),
					reqReferenceName -> evitaRequest.isRequiresEntityReferences() && entityReferenceSet.isEmpty() || entityReferenceSet.contains(reqReferenceName),
					referenceSchema,
					true,
					referenceFetcher,
					extraResultPlanner.getGlobalEntityIndex(entityType),
					parentConstraint.getEntityRequirement()
				);
			} else {
				// otherwise, just add another computational lambda
				hierarchyParentsProducer.addRequestedParentsIncludingSelf(
					referenceSchema,
					extraResultPlanner.getGlobalEntityIndex(entityType),
					parentConstraint.getEntityRequirement()
				);
			}
		}

		return hierarchyParentsProducer;
	}

}
