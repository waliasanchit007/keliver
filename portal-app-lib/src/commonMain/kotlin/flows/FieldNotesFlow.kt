/*
 * #13: the Field Notes flow declaration — ONE source serving both sides: the
 * app composes real navigation from it (preview now; device FlowScope later),
 * and the relay PSI-parses this same file to derive the nav graph (/flow).
 * openNote carries a dynamic note id, so the edge keys on the ACTION NAME.
 */
package dev.keliver.portalpublished.flows

import dev.keliver.portal.flow.flow

val FieldNotesFlow = flow("FieldNotes", start = "feed") {
  route("openNote", to = "detail")
}
