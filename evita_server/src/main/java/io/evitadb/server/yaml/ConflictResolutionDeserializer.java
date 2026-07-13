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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

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
 * The flat-list form is mapped through {@link ConflictResolution#fromLegacyPolicySet}, which collapses the
 * coarsest member and folds granular members into the refinement set. The object form is validated by the
 * {@link ConflictResolution} constructor (granular refinements require an {@link ConflictPolicy#ENTITY} scope).
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
			// deprecated flat-list form
			final List<ConflictPolicy> policies = new ArrayList<>(node.size());
			for (JsonNode element : node) {
				policies.add(parseConflictPolicy(element.asText()));
			}
			return ConflictResolution.fromLegacyPolicySet(policies);
		}
		if (node.isObject()) {
			final JsonNode policyNode = node.get(POLICY_FIELD);
			final ConflictPolicy policy = policyNode == null || policyNode.isNull()
				? ConflictPolicy.ENTITY
				: parseConflictPolicy(policyNode.asText());
			final EnumSet<GranularConflictPolicy> granularity = EnumSet.noneOf(GranularConflictPolicy.class);
			final JsonNode granularityNode = node.get(GRANULARITY_FIELD);
			if (granularityNode != null && granularityNode.isArray()) {
				for (JsonNode element : granularityNode) {
					granularity.add(parseGranularConflictPolicy(element.asText()));
				}
			}
			return new ConflictResolution(policy, granularity);
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
