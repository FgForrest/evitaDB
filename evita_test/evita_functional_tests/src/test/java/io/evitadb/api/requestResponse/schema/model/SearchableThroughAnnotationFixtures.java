/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.dataType.Scope;

/** Annotation fixtures for independently declared reference text composition metadata. */
public interface SearchableThroughAnnotationFixtures {
	String OWNER = "SearchableThroughAnnotationOwner";
	String TARGET = "ContentBlock";

	/** Marker return type; the annotation supplies the managed target type. */
	interface BlockRef {
	}

	/** General annotation shape. */
	@Entity(name = OWNER)
	interface General {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, indexed = ReferenceIndexType.FOR_FILTERING,
			searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.75f)
		BlockRef[] getBlocks();
	}

	/** Defaults annotation shape. */
	@Entity(name = OWNER)
	interface Defaults {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET)
		BlockRef[] getBlocks();
	}

	/** UnmanagedTarget annotation shape: the reference targets an entity type evitaDB does not manage. */
	@Entity(name = OWNER)
	interface UnmanagedTarget {
		@PrimaryKey
		int getId();

		@Reference(entity = "ExternalBlock", managed = false)
		BlockRef[] getBlocks();
	}

	/** WeightOnly annotation shape. */
	@Entity(name = OWNER)
	interface WeightOnly {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, searchableThroughWeight = 0.0f)
		BlockRef[] getBlocks();
	}

	/** Scoped annotation shape. */
	@Entity(name = OWNER)
	interface Scoped {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, searchableThroughWeight = 9.0f,
			scope = @ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.3f))
		BlockRef[] getBlocks();
	}

	/** ConflictingScopedWeights annotation shape: two scope entries ask for different weights. */
	@Entity(name = OWNER)
	interface ConflictingScopedWeights {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, scope = {
			@ScopeReferenceSettings(indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.2f),
			@ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.8f)
		})
		BlockRef[] getBlocks();
	}

	/** RepeatedScopedWeight annotation shape: both scope entries repeat the same weight. */
	@Entity(name = OWNER)
	interface RepeatedScopedWeight {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, scope = {
			@ScopeReferenceSettings(indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.8f),
			@ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.8f)
		})
		BlockRef[] getBlocks();
	}

	/** EarlierScopedWeight annotation shape: only the first scope entry sets a weight. */
	@Entity(name = OWNER)
	interface EarlierScopedWeight {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, scope = {
			@ScopeReferenceSettings(indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.8f),
			@ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING)
		})
		BlockRef[] getBlocks();
	}

	/** ReflectedGeneral annotation shape. */
	@Entity(name = OWNER)
	interface ReflectedGeneral {
		@PrimaryKey
		int getId();

		@ReflectedReference(ofEntity = TARGET, ofName = "products",
			searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.4f)
		BlockRef[] getBlocks();
	}

	/** ReflectedScoped annotation shape. */
	@Entity(name = OWNER)
	interface ReflectedScoped {
		@PrimaryKey
		int getId();

		@ReflectedReference(ofEntity = TARGET, ofName = "products",
			scope = @ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION, searchableThroughWeight = 0.6f))
		BlockRef[] getBlocks();
	}

	/** ReflectedDefaults annotation shape. */
	@Entity(name = OWNER)
	interface ReflectedDefaults {
		@PrimaryKey
		int getId();

		@ReflectedReference(ofEntity = TARGET, ofName = "products")
		BlockRef[] getBlocks();
	}

	/** TooManyGeneral annotation shape. */
	@Entity(name = OWNER)
	interface TooManyGeneral {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, indexed = ReferenceIndexType.FOR_FILTERING,
			searchableThrough = {ReferenceSearchMode.COMPOSITION, ReferenceSearchMode.COMPOSITION})
		BlockRef[] getBlocks();
	}

	/** TooManyScoped annotation shape. */
	@Entity(name = OWNER)
	interface TooManyScoped {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET,
			scope = @ScopeReferenceSettings(indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = {ReferenceSearchMode.COMPOSITION, ReferenceSearchMode.COMPOSITION}))
		BlockRef[] getBlocks();
	}

	/** TooManyReflected annotation shape. */
	@Entity(name = OWNER)
	interface TooManyReflected {
		@PrimaryKey
		int getId();

		@ReflectedReference(ofEntity = TARGET, ofName = "products",
			searchableThrough = {ReferenceSearchMode.COMPOSITION, ReferenceSearchMode.COMPOSITION})
		BlockRef[] getBlocks();
	}

	/** MixedGeneralAndScoped annotation shape. */
	@Entity(name = OWNER)
	interface MixedGeneralAndScoped {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, searchableThrough = ReferenceSearchMode.COMPOSITION,
			scope = @ScopeReferenceSettings(scope = Scope.ARCHIVED, indexed = ReferenceIndexType.FOR_FILTERING,
				searchableThrough = ReferenceSearchMode.COMPOSITION))
		BlockRef[] getBlocks();
	}

	/** InvalidWeight annotation shape. */
	@Entity(name = OWNER)
	interface InvalidWeight {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, searchableThroughWeight = -1.0f)
		BlockRef[] getBlocks();
	}

	/** NegativeZero annotation shape. */
	@Entity(name = OWNER)
	interface NegativeZero {
		@PrimaryKey
		int getId();

		@Reference(entity = TARGET, searchableThroughWeight = -0.0f)
		BlockRef[] getBlocks();
	}
}
