/*
 * Copyright (C) 2026 The media3-decoder-ape Authors
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
package io.github.eugenedibtsev.media3.ape;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Contract tests for {@link ApeSupport}: APE first, everything else delegated. */
@RunWith(AndroidJUnit4.class)
public final class ApeSupportTest {

  @Test
  public void noArgFactory_apeFirstThenDefaults() {
    Extractor[] extractors = ApeSupport.extractorsFactory().createExtractors();
    Extractor[] defaults = new DefaultExtractorsFactory().createExtractors();

    assertThat(extractors[0]).isInstanceOf(ApeExtractor.class);
    assertThat(extractors).hasLength(defaults.length + 1);
    for (int i = 0; i < defaults.length; i++) {
      assertThat(extractors[i + 1].getClass()).isEqualTo(defaults[i].getClass());
    }
  }

  @Test
  public void wrappingFactory_preservesBaseExtractorsAndOrder() {
    Extractor first = new FakeExtractor();
    Extractor second = new FakeExtractor();
    RecordingFactory base = new RecordingFactory(first, second);

    Extractor[] extractors = ApeSupport.extractorsFactory(base).createExtractors();

    assertThat(extractors).hasLength(3);
    assertThat(extractors[0]).isInstanceOf(ApeExtractor.class);
    assertThat(extractors[1]).isSameInstanceAs(first);
    assertThat(extractors[2]).isSameInstanceAs(second);
  }

  @Test
  public void uriOverload_delegatesToBaseUriOverload() {
    Extractor baseExtractor = new FakeExtractor();
    RecordingFactory base = new RecordingFactory(baseExtractor);
    Uri uri = Uri.parse("https://example.invalid/track.ape");
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("Content-Type", Arrays.asList("audio/x-ape"));

    Extractor[] extractors = ApeSupport.extractorsFactory(base).createExtractors(uri, headers);

    assertThat(base.calls).containsExactly("createExtractors(uri)");
    assertThat(base.lastUri).isEqualTo(uri);
    assertThat(base.lastHeaders).isSameInstanceAs(headers);
    assertThat(extractors[0]).isInstanceOf(ApeExtractor.class);
    assertThat(extractors[1]).isSameInstanceAs(baseExtractor);
  }

  @Test
  @SuppressWarnings("deprecation") // Delegation of the deprecated default is exactly what's tested.
  public void defaultMethods_delegateToBase() {
    RecordingFactory base = new RecordingFactory();
    ExtractorsFactory factory = ApeSupport.extractorsFactory(base);

    SubtitleParser.Factory subtitleParserFactory = SubtitleParser.Factory.UNSUPPORTED;
    assertThat(factory.setSubtitleParserFactory(subtitleParserFactory)).isSameInstanceAs(factory);
    assertThat(factory.experimentalSetTextTrackTranscodingEnabled(true)).isSameInstanceAs(factory);
    assertThat(factory.experimentalSetCodecsToParseWithinGopSampleDependencies(7))
        .isSameInstanceAs(factory);

    assertThat(base.calls)
        .containsExactly(
            "setSubtitleParserFactory",
            "experimentalSetTextTrackTranscodingEnabled(true)",
            "experimentalSetCodecsToParseWithinGopSampleDependencies(7)")
        .inOrder();
    assertThat(base.lastSubtitleParserFactory).isSameInstanceAs(subtitleParserFactory);
  }

  @Test
  public void setterReturningReplacementBase_replacementIsUsed() {
    Extractor replacementExtractor = new FakeExtractor();
    RecordingFactory replacement = new RecordingFactory(replacementExtractor);
    // A base that, like an immutable factory, answers the setter with a different instance.
    ExtractorsFactory base =
        new RecordingFactory() {
          @Override
          public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory f) {
            return replacement;
          }
        };

    ExtractorsFactory factory = ApeSupport.extractorsFactory(base);
    factory.setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED);
    Extractor[] extractors = factory.createExtractors();

    assertThat(extractors[0]).isInstanceOf(ApeExtractor.class);
    assertThat(extractors[1]).isSameInstanceAs(replacementExtractor);
  }

  /** Minimal extractor stub; identity is all the tests compare. */
  private static final class FakeExtractor implements Extractor {
    @Override
    public boolean sniff(androidx.media3.extractor.ExtractorInput input) {
      return false;
    }

    @Override
    public void init(androidx.media3.extractor.ExtractorOutput output) {}

    @Override
    public int read(
        androidx.media3.extractor.ExtractorInput input,
        androidx.media3.extractor.PositionHolder seekPosition) {
      return RESULT_END_OF_INPUT;
    }

    @Override
    public void seek(long position, long timeUs) {}

    @Override
    public void release() {}
  }

  /** Base factory that records every call made to it. */
  private static class RecordingFactory implements ExtractorsFactory {
    final List<String> calls = new ArrayList<>();
    final Extractor[] extractors;
    Uri lastUri;
    Map<String, List<String>> lastHeaders;
    SubtitleParser.Factory lastSubtitleParserFactory;

    RecordingFactory(Extractor... extractors) {
      this.extractors = extractors;
    }

    @Override
    public Extractor[] createExtractors() {
      calls.add("createExtractors()");
      return extractors.clone();
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
      calls.add("createExtractors(uri)");
      lastUri = uri;
      lastHeaders = responseHeaders;
      return extractors.clone();
    }

    @Override
    public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory f) {
      calls.add("setSubtitleParserFactory");
      lastSubtitleParserFactory = f;
      return this;
    }

    @SuppressWarnings("deprecation")
    @Override
    public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
      calls.add("experimentalSetTextTrackTranscodingEnabled(" + enabled + ")");
      return this;
    }

    @Override
    public ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int codecs) {
      calls.add("experimentalSetCodecsToParseWithinGopSampleDependencies(" + codecs + ")");
      return this;
    }
  }
}
