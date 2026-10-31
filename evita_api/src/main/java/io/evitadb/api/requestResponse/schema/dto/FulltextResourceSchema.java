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


package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * Internal implementation of {@link FulltextResourceSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public final class FulltextResourceSchema implements FulltextResourceSchemaContract {
	@Serial private static final long serialVersionUID = -2716931650448127905L;

	/**
	 * The name steps refer to the resource by.
	 */
	@Nonnull private final String name;
	/**
	 * The content of the resource.
	 */
	@Nonnull private final FulltextResourceContent content;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of the schema from a different
	 * package than the current one, but still internal code of the Evita ecosystem.
	 *
	 * Do not use this method from the client code!
	 *
	 * @param name    the name steps refer to the resource by
	 * @param content the content of the resource
	 * @return the schema
	 */
	@Nonnull
	public static FulltextResourceSchema _internalBuild(
		@Nonnull String name,
		@Nonnull FulltextResourceContent content
	) {
		return new FulltextResourceSchema(name, content);
	}

	/**
	 * Creates the schema, validating it.
	 *
	 * @param name    the name steps refer to the resource by, never blank
	 * @param content the content of the resource
	 */
	private FulltextResourceSchema(@Nonnull String name, @Nonnull FulltextResourceContent content) {
		Assert.notNull(name, "Fulltext resource name must not be null.");
		Assert.isTrue(
			!name.isBlank(), () -> new InvalidSchemaMutationException("Fulltext resource name must not be blank.")
		);
		Assert.notNull(content, "Fulltext resource content must not be null.");
		this.name = name;
		this.content = content;
	}

	@Nonnull
	@Override
	public String getName() {
		return this.name;
	}

	@Nonnull
	@Override
	public FulltextResourceContent getContent() {
		return this.content;
	}

	@Nonnull
	@Override
	public String toString() {
		return "FulltextResourceSchema{name='" + this.name + "', content=" + this.content + '}';
	}

}
