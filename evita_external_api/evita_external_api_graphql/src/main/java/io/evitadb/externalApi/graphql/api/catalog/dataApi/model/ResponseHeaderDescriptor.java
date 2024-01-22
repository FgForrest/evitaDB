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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of header arguments of fields of returned full response defined by {@link ResponseDescriptor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ResponseHeaderDescriptor {

	/**
	 * Descriptor of header arguments of field {@link ResponseDescriptor#RECORD_PAGE}
	 */
	interface RecordPageFieldHeaderDescriptor {

		PropertyDescriptor NUMBER = PropertyDescriptor.builder()
			.name("number")
			.description("""
				Defines page number to return. If missing, default is used.
				""")
			.type(nullable(Integer.class))
			.build();
		PropertyDescriptor SIZE = PropertyDescriptor.builder()
			.name("size")
			.description("""
				Defines size of returned page. If missing, default is used.
				""")
			.type(nullable(Integer.class))
			.build();
	}

	/**
	 * Descriptor of header arguments of field {@link ResponseDescriptor#RECORD_STRIP}
	 */
	interface RecordStripFieldHeaderDescriptor {

		PropertyDescriptor OFFSET = PropertyDescriptor.builder()
			.name("offset")
			.description("""
                Defines offset of records in all satisfactory records. If missing, default is used.
				""")
			.type(nullable(Integer.class))
			.build();
		PropertyDescriptor LIMIT = PropertyDescriptor.builder()
			.name("limit")
			.description("""
                Defines number of records to return. If missing, default is used.
				""")
			.type(nullable(Integer.class))
			.build();
	}

	/**
	 * Descriptor of header arguments of field {@link HistogramDescriptor#BUCKETS}
	 */
	interface BucketsFieldHeaderDescriptor {

		PropertyDescriptor REQUESTED_COUNT = PropertyDescriptor.builder()
			.name("requestedCount")
			.description("""
                States the number of histogram buckets (columns) that can be safely visualized to the user.
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor BEHAVIOR = PropertyDescriptor.builder()
			.name("behavior")
			.description("""
				Defines behavior of a histogram computer regarding the histogram buckets (columns).
				""")
			.type(nullable(HistogramBehavior.class))
			.build();
	}

	/**
	 * Descriptor of header arguments of field {@link ExtraResultsDescriptor#QUERY_TELEMETRY}.
	 */
	interface QueryTelemetryFieldHeaderDescriptor {

		PropertyDescriptor FORMATTED = PropertyDescriptor.builder()
			.name("formatted")
			.description("""
                Formats machine data like nanoseconds to human-readable formats.
				""")
			.type(nullable(Boolean.class))
			.build();
	}
}
