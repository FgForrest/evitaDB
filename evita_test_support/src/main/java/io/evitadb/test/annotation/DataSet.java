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

package io.evitadb.test.annotation;

import io.evitadb.api.CatalogState;
import io.evitadb.test.TestConstants;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation bootstraps shared dataset to be used among multiple methods in entire test suite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface DataSet {

	/**
	 * Defines name of the dataset.
	 */
	String value();

	/**
	 * Defines catalog name for the initial catalog. If annotation is not used, catalog name defaults to
	 * {@link TestConstants#TEST_CATALOG}.
	 */
	String catalogName() default TestConstants.TEST_CATALOG;

	/**
	 * Defines list of web API that should be started along with the evita server instance. Use one of the following
	 * codes:
	 *
	 * - {@link io.evitadb.externalApi.grpc.GrpcProvider#CODE}
	 * - {@link io.evitadb.externalApi.graphql.GraphQLProvider#CODE}
	 * - {@link io.evitadb.externalApi.rest.RestProvider#CODE}
	 */
	String[] openWebApi() default {};

	/**
	 * Marks the dataset as read-only after the initialization method has been finished. This is a safety lock.
	 * When you need to write to a data set from the unit test methods, you'd probably don't want it to be shared with
	 * other tests or only a controlled sub-set of them.
	 *
	 * If you disable readOnly safety lock, you should probably enable {@link #destroyAfterClass()} or
	 * {@link UseDataSet#destroyAfterTest()} attributes to true.
	 *
	 * That's why readOnly is set to true by default, we want you to think about it before you switch off this safety
	 * lock.
	 */
	boolean readOnly() default true;

	/**
	 * If set to true the evitaDB server instance is closed and deleted after all test methods of the set where
	 * {@link DataSet} annotation is used were executed.
	 */
	boolean destroyAfterClass() default false;

	/**
	 * Defines the state the catalog is expected to be in. By default, evita is automatically switched to
	 * the transactional {@link CatalogState#ALIVE} mode.
	 */
	CatalogState expectedCatalogState() default CatalogState.ALIVE;

}
