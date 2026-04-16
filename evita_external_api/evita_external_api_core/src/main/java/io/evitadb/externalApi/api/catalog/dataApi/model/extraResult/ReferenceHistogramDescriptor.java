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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Represents a histogram computed over a reference. In addition to the four base histogram properties
 * (inherited from {@link HistogramDescriptor#THIS_INTERFACE}), this descriptor exposes the referenced
 * entities whose values anchor the minimum and maximum buckets of the histogram.
 *
 * Note: this descriptor is meant to be a template for per-referenced-entity-type generated DTOs.
 * The `minReferencedEntity` and `maxReferencedEntity` properties are dynamically registered by the
 * GraphQL and REST schema builders with the concrete referenced entity type plugged in at build time.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface ReferenceHistogramDescriptor extends HistogramDescriptor {

	PropertyDescriptor MIN_REFERENCED_ENTITY = PropertyDescriptor.builder()
		.name("minReferencedEntity")
		.description("""
			Referenced entity whose value anchors the minimum bucket of the histogram.
			Populated only when an entity fetch for the associated reference has been requested.
			""")
		// type is expected to be an entity object of the reference target entity type
		.build();

	PropertyDescriptor MAX_REFERENCED_ENTITY = PropertyDescriptor.builder()
		.name("maxReferencedEntity")
		.description("""
			Referenced entity whose value anchors the maximum bucket of the histogram.
			Populated only when an entity fetch for the associated reference has been requested.
			""")
		// type is expected to be an entity object of the reference target entity type
		.build();

	/**
	 * Concrete per-referenced-entity-type histogram descriptor. Implements the shared
	 * {@link HistogramDescriptor#THIS_INTERFACE}, inheriting its four base properties.
	 * The wildcard in the name is resolved to the referenced entity type at build time
	 * (e.g. `Category` -> `CategoryHistogram`) so that a single concrete type is shared
	 * across every reference targeting the same entity type.
	 */
	ObjectDescriptor THIS = ObjectDescriptor.implementing(HistogramDescriptor.THIS_INTERFACE)
		.name("*Histogram")
		.description("""
			Histogram computed over a reference. In addition to the base histogram data, this object
			carries optional referenced entities whose values anchor the minimum and maximum buckets.
			""")
		.build();
}
