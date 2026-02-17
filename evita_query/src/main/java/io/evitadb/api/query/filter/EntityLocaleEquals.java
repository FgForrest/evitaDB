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
 * Specifies the locale context for all localized attributes and associated data accessed in the query. This constraint
 * establishes the language/region context used to resolve localized entity properties, ensuring that attribute filters,
 * sorting operations, and fetched data all respect the same locale. It is a mandatory constraint when any part of the
 * query references localized attributes or associated data.
 *
 * **Purpose**
 *
 * evitaDB supports fully localized entity data using {@link Locale} instances that follow the IETF BCP 47 standard for
 * language tags (e.g., `en-US`, `fr-FR`, `cs-CZ`). Localized attributes are stored separately for each locale, and to
 * access them, you must explicitly specify which locale's values to use. This constraint serves that purpose.
 *
 * Without this constraint, the query engine cannot determine which locale variant of a localized attribute to access,
 * and attempting to filter or sort by a localized attribute without providing `entityLocaleEquals` will result in a
 * query validation error.
 *
 * **Constraint Classification**
 *
 * This constraint implements {@link EntityConstraint} because it operates on the built-in `locale` property that
 * defines the context for localized entity data. It is a {@link FilterConstraint} that narrows the result set to
 * entities that have data available in the specified locale.
 *
 * **Single Locale Restriction**
 *
 * Only a single `entityLocaleEquals` constraint is permitted per query filter. This means you cannot build queries like
 * "find products whose English name is 'screwdriver' OR whose Czech name is 'šroubovák'" within a single filter. Each
 * query operates in exactly one locale context.
 *
 * Similarly, you cannot omit the locale and ask "find products whose name is 'screwdriver' in any language." Localized
 * attributes must always be accessed in a specific locale context.
 *
 * If you need to query across multiple locales, you must execute separate queries for each locale and combine the
 * results in your application logic.
 *
 * **Effect on Query Results**
 *
 * When `entityLocaleEquals` is specified:
 * - All localized attribute filters operate on values in the specified locale
 * - All localized attribute sorting operates on values in the specified locale
 * - All fetched localized attributes and associated data are returned in the specified locale
 * - Entities without data in the specified locale may be excluded or return null values for localized properties
 *
 * **Supported Constraint Domains**
 *
 * This constraint is only valid in the `ENTITY` domain — it establishes the locale context for the primary queried
 * entities. It does not apply to referenced entities or facets, which inherit the locale context from the main query.
 *
 * **EvitaQL Syntax**
 *
 * ```
 * entityLocaleEquals(argument:string!)
 * ```
 *
 * The argument is a string representation of a locale in IETF BCP 47 format.
 *
 * **Usage Examples**
 *
 * ```java
 * // Filter products with localized attribute in English
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityLocaleEquals(Locale.ENGLISH),
 *             attributeContains("name", "phone")  // searches English "name" attribute
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("name")  // returns English "name" value
 *         )
 *     )
 * )
 * ```
 *
 * ```java
 * // Fetch product data in French
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityPrimaryKeyInSet(1, 2, 3),
 *             entityLocaleEquals(Locale.FRANCE)  // establishes French locale context
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContentAll(),  // all localized attributes returned in French
 *             associatedDataContentAll()  // all localized associated data returned in French
 *         )
 *     )
 * )
 * ```
 *
 * ```java
 * // Sort by localized attribute in specific locale
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityLocaleEquals(new Locale("cs", "CZ"))  // Czech locale
 *     ),
 *     orderBy(
 *         attributeNatural("name", ASC)  // sorts by Czech "name" values
 *     )
 * )
 * ```
 *
 * ```java
 * // Combine with hierarchy navigation
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityLocaleEquals(Locale.ENGLISH),
 *             hierarchyWithin(
 *                 "categories",
 *                 attributeEquals("code", "electronics")  // "code" might be localized
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * **Mandatory Requirement**
 *
 * If your query uses any localized attribute constraints (e.g., `attributeEquals`, `attributeContains`,
 * `attributeNatural` in orderBy), you MUST include `entityLocaleEquals` in your filter. Otherwise, the query
 * interpreter will throw a validation error because it cannot determine which locale variant to access.
 *
 * Non-localized attributes (those not declared with `localized()` in the schema) do not require this constraint and
 * can be queried without specifying a locale.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/locale#entity-locale-equals)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "equals",
	shortDescription = "The constraint sets the locale context for the query, affecting all localized attributes and associated data." +
		" Entities without data in the specified locale may be excluded from results.",
	userDocsLink = "/documentation/query/filtering/locale#entity-locale-equals",
	supportedIn = ConstraintDomain.ENTITY
)
public class EntityLocaleEquals extends AbstractFilterConstraintLeaf
	implements EntityConstraint<FilterConstraint>, FilterConstraint {

	@Serial private static final long serialVersionUID = 4716406488516855299L;

	private EntityLocaleEquals(@Nonnull Serializable... arguments) {
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
