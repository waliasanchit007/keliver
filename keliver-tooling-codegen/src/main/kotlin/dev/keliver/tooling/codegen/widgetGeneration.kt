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
package dev.keliver.tooling.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import dev.keliver.tooling.schema.ProtocolWidget
import dev.keliver.tooling.schema.ProtocolWidget.ProtocolTrait
import dev.keliver.tooling.schema.Schema
import dev.keliver.tooling.schema.SchemaSet
import dev.keliver.tooling.schema.Widget
import dev.keliver.tooling.schema.Widget.Children
import dev.keliver.tooling.schema.Widget.Event
import dev.keliver.tooling.schema.Widget.Property

/*
@OptIn(RedwoodCodegenApi::class)
@ObjCName("ExampleWidgetSystem", exact = true)
class ExampleWidgetSystem<W : Any>(
  override val Example: ExampleWidgetFactory<W>,
  override val RedwoodLayout: RedwoodLayoutWidgetFactory<W>,
) : WidgetSystem<W>,
    ExampleWidgetFactoryOwner<W>,
    RedwoodLayoutWidgetFactoryOwner<W> {
  override fun apply(value: W, element: Modifier.UnscopedElement) {
    Example.apply(value, element)
    RedwoodLayout.apply(value, element)
  }
}

@RedwoodCodegenApi
interface ExampleWidgetFactoryOwner<W : Any> {
  val Example: ExampleWidgetFactory<W>

  companion object {
    fun <W : Any> apply(
      factory: ExampleWidgetFactory<W>,
      value: W,
      element: Modifier.UnscopedElement,
    ): W? {
      when (element) {
        is BackgroundColor -> factory.BackgroundColor(value, element)
      }
    }
  }
}
 */
internal fun generateWidgetSystem(schemaSet: SchemaSet): FileSpec {
  if (schemaSet.schema.acceptsAbsentGroups) return generateGroupsWidgetSystem(schemaSet)
  val schema = schemaSet.schema
  val widgetSystemType = schema.getWidgetSystemType()
  return buildFileSpec(widgetSystemType) {
    addAnnotation(suppressDeprecations)
    addType(
      TypeSpec.classBuilder(widgetSystemType)
        .addTypeVariable(typeVariableW)
        .addSuperinterface(RedwoodWidget.WidgetSystem.parameterizedBy(typeVariableW))
        .optIn(Stdlib.ExperimentalObjCName, Redwood.RedwoodCodegenApi)
        .addAnnotation(
          AnnotationSpec.builder(Stdlib.ObjCName)
            .addMember("%S", widgetSystemType.simpleName)
            .addMember("exact = true")
            .build(),
        )
        .apply {
          for (dependency in schemaSet.all) {
            addSuperinterface(dependency.getWidgetFactoryOwnerType().parameterizedBy(typeVariableW))
          }

          val constructorBuilder = FunSpec.constructorBuilder()

          for (dependency in schemaSet.all) {
            val dependencyType = dependency.getWidgetFactoryType().parameterizedBy(typeVariableW)
            addProperty(
              PropertySpec.builder(dependency.type.flatName, dependencyType, OVERRIDE)
                .initializer(dependency.type.flatName)
                .build(),
            )
            constructorBuilder.addParameter(dependency.type.flatName, dependencyType)
          }

          primaryConstructor(constructorBuilder.build())
        }
        .addFunction(
          FunSpec.builder("apply")
            .addModifiers(OVERRIDE)
            .addParameter("value", typeVariableW)
            .addParameter("element", Redwood.ModifierUnscopedElement)
            .apply {
              for (dependency in schemaSet.all) {
                addStatement(
                  "%T.apply(%N, value, element)",
                  dependency.getWidgetFactoryOwnerType(),
                  dependency.type.flatName,
                )
              }
            }
            .build(),
        )
        .addType(allChildrenCompanion(schemaSet))
        .build(),
    )
    addType(widgetFactoryOwner(schema))
  }
}

/** The widget system's companion: `allChildren`, over every widget with children in the set. */
private fun allChildrenCompanion(schemaSet: SchemaSet): TypeSpec = TypeSpec.companionObjectBuilder()
  .addFunction(
    FunSpec.builder("allChildren")
      .addTypeVariable(typeVariableW)
      .addParameter("widget", RedwoodWidget.Widget.parameterizedBy(typeVariableW))
      .returns(LIST.parameterizedBy(RedwoodWidget.Widget.parameterizedBy(typeVariableW)))
      .beginControlFlow("return when (widget)")
      .apply {
        for (aSchema in schemaSet.all) {
          for (widget in aSchema.widgets) {
            val childrens = widget.traits.filterIsInstance<Children>()
            if (childrens.size == 0) continue

            val widgetOfStar = aSchema.widgetType(widget).parameterizedBy(STAR)
            beginControlFlow("is %T ->", widgetOfStar)

            val widgetOfW = aSchema.widgetType(widget).parameterizedBy(typeVariableW)
            addStatement("widget as %T", widgetOfW)

            if (childrens.size == 1) {
              addStatement("widget.%N.widgets", childrens[0].name)
            } else {
              addStatement("val size = %L", childrens.joinToCode(" + ") { CodeBlock.of("widget.%N.widgets.size", it.name) })
              beginControlFlow("buildList(size)", widgetOfStar)
              for (children in childrens) {
                addStatement("addAll(widget.%N.widgets)", children.name)
              }
              endControlFlow()
            }
            endControlFlow()
          }
        }
      }
      .addStatement("else -> emptyList()")
      .endControlFlow()
      .build(),
  )
  .build()

/** The schema's factory owner interface, with the static `apply` other schemas' systems call. */
private fun widgetFactoryOwner(schema: Schema): TypeSpec = TypeSpec.interfaceBuilder(schema.getWidgetFactoryOwnerType())
  .addKdoc("@suppress For generated code usage only.")
  .addTypeVariable(typeVariableW)
  .addAnnotation(Redwood.RedwoodCodegenApi)
  .addSuperinterface(RedwoodWidget.WidgetFactoryOwner.parameterizedBy(typeVariableW))
  .addProperty(schema.type.flatName, schema.getWidgetFactoryType().parameterizedBy(typeVariableW))
  .addType(
    TypeSpec.companionObjectBuilder()
      // Needed because https://github.com/Kotlin/binary-compatibility-validator/issues/91
      .addAnnotation(Redwood.RedwoodCodegenApi)
      .addFunction(
        FunSpec.builder("apply")
          .addTypeVariable(typeVariableW)
          .addParameter("factory", schema.getWidgetFactoryType().parameterizedBy(typeVariableW))
          .addParameter("value", typeVariableW)
          .addParameter("element", Redwood.ModifierUnscopedElement)
          .apply {
            if (schema.unscopedModifiers.isEmpty()) {
              // Even with no modifiers, we need to maintain this function signature for
              // other modules to call into it.
              addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                  .addMember("%S", "UNUSED_PARAMETER")
                  .build(),
              )
            } else {
              beginControlFlow("when (element)")
              for (globalModifier in schema.unscopedModifiers) {
                addStatement(
                  "is %T -> factory.%N(value, element)",
                  schema.modifierType(globalModifier),
                  globalModifier.type.flatName,
                )
              }
              endControlFlow()
            }
          }
          .build(),
      )
      .build(),
  )
  .build()

/*
A root whose groups a host may leave out (MS4-03): a schema with no widgets or modifiers of its own.

@OptIn(RedwoodCodegenApi::class)
@ObjCName("ExampleWidgetSystem", exact = true)
class ExampleWidgetSystem<W : Any>(
  override val Example: ExampleWidgetFactory<W>,
  RedwoodLayout: RedwoodLayoutWidgetFactory<W>?,
  val absentWidget: () -> Widget<W>,
) : WidgetSystem<W>,
    ExampleWidgetFactoryOwner<W>,
    RedwoodLayoutWidgetFactoryOwner<W> {
  val RedwoodLayoutOrNull: RedwoodLayoutWidgetFactory<W>? = RedwoodLayout
  override val RedwoodLayout: RedwoodLayoutWidgetFactory<W>
    get() = RedwoodLayoutOrNull ?: throw IllegalStateException("…")

  override fun apply(value: W, element: Modifier.UnscopedElement) {
    ExampleWidgetFactoryOwner.apply(Example, value, element)
    RedwoodLayoutOrNull?.let { RedwoodLayoutWidgetFactoryOwner.apply(it, value, element) }
  }
}
*/
private fun generateGroupsWidgetSystem(schemaSet: SchemaSet): FileSpec {
  val schema = schemaSet.schema
  val widgetSystemType = schema.getWidgetSystemType()
  val groups = schemaSet.all.filter { it != schema }
  return buildFileSpec(widgetSystemType) {
    addAnnotation(suppressDeprecations)
    addType(
      TypeSpec.classBuilder(widgetSystemType)
        .addTypeVariable(typeVariableW)
        .addSuperinterface(RedwoodWidget.WidgetSystem.parameterizedBy(typeVariableW))
        .apply {
          // Every group's owner too: a guest's generated composables find their group's factory
          // through it (a guest always has every group).
          for (dependency in schemaSet.all) {
            addSuperinterface(dependency.getWidgetFactoryOwnerType().parameterizedBy(typeVariableW))
          }
        }
        .optIn(Stdlib.ExperimentalObjCName, Redwood.RedwoodCodegenApi)
        .addAnnotation(
          AnnotationSpec.builder(Stdlib.ObjCName)
            .addMember("%S", widgetSystemType.simpleName)
            .addMember("exact = true")
            .build(),
        )
        .addKdoc(
          "The root's widget system: one factory per group. A group is null when this host is built " +
            "without it: its widgets are then reported as unknown, once per tag, and each stands in as " +
            "[absentWidget], so the tree keeps its shape.\n",
        )
        .apply {
          val constructorBuilder = FunSpec.constructorBuilder()
          val ownType = schema.getWidgetFactoryType().parameterizedBy(typeVariableW)
          addProperty(
            PropertySpec.builder(schema.type.flatName, ownType, OVERRIDE)
              .initializer(schema.type.flatName)
              .build(),
          )
          constructorBuilder.addParameter(schema.type.flatName, ownType)
          for (group in groups) {
            val name = group.type.flatName
            val groupType = group.getWidgetFactoryType().parameterizedBy(typeVariableW)
            addProperty(
              PropertySpec.builder("${name}OrNull", groupType.copy(nullable = true))
                .initializer(name)
                .addKdoc("Group $name's factory, or null when this host is built without it.\n")
                .build(),
            )
            addProperty(
              PropertySpec.builder(name, groupType, OVERRIDE)
                .getter(
                  FunSpec.getterBuilder()
                    .addStatement(
                      "return %NOrNull ?: throw %T(%S)",
                      name,
                      ClassName("kotlin", "IllegalStateException"),
                      "group $name is absent from this host",
                    )
                    .build(),
                )
                .build(),
            )
            constructorBuilder.addParameter(name, groupType.copy(nullable = true))
          }
          val absentType = LambdaTypeName.get(returnType = RedwoodWidget.Widget.parameterizedBy(typeVariableW))
          addProperty(
            PropertySpec.builder("absentWidget", absentType)
              .initializer("absentWidget")
              .addKdoc("A widget that stands in for one of an absent group's: it draws nothing and takes any property.\n")
              .build(),
          )
          constructorBuilder.addParameter("absentWidget", absentType)
          primaryConstructor(constructorBuilder.build())
        }
        .addFunction(
          FunSpec.builder("apply")
            .addModifiers(OVERRIDE)
            .addParameter("value", typeVariableW)
            .addParameter("element", Redwood.ModifierUnscopedElement)
            .addStatement(
              "%T.apply(%N, value, element)",
              schema.getWidgetFactoryOwnerType(),
              schema.type.flatName,
            )
            .apply {
              for (group in groups) {
                addStatement(
                  "%NOrNull?.let { %T.apply(it, value, element) }",
                  group.type.flatName,
                  group.getWidgetFactoryOwnerType(),
                )
              }
            }
            .build(),
        )
        .addType(allChildrenCompanion(schemaSet))
        .build(),
    )
    addType(widgetFactoryOwner(schema))
  }
}

/*
@ObjCName("ExampleWidgetFactory", exact = true)
interface ExampleWidgetFactory<W : Any> : Widget.Factory<W> {
  /** {tag=1} */
  fun Text(): Text<W>
  /** {tag=2} */
  fun Button(): Button<W>
}
*/
internal fun generateWidgetFactory(schema: Schema): FileSpec {
  val widgetFactoryType = schema.getWidgetFactoryType()
  return buildFileSpec(widgetFactoryType) {
    addAnnotation(suppressDeprecations)
    addType(
      TypeSpec.interfaceBuilder(widgetFactoryType)
        .addTypeVariable(typeVariableW)
        .optIn(Stdlib.ExperimentalObjCName)
        .addAnnotation(
          AnnotationSpec.builder(Stdlib.ObjCName)
            .addMember("%S", widgetFactoryType.simpleName)
            .addMember("exact = true")
            .build(),
        )
        .maybeAddKDoc(schema.documentation)
        .apply {
          for (widget in schema.widgets) {
            addFunction(
              FunSpec.builder(widget.type.flatName)
                .addModifiers(ABSTRACT)
                .returns(schema.widgetType(widget).parameterizedBy(typeVariableW))
                .maybeAddDeprecation(widget.deprecation)
                .maybeAddKDoc(widget.documentation)
                .apply {
                  if (widget is ProtocolWidget) {
                    addKdoc("{tag=${widget.tag}}")
                  }
                }
                .build(),
            )
          }

          for (modifier in schema.unscopedModifiers) {
            addFunction(
              FunSpec.builder(modifier.type.flatName)
                .addModifiers(ABSTRACT)
                .addParameter("value", typeVariableW)
                .addParameter("modifier", schema.modifierType(modifier))
                .build(),
            )
          }
        }
        .build(),
    )
  }
}

/*
/** {tag=2} */
@ObjCName("Button", exact = true)
interface Button<W: Any> : Widget<W> {
  /** {tag=1} */
  fun text(text: String?)
  /** {tag=2} */
  fun enabled(enabled: Boolean)
  /** {tag=3} */
  fun onClick(onClick: (() -> Unit)?)
}
*/
internal fun generateWidget(schema: Schema, widget: Widget): FileSpec {
  val flatName = widget.type.flatName
  return buildFileSpec(schema.widgetPackage(), flatName) {
    addAnnotation(suppressDeprecations)
    addType(
      TypeSpec.interfaceBuilder(flatName)
        .addTypeVariable(typeVariableW)
        .addSuperinterface(RedwoodWidget.Widget.parameterizedBy(typeVariableW))
        .optIn(Stdlib.ExperimentalObjCName)
        .addAnnotation(
          AnnotationSpec.builder(Stdlib.ObjCName)
            .addMember("%S", flatName)
            .addMember("exact = true")
            .build(),
        )
        .maybeAddDeprecation(widget.deprecation)
        .maybeAddKDoc(widget.documentation)
        .apply {
          if (widget is ProtocolWidget) {
            addKdoc("{tag=${widget.tag}}")
          }

          for (trait in widget.traits) {
            when (trait) {
              is Property -> {
                addFunction(
                  FunSpec.builder(trait.name)
                    .addModifiers(ABSTRACT)
                    .addParameter(trait.name, trait.type.asTypeName())
                    .maybeAddDeprecation(trait.deprecation)
                    .maybeAddKDoc(trait.documentation)
                    .apply {
                      if (trait is ProtocolTrait) {
                        addKdoc("{tag=${trait.tag}}")
                      }
                    }
                    .build(),
                )
              }

              is Event -> {
                addFunction(
                  FunSpec.builder(trait.name)
                    .addModifiers(ABSTRACT)
                    .addParameter(trait.name, trait.lambdaType)
                    .maybeAddDeprecation(trait.deprecation)
                    .maybeAddKDoc(trait.documentation)
                    .apply {
                      if (trait is ProtocolTrait) {
                        addKdoc("{tag=${trait.tag}}")
                      }
                    }
                    .build(),
                )
              }

              is Children -> {
                addProperty(
                  PropertySpec.builder(trait.name, RedwoodWidget.WidgetChildrenOfW)
                    .addModifiers(ABSTRACT)
                    .maybeAddDeprecation(trait.deprecation)
                    .maybeAddKDoc(trait.documentation)
                    .apply {
                      if (trait is ProtocolTrait) {
                        addKdoc("{tag=${trait.tag}}")
                      }
                    }
                    .build(),
                )
              }

              is ProtocolTrait -> throw AssertionError()
            }
          }
        }
        .build(),
    )
  }
}
