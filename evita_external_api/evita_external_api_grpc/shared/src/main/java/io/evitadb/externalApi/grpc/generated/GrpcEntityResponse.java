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
 * Response to GetEntity request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcEntityResponse}
 */
public final class GrpcEntityResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcEntityResponse)
    GrpcEntityResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcEntityResponse.newBuilder() to construct.
  private GrpcEntityResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcEntityResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcEntityResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcEntityResponse(
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
            io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder subBuilder = null;
            if (entity_ != null) {
              subBuilder = entity_.toBuilder();
            }
            entity_ = input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(entity_);
              entity_ = subBuilder.buildPartial();
            }

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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.class, io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.Builder.class);
  }

  public static final int ENTITY_FIELD_NUMBER = 1;
  private io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity_;
  /**
   * <pre>
   * The found entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
   * @return Whether the entity field is set.
   */
  @java.lang.Override
  public boolean hasEntity() {
    return entity_ != null;
  }
  /**
   * <pre>
   * The found entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
   * @return The entity.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntity() {
    return entity_ == null ? io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance() : entity_;
  }
  /**
   * <pre>
   * The found entity.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntityOrBuilder() {
    return getEntity();
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
    if (entity_ != null) {
      output.writeMessage(1, getEntity());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (entity_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getEntity());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcEntityResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcEntityResponse other = (io.evitadb.externalApi.grpc.generated.GrpcEntityResponse) obj;

    if (hasEntity() != other.hasEntity()) return false;
    if (hasEntity()) {
      if (!getEntity()
          .equals(other.getEntity())) return false;
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
    if (hasEntity()) {
      hash = (37 * hash) + ENTITY_FIELD_NUMBER;
      hash = (53 * hash) + getEntity().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcEntityResponse prototype) {
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
   * Response to GetEntity request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcEntityResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcEntityResponse)
      io.evitadb.externalApi.grpc.generated.GrpcEntityResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.class, io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.newBuilder()
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
      if (entityBuilder_ == null) {
        entity_ = null;
      } else {
        entity_ = null;
        entityBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcEntityResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcEntityResponse build() {
      io.evitadb.externalApi.grpc.generated.GrpcEntityResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcEntityResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcEntityResponse result = new io.evitadb.externalApi.grpc.generated.GrpcEntityResponse(this);
      if (entityBuilder_ == null) {
        result.entity_ = entity_;
      } else {
        result.entity_ = entityBuilder_.build();
      }
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcEntityResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcEntityResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcEntityResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcEntityResponse.getDefaultInstance()) return this;
      if (other.hasEntity()) {
        mergeEntity(other.getEntity());
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
      io.evitadb.externalApi.grpc.generated.GrpcEntityResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcEntityResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcSealedEntity, io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder, io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder> entityBuilder_;
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     * @return Whether the entity field is set.
     */
    public boolean hasEntity() {
      return entityBuilder_ != null || entity_ != null;
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     * @return The entity.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcSealedEntity getEntity() {
      if (entityBuilder_ == null) {
        return entity_ == null ? io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance() : entity_;
      } else {
        return entityBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    public Builder setEntity(io.evitadb.externalApi.grpc.generated.GrpcSealedEntity value) {
      if (entityBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        entity_ = value;
        onChanged();
      } else {
        entityBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    public Builder setEntity(
        io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder builderForValue) {
      if (entityBuilder_ == null) {
        entity_ = builderForValue.build();
        onChanged();
      } else {
        entityBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    public Builder mergeEntity(io.evitadb.externalApi.grpc.generated.GrpcSealedEntity value) {
      if (entityBuilder_ == null) {
        if (entity_ != null) {
          entity_ =
            io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.newBuilder(entity_).mergeFrom(value).buildPartial();
        } else {
          entity_ = value;
        }
        onChanged();
      } else {
        entityBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    public Builder clearEntity() {
      if (entityBuilder_ == null) {
        entity_ = null;
        onChanged();
      } else {
        entity_ = null;
        entityBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder getEntityBuilder() {

      onChanged();
      return getEntityFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder getEntityOrBuilder() {
      if (entityBuilder_ != null) {
        return entityBuilder_.getMessageOrBuilder();
      } else {
        return entity_ == null ?
            io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.getDefaultInstance() : entity_;
      }
    }
    /**
     * <pre>
     * The found entity.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcSealedEntity entity = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcSealedEntity, io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder, io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder>
        getEntityFieldBuilder() {
      if (entityBuilder_ == null) {
        entityBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcSealedEntity, io.evitadb.externalApi.grpc.generated.GrpcSealedEntity.Builder, io.evitadb.externalApi.grpc.generated.GrpcSealedEntityOrBuilder>(
                getEntity(),
                getParentForChildren(),
                isClean());
        entity_ = null;
      }
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcEntityResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcEntityResponse)
  private static final io.evitadb.externalApi.grpc.generated.GrpcEntityResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcEntityResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcEntityResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcEntityResponse>
      PARSER = new com.google.protobuf.AbstractParser<GrpcEntityResponse>() {
    @java.lang.Override
    public GrpcEntityResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcEntityResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcEntityResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcEntityResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcEntityResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

