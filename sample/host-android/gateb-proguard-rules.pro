# Gate B-e (prototype builds only, sample/host-android/build.gradle.kts): R8 rules for the release
# build used to measure app size. Zipline and Treehouse ship their own consumer rules; these keep
# the sample's own Zipline service interfaces, which R8 cannot see being used through reflection-free
# generated adapters from the guest's side.
-keep interface dev.keliver.sample.shared.** { *; }
-keep interface dev.keliver.sample.gateb.** { *; }
-dontwarn org.slf4j.**
