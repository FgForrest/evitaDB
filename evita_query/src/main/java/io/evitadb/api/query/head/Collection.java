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

package io.evitadb.api.query.head;

import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `collection` constraint specifies which entity collection (entity type) the query targets. Each query
 * must target exactly one entity type, making this constraint mandatory in most cases.
 *
 * **Exception:** If the filter contains a constraint targeting a globally unique attribute (such as `entityPrimaryKeyInSet`
 * or `attributeEquals` on a globally unique attribute), the `collection` constraint can be omitted. In such cases,
 * evitaDB automatically identifies the implicit collection from the attribute, since globally unique attributes
 * can only belong to one collection. This is especially useful in e-commerce routing scenarios where the requested
 * URI needs to match one of the existing entities without knowing its type upfront.
 *
 * Example:
 *
 * ```evitaql
 * query(
 *    collection('Product'),
 *    filterBy(
 *       attributeEquals('code', 'garmin-fenix')
 *    )
 * )
 * ```
 *
 * The constraint accepts a single {@link String} argument identifying the entity type (collection name).
 *
 * Note: This class extends {@link ConstraintLeaf} directly rather than {@link AbstractHeadConstraintLeaf}
 * for historical reasons, predating the introduction of the abstract base class.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/header/header#collection)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "collection",
	shortDescription = "The constraint specifies which entity collection will be searched for results.",
	userDocsLink = "/documentation/query/header/header#collection"
)
public class Collection extends ConstraintLeaf<HeadConstraint> implements HeadConstraint, GenericConstraint<HeadConstraint> {
	@Serial private static final long serialVersionUID = -7064678623633579615L;

	private Collection(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public Collection(@Nonnull String entityType) {
		super(new Serializable[]{ entityType });
	}

	/**
	 * Returns type of the entity that will be queried by associated query.
	 */
	@Nonnull
	public String getEntityType() {
		return (String) getArguments()[0];
	}

	@Nonnull
	@Override
	public Class<HeadConstraint> getType() {
		return HeadConstraint.class;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

	@Nonnull
	@Override
	public HeadConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Collection(newArguments);
	}
}
