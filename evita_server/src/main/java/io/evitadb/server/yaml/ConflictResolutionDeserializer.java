/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.server.yaml;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.util.EnumSet;
import java.util.Iterator;

/**
 * Custom Jackson deserializer for {@link ConflictResolution} that accepts both the current object form and
 * the deprecated flat-list form, so existing configuration files keep parsing.
 *
 * Accepted YAML shapes:
 * ```yaml
 * # deprecated flat-list form (coarse + granular members mixed in a single list)
 * conflictPolicy: [ENTITY, ENTITY_ATTRIBUTE]
 * conflictPolicy: []            # last-writer-wins
 *
 * # current object form
 * conflictPolicy:
 *   policy: ENTITY              # optional, defaults to ENTITY
 *   granularity: [ENTITY_ATTRIBUTE, PRICE]
 *
 * # scalar shorthand for a coarse-only policy
 * conflictPolicy: COLLECTION
 * ```
 *
 * The flat-list form collapses the coarsest member (a catalog- or collection-wide lock subsumes any finer
 * member declared alongside it) and folds any granular members into the refinement set, mirroring the legacy
 * semantics. The object form is validated by the {@link ConflictResolution} constructor (granular refinements
 * require an {@link ConflictPolicy#ENTITY} scope).
 *
 * Invalid input fails loudly at load time rather than being silently ignored: the object form
 * rejects unknown field names (only `policy` and `granularity` are accepted) and a non-list
 * `granularity` value, and the deprecated flat-list rejects a `NONE` token — use the empty list
 * `[]` to disable conflict detection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictResolutionDeserializer extends StdDeserializer<ConflictResolution> {
	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Name of the object-form field carrying the coarse conflict policy.
	 */
	private static final String POLICY_FIELD = "policy";
	/**
	 * Name of the object-form field carrying the sub-entity refinements.
	 */
	private static final String GRANULARITY_FIELD = "granularity";

	/**
	 * Creates a new instance of the deserializer.
	 */
	public ConflictResolutionDeserializer() {
		super(ConflictResolution.class);
	}

	@Override
	@Nonnull
	public ConflictResolution deserialize(
		@Nonnull JsonParser parser,
		@Nonnull DeserializationContext context
	) throws IOException {
		final ObjectMapper mapper = (ObjectMapper) parser.getCodec();
		final JsonNode root = mapper.readTree(parser);
		return parseNode(root);
	}

	/**
	 * Interprets a single configuration node as a {@link ConflictResolution}.
	 *
	 * @param node the configuration node, may be null / missing
	 * @return the parsed conflict resolution
	 */
	@Nonnull
	private ConflictResolution parseNode(@Nullable JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			// no value supplied -> fall back to the engine default
			return new ConflictResolution(ConflictPolicy.ENTITY);
		}
		if (node.isArray()) {
			return parseFlatList(node);
		}
		if (node.isObject()) {
			return parseObjectForm(node);
		}
		if (node.isTextual()) {
			// scalar shorthand for a coarse-only policy
			return new ConflictResolution(parseConflictPolicy(node.asText()));
		}
		throw new EvitaInvalidUsageException(
			"Unsupported `conflictPolicy` configuration value: " + node + "!"
		);
	}

	/**
	 * Interprets the deprecated flat-list form, classifying each token by name against the coarse
	 * {@link ConflictPolicy} and, failing that, the {@link GranularConflictPolicy} refinements.
	 *
	 * The historical collapse rules are reproduced without ever materialising a granular
	 * {@link ConflictPolicy} constant:
	 * - an empty list collapses to {@link ConflictPolicy#NONE} (last-writer-wins),
	 * - a catalog-wide lock subsumes a collection-wide lock, which subsumes entity scope,
	 * - otherwise entity scope applies with the granular members folded into the refinement set.
	 *
	 * @param node the flat-list configuration node
	 * @return the parsed conflict resolution
	 */
	@Nonnull
	private ConflictResolution parseFlatList(@Nonnull JsonNode node) {
		if (node.isEmpty()) {
			return new ConflictResolution(ConflictPolicy.NONE);
		}
		boolean hasCatalog = false;
		boolean hasCollection = false;
		final EnumSet<GranularConflictPolicy> granularity = EnumSet.noneOf(GranularConflictPolicy.class);
		for (JsonNode element : node) {
			final String token = element.asText().trim();
			final ConflictPolicy coarse = parseCoarsePolicyOrNull(token);
			if (coarse == ConflictPolicy.CATALOG) {
				hasCatalog = true;
			} else if (coarse == ConflictPolicy.COLLECTION) {
				hasCollection = true;
			} else if (coarse == ConflictPolicy.NONE) {
				// the empty list `[]` is the canonical "no detection" form; a NONE token mixed into a
				// flat list (or standing alone) contradicts every other member and is a configuration error
				throw new EvitaInvalidUsageException(
					"The `NONE` conflict policy cannot appear in a `conflictPolicy` list; use an empty list " +
						"`[]` to disable conflict detection!"
				);
			} else if (coarse == null) {
				// not a coarse policy name -> it must be a granular refinement (or an unknown token)
				granularity.add(parseGranularConflictPolicy(token));
			}
			// an ENTITY coarse member contributes nothing beyond making the list non-empty
		}
		if (hasCatalog) {
			return new ConflictResolution(ConflictPolicy.CATALOG);
		}
		if (hasCollection) {
			return new ConflictResolution(ConflictPolicy.COLLECTION);
		}
		return new ConflictResolution(ConflictPolicy.ENTITY, granularity);
	}

	/**
	 * Interprets the current object form with optional `policy` and `granularity` fields.
	 *
	 * Unknown field names are rejected so an omitted `policy` only ever defaults to
	 * {@link ConflictPolicy#ENTITY} on a genuine omission, never because the field name was misspelled
	 * (which would silently downgrade the configured scope). The `granularity` refinement set has a
	 * single canonical shape - a list; a scalar value is a configuration error rather than something to
	 * coerce into a one-element set.
	 *
	 * @param node the object-form configuration node
	 * @return the parsed conflict resolution
	 */
	@Nonnull
	private ConflictResolution parseObjectForm(@Nonnull JsonNode node) {
		final Iterator<String> fieldNames = node.fieldNames();
		while (fieldNames.hasNext()) {
			final String fieldName = fieldNames.next();
			if (!POLICY_FIELD.equals(fieldName) && !GRANULARITY_FIELD.equals(fieldName)) {
				throw new EvitaInvalidUsageException(
					"Unknown `conflictPolicy` object field `" + fieldName + "`; only `" + POLICY_FIELD +
						"` and `" + GRANULARITY_FIELD + "` are allowed!"
				);
			}
		}
		final JsonNode policyNode = node.get(POLICY_FIELD);
		final ConflictPolicy policy = policyNode == null || policyNode.isNull()
			? ConflictPolicy.ENTITY
			: parseConflictPolicy(policyNode.asText());
		final EnumSet<GranularConflictPolicy> granularity = EnumSet.noneOf(GranularConflictPolicy.class);
		final JsonNode granularityNode = node.get(GRANULARITY_FIELD);
		if (granularityNode != null && !granularityNode.isNull()) {
			if (!granularityNode.isArray()) {
				throw new EvitaInvalidUsageException(
					"The `conflictPolicy.granularity` value must be a list, but was `" + granularityNode + "`!"
				);
			}
			for (JsonNode element : granularityNode) {
				granularity.add(parseGranularConflictPolicy(element.asText()));
			}
		}
		return new ConflictResolution(policy, granularity);
	}

	/**
	 * Parses a {@link ConflictPolicy} constant, raising a descriptive error on an unknown value.
	 *
	 * @param value the textual representation of the policy
	 * @return the resolved policy constant
	 */
	@Nonnull
	private static ConflictPolicy parseConflictPolicy(@Nonnull String value) {
		try {
			return ConflictPolicy.valueOf(value.trim());
		} catch (IllegalArgumentException ex) {
			throw new EvitaInvalidUsageException(
				"Unknown conflict policy `" + value + "` in `conflictPolicy` configuration!"
			);
		}
	}

	/**
	 * Resolves a token to a coarse {@link ConflictPolicy} constant, or returns null when the token is not a
	 * coarse policy name (so a flat-list member can then be tried as a {@link GranularConflictPolicy}).
	 *
	 * @param value the textual representation of the token
	 * @return the coarse policy, or null if the token names no coarse policy
	 */
	@Nullable
	private static ConflictPolicy parseCoarsePolicyOrNull(@Nonnull String value) {
		try {
			return ConflictPolicy.valueOf(value);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Parses a {@link GranularConflictPolicy} constant, raising a descriptive error on an unknown value.
	 *
	 * @param value the textual representation of the granular policy
	 * @return the resolved granular policy constant
	 */
	@Nonnull
	private static GranularConflictPolicy parseGranularConflictPolicy(@Nonnull String value) {
		try {
			return GranularConflictPolicy.valueOf(value.trim());
		} catch (IllegalArgumentException ex) {
			throw new EvitaInvalidUsageException(
				"Unknown granular conflict policy `" + value + "` in `conflictPolicy.granularity` configuration!"
			);
		}
	}

}
