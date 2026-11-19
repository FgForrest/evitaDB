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
package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * A typed reference to a named fulltext resource of the catalog schema, created by
 * {@link AnalysisSteps#resource(String)} and accepted by the step factories whose parameters read resources.
 *
 * Only the name reaches the stored step: a parameter that reads resources is a plain `String[]` of resource names,
 * and the parameter itself declares which kind of resource it reads. The type exists so that Java code cannot pass a
 * word where a resource name is expected.
 *
 * @param name the name of the resource, either declared in the catalog schema or one of the built-in lists such as
 *             {@link AnalysisSteps#CZECH_STOP_WORDS}
 */
public record FulltextResourceReference(@Nonnull String name) implements Serializable {

	/**
	 * Validates the name.
	 *
	 * @param name the name of the resource, never blank
	 */
	public FulltextResourceReference {
		Assert.notNull(name, "Resource name must not be null.");
		Assert.isTrue(!name.isBlank(), "Resource name must not be blank.");
	}

	@Nonnull
	@Override
	public String toString() {
		return "resource('" + this.name + "')";
	}

}
