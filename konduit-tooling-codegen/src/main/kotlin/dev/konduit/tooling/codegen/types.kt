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
package dev.konduit.tooling.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT

internal object Protocol {
  val ChildrenTag = ClassName("dev.konduit.protocol", "ChildrenTag")
  val Event = ClassName("dev.konduit.protocol", "Event")
  val EventTag = ClassName("dev.konduit.protocol", "EventTag")
  val EventSink = ClassName("dev.konduit.protocol", "EventSink")
  val Id = ClassName("dev.konduit.protocol", "Id")
  val ModifierElement = ClassName("dev.konduit.protocol", "ModifierElement")
  val ModifierTag = ClassName("dev.konduit.protocol", "ModifierTag")
  val PropertyChange = ClassName("dev.konduit.protocol", "PropertyChange")
  val PropertyTag = ClassName("dev.konduit.protocol", "PropertyTag")
  val WidgetTag = ClassName("dev.konduit.protocol", "WidgetTag")
}

internal object ProtocolGuest {
  val GuestProtocolAdapter = ClassName("dev.konduit.protocol.guest", "GuestProtocolAdapter")
  val ProtocolMismatchHandler =
    ClassName("dev.konduit.protocol.guest", "ProtocolMismatchHandler")
  val ProtocolWidget = ClassName("dev.konduit.protocol.guest", "ProtocolWidget")
  val ProtocolWidgetChildren = ClassName("dev.konduit.protocol.guest", "ProtocolWidgetChildren")
  val ProtocolWidgetChildrenVisitor = ProtocolWidget.nestedClass("ChildrenVisitor")
  val ProtocolWidgetSystemFactory = ClassName("dev.konduit.protocol.guest", "ProtocolWidgetSystemFactory")
}

internal object ProtocolHost {
  val GeneratedHostProtocol = ClassName("dev.konduit.protocol.host", "GeneratedHostProtocol")
  val GeneratedUiEvent = ClassName("dev.konduit.protocol.host", "GeneratedUiEvent")
  val HostProtocol = ClassName("dev.konduit.protocol.host", "HostProtocol")
  val HostProtocolFactory = HostProtocol.nestedClass("Factory")
  val IdVisitor = ClassName("dev.konduit.protocol.host", "IdVisitor")
  val ProtocolMismatchHandler =
    ClassName("dev.konduit.protocol.host", "ProtocolMismatchHandler")
  val ProtocolNode = ClassName("dev.konduit.protocol.host", "ProtocolNode")
  val ProtocolChildren = ClassName("dev.konduit.protocol.host", "ProtocolChildren")
  val UiEventSink = ClassName("dev.konduit.protocol.host", "UiEventSink")
  val UiPropertyChange = ClassName("dev.konduit.protocol.host", "UiPropertyChange")
  val WidgetHostProtocol = ClassName("dev.konduit.protocol.host", "WidgetHostProtocol")
}

internal object Redwood {
  val Modifier = ClassName("dev.konduit", "Modifier")
  val ModifierElement = Modifier.nestedClass("Element")
  val ModifierScopedElement = Modifier.nestedClass("ScopedElement")
  val ModifierUnscopedElement = Modifier.nestedClass("UnscopedElement")
  val LayoutScopeMarker = ClassName("dev.konduit", "LayoutScopeMarker")
  val RedwoodCodegenApi = ClassName("dev.konduit", "RedwoodCodegenApi")
  val OnBackPressedDispatcher = ClassName("dev.konduit.ui", "OnBackPressedDispatcher")
  val UiConfiguration = ClassName("dev.konduit.ui", "UiConfiguration")
}

internal object RedwoodTesting {
  val NoOpOnBackPressedDispatcher = ClassName("dev.konduit.testing", "NoOpOnBackPressedDispatcher")
  val TestRedwoodComposition = ClassName("dev.konduit.testing", "TestRedwoodComposition")
  val TestSavedState = ClassName("dev.konduit.testing", "TestSavedState")
  val WidgetValue = ClassName("dev.konduit.testing", "WidgetValue")
}

internal object RedwoodWidget {
  val Widget = ClassName("dev.konduit.widget", "Widget")
  val WidgetChildren = Widget.nestedClass("Children")
  val WidgetChildrenOfW = WidgetChildren.parameterizedBy(typeVariableW)
  val WidgetSystem = ClassName("dev.konduit.widget", "WidgetSystem")
  val WidgetFactoryOwner = ClassName("dev.konduit.widget", "WidgetFactoryOwner")
  val MutableListChildren = ClassName("dev.konduit.widget", "MutableListChildren")
}

internal object RedwoodCompose {
  val RedwoodComposeNode = MemberName("dev.konduit.compose", "RedwoodComposeNode")
  val WidgetNode = ClassName("dev.konduit.compose", "WidgetNode")
}

internal object ComposeRuntime {
  val Composable = ClassName("androidx.compose.runtime", "Composable")
  val Stable = ClassName("androidx.compose.runtime", "Stable")
}

internal object AndroidxCollection {
  val IntObjectMap = ClassName("androidx.collection", "IntObjectMap")
  val MutableIntObjectMap = ClassName("androidx.collection", "MutableIntObjectMap")
}

internal fun composableLambda(
  receiver: TypeName?,
): TypeName {
  return LambdaTypeName.get(
    returnType = UNIT,
    receiver = receiver,
  ).copy(
    annotations = listOf(
      AnnotationSpec.builder(ComposeRuntime.Composable).build(),
    ),
  )
}

internal object Stdlib {
  val AssertionError = ClassName("kotlin", "AssertionError")
  val ExperimentalObjCName = ClassName("kotlin.experimental", "ExperimentalObjCName")
  val List = ClassName("kotlin.collections", "List")
  val ObjCName = ClassName("kotlin.native", "ObjCName")
  val Pair = ClassName("kotlin", "Pair")
  val listOf = MemberName("kotlin.collections", "listOf")
}

internal val typeVariableW = TypeVariableName("W", listOf(ANY))

internal object KotlinxSerialization {
  val Contextual = ClassName("kotlinx.serialization", "Contextual")
  val ContextualSerializer = ClassName("kotlinx.serialization", "ContextualSerializer")
  val DeserializationStrategy = ClassName("kotlinx.serialization", "DeserializationStrategy")
  val ExperimentalSerializationApi = ClassName("kotlinx.serialization", "ExperimentalSerializationApi")
  val KSerializer = ClassName("kotlinx.serialization", "KSerializer")
  val Serializable = ClassName("kotlinx.serialization", "Serializable")
  val SerializationStrategy = ClassName("kotlinx.serialization", "SerializationStrategy")
  val serializer = MemberName("kotlinx.serialization", "serializer")

  // The `.serializer()` extension on builtin/stdlib companions
  // (UInt, Duration, …) lives in kotlinx.serialization.builtins —
  // it's a library function, NOT the compiler-plugin-generated
  // companion accessor that user @Serializable types get. Emitting
  // `Type.serializer()` for a stdlib type without this import fails
  // with `Unresolved reference 'serializer'` (KNOWN_BUGS U13).
  val builtinsSerializer = MemberName("kotlinx.serialization.builtins", "serializer")

  val SerialDescriptor = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
  val buildClassSerialDescriptor = MemberName("kotlinx.serialization.descriptors", "buildClassSerialDescriptor")
  val element = MemberName("kotlinx.serialization.descriptors", "element")

  val Decoder = ClassName("kotlinx.serialization.encoding", "Decoder")
  val Encoder = ClassName("kotlinx.serialization.encoding", "Encoder")

  val Json = ClassName("kotlinx.serialization.json", "Json")
  val JsonDefault = Json.nestedClass("Default")
}

internal object KotlinxCoroutines {
  val coroutineScope = MemberName("kotlinx.coroutines", "coroutineScope")
}

/**
 * True for stdlib `kotlin.*` types (e.g. kotlin.time.Duration,
 * kotlin.UInt) used as schema custom-types. Their `.serializer()`
 * comes from `kotlinx.serialization.builtins` (a library extension),
 * not the compiler-plugin-generated companion accessor that user
 * `@Serializable` types get — so the codegen must reference the
 * builtins extension (with its import) for them. User types live in
 * non-kotlin packages and keep the companion `.serializer()` (the
 * U10 fallback). See KNOWN_BUGS U13.
 */
internal fun ClassName.isStdlibSerializableType(): Boolean =
  packageName == "kotlin" || packageName.startsWith("kotlin.")
