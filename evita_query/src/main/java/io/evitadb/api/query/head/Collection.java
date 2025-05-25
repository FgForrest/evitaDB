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
 * Each query must specify collection. This mandatory {@link String} entity type controls what collection
 * the query will be applied on.
 *
 * Sample of the header is:
 *
 * <pre>
 * collection('category')
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/header/header#collection">Visit detailed user documentation</a></p>
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
