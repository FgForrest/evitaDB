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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcReferenceSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcSetReferenceSchemaFacetedMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaFacetedMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * deprecated in favor of `facetedInScopes`
   * </pre>
   *
   * <code>bool faceted = 2 [deprecated = true];</code>
   * @deprecated
   * @return The faceted.
   */
  @java.lang.Deprecated boolean getFaceted();

  /**
   * <pre>
   * Set to true when the faceted property should be inherited from the original.
   * This property makes sense only for inherited reference attributes on reflected reference. For all other cases it
   * must be left as false. When set to TRUE the value of `faceted` field is ignored.
   * </pre>
   *
   * <code>bool inherited = 3;</code>
   * @return The inherited.
   */
  boolean getInherited();

  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope facetedInScopes = 4;</code>
   * @return A list containing the facetedInScopes.
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityScope> getFacetedInScopesList();
  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope facetedInScopes = 4;</code>
   * @return The count of facetedInScopes.
   */
  int getFacetedInScopesCount();
  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope facetedInScopes = 4;</code>
   * @param index The index of the element to return.
   * @return The facetedInScopes at the given index.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityScope getFacetedInScopes(int index);
  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope facetedInScopes = 4;</code>
   * @return A list containing the enum numeric values on the wire for facetedInScopes.
   */
  java.util.List<java.lang.Integer>
  getFacetedInScopesValueList();
  /**
   * <pre>
   * Whether the statistics data for this reference should be maintained and this allowing to get
   * `facetSummary` for this reference or use `facet_{reference name}_inSet`
   * filtering query.
   * Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope facetedInScopes = 4;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of facetedInScopes at the given index.
   */
  int getFacetedInScopesValue(int index);
}
