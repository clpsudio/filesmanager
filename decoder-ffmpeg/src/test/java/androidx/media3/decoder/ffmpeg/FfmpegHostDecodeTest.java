/*
 * Copyright 2026 CLP Studio. Licensed under the Apache License, Version 2.0.
 */
package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Exercises the real decoding path - the unmodified AndroidX Media Java classes, the JNI glue and
 * FFmpeg at the pinned commit with the app's 6 decoders - on the build machine.
 *
 * <p>The Android (ARM) libffmpegJNI.so can't run here, so this loads a host build of the same
 * sources made by scripts/ffmpeg/build-host-jni-for-tests.sh. Without it the tests are skipped,
 * unless -Pclp.requireHostFfmpeg=true (CI) makes a missing library a failure.
 *
 * <p>Fixtures (src/test/resources/fixtures): the first 4 packets of a 0.5 s, 1 kHz test tone that
 * CLP Studio encoded with FFmpeg 5.1's own encoders, one file per codec (ALAC with its magic
 * cookie).
 *
 * <p>AAC, E-AC-3, DTS and TrueHD/MLP are deliberately not built (codec patents). For those formats
 * the library must say it can't decode them, so FfmpegAudioRenderer reports
 * FORMAT_UNSUPPORTED_SUBTYPE and the player uses the phone's own (MediaCodec) decoder.
 */
public final class FfmpegHostDecodeTest {

  private static final String[] DECODERS = {"flac", "alac", "pcm_mulaw", "pcm_alaw", "mp3", "ac3"};

  /** Removed from the build: left to the phone's own decoders. */
  private static final String[] REMOVED_DECODERS = {"aac", "eac3", "dca", "mlp", "truehd"};

  @BeforeClass
  public static void loadHostLibrary() {
    // Media3's Log needs android.util.Log.getStackTraceString, which is a stub here (returns null);
    // FfmpegLibrary.supportsFormat logs a warning for formats it has no decoder for.
    androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
    String dir = System.getProperty("clp.ffmpeg.hostLibDir", "");
    boolean present = new File(dir, "libffmpegJNI.so").isFile();
    if (Boolean.getBoolean("clp.ffmpeg.requireHost") && !present) {
      fail("Host libffmpegJNI.so missing in '" + dir + "'; run scripts/ffmpeg/build-host-jni-for-tests.sh");
    }
    Assume.assumeTrue("host libffmpegJNI.so not built; run scripts/ffmpeg/build-host-jni-for-tests.sh", present);
  }

  @Test
  public void libraryLoadsAndReportsFfmpeg60() {
    assertTrue(FfmpegLibrary.isAvailable());
    assertEquals("Lavc60.3.100", FfmpegLibrary.getVersion());
  }

  @Test
  public void exactlyTheSixAudioDecodersAreBuiltIn() throws Exception {
    Method has = FfmpegLibrary.class.getDeclaredMethod("ffmpegHasDecoder", String.class);
    has.setAccessible(true);
    assertTrue(FfmpegLibrary.isAvailable());
    for (String name : DECODERS) {
      assertTrue("decoder " + name, (Boolean) has.invoke(null, name));
    }
    for (String name : REMOVED_DECODERS) {
      assertFalse("decoder " + name + " must not be built", (Boolean) has.invoke(null, name));
    }
    // --disable-everything: nothing else, in particular no video decoders.
    for (String name : new String[] {"vorbis", "opus", "amrnb", "h264", "hevc", "mp2", "wmav2", "aac_latm", "aac_fixed"}) {
      assertFalse("decoder " + name + " must not be built", (Boolean) has.invoke(null, name));
    }
  }

  @Test
  public void supportsTheBundledAudioFormats() {
    String[] mimes = {
      MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC,
      MimeTypes.AUDIO_ALAW, MimeTypes.AUDIO_MLAW
    };
    for (String mime : mimes) {
      assertTrue(mime, FfmpegLibrary.supportsFormat(mime));
    }
  }

  /**
   * The FFmpeg renderer must not claim the removed formats: FfmpegAudioRenderer.supportsFormatInternal
   * returns FORMAT_UNSUPPORTED_SUBTYPE when FfmpegLibrary.supportsFormat is false, so ExoPlayer picks
   * the phone's MediaCodec renderer for them (or none, if the phone has no decoder).
   */
  @Test
  public void doesNotClaimTheFormatsLeftToThePhone() {
    String[] mimes = {
      MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS,
      MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD
    };
    for (String mime : mimes) {
      assertFalse(mime + " must be left to the phone's decoders", FfmpegLibrary.supportsFormat(mime));
    }
  }

  @Test
  public void creatingADecoderForARemovedFormatFails() {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(44100).setChannelCount(2).build();
    try {
      new FfmpegAudioDecoder(format, 2, 2, 32 * 1024, false).release();
      fail("an AAC decoder must not be available");
    } catch (FfmpegDecoderException | RuntimeException expected) {
      // FfmpegAudioDecoder throws when FFmpeg has no decoder for the codec.
    }
  }

  @Test public void decodesAc3() throws Exception { decode("ac3", MimeTypes.AUDIO_AC3, 48000, 2); }
  @Test public void decodesMp3() throws Exception { decode("mp3", MimeTypes.AUDIO_MPEG, 44100, 2); }
  @Test public void decodesFlac() throws Exception { decode("flac", MimeTypes.AUDIO_FLAC, 44100, 2); }
  @Test public void decodesAlac() throws Exception { decode("alac", MimeTypes.AUDIO_ALAC, 44100, 2); }
  @Test public void decodesALaw() throws Exception { decode("pcm_alaw", MimeTypes.AUDIO_ALAW, 8000, 1); }
  @Test public void decodesMuLaw() throws Exception { decode("pcm_mulaw", MimeTypes.AUDIO_MLAW, 8000, 1); }

  private static void decode(String fixture, String mime, int sampleRate, int channels) throws Exception {
    List<byte[]> packets = readPackets("fixtures/" + fixture + ".packets");
    byte[] extra = readOptional("fixtures/" + fixture + ".extradata");
    Format format =
        new Format.Builder()
            .setSampleMimeType(mime)
            .setSampleRate(sampleRate)
            .setChannelCount(channels)
            .setInitializationData(extra == null ? Collections.emptyList() : Collections.singletonList(extra))
            .build();
    FfmpegAudioDecoder decoder =
        new FfmpegAudioDecoder(format, packets.size() + 1, packets.size() + 1, 32 * 1024, /* outputFloat= */ false);
    try {
      long timeUs = 0;
      for (byte[] packet : packets) {
        DecoderInputBuffer in = decoder.dequeueInputBuffer();
        assertNotNull(in);
        in.ensureSpaceForWrite(packet.length);
        in.data.put(packet);
        in.timeUs = timeUs;
        timeUs += 10_000;
        in.flip();
        decoder.queueInputBuffer(in);
      }
      DecoderInputBuffer eos = decoder.dequeueInputBuffer();
      assertNotNull(eos);
      eos.setFlags(androidx.media3.common.C.BUFFER_FLAG_END_OF_STREAM);
      decoder.queueInputBuffer(eos);
      long pcmBytes = 0;
      boolean ended = false;
      long deadline = System.currentTimeMillis() + 10_000;
      while (!ended && System.currentTimeMillis() < deadline) {
        SimpleDecoderOutputBuffer out = decoder.dequeueOutputBuffer();
        if (out == null) {
          Thread.sleep(2);
          continue;
        }
        ended = out.isEndOfStream();
        if (!ended && out.data != null) {
          pcmBytes += out.data.limit();
        }
        out.release();
      }
      assertTrue(fixture + ": reached end of stream", ended);
      assertTrue(fixture + ": decoded PCM", pcmBytes > 0);
      assertEquals(fixture + ": channels", channels, decoder.getChannelCount());
      assertEquals(fixture + ": sample rate", sampleRate, decoder.getSampleRate());
    } finally {
      decoder.release();
    }
  }

  private static List<byte[]> readPackets(String resource) throws IOException {
    ByteBuffer all = ByteBuffer.wrap(read(resource));
    List<byte[]> packets = new ArrayList<>();
    while (all.remaining() >= 4) {
      byte[] p = new byte[all.getInt()];
      all.get(p);
      packets.add(p);
    }
    return packets;
  }

  private static byte[] readOptional(String resource) throws IOException {
    return FfmpegHostDecodeTest.class.getClassLoader().getResource(resource) == null ? null : read(resource);
  }

  private static byte[] read(String resource) throws IOException {
    try (InputStream in = FfmpegHostDecodeTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(resource, in);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      return out.toByteArray();
    }
  }
}
