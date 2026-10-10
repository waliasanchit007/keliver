// ocr.swift <png>: prints the text macOS Vision recognizes in a screenshot,
// one line per observation, top to bottom. Evidence of what a screen SHOWS,
// for a platform with no view-hierarchy dump (the iOS simulator).
import Foundation
import Vision
import AppKit

guard CommandLine.arguments.count == 2,
      let image = NSImage(contentsOfFile: CommandLine.arguments[1]),
      let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
  FileHandle.standardError.write("usage: ocr.swift <png>\n".data(using: .utf8)!); exit(2)
}
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
try VNImageRequestHandler(cgImage: cg, options: [:]).perform([request])
let lines = (request.results ?? [])
  .sorted { $0.boundingBox.minY > $1.boundingBox.minY }
  .compactMap { $0.topCandidates(1).first?.string }
print(lines.joined(separator: "\n"))
