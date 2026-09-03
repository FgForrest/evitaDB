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

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
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
		final boolean cutBelowBodylessAncestor = requestsAncestorBodies(ancestors, environment);

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
	 * A materialized ancestor anywhere in the chain settles the question outright. Otherwise the answer is taken from
	 * the field's own selection: anything beyond {@link #CLASSIFIER_FIELDS} can only be answered from a body, so the
	 * requirement must have asked for one.
	 *
	 * The two together leave one case undecided - a `COMPLETE` chain in which *no* ancestor materialized while this
	 * field selected the classifier alone. Its chain is then reported whole rather than cut, which hands back genuine
	 * primary keys of ancestors a strict `MATCHING` view would have omitted; the alternative reading would truncate
	 * the ordinary bodyless `parents { primaryKey }` chain to nothing.
	 *
	 * @param ancestors   the fetched chain, the immediate parent first
	 * @param environment the environment of the `parents` field being resolved
	 * @return TRUE when a chain element that is not a {@link SealedEntity} is to be read as a bodyless pointer
	 */
	private static boolean requestsAncestorBodies(
		@Nonnull List<EntityClassifierWithParent> ancestors,
		@Nonnull DataFetchingEnvironment environment
	) {
		for (EntityClassifierWithParent ancestor : ancestors) {
			if (ancestor instanceof SealedEntity) {
				return true;
			}
		}
		for (SelectedField selectedField : SelectionSetAggregator.getImmediateFields(environment.getSelectionSet())) {
			if (!CLASSIFIER_FIELDS.contains(selectedField.getName())) {
				return true;
			}
		}
		return false;
	}
}
