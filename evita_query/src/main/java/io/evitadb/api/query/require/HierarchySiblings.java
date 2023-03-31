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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * TOBEDONE JNO: docs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDef(
	name = "siblings",
	shortDescription = "The constraint triggers computing the sibling axis for currently requested hierarchy node in filter by constraint or processed node by hierarchy parents axis.",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchySiblings extends AbstractRequireConstraintContainer implements HierarchyRequireConstraint, HierarchyOutputRequireConstraint {
	@Serial private static final long serialVersionUID = 6203461550836216251L;
	private static final String CONSTRAINT_NAME = "siblings";

	private HierarchySiblings(@Nullable String outputName, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, children, additionalChildren);
	}

	public HierarchySiblings() {
		super(CONSTRAINT_NAME);
	}

	public HierarchySiblings(@Nonnull FilterBy filterBy) {
		super(CONSTRAINT_NAME, new RequireConstraint[0], filterBy);
	}

	public HierarchySiblings(@Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, statistics);
	}

	public HierarchySiblings(@Nonnull EntityFetch entityFetch) {
		super(CONSTRAINT_NAME, entityFetch);
	}

	public HierarchySiblings(@Nonnull FilterBy filterBy, @Nonnull EntityFetch entityFetch) {
		super(CONSTRAINT_NAME, new RequireConstraint[] {entityFetch}, filterBy);
	}

	public HierarchySiblings(@Nonnull FilterBy filterBy, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, new RequireConstraint[] {statistics}, filterBy);
	}

	public HierarchySiblings(@Nonnull EntityFetch entityFetch, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, entityFetch, statistics);
	}

	public HierarchySiblings(@Nonnull FilterBy filterBy, @Nonnull EntityFetch entityFetch, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, new RequireConstraint[] {entityFetch, statistics}, filterBy);
	}

	public HierarchySiblings(@Nonnull String outputName) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName});
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull FilterBy filterBy) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, new RequireConstraint[0], filterBy);
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, statistics);
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull EntityFetch entityFetch) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, entityFetch);
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull FilterBy filterBy, @Nonnull EntityFetch entityFetch) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, new RequireConstraint[] {entityFetch}, filterBy);
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull FilterBy filterBy, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, new RequireConstraint[] {statistics}, filterBy);
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull EntityFetch entityFetch, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, entityFetch, statistics);
	}

	public HierarchySiblings(@Nonnull String outputName, @Nonnull FilterBy filterBy, @Nonnull EntityFetch entityFetch, @Nonnull HierarchyStatistics statistics) {
		super(CONSTRAINT_NAME, new Serializable[] {outputName}, new RequireConstraint[] {entityFetch, statistics}, filterBy);
	}

	/**
	 * Returns the key the computed extra result should be registered to.
	 */
	@Nullable
	public String getOutputName() {
		return (String) getArguments()[0];
	}

	/**
	 * Contains filtering condition for siblings. Only those siblings that match the filter condition will be returned.
	 */
	@Nullable
	public FilterBy getFilterBy() {
		for (Constraint<?> constraint : getAdditionalChildren()) {
			if (constraint instanceof FilterBy filterBy) {
				return filterBy;
			}
		}
		return null;
	}

	/**
	 * Returns content requirements for hierarchy entities.
	 */
	@Nonnull
	public Optional<EntityFetch> getEntityFetch() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof EntityFetch entityFetch) {
				return of(entityFetch);
			}
		}
		return empty();
	}

	/**
	 * Returns {@link HierarchyStatistics} settings.
	 */
	@Nonnull
	public Optional<HierarchyStatistics> getStatistics() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyStatistics statistics) {
				return of(statistics);
			}
		}
		return empty();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		for (RequireConstraint constraint : children) {
			Assert.isTrue(
				constraint instanceof HierarchyOutputRequireConstraint ||
					constraint instanceof EntityFetch,
				"Constraint HierarchySiblings accepts only FilterBy, HierarchyStopAt and EntityFetch as inner constraints!"
			);
		}
		for (Constraint<?> constraint : additionalChildren) {
			Assert.isTrue(
				constraint instanceof FilterBy,
				"Constraint HierarchySiblings accepts only FilterBy, HierarchyStopAt and EntityFetch as inner constraints!"
			);
		}
		if (additionalChildren.length == 0) {
			return new HierarchySiblings(getOutputName());
		} else {
			return new HierarchySiblings(getOutputName(), (FilterBy) additionalChildren[0]);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"HierarchySiblings container accepts only single String argument!"
		);
		return new HierarchySiblings((String) newArguments[0], getChildren(), getAdditionalChildren());
	}

}
