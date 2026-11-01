# A guest finds a host's BridgeCallFailed in Zipline's stack-trace text by its qualified name
# (BridgeCallFailed.fromZiplineMessage): a minified Android host must not rename it.
-keepnames class dev.keliver.bridge.BridgeCallFailed
