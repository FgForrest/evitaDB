/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.UnsatisfiedDependencyException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * This {@link ProxyFactory} implementation throws an {@link UnsatisfiedDependencyException} when
 * {@link EvitaSessionContract} is asked for creating a object of type that is neither {@link EntityClassifier}
 * nor {@link SealedEntity}.
 *
 * The implementation is used when Proxycian is not present on the classpath.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class UnsatisfiedDependencyFactory implements ProxyFactory {
	public static final UnsatisfiedDependencyFactory INSTANCE = new UnsatisfiedDependencyFactory();
	private static final UnsatisfiedDependencyException UNSATISFIED_DEPENDENCY_EXCEPTION = new UnsatisfiedDependencyException(
		"ProxyFactory requires a Proxycian (https://github.com/FgForrest/Proxycian) and " +
			"ByteBuddy (https://github.com/raphw/byte-buddy) to be present on the classpath.",
		"Required dependency is not available in evitaDB engine, contact developers of the application."
	);

	@Nonnull
	@Override
	public <T> T createEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) throws EntityClassInvalidException {
		throw UNSATISFIED_DEPENDENCY_EXCEPTION;
	}

	@Nonnull
	@Override
	public <T> T createEntityBuilderProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) throws EntityClassInvalidException {
		throw UNSATISFIED_DEPENDENCY_EXCEPTION;
	}
}
