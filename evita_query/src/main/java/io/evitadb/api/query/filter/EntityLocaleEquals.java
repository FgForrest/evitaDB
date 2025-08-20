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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * If any filter constraint of the query targets a localized attribute, the `entityLocaleEquals` must also be provided,
 * otherwise the query interpreter will return an error. Localized attributes must be identified by both their name and
 * {@link Locale} in order to be used.
 *
 * Only a single occurrence of entityLocaleEquals is allowed in the filter part of the query. Currently, there is no way
 * to switch context between different parts of the filter and build queries such as find a product whose name in en-US
 * is "screwdriver" or in cs is "šroubovák".
 *
 * Also, it's not possible to omit the language specification for a localized attribute and ask questions like: find
 * a product whose name in any language is "screwdriver".
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "vouchers-for-shareholders")
 *         ),
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *        entityFetch(
 *            attributeContent("code", "name")
 *        )
 *     )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/locale#entity-locale-equals">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "equals",
	shortDescription = "The constraint if at least one of entity locales (derived from entity attributes or associated data) equals to the passed one.",
	userDocsLink = "/documentation/query/filtering/locale#entity-locale-equals",
	supportedIn = ConstraintDomain.ENTITY
)
public class EntityLocaleEquals extends AbstractFilterConstraintLeaf
	implements EntityConstraint<FilterConstraint>, FilterConstraint {

	@Serial private static final long serialVersionUID = 4716406488516855299L;

	private EntityLocaleEquals(Serializable... arguments) {
		super(arguments);
	}

	@Creator(implicitClassifier = "locale")
	public EntityLocaleEquals(@Nonnull Locale locale) {
		super(locale);
	}

	/**
	 * Returns locale that should be used for localized attribute search.
	 */
	@Nonnull
	public Locale getLocale() {
		return (Locale) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityLocaleEquals(newArguments);
	}
}
