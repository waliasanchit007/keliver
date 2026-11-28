/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.tooling.schema

import dev.keliver.tooling.schema.Widget.Children
import dev.keliver.tooling.schema.Widget.Event
import dev.keliver.tooling.schema.Widget.Property
import dev.keliver.tooling.schema.Widget.Trait
import kotlin.reflect.KClass

/** A [Schema] and its dependencies. */
public interface SchemaSet {
  public val schema: Schema
  public val dependencies: Map<FqType, Schema>

  public val all: List<Schema> get() = buildList {
    add(schema)
    addAll(dependencies.values)
  }
}

public interface Schema {
  public val type: FqType
  public val documentation: String?
  public val scopes: List<FqType>
  public val widgets: List<Widget>
  public val modifiers: List<Modifier>
  public val dependencies: List<FqType>
}

public data class EmbeddedSchema(
  val path: String,
  val json: String,
)

public interface Deprecation {
  public val level: Level
  public val message: String

  public enum class Level {
    WARNING,
    ERROR,
  }
}

public interface Widget {
  /** Either a 'data class' or 'object'. */
  public val type: FqType

  public val documentation: String?

  /** Non-null if this widget is deprecated. */
  public val deprecation: Deprecation?

  /** Non-empty list for a 'data class' [type] or empty list for 'object' [type]. */
  public val traits: List<Trait>

  public val internalComposable: Boolean

  /** `@Replayable`: this widget may be drawn from a stored frame (NF5, [FramePropertyRule]). */
  public val replayable: Boolean get() = false

  /** `@Scrolls`: this widget scrolls, so a frame is recorded only at its origin (NF5). */
  public val scrolls: Boolean get() = false

  public sealed interface Trait {
    public val name: String
    public val documentation: String?
    public val defaultExpression: String?

    /** Non-null if this trait is deprecated. */
    public val deprecation: Deprecation?
  }

  public interface Property : Trait {
    public val type: FqType

    /** This property's frame-rule annotation, or null (NF5, [FramePropertyRule]). */
    public val frameRule: FramePropertyRule? get() = null

    /**
     * Whether this property's value may carry a String (text or a URL): its type is or contains a
     * String, as far as the parser could see. Set by the parser for the properties of a
     * `@Replayable` widget and for every property with a [frameRule]; false otherwise.
     */
    public val containsString: Boolean get() = false
  }

  public interface Event : Trait {
    public val parameters: List<Parameter>
    public val isNullable: Boolean

    public interface Parameter {
      public val type: FqType
      public val name: String?
    }
  }

  public interface Children : Trait {
    public val scope: FqType?
  }
}

public interface Modifier {
  public val scopes: List<FqType>

  /** Either a 'data class' or 'object'. */
  public val type: FqType

  public val documentation: String?

  /** Non-null if this layout modifier is deprecated. */
  public val deprecation: Deprecation?

  /** Non-empty list for a 'data class' [type] or empty list for 'object' [type]. */
  public val properties: List<Property>

  /** `@Replayable`: this modifier may be drawn from a stored frame (NF5, [FramePropertyRule]). */
  public val replayable: Boolean get() = false

  /** `@Scrolls`: this modifier makes its node scroll, so a frame is recorded only at its origin (NF5). */
  public val scrolls: Boolean get() = false

  public interface Property {
    public val name: String
    public val documentation: String?
    public val type: FqType
    public val isSerializable: Boolean
    public val defaultExpression: String?

    /** Non-null if this property is deprecated. */
    public val deprecation: Deprecation?

    /** This property's frame-rule annotation, or null (NF5, [FramePropertyRule]). */
    public val frameRule: FramePropertyRule? get() = null

    /** As [Widget.Property.containsString], for the properties of a `@Replayable` modifier. */
    public val containsString: Boolean get() = false
  }
}

/**
 * NF5's property annotations (`dev.keliver.schema`, docs/SHOW_BEFORE_COMPUTE.md 2.4): what a stored
 * first frame does with a property's value. A property carries at most one.
 */
public enum class FramePropertyRule {
  /** `@NeverCached`: never stored, at either content level. */
  NeverCached,

  /** `@BlocksFrameWhenTrue` (a `Boolean`): nothing is recorded while it is true. */
  BlocksFrameWhenTrue,

  /** `@StyleToken` (a String-bearing type): its strings are style or asset codes, kept by `Shell`. */
  StyleToken,

  /** `@NotData` (a type without a String): layout or style, left off the review list. */
  NotData,
}

/** A [ProtocolSchema] and its dependencies. */
public interface ProtocolSchemaSet : SchemaSet {
  override val schema: ProtocolSchema
  override val dependencies: Map<FqType, ProtocolSchema>

  override val all: List<ProtocolSchema> get() = buildList {
    add(schema)
    addAll(dependencies.values)
  }

  public companion object {
    public fun load(type: KClass<*>): ProtocolSchemaSet {
      return load(type.toFqType(), type.java.classLoader)
    }

    public fun load(type: FqType, classLoader: ClassLoader): ProtocolSchemaSet {
      return loadProtocolSchemaSet(type, classLoader)
    }
  }
}

public interface ProtocolSchema : Schema {
  override val widgets: List<ProtocolWidget>
  override val modifiers: List<ProtocolModifier>
  override val dependencies: List<FqType> get() = taggedDependencies.values.toList()
  public val taggedDependencies: Map<Int, FqType>

  /**
   * Convert this schema to JSON which can be embedded inside the schema artifact.
   * This JSON will be read when the schema is used as a dependency.
   */
  public fun toEmbeddedSchema(): EmbeddedSchema

  /** Return a copy of this schema with all widget and modifier tags adjusted by [offset]. */
  public fun withTagOffset(offset: Int): ProtocolSchema
}

public interface ProtocolWidget : Widget {
  public val tag: Int
  override val traits: List<ProtocolTrait>

  public sealed interface ProtocolTrait : Trait {
    public val tag: Int
  }
  public interface ProtocolProperty :
    Property,
    ProtocolTrait
  public interface ProtocolEvent :
    Event,
    ProtocolTrait
  public interface ProtocolChildren :
    Children,
    ProtocolTrait
}

public interface ProtocolModifier : Modifier {
  public val tag: Int
}
