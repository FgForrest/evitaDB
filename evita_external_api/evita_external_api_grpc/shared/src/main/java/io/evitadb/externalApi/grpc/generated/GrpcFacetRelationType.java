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
// source: GrpcEnums.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Enum defines all supported relation type that can be used in the facet summary impact calculation.
 * </pre>
 *
 * Protobuf enum {@code io.evitadb.externalApi.grpc.generated.GrpcFacetRelationType}
 */
public enum GrpcFacetRelationType
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <pre>
   * Logical OR relation.
   * </pre>
   *
   * <code>DISJUNCTION = 0;</code>
   */
  DISJUNCTION(0),
  /**
   * <pre>
   * Logical AND relation.
   * </pre>
   *
   * <code>CONJUNCTION = 1;</code>
   */
  CONJUNCTION(1),
  /**
   * <pre>
   * Logical AND NOT relation.
   * </pre>
   *
   * <code>NEGATION = 2;</code>
   */
  NEGATION(2),
  /**
   * <pre>
   * Exclusive relations to other facets on the same level, when selected no other facet on that level can be selected.
   * </pre>
   *
   * <code>EXCLUSIVITY = 3;</code>
   */
  EXCLUSIVITY(3),
  UNRECOGNIZED(-1),
  ;

  /**
   * <pre>
   * Logical OR relation.
   * </pre>
   *
   * <code>DISJUNCTION = 0;</code>
   */
  public static final int DISJUNCTION_VALUE = 0;
  /**
   * <pre>
   * Logical AND relation.
   * </pre>
   *
   * <code>CONJUNCTION = 1;</code>
   */
  public static final int CONJUNCTION_VALUE = 1;
  /**
   * <pre>
   * Logical AND NOT relation.
   * </pre>
   *
   * <code>NEGATION = 2;</code>
   */
  public static final int NEGATION_VALUE = 2;
  /**
   * <pre>
   * Exclusive relations to other facets on the same level, when selected no other facet on that level can be selected.
   * </pre>
   *
   * <code>EXCLUSIVITY = 3;</code>
   */
  public static final int EXCLUSIVITY_VALUE = 3;


  public final int getNumber() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalArgumentException(
          "Can't get the number of an unknown enum value.");
    }
    return value;
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   * @deprecated Use {@link #forNumber(int)} instead.
   */
  @java.lang.Deprecated
  public static GrpcFacetRelationType valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static GrpcFacetRelationType forNumber(int value) {
    switch (value) {
      case 0: return DISJUNCTION;
      case 1: return CONJUNCTION;
      case 2: return NEGATION;
      case 3: return EXCLUSIVITY;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<GrpcFacetRelationType>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      GrpcFacetRelationType> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<GrpcFacetRelationType>() {
          public GrpcFacetRelationType findValueByNumber(int number) {
            return GrpcFacetRelationType.forNumber(number);
          }
        };

  public final com.google.protobuf.Descriptors.EnumValueDescriptor
      getValueDescriptor() {
    if (this == UNRECOGNIZED) {
      throw new java.lang.IllegalStateException(
          "Can't get the descriptor of an unrecognized enum value.");
    }
    return getDescriptor().getValues().get(ordinal());
  }
  public final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptorForType() {
    return getDescriptor();
  }
  public static final com.google.protobuf.Descriptors.EnumDescriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor().getEnumTypes().get(32);
  }

  private static final GrpcFacetRelationType[] VALUES = values();

  public static GrpcFacetRelationType valueOf(
      com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
    if (desc.getType() != getDescriptor()) {
      throw new java.lang.IllegalArgumentException(
        "EnumValueDescriptor is not for this type.");
    }
    if (desc.getIndex() == -1) {
      return UNRECOGNIZED;
    }
    return VALUES[desc.getIndex()];
  }

  private final int value;

  private GrpcFacetRelationType(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:io.evitadb.externalApi.grpc.generated.GrpcFacetRelationType)
}

