/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Set;

/**
 * Validates schema names and locale availability for client fetches which do not pass through query planning.
 * Internal fetches deliberately bypass this check. Raw constraints are scanned before any allocating request
 * accessor is used; successful leaf checks use the schema maps and the original argument arrays directly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class EntityFetchContentVerifier {

	/** Utility class; instances carry no validation state. */
	private EntityFetchContentVerifier() {
	}

	/**
	 * Checks the client fetch as written before a direct lookup or mutation. Unknown names fail immediately, while
	 * missing locales are accumulated across every managed body. Bulk content without named leaves needs no schema
	 * lookup.
	 *
	 * @param request the client request
	 * @param schema the current owner schema, after evolution for a fetching upsert
	 * @param catalogSchema the catalog used to resolve managed target schemas
	 * @throws AttributeNotFoundException when a named attribute is unknown
	 * @throws AssociatedDataNotFoundException when named associated data is unknown
	 * @throws ReferenceNotFoundException when a named reference is unknown
	 * @throws EntityLocaleMissingException when named localized content has no locale at its level
	 * @throws EvitaInvalidUsageException when a managed referenced type has no schema in the catalog yet
	 */
	public static void verifyEntityFetchContent(
		@Nonnull EvitaRequest request,
		@Nonnull EntitySchemaContract schema,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final Require require = request.getQuery().getRequire();
		if (require == null || !needsSchemaLookup(require)) {
			return;
		}
		final Constraint<?>[] children = require.getChildren();
		for (int i = 0; i < children.length; i++) {
			if (children[i] instanceof EntityFetch fetch) {
				final EntityLocaleMissingException missing = verifyBody(
					fetch, schema, catalogSchema,
					request.getRequiredLocales() != null || request.getImplicitLocale() != null,
					request.getScopes(), null
				);
				if (missing != null) {
					throw missing;
				}
			}
		}
	}

	/**
	 * Scans raw children and arguments, avoiding memoized name arrays, sets and reduced fetches on the unchecked path.
	 *
	 * @param constraint subtree to inspect for explicitly named attributes, associated data or references
	 * @return `true` if any leaf names content that needs schema validation; `false` for bulk-only content
	 */
	private static boolean needsSchemaLookup(@Nonnull Constraint<?> constraint) {
		if (constraint instanceof AttributeContent || constraint instanceof AssociatedDataContent ||
			constraint instanceof ReferenceContent) {
			final Serializable[] arguments = constraint.getArguments();
			for (int i = 0; i < arguments.length; i++) {
				if (arguments[i] instanceof String) {
					return true;
				}
			}
		}
		if (constraint instanceof ConstraintContainer<?> container) {
			final Constraint<?>[] children = container.getChildren();
			for (int i = 0; i < children.length; i++) {
				if (needsSchemaLookup(children[i])) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns whether this body explicitly requests some or all locales, without creating a locale set.
	 *
	 * @param children direct requirements of the fetched body whose locale selection is being checked
	 * @return `true` if `dataInLocales` is present, including an empty selection meaning all locales
	 */
	private static boolean hasLocales(@Nonnull Constraint<?>[] children) {
		for (int i = 0; i < children.length; i++) {
			if (children[i] instanceof DataInLocales) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks a body and every nested body, retaining root scopes and monotonically growing locale availability.
	 *
	 * @param fetch body requirements to validate, including nested bodies and hierarchy parents
	 * @param schema definition of the entity whose body is being checked
	 * @param catalogSchema catalog definitions used to resolve managed referenced entity and group types
	 * @param localeAvailable whether a locale is available at this level from its owner, inheritance,
	 *     `dataInLocales`, or the reference filter's body locale
	 * @param scopes request root scopes, used at every level for the unique-in-scope exemption, as in query planning
	 * @param missing locale failures collected so far, or `null` when none have occurred
	 * @return the collected locale failures extended by this check, or `null` when none have occurred
	 */
	@Nullable
	private static EntityLocaleMissingException verifyBody(
		@Nonnull EntityFetchRequire fetch,
		@Nonnull EntitySchemaContract schema,
		@Nonnull CatalogSchemaContract catalogSchema,
		boolean localeAvailable,
		@Nonnull Set<Scope> scopes,
		@Nullable EntityLocaleMissingException missing
	) {
		final Constraint<?>[] children = ((ConstraintContainer<?>) fetch).getChildren();
		final boolean available = localeAvailable || hasLocales(children);
		for (int i = 0; i < children.length; i++) {
			final Constraint<?> child = children[i];
			if (child instanceof AttributeContent attributes) {
				try {
					AttributeContentTranslator.verifyAttributes(schema, null, schema, attributes, available, scopes);
				} catch (EntityLocaleMissingException ex) {
					missing = mergeMissing(missing, ex);
				}
			} else if (child instanceof AssociatedDataContent data) {
				try {
					AssociatedDataContentTranslator.verifyAssociatedData(data, schema, available);
				} catch (EntityLocaleMissingException ex) {
					missing = mergeMissing(missing, ex);
				}
			} else if (child instanceof ReferenceContent reference) {
				missing = verifyReferences(reference, schema, catalogSchema, available, scopes, missing);
			} else if (child instanceof HierarchyContent hierarchy) {
				final Constraint<?>[] parents = hierarchy.getChildren();
				for (int j = 0; j < parents.length; j++) {
					if (parents[j] instanceof EntityFetch parentFetch) {
						missing = verifyBody(parentFetch, schema, catalogSchema, available, scopes, missing);
					}
				}
			}
		}
		return missing;
	}

	/**
	 * Resolves explicit reference names or every schema reference, independently of stored rows.
	 *
	 * @param content reference requirements selecting explicit names or all references declared by the owner
	 * @param schema definition of the owner used to resolve reference names even when no rows exist
	 * @param catalogSchema catalog definitions used to resolve managed referenced entity and group types
	 * @param localeAvailable whether a locale is available at this level from its owner, inheritance,
	 *     `dataInLocales`, or the reference filter's body locale
	 * @param scopes request root scopes, used at every level for the unique-in-scope exemption, as in query planning
	 * @param missing locale failures collected so far, or `null` when none have occurred
	 * @return the collected locale failures extended by this check, or `null` when none have occurred
	 */
	@Nullable
	private static EntityLocaleMissingException verifyReferences(
		@Nonnull ReferenceContent content,
		@Nonnull EntitySchemaContract schema,
		@Nonnull CatalogSchemaContract catalogSchema,
		boolean localeAvailable,
		@Nonnull Set<Scope> scopes,
		@Nullable EntityLocaleMissingException missing
	) {
		final Serializable[] arguments = content.getArguments();
		boolean named = false;
		for (int i = 0; i < arguments.length; i++) {
			if (arguments[i] instanceof String name) {
				named = true;
				final ReferenceSchemaContract reference = schema.getReferences().get(name);
				if (reference == null) {
					throw new ReferenceNotFoundException(name, schema);
				}
				missing = verifyReference(content, reference, schema, catalogSchema, localeAvailable, scopes, missing);
			}
		}
		if (!named && needsSchemaLookup(content)) {
			for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
				missing = verifyReference(content, reference, schema, catalogSchema, localeAvailable, scopes, missing);
			}
		}
		return missing;
	}

	/**
	 * Checks reference attributes at their owner's level and managed entity/group bodies at their own levels.
	 *
	 * @param content requirements for this reference's attributes and fetched entity or group bodies
	 * @param reference definition used to validate attributes and identify managed entity and group targets
	 * @param schema definition of the owner used to diagnose reference attribute failures
	 * @param catalogSchema catalog definitions used to resolve managed referenced entity and group types
	 * @param localeAvailable whether a locale is available at this level from its owner, inheritance,
	 *     `dataInLocales`, or the reference filter's body locale
	 * @param scopes request root scopes, used at every level for the unique-in-scope exemption, as in query planning
	 * @param missing locale failures collected so far, or `null` when none have occurred
	 * @return the collected locale failures extended by this check, or `null` when none have occurred
	 */
	@Nullable
	private static EntityLocaleMissingException verifyReference(
		@Nonnull ReferenceContent content,
		@Nonnull ReferenceSchemaContract reference,
		@Nonnull EntitySchemaContract schema,
		@Nonnull CatalogSchemaContract catalogSchema,
		boolean localeAvailable,
		@Nonnull Set<Scope> scopes,
		@Nullable EntityLocaleMissingException missing
	) {
		final Constraint<?>[] children = content.getChildren();
		for (int i = 0; i < children.length; i++) {
			final Constraint<?> child = children[i];
			if (child instanceof AttributeContent attributes) {
				try {
					AttributeContentTranslator.verifyAttributes(
						schema, reference, reference, attributes, localeAvailable, scopes
					);
				} catch (EntityLocaleMissingException ex) {
					missing = mergeMissing(missing, ex);
				}
			} else if (child instanceof EntityFetchRequire body && needsSchemaLookup(child)) {
				final boolean group = child instanceof EntityGroupFetch;
				final String targetType = group ?
					reference.getReferencedGroupType() : reference.getReferencedEntityType();
				final boolean managed = group ?
					reference.isReferencedGroupTypeManaged() : reference.isReferencedEntityTypeManaged();
				if (targetType == null || !managed) {
					continue;
				}
				final EntitySchemaContract target = catalogSchema.getEntitySchema(targetType).orElse(null);
				if (target == null) {
					throw new EvitaInvalidUsageException(
						"Schema for entity with name `" + targetType + "` was not found!"
					);
				}
				final boolean available = localeAvailable ||
					hasLocales(((ConstraintContainer<?>) body).getChildren()) ||
					QueryUtils.resolveReferenceBodyLocale(findFilter(content), group, null) != null;
				missing = verifyBody(body, target, catalogSchema, available, scopes, missing);
			}
		}
		return missing;
	}

	/**
	 * Finds the reference filter without wrapping it in an `Optional`.
	 *
	 * @param content reference requirements whose additional children may contain a filter
	 * @return the reference filter, or `null` when no filter is present
	 */
	@Nullable
	private static FilterBy findFilter(@Nonnull ReferenceContent content) {
		final Constraint<?>[] additional = content.getAdditionalChildren();
		for (int i = 0; i < additional.length; i++) {
			if (additional[i] instanceof FilterBy filter) {
				return filter;
			}
		}
		return null;
	}

	/**
	 * Combines diagnostic payloads only after a locale failure; successful checks allocate no collector.
	 *
	 * @param previous locale failures collected so far, or `null` when this is the first failure
	 * @param next newly detected locale failure whose attribute and associated-data names are to be included
	 * @return `next` if no previous failure exists; otherwise a combined failure with each name reported once
	 */
	@Nonnull
	private static EntityLocaleMissingException mergeMissing(
		@Nullable EntityLocaleMissingException previous, @Nonnull EntityLocaleMissingException next
	) {
		return previous == null ? next : new EntityLocaleMissingException(
			appendAbsent(previous.getAttributeNames(), next.getAttributeNames()),
			appendAbsent(previous.getAssociatedDataNames(), next.getAssociatedDataNames())
		);
	}

	/**
	 * Appends the names not reported yet - a leaf reports each name once, and so does the merged diagnostic.
	 *
	 * @param reported names already included in the diagnostic, each occurring once
	 * @param added names from one new failure, each occurring once, to append if not already reported
	 * @return names in encounter order without duplicates; the original array when nothing is appended
	 */
	@Nonnull
	private static String[] appendAbsent(@Nonnull String[] reported, @Nonnull String[] added) {
		int absent = 0;
		for (int i = 0; i < added.length; i++) {
			if (!ArrayUtils.contains(reported, added[i])) {
				absent++;
			}
		}
		if (absent == 0) {
			return reported;
		}
		final String[] result = Arrays.copyOf(reported, reported.length + absent);
		int index = reported.length;
		for (int i = 0; i < added.length; i++) {
			if (!ArrayUtils.contains(reported, added[i])) {
				result[index++] = added[i];
			}
		}
		return result;
	}
}
