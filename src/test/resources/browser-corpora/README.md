# Browser AVIF corpus provenance

The browser corpus tests download selected fixtures at test time. No Firefox or Chromium fixture is
redistributed in the source tree.

## Firefox

- Repository: <https://github.com/mozilla-firefox/firefox>
- Revision: `ac91bfcce1bf3240e2dce40f47c372e76bc4f26c`
- Test logic: `image/test/gtest/TestDecoders.cpp` and `image/test/gtest/Common.cpp`
- Fixtures: `image/test/gtest` and `image/test/crashtests`
- Upstream source license: Mozilla Public License 2.0

The port retains the observable decoder assertions relevant to this library: decoded dimensions,
colors, bit depth, chroma layout, alpha, animation, corrupt-input rejection, and checked handling of
crash-test inputs. Firefox-specific incremental surface notifications and platform graphics behavior
are outside this library's API and are not reproduced.

## Chromium

- Repository: <https://chromium.googlesource.com/chromium/src>
- GitHub mirror used for per-file downloads: <https://github.com/chromium/chromium>
- Revision: `ddb449c8c2536723346df7ea26ca13d99857c302`
- Test logic: `third_party/blink/renderer/platform/image-decoders/avif/avif_image_decoder_test.cc`
- Fixtures: `third_party/blink/web_tests/images/resources/avif`
- Chromium source license: BSD 3-Clause

The port retains assertions for YUV conversion, bit depth, chroma layout, straight alpha, sequence
timing and repetition, clean apertures, orientation properties, grids, scalable images, gain maps,
ICC payloads, and corrupt inputs. Blink streaming callbacks, UMA histograms, Skia color-management
policy, and Chromium task-pool behavior are not part of this library's API and are not reproduced.

Two compatibility policies intentionally differ from Blink. Alpha items without `ispe` inherit the
master image dimensions for compatibility with legacy libavif files, and valid nonzero-origin clean
apertures are exposed rather than ignored. Sequences without an edit list report
`AvifImageInfo.REPETITION_COUNT_UNKNOWN`; browser presentation code may map that state to an
infinite loop.

Individual Chromium fixtures can carry additional provenance or licensing recorded in the upstream
fixture `README.md`. In particular, the selected tiger fixtures derive from AOMedia test data under
CC BY-SA 3.0. All files remain in Gradle's generated `build` directory.
