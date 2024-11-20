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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Response to RestoreEntity request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse}
 */
public final class GrpcRestoreEntityResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)
    GrpcRestoreEntityResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcRestoreEntityResponse.newBuilder() to construct.
  private GrpcRestoreEntityResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcRestoreEntityResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcRestoreEntityResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcRestoreEntityResponse(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 10: {
            io.evitadb.externalApi.grpc.generated.GrpcEntityReference.Builder subBuilder = null;
            if (responseCase_ == 1) {
              subBuilder = ((io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_).toBuilder();
            }
            response_ =
                input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcEntityReference.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_);
              response_ = subBuilder.buildPartial();
            }
            responseCase_ = 1;
            break;
          }
          case 18: {
            io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder subBuilder = null;
            if (responseCase_ == 2) {
              subBuilder = ((io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_).toBuilder();
            }
            response_ =
                input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_);
              response_ = subBuilder.buildPartial();
            }
            responseCase_ = 2;
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreEntityResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreEntityResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.class, io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.Builder.class);
  }

  private int responseCase_ = 0;
  private java.lang.Object response_;
  public enum ResponseCase
      implements com.google.protobuf.Internal.EnumLite,
          com.google.protobuf.AbstractMessage.InternalOneOfEnum {
    ENTITYREFERENCE(1),
    ENTITY(2),
    RESPONSE_NOT_SET(0);
    private final int value;
    private ResponseCase(int value) {
      this.value = value;
    }
    /**
     * @param value The number of the enum to look for.
     * @return The enum associated with the given number.
     * @deprecated Use {@link #forNumber(int)} instead.
     */
    @java.lang.Deprecated
    public static ResponseCase valueOf(int value) {
      return forNumber(value);
    }

    public static ResponseCase forNumber(int value) {
      switch (value) {
        case 1: return ENTITYREFERENCE;
        case 2: return ENTITY;
        case 0: return RESPONSE_NOT_SET;
        default: return null;
      }
    }
    public int getNumber() {
      return this.value;
    }
  };

  public ResponseCase
  getResponseCase() {
    return ResponseCase.forNumber(
        responseCase_);
  }

  public static final int ENTITYREFERENCE_FIELD_NUMBER = 1;
  /**
   * <pre>
   * The restored entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   * @return Whether the entityReference field is set.
   */
  @java.lang.Override
  public boolean hasEntityReference() {
    return responseCase_ == 1;
  }
  /**
   * <pre>
   * The restored entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   * @return The entityReference.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcEntityReference getEntityReference() {
    if (responseCase_ == 1) {
       return (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_;
    }
    return io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance();
  }
  /**
   * <pre>
   * The restored entity reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder getEntityReferenceOrBuilder() {
    if (responseCase_ == 1) {
       return (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_;
    }
    return io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance();
  }

  public static final int ENTITY_FIELD_NUMBER = 2;
  /**
   * <pre>
   * The restored entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   * @return Whether the entity field is set.
   */
  @java.lang.Override
  public boolean hasEntity() {
    return responseCase_ == 2;
  }
  /**
   * <pre>
   * The restored entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   * @return The entity.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntity() {
    if (responseCase_ == 2) {
       return (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_;
    }
    return io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance();
  }
  /**
   * <pre>
   * The restored entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntityOrBuilder() {
    if (responseCase_ == 2) {
       return (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_;
    }
    return io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance();
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (responseCase_ == 1) {
      output.writeMessage(1, (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_);
    }
    if (responseCase_ == 2) {
      output.writeMessage(2, (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (responseCase_ == 1) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_);
    }
    if (responseCase_ == 2) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse other = (io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse) obj;

    if (!getResponseCase().equals(other.getResponseCase())) return false;
    switch (responseCase_) {
      case 1:
        if (!getEntityReference()
            .equals(other.getEntityReference())) return false;
        break;
      case 2:
        if (!getEntity()
            .equals(other.getEntity())) return false;
        break;
      case 0:
      default:
    }
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    switch (responseCase_) {
      case 1:
        hash = (37 * hash) + ENTITYREFERENCE_FIELD_NUMBER;
        hash = (53 * hash) + getEntityReference().hashCode();
        break;
      case 2:
        hash = (37 * hash) + ENTITY_FIELD_NUMBER;
        hash = (53 * hash) + getEntity().hashCode();
        break;
      case 0:
      default:
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Response to RestoreEntity request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)
      io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreEntityResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreEntityResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.class, io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      responseCase_ = 0;
      response_ = null;
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreEntityResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse build() {
      io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse result = new io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse(this);
      if (responseCase_ == 1) {
        if (entityReferenceBuilder_ == null) {
          result.response_ = response_;
        } else {
          result.response_ = entityReferenceBuilder_.build();
        }
      }
      if (responseCase_ == 2) {
        if (entityBuilder_ == null) {
          result.response_ = response_;
        } else {
          result.response_ = entityBuilder_.build();
        }
      }
      result.responseCase_ = responseCase_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse.getDefaultInstance()) return this;
      switch (other.getResponseCase()) {
        case ENTITYREFERENCE: {
          mergeEntityReference(other.getEntityReference());
          break;
        }
        case ENTITY: {
          mergeEntity(other.getEntity());
          break;
        }
        case RESPONSE_NOT_SET: {
          break;
        }
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int responseCase_ = 0;
    private java.lang.Object response_;
    public ResponseCase
        getResponseCase() {
      return ResponseCase.forNumber(
          responseCase_);
    }

    public Builder clearResponse() {
      responseCase_ = 0;
      response_ = null;
      onChanged();
      return this;
    }


    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcEntityReference, io.evitadb.externalApi.grpc.generated.GrpcEntityReference.Builder, io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder> entityReferenceBuilder_;
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     * @return Whether the entityReference field is set.
     */
    @java.lang.Override
    public boolean hasEntityReference() {
      return responseCase_ == 1;
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     * @return The entityReference.
     */
    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcEntityReference getEntityReference() {
      if (entityReferenceBuilder_ == null) {
        if (responseCase_ == 1) {
          return (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_;
        }
        return io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance();
      } else {
        if (responseCase_ == 1) {
          return entityReferenceBuilder_.getMessage();
        }
        return io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance();
      }
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    public Builder setEntityReference(io.evitadb.externalApi.grpc.generated.GrpcEntityReference value) {
      if (entityReferenceBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        response_ = value;
        onChanged();
      } else {
        entityReferenceBuilder_.setMessage(value);
      }
      responseCase_ = 1;
      return this;
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    public Builder setEntityReference(
        io.evitadb.externalApi.grpc.generated.GrpcEntityReference.Builder builderForValue) {
      if (entityReferenceBuilder_ == null) {
        response_ = builderForValue.build();
        onChanged();
      } else {
        entityReferenceBuilder_.setMessage(builderForValue.build());
      }
      responseCase_ = 1;
      return this;
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    public Builder mergeEntityReference(io.evitadb.externalApi.grpc.generated.GrpcEntityReference value) {
      if (entityReferenceBuilder_ == null) {
        if (responseCase_ == 1 &&
            response_ != io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance()) {
          response_ = io.evitadb.externalApi.grpc.generated.GrpcEntityReference.newBuilder((io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_)
              .mergeFrom(value).buildPartial();
        } else {
          response_ = value;
        }
        onChanged();
      } else {
        if (responseCase_ == 1) {
          entityReferenceBuilder_.mergeFrom(value);
        }
        entityReferenceBuilder_.setMessage(value);
      }
      responseCase_ = 1;
      return this;
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    public Builder clearEntityReference() {
      if (entityReferenceBuilder_ == null) {
        if (responseCase_ == 1) {
          responseCase_ = 0;
          response_ = null;
          onChanged();
        }
      } else {
        if (responseCase_ == 1) {
          responseCase_ = 0;
          response_ = null;
        }
        entityReferenceBuilder_.clear();
      }
      return this;
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityReference.Builder getEntityReferenceBuilder() {
      return getEntityReferenceFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder getEntityReferenceOrBuilder() {
      if ((responseCase_ == 1) && (entityReferenceBuilder_ != null)) {
        return entityReferenceBuilder_.getMessageOrBuilder();
      } else {
        if (responseCase_ == 1) {
          return (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_;
        }
        return io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance();
      }
    }
    /**
     * <pre>
     * The restored entity reference.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcEntityReference entityReference = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcEntityReference, io.evitadb.externalApi.grpc.generated.GrpcEntityReference.Builder, io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder>
        getEntityReferenceFieldBuilder() {
      if (entityReferenceBuilder_ == null) {
        if (!(responseCase_ == 1)) {
          response_ = io.evitadb.externalApi.grpc.generated.GrpcEntityReference.getDefaultInstance();
        }
        entityReferenceBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcEntityReference, io.evitadb.externalApi.grpc.generated.GrpcEntityReference.Builder, io.evitadb.externalApi.grpc.generated.GrpcEntityReferenceOrBuilder>(
                (io.evitadb.externalApi.grpc.generated.GrpcEntityReference) response_,
                getParentForChildren(),
                isClean());
        response_ = null;
      }
      responseCase_ = 1;
      onChanged();;
      return entityReferenceBuilder_;
    }

    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcSealedEntity, io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder, io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder> entityBuilder_;
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     * @return Whether the entity field is set.
     */
    @java.lang.Override
    public boolean hasEntity() {
      return responseCase_ == 2;
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     * @return The entity.
     */
    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntity() {
      if (entityBuilder_ == null) {
        if (responseCase_ == 2) {
          return (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_;
        }
        return io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance();
      } else {
        if (responseCase_ == 2) {
          return entityBuilder_.getMessage();
        }
        return io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance();
      }
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    public Builder setEntity(io.evitadb.externalApi.grpc.generated.GrpcSealedEntity value) {
      if (entityBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        response_ = value;
        onChanged();
      } else {
        entityBuilder_.setMessage(value);
      }
      responseCase_ = 2;
      return this;
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    public Builder setEntity(
        io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder builderForValue) {
      if (entityBuilder_ == null) {
        response_ = builderForValue.build();
        onChanged();
      } else {
        entityBuilder_.setMessage(builderForValue.build());
      }
      responseCase_ = 2;
      return this;
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    public Builder mergeEntity(io.evitadb.externalApi.grpc.generated.GrpcSealedEntity value) {
      if (entityBuilder_ == null) {
        if (responseCase_ == 2 &&
            response_ != io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance()) {
          response_ = io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.newBuilder((io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_)
              .mergeFrom(value).buildPartial();
        } else {
          response_ = value;
        }
        onChanged();
      } else {
        if (responseCase_ == 2) {
          entityBuilder_.mergeFrom(value);
        }
        entityBuilder_.setMessage(value);
      }
      responseCase_ = 2;
      return this;
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    public Builder clearEntity() {
      if (entityBuilder_ == null) {
        if (responseCase_ == 2) {
          responseCase_ = 0;
          response_ = null;
          onChanged();
        }
      } else {
        if (responseCase_ == 2) {
          responseCase_ = 0;
          response_ = null;
        }
        entityBuilder_.clear();
      }
      return this;
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder getEntityBuilder() {
      return getEntityFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntityOrBuilder() {
      if ((responseCase_ == 2) && (entityBuilder_ != null)) {
        return entityBuilder_.getMessageOrBuilder();
      } else {
        if (responseCase_ == 2) {
          return (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_;
        }
        return io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance();
      }
    }
    /**
     * <pre>
     * The restored entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcSealedEntity, io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder, io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder>
        getEntityFieldBuilder() {
      if (entityBuilder_ == null) {
        if (!(responseCase_ == 2)) {
          response_ = io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance();
        }
        entityBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcSealedEntity, io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder, io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder>(
                (io.evitadb.externalApi.grpc.generated.GrpcSealedEntity) response_,
                getParentForChildren(),
                isClean());
        response_ = null;
      }
      responseCase_ = 2;
      onChanged();;
      return entityBuilder_;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse)
  private static final io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcRestoreEntityResponse>
      PARSER = new com.google.protobuf.AbstractParser<GrpcRestoreEntityResponse>() {
    @java.lang.Override
    public GrpcRestoreEntityResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcRestoreEntityResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcRestoreEntityResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcRestoreEntityResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcRestoreEntityResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}
