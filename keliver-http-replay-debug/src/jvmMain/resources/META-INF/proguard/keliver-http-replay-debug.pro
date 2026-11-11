# keliver-http-replay-debug is for DEBUG builds only. Should a minified build ever contain it,
# keep its class names readable, so the release-build self-test's search of the dex for
# dev/keliver/http/replay/debug finds it (docs/DEBUG_HTTP_REPLAY.md). -keepnames keeps no
# class that is not otherwise used: a build that does not reference it still drops it.
-keepnames class dev.keliver.http.replay.debug.**
