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
 * Response to GrpcCatalogVersionAt request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse}
 */
public final class GrpcCatalogVersionAtResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse)
    GrpcCatalogVersionAtResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcCatalogVersionAtResponse.newBuilder() to construct.
  private GrpcCatalogVersionAtResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcCatalogVersionAtResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcCatalogVersionAtResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcCatalogVersionAtResponse(
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
          case 8: {

            version_ = input.readInt64();
            break;
          }
          case 18: {
            io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.Builder subBuilder = null;
            if (introducedAt_ != null) {
              subBuilder = introducedAt_.toBuilder();
            }
            introducedAt_ = input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(introducedAt_);
              introducedAt_ = subBuilder.buildPartial();
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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogVersionAtResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogVersionAtResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.class, io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.Builder.class);
  }

  public static final int VERSION_FIELD_NUMBER = 1;
  private long version_;
  /**
   * <pre>
   * The version of the catalog at the specified moment in time.
   * </pre>
   *
   * <code>int64 version = 1;</code>
   * @return The version.
   */
  @java.lang.Override
  public long getVersion() {
    return version_;
  }

  public static final int INTRODUCEDAT_FIELD_NUMBER = 2;
  private io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt_;
  /**
   * <pre>
   * Exact moment when this version was stored (introduced).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
   * @return Whether the introducedAt field is set.
   */
  @java.lang.Override
  public boolean hasIntroducedAt() {
    return introducedAt_ != null;
  }
  /**
   * <pre>
   * Exact moment when this version was stored (introduced).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
   * @return The introducedAt.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getIntroducedAt() {
    return introducedAt_ == null ? io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.getDefaultInstance() : introducedAt_;
  }
  /**
   * <pre>
   * Exact moment when this version was stored (introduced).
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getIntroducedAtOrBuilder() {
    return getIntroducedAt();
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
    if (version_ != 0L) {
      output.writeInt64(1, version_);
    }
    if (introducedAt_ != null) {
      output.writeMessage(2, getIntroducedAt());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (version_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(1, version_);
    }
    if (introducedAt_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, getIntroducedAt());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse other = (io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse) obj;

    if (getVersion()
        != other.getVersion()) return false;
    if (hasIntroducedAt() != other.hasIntroducedAt()) return false;
    if (hasIntroducedAt()) {
      if (!getIntroducedAt()
          .equals(other.getIntroducedAt())) return false;
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
    hash = (37 * hash) + VERSION_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getVersion());
    if (hasIntroducedAt()) {
      hash = (37 * hash) + INTRODUCEDAT_FIELD_NUMBER;
      hash = (53 * hash) + getIntroducedAt().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse prototype) {
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
   * Response to GrpcCatalogVersionAt request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse)
      io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogVersionAtResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogVersionAtResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.class, io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.newBuilder()
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
      version_ = 0L;

      if (introducedAtBuilder_ == null) {
        introducedAt_ = null;
      } else {
        introducedAt_ = null;
        introducedAtBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogVersionAtResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse build() {
      io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse result = new io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse(this);
      result.version_ = version_;
      if (introducedAtBuilder_ == null) {
        result.introducedAt_ = introducedAt_;
      } else {
        result.introducedAt_ = introducedAtBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse.getDefaultInstance()) return this;
      if (other.getVersion() != 0L) {
        setVersion(other.getVersion());
      }
      if (other.hasIntroducedAt()) {
        mergeIntroducedAt(other.getIntroducedAt());
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
      io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private long version_ ;
    /**
     * <pre>
     * The version of the catalog at the specified moment in time.
     * </pre>
     *
     * <code>int64 version = 1;</code>
     * @return The version.
     */
    @java.lang.Override
    public long getVersion() {
      return version_;
    }
    /**
     * <pre>
     * The version of the catalog at the specified moment in time.
     * </pre>
     *
     * <code>int64 version = 1;</code>
     * @param value The version to set.
     * @return This builder for chaining.
     */
    public Builder setVersion(long value) {
      
      version_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The version of the catalog at the specified moment in time.
     * </pre>
     *
     * <code>int64 version = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearVersion() {
      
      version_ = 0L;
      onChanged();
      return this;
    }

    private io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime, io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.Builder, io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder> introducedAtBuilder_;
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     * @return Whether the introducedAt field is set.
     */
    public boolean hasIntroducedAt() {
      return introducedAtBuilder_ != null || introducedAt_ != null;
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     * @return The introducedAt.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getIntroducedAt() {
      if (introducedAtBuilder_ == null) {
        return introducedAt_ == null ? io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.getDefaultInstance() : introducedAt_;
      } else {
        return introducedAtBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    public Builder setIntroducedAt(io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime value) {
      if (introducedAtBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        introducedAt_ = value;
        onChanged();
      } else {
        introducedAtBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    public Builder setIntroducedAt(
        io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.Builder builderForValue) {
      if (introducedAtBuilder_ == null) {
        introducedAt_ = builderForValue.build();
        onChanged();
      } else {
        introducedAtBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    public Builder mergeIntroducedAt(io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime value) {
      if (introducedAtBuilder_ == null) {
        if (introducedAt_ != null) {
          introducedAt_ =
            io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.newBuilder(introducedAt_).mergeFrom(value).buildPartial();
        } else {
          introducedAt_ = value;
        }
        onChanged();
      } else {
        introducedAtBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    public Builder clearIntroducedAt() {
      if (introducedAtBuilder_ == null) {
        introducedAt_ = null;
        onChanged();
      } else {
        introducedAt_ = null;
        introducedAtBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.Builder getIntroducedAtBuilder() {
      
      onChanged();
      return getIntroducedAtFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getIntroducedAtOrBuilder() {
      if (introducedAtBuilder_ != null) {
        return introducedAtBuilder_.getMessageOrBuilder();
      } else {
        return introducedAt_ == null ?
            io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.getDefaultInstance() : introducedAt_;
      }
    }
    /**
     * <pre>
     * Exact moment when this version was stored (introduced).
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime introducedAt = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime, io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.Builder, io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder> 
        getIntroducedAtFieldBuilder() {
      if (introducedAtBuilder_ == null) {
        introducedAtBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime, io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime.Builder, io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder>(
                getIntroducedAt(),
                getParentForChildren(),
                isClean());
        introducedAt_ = null;
      }
      return introducedAtBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse)
  private static final io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcCatalogVersionAtResponse>
      PARSER = new com.google.protobuf.AbstractParser<GrpcCatalogVersionAtResponse>() {
    @java.lang.Override
    public GrpcCatalogVersionAtResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcCatalogVersionAtResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcCatalogVersionAtResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcCatalogVersionAtResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcCatalogVersionAtResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

