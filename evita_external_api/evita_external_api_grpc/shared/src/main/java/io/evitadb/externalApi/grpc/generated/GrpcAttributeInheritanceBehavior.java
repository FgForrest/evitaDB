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
 * Enum specifies different modes for reference attributes inheritance in reflected schema.
 * </pre>
 *
 * Protobuf enum {@code io.evitadb.externalApi.grpc.generated.GrpcAttributeInheritanceBehavior}
 */
public enum GrpcAttributeInheritanceBehavior
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <pre>
   **
   * Inherit all attributes by default except those listed in the {&#64;link #getAttributeInheritanceFilter()} array.
   * </pre>
   *
   * <code>INHERIT_ALL_EXCEPT = 0;</code>
   */
  INHERIT_ALL_EXCEPT(0),
  /**
   * <pre>
   **
   * Do not inherit any attributes by default except those listed in the {&#64;link #getAttributeInheritanceFilter()} array.
   * </pre>
   *
   * <code>INHERIT_ONLY_SPECIFIED = 1;</code>
   */
  INHERIT_ONLY_SPECIFIED(1),
  UNRECOGNIZED(-1),
  ;

  /**
   * <pre>
   **
   * Inherit all attributes by default except those listed in the {&#64;link #getAttributeInheritanceFilter()} array.
   * </pre>
   *
   * <code>INHERIT_ALL_EXCEPT = 0;</code>
   */
  public static final int INHERIT_ALL_EXCEPT_VALUE = 0;
  /**
   * <pre>
   **
   * Do not inherit any attributes by default except those listed in the {&#64;link #getAttributeInheritanceFilter()} array.
   * </pre>
   *
   * <code>INHERIT_ONLY_SPECIFIED = 1;</code>
   */
  public static final int INHERIT_ONLY_SPECIFIED_VALUE = 1;


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
  public static GrpcAttributeInheritanceBehavior valueOf(int value) {
    return forNumber(value);
  }

  /**
   * @param value The numeric wire value of the corresponding enum entry.
   * @return The enum associated with the given numeric wire value.
   */
  public static GrpcAttributeInheritanceBehavior forNumber(int value) {
    switch (value) {
      case 0: return INHERIT_ALL_EXCEPT;
      case 1: return INHERIT_ONLY_SPECIFIED;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<GrpcAttributeInheritanceBehavior>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      GrpcAttributeInheritanceBehavior> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<GrpcAttributeInheritanceBehavior>() {
          public GrpcAttributeInheritanceBehavior findValueByNumber(int number) {
            return GrpcAttributeInheritanceBehavior.forNumber(number);
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
    return io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor().getEnumTypes().get(28);
  }

  private static final GrpcAttributeInheritanceBehavior[] VALUES = values();

  public static GrpcAttributeInheritanceBehavior valueOf(
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

  private GrpcAttributeInheritanceBehavior(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:io.evitadb.externalApi.grpc.generated.GrpcAttributeInheritanceBehavior)
}

