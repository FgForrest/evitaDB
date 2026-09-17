/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.execution.ExecutionStepInfo;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Returns flattened list of all parents of particular entity, sorted from the root down to the immediate parent.
 *
 * The field reports the {@link HierarchyParentsBehaviour#MATCHING} view of the fetched chain: every element it hands
 * back carries the body that was asked for. When the sibling {@link GraphQLEntityDescriptor#PARENTS_COMPLETE} field is
 * selected too, the single `hierarchyContent` both fields are served from asks for
 * {@link HierarchyParentsBehaviour#COMPLETE}, so the fetched chain may contain bodyless pointers - the walk then stops
 * below the first of them, which is exactly what {@link HierarchyParentsBehaviour#MATCHING} would have returned.
 *
 * The cut is therefore conditional: it is applied only where the requirement asked for ancestor bodies in the first
 * place, since otherwise an element arriving without one is a plain primary key rather than a pointer standing in for
 * a body that failed. A `parents { primaryKey }` selected on its own is returned whole for that reason - see
 * {@link #requestsAncestorBodies(DataFetchingEnvironment)} for how the two cases are told apart.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ParentsDataFetcher implements DataFetcher<Deque<EntityClassifierWithParent>> {

	/**
	 * Fields a bodyless ancestor can answer on its own. A selection limited to these asks for no ancestor body, and
	 * therefore cannot be truncated by a body that failed to materialize.
	 */
	private static final Set<String> CLASSIFIER_FIELDS = Set.of(
		EntityDescriptor.PRIMARY_KEY.name(),
		EntityDescriptor.TYPE.name()
	);

	@Nullable
	private static ParentsDataFetcher INSTANCE;

	@Nonnull
	public static ParentsDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ParentsDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public Deque<EntityClassifierWithParent> get(DataFetchingEnvironment environment) throws Exception {
		final List<EntityClassifierWithParent> ancestors = collectAncestors(environment.getSource());
		final boolean cutBelowBodylessAncestor = requestsAncestorBodies(environment);

		// the chain is walked from the immediate parent upwards and prepended, so that the result reads from the root
		final Deque<EntityClassifierWithParent> parents = new LinkedList<>();
		for (EntityClassifierWithParent ancestor : ancestors) {
			if (cutBelowBodylessAncestor && !(ancestor instanceof SealedEntity)) {
				break;
			}
			parents.addFirst(ancestor);
		}
		return parents;
	}

	/**
	 * Walks the fetched parent chain from the immediate parent of `entity` upwards and collects it in that order.
	 *
	 * @param entity the entity whose ancestors are to be collected
	 * @return the ancestors, the immediate parent first and the topmost reported one last
	 */
	@Nonnull
	static List<EntityClassifierWithParent> collectAncestors(@Nonnull EntityClassifierWithParent entity) {
		final List<EntityClassifierWithParent> ancestors = new ArrayList<>();
		EntityClassifierWithParent currentEntity = entity;
		EntityClassifierWithParent parent;
		while ((parent = currentEntity.getParentEntity().orElse(null)) != null) {
			ancestors.add(parent);
			currentEntity = parent;
		}
		return ancestors;
	}

	/**
	 * Returns TRUE when the `hierarchyContent` the chain was fetched with asked for ancestor bodies, and therefore when
	 * a chain element that is not a {@link SealedEntity} is a bodyless pointer rather than a plain primary key.
	 *
	 * The question is answered from the selection the requirement was derived from rather than from the chain that
	 * came back, because a chain in which nothing materialized carries no evidence of its own. Exactly two selections
	 * make {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver}
	 * ask for ancestor bodies: this field reaching past {@link #CLASSIFIER_FIELDS}, which can only be answered from
	 * a body, and the sibling {@link GraphQLEntityDescriptor#PARENTS_COMPLETE} being selected beside it, which the
	 * resolver always gives an inner `entityFetch` - an empty one when its own selection derives none.
	 *
	 * The sibling is read off the selection set of the field that produced the entity, which is the enclosing
	 * execution step: a data fetcher is handed the selection set of its own field alone. A bare
	 * `parents { primaryKey }` therefore keeps reporting the whole key chain, which is what a requirement asking for
	 * no ancestor body genuinely returns.
	 *
	 * @param environment the environment of the `parents` field being resolved
	 * @return TRUE when a chain element that is not a {@link SealedEntity} is to be read as a bodyless pointer
	 */
	private static boolean requestsAncestorBodies(@Nonnull DataFetchingEnvironment environment) {
		if (parentsCompleteSelectedBeside(environment)) {
			return true;
		}
		for (SelectedField selectedField : SelectionSetAggregator.getImmediateFields(environment.getSelectionSet())) {
			if (!CLASSIFIER_FIELDS.contains(selectedField.getName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE when {@link GraphQLEntityDescriptor#PARENTS_COMPLETE} is selected on the same entity object as the
	 * `parents` field being resolved, and the single `hierarchyContent` serving both fields therefore asked for
	 * ancestor bodies.
	 *
	 * @param environment the environment of the `parents` field being resolved
	 * @return TRUE when the sibling complete-chain field is selected beside this one
	 */
	private static boolean parentsCompleteSelectedBeside(@Nonnull DataFetchingEnvironment environment) {
		final ExecutionStepInfo stepInfo = environment.getExecutionStepInfo();
		Assert.isPremiseValid(
			stepInfo.hasParent(),
			() -> new GraphQLInternalError(
				"Field `" + GraphQLEntityDescriptor.PARENTS.name() + "` is declared on an entity object, so it is " +
					"always resolved below the field that produced the entity."
			)
		);
		final Map<String, FragmentDefinition> fragments = environment.getFragmentsByName();
		for (Field entityField : stepInfo.getParent().getField().getFields()) {
			if (selects(entityField.getSelectionSet(), GraphQLEntityDescriptor.PARENTS_COMPLETE.name(), fragments)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE when `selectionSet` selects the field `fieldName` on the object it is written against. Fragments -
	 * inline and spread alike - are transparent, since a field written inside one is selected on the very same object;
	 * a sub-selection of another field is not descended into, since a field of the same name written there belongs to
	 * a different object.
	 *
	 * @param selectionSet the selection set to examine, may be NULL for a field selecting nothing
	 * @param fieldName    name of the field looked for, matched on the field itself rather than on its response key
	 * @param fragments    the fragments declared by the operation, by name
	 * @return TRUE when the field is selected
	 */
	private static boolean selects(@Nullable SelectionSet selectionSet,
	                               @Nonnull String fieldName,
	                               @Nonnull Map<String, FragmentDefinition> fragments) {
		if (selectionSet == null) {
			return false;
		}
		for (Selection<?> selection : selectionSet.getSelections()) {
			if (selection instanceof Field field) {
				if (fieldName.equals(field.getName())) {
					return true;
				}
			} else if (selection instanceof InlineFragment inlineFragment) {
				if (selects(inlineFragment.getSelectionSet(), fieldName, fragments)) {
					return true;
				}
			} else if (selection instanceof FragmentSpread fragmentSpread) {
				final FragmentDefinition fragmentDefinition = fragments.get(fragmentSpread.getName());
				Assert.isPremiseValid(
					fragmentDefinition != null,
					() -> new GraphQLInternalError(
						"Fragment `" + fragmentSpread.getName() + "` is spread by the operation but not declared by it."
					)
				);
				if (selects(fragmentDefinition.getSelectionSet(), fieldName, fragments)) {
					return true;
				}
			} else {
				throw new GraphQLInternalError(
					"Unsupported selection of type `" + selection.getClass().getName() + "` encountered."
				);
			}
		}
		return false;
	}
}
