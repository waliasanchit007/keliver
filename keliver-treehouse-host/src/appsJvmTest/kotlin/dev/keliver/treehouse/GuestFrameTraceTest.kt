/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.treehouse

import app.cash.zipline.Call
import app.cash.zipline.CallResult
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.example.redwood.testapp.testing.ButtonValue
import com.example.redwood.testapp.testing.TextInputValue
import com.example.redwood.testapp.treehouse.TestAppPresenter
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * M4's composition/encoding split, end to end: the test-app guest (Kotlin/JS in QuickJS, through
 * keliver-treehouse-guest's StandardAppLifecycle) calls the host's KeliverTrace.composed() once
 * its composition is done, inside the host's call (a screen's start, or a frame), and before the
 * change list that composition made reaches the host. A host that binds no KeliverTrace, or one
 * without composed(), gets no such call and the screen works as before.
 *
 * The test-app guest's main calls Zipline.get() with its own serializers module, as real guests
 * do; the library must find the host's KeliverTrace without naming that module.
 */
class GuestFrameTraceTest {
  /** The host's timing service as a host that profiles compositions binds it, by shape. */
  interface KeliverTrace : ZiplineService {
    fun mark(name: String)
    fun composed()
  }

  /** As the debug hosts of tools 0.3.10 to 0.3.18 bind it: marks only. */
  interface MarksOnlyKeliverTrace : ZiplineService {
    fun mark(name: String)
  }

  @Test fun eachCompositionsEndIsReportedBeforeItsChangeList() = runTest {
    val log = mutableListOf<String>()
    var composedCalls = 0
    val trace = object : KeliverTrace {
      override fun mark(name: String) {
        log += "mark $name"
      }

      override fun composed() {
        composedCalls++
      }
    }
    val tester = TreehouseTester(this)
    tester.eventListenerFactory = CallLog(log)
    tester.alsoBind { it.bind<KeliverTrace>("KeliverTrace", trace) }
    val treehouseApp = tester.loadApp()
    val content = tester.content(treehouseApp)
    val view = tester.view()

    content.bind(view)
    content.awaitContent(1)
    val textInput = view.views.single() as TextInputValue
    assertThat(textInput.text).isEqualTo("what would you like to see?")
    textInput.onChange!!.invoke("TreehouseTesterTestHappyPathStep2")
    tester.sendFrame()
    content.awaitContent(2)
    assertThat((view.views.single() as ButtonValue).text).isEqualTo("This is TreehouseTesterTestHappyPathStep2")

    assertThat(log).containsExactly(
      // The app's lifecycle starts: the guest asks once which services the host bound.
      "serviceNames",
      // The screen's start: its first composition, then its first change list.
      "start {",
      "KeliverTrace.composed",
      "sendChanges",
      "start }",
      // A frame: its composition, then its change list. Once per frame, not more.
      "sendFrame {",
      "KeliverTrace.composed",
      "sendChanges",
      "sendFrame }",
    )
    assertThat(composedCalls).isEqualTo(2)

    treehouseApp.stop()
    treehouseApp.close()
  }

  @Test fun aHostWithoutComposedIsNotCalledAgainAndTheScreenWorks() = runTest {
    val marks = mutableListOf<String>()
    val trace = object : MarksOnlyKeliverTrace {
      override fun mark(name: String) {
        marks += name
      }
    }
    val log = mutableListOf<String>()
    val tester = TreehouseTester(this)
    tester.eventListenerFactory = CallLog(log)
    tester.alsoBind { it.bind<MarksOnlyKeliverTrace>("KeliverTrace", trace) }

    tester.openAndChangeTheScreen()

    // The guest's one composed() reached the host, failed in Zipline (the host's service has no
    // such function), and was not repeated: the frame's call log has nothing between its start
    // and its change list.
    assertThat(log).containsExactly(
      "serviceNames",
      "start {",
      "KeliverTrace.unknownFunction",
      "sendChanges",
      "start }",
      "sendFrame {",
      "sendChanges",
      "sendFrame }",
    )
    assertThat(marks).isEmpty()
  }

  @Test fun aHostWithoutKeliverTraceGetsNoCallAndTheScreenWorks() = runTest {
    val log = mutableListOf<String>()
    val tester = TreehouseTester(this)
    tester.eventListenerFactory = CallLog(log)

    tester.openAndChangeTheScreen()

    // The guest asked once which services the host bound, found no KeliverTrace, and never
    // called it: not in the start, not in the frame.
    assertThat(log).containsExactly(
      "serviceNames",
      "start {",
      "sendChanges",
      "start }",
      "sendFrame {",
      "sendChanges",
      "sendFrame }",
    )
  }

  private suspend fun TreehouseTester.openAndChangeTheScreen() {
    val treehouseApp = loadApp()
    val content = content(treehouseApp)
    val view = view()
    content.bind(view)
    content.awaitContent(1)
    (view.views.single() as TextInputValue).onChange!!.invoke("TreehouseTesterTestHappyPathStep2")
    sendFrame()
    content.awaitContent(2)
    assertThat((view.views.single() as ButtonValue).text).isEqualTo("This is TreehouseTesterTestHappyPathStep2")
    treehouseApp.stop()
    treehouseApp.close()
  }

  /** The tester's services, and what [bind] binds. */
  private fun TreehouseTester.alsoBind(bind: (Zipline) -> Unit) {
    val original = spec
    spec = object : TreehouseApp.Spec<TestAppPresenter>() {
      override val name get() = original.name
      override val manifestUrl get() = original.manifestUrl
      override val loadCodeFromNetworkOnly get() = original.loadCodeFromNetworkOnly

      override suspend fun bindServices(treehouseApp: TreehouseApp<TestAppPresenter>, zipline: Zipline) {
        original.bindServices(treehouseApp, zipline)
        bind(zipline)
      }

      override fun create(zipline: Zipline) = original.create(zipline)
    }
  }

  /**
   * Logs the host's calls into a screen's start and a frame ("start {" … "start }"), and the
   * guest's calls to the host (Zipline reports those as well): its change lists, its calls into
   * KeliverTrace, and its one lookup of the host's service names.
   */
  private class CallLog(private val log: MutableList<String>) : EventListener.Factory {
    override fun create(app: TreehouseApp<*>, manifestUrl: String?) = object : EventListener() {
      override fun callStart(call: Call): Any? {
        val signature = call.function.signature
        when {
          signature.startsWith("fun start(dev.keliver.treehouse.ZiplineTreehouseUi.Host)") -> log += "start {"
          signature.startsWith("fun sendFrame(") -> log += "sendFrame {"
          signature.startsWith("fun sendChanges(") -> log += "sendChanges"
          // Every call the guest makes into the host's KeliverTrace, including one the host's
          // service has no function for: Zipline reports that as "fun unknownFunction()".
          call.serviceName == "KeliverTrace" -> {
            log += "KeliverTrace." + signature.substringAfter("fun ").substringBefore("(")
            return null
          }
          // The guest's question to the host: which services did you bind?
          call.serviceName == "zipline/host" && signature.startsWith("val serviceNames:") -> {
            log += "serviceNames"
            return null
          }
          else -> return null
        }
        return signature
      }

      override fun callEnd(call: Call, result: CallResult, startValue: Any?) {
        val signature = startValue as? String ?: return
        when {
          signature.startsWith("fun start(") -> log += "start }"
          signature.startsWith("fun sendFrame(") -> log += "sendFrame }"
        }
      }
    }

    override fun close() {
    }
  }
}
