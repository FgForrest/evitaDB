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
// source: GrpcAttributeMutations.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Remove attribute mutation will drop existing attribute - ie.generates new version of the attribute with tombstone
 * on it.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation}
 */
public final class GrpcRemoveAttributeMutation extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)
    GrpcRemoveAttributeMutationOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcRemoveAttributeMutation.newBuilder() to construct.
  private GrpcRemoveAttributeMutation(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcRemoveAttributeMutation() {
    attributeName_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcRemoveAttributeMutation();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcRemoveAttributeMutation(
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
            java.lang.String s = input.readStringRequireUtf8();

            attributeName_ = s;
            break;
          }
          case 18: {
            io.evitadb.externalApi.grpc.generated.GrpcLocale.Builder subBuilder = null;
            if (attributeLocale_ != null) {
              subBuilder = attributeLocale_.toBuilder();
            }
            attributeLocale_ = input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcLocale.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(attributeLocale_);
              attributeLocale_ = subBuilder.buildPartial();
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
    return io.evitadb.externalApi.grpc.generated.GrpcAttributeMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveAttributeMutation_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcAttributeMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveAttributeMutation_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.class, io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.Builder.class);
  }

  public static final int ATTRIBUTENAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object attributeName_;
  /**
   * <pre>
   * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
   * single entity instance.
   * </pre>
   *
   * <code>string attributeName = 1;</code>
   * @return The attributeName.
   */
  @java.lang.Override
  public java.lang.String getAttributeName() {
    java.lang.Object ref = attributeName_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      attributeName_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
   * single entity instance.
   * </pre>
   *
   * <code>string attributeName = 1;</code>
   * @return The bytes for attributeName.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getAttributeNameBytes() {
    java.lang.Object ref = attributeName_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      attributeName_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int ATTRIBUTELOCALE_FIELD_NUMBER = 2;
  private io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale_;
  /**
   * <pre>
   * Contains locale in case the attribute is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
   * @return Whether the attributeLocale field is set.
   */
  @java.lang.Override
  public boolean hasAttributeLocale() {
    return attributeLocale_ != null;
  }
  /**
   * <pre>
   * Contains locale in case the attribute is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
   * @return The attributeLocale.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLocale getAttributeLocale() {
    return attributeLocale_ == null ? io.evitadb.externalApi.grpc.generated.GrpcLocale.getDefaultInstance() : attributeLocale_;
  }
  /**
   * <pre>
   * Contains locale in case the attribute is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder getAttributeLocaleOrBuilder() {
    return getAttributeLocale();
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
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(attributeName_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, attributeName_);
    }
    if (attributeLocale_ != null) {
      output.writeMessage(2, getAttributeLocale());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(attributeName_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, attributeName_);
    }
    if (attributeLocale_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, getAttributeLocale());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation other = (io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation) obj;

    if (!getAttributeName()
        .equals(other.getAttributeName())) return false;
    if (hasAttributeLocale() != other.hasAttributeLocale()) return false;
    if (hasAttributeLocale()) {
      if (!getAttributeLocale()
          .equals(other.getAttributeLocale())) return false;
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
    hash = (37 * hash) + ATTRIBUTENAME_FIELD_NUMBER;
    hash = (53 * hash) + getAttributeName().hashCode();
    if (hasAttributeLocale()) {
      hash = (37 * hash) + ATTRIBUTELOCALE_FIELD_NUMBER;
      hash = (53 * hash) + getAttributeLocale().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation prototype) {
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
   * Remove attribute mutation will drop existing attribute - ie.generates new version of the attribute with tombstone
   * on it.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)
      io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutationOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcAttributeMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveAttributeMutation_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcAttributeMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveAttributeMutation_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.class, io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.newBuilder()
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
      attributeName_ = "";

      if (attributeLocaleBuilder_ == null) {
        attributeLocale_ = null;
      } else {
        attributeLocale_ = null;
        attributeLocaleBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcAttributeMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveAttributeMutation_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation build() {
      io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation result = new io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation(this);
      result.attributeName_ = attributeName_;
      if (attributeLocaleBuilder_ == null) {
        result.attributeLocale_ = attributeLocale_;
      } else {
        result.attributeLocale_ = attributeLocaleBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation.getDefaultInstance()) return this;
      if (!other.getAttributeName().isEmpty()) {
        attributeName_ = other.attributeName_;
        onChanged();
      }
      if (other.hasAttributeLocale()) {
        mergeAttributeLocale(other.getAttributeLocale());
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
      io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object attributeName_ = "";
    /**
     * <pre>
     * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
     * single entity instance.
     * </pre>
     *
     * <code>string attributeName = 1;</code>
     * @return The attributeName.
     */
    public java.lang.String getAttributeName() {
      java.lang.Object ref = attributeName_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        attributeName_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
     * single entity instance.
     * </pre>
     *
     * <code>string attributeName = 1;</code>
     * @return The bytes for attributeName.
     */
    public com.google.protobuf.ByteString
        getAttributeNameBytes() {
      java.lang.Object ref = attributeName_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        attributeName_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
     * single entity instance.
     * </pre>
     *
     * <code>string attributeName = 1;</code>
     * @param value The attributeName to set.
     * @return This builder for chaining.
     */
    public Builder setAttributeName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      attributeName_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
     * single entity instance.
     * </pre>
     *
     * <code>string attributeName = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearAttributeName() {

      attributeName_ = getDefaultInstance().getAttributeName();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
     * single entity instance.
     * </pre>
     *
     * <code>string attributeName = 1;</code>
     * @param value The bytes for attributeName to set.
     * @return This builder for chaining.
     */
    public Builder setAttributeNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      attributeName_ = value;
      onChanged();
      return this;
    }

    private io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcLocale, io.evitadb.externalApi.grpc.generated.GrpcLocale.Builder, io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder> attributeLocaleBuilder_;
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     * @return Whether the attributeLocale field is set.
     */
    public boolean hasAttributeLocale() {
      return attributeLocaleBuilder_ != null || attributeLocale_ != null;
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     * @return The attributeLocale.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocale getAttributeLocale() {
      if (attributeLocaleBuilder_ == null) {
        return attributeLocale_ == null ? io.evitadb.externalApi.grpc.generated.GrpcLocale.getDefaultInstance() : attributeLocale_;
      } else {
        return attributeLocaleBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    public Builder setAttributeLocale(io.evitadb.externalApi.grpc.generated.GrpcLocale value) {
      if (attributeLocaleBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        attributeLocale_ = value;
        onChanged();
      } else {
        attributeLocaleBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    public Builder setAttributeLocale(
        io.evitadb.externalApi.grpc.generated.GrpcLocale.Builder builderForValue) {
      if (attributeLocaleBuilder_ == null) {
        attributeLocale_ = builderForValue.build();
        onChanged();
      } else {
        attributeLocaleBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    public Builder mergeAttributeLocale(io.evitadb.externalApi.grpc.generated.GrpcLocale value) {
      if (attributeLocaleBuilder_ == null) {
        if (attributeLocale_ != null) {
          attributeLocale_ =
            io.evitadb.externalApi.grpc.generated.GrpcLocale.newBuilder(attributeLocale_).mergeFrom(value).buildPartial();
        } else {
          attributeLocale_ = value;
        }
        onChanged();
      } else {
        attributeLocaleBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    public Builder clearAttributeLocale() {
      if (attributeLocaleBuilder_ == null) {
        attributeLocale_ = null;
        onChanged();
      } else {
        attributeLocale_ = null;
        attributeLocaleBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocale.Builder getAttributeLocaleBuilder() {

      onChanged();
      return getAttributeLocaleFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder getAttributeLocaleOrBuilder() {
      if (attributeLocaleBuilder_ != null) {
        return attributeLocaleBuilder_.getMessageOrBuilder();
      } else {
        return attributeLocale_ == null ?
            io.evitadb.externalApi.grpc.generated.GrpcLocale.getDefaultInstance() : attributeLocale_;
      }
    }
    /**
     * <pre>
     * Contains locale in case the attribute is locale specific.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcLocale, io.evitadb.externalApi.grpc.generated.GrpcLocale.Builder, io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder>
        getAttributeLocaleFieldBuilder() {
      if (attributeLocaleBuilder_ == null) {
        attributeLocaleBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcLocale, io.evitadb.externalApi.grpc.generated.GrpcLocale.Builder, io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder>(
                getAttributeLocale(),
                getParentForChildren(),
                isClean());
        attributeLocale_ = null;
      }
      return attributeLocaleBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)
  private static final io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcRemoveAttributeMutation>
      PARSER = new com.google.protobuf.AbstractParser<GrpcRemoveAttributeMutation>() {
    @java.lang.Override
    public GrpcRemoveAttributeMutation parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcRemoveAttributeMutation(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcRemoveAttributeMutation> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcRemoveAttributeMutation> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

