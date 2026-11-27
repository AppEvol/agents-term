#define GHOSTTY_STATIC
#include <ghostty/vt.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define JNI_CLASS_PREFIX Java_com_example_terminal_GhosttyVtNative_
#define JNI_CONCAT_INNER(a, b) a##b
#define JNI_CONCAT(a, b) JNI_CONCAT_INNER(a, b)
#define JNI_FN(name) JNI_CONCAT(JNI_CLASS_PREFIX, name)

typedef struct {
  uint8_t* data;
  size_t len;
  size_t cap;
} ByteBuf;

static bool bytebuf_reserve(ByteBuf* buf, size_t extra) {
  size_t needed = buf->len + extra;
  if (needed <= buf->cap) return true;
  size_t new_cap = buf->cap == 0 ? 4096 : buf->cap;
  while (new_cap < needed) {
    if (new_cap > (SIZE_MAX / 2)) {
      new_cap = needed;
      break;
    }
    new_cap *= 2;
  }
  uint8_t* next = (uint8_t*)realloc(buf->data, new_cap);
  if (next == NULL) return false;
  buf->data = next;
  buf->cap = new_cap;
  return true;
}

static bool bytebuf_append(ByteBuf* buf, const uint8_t* src, size_t len) {
  if (len == 0) return true;
  if (!bytebuf_reserve(buf, len)) return false;
  memcpy(buf->data + buf->len, src, len);
  buf->len += len;
  return true;
}

static bool bytebuf_u8(ByteBuf* buf, uint8_t v) {
  return bytebuf_append(buf, &v, 1);
}

static bool bytebuf_u16_be(ByteBuf* buf, uint16_t v) {
  uint8_t tmp[2] = {(uint8_t)(v >> 8), (uint8_t)(v & 0xFFu)};
  return bytebuf_append(buf, tmp, 2);
}

static bool bytebuf_u32_be(ByteBuf* buf, uint32_t v) {
  uint8_t tmp[4] = {
      (uint8_t)((v >> 24) & 0xFFu),
      (uint8_t)((v >> 16) & 0xFFu),
      (uint8_t)((v >> 8) & 0xFFu),
      (uint8_t)(v & 0xFFu),
  };
  return bytebuf_append(buf, tmp, 4);
}

static uint32_t rgb_to_argb(GhosttyColorRgb c) {
  return 0xFF000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) | (uint32_t)c.b;
}

static GhosttyColorRgb rgb_from_int(jint rgb) {
  GhosttyColorRgb c;
  c.r = (uint8_t)((rgb >> 16) & 0xFF);
  c.g = (uint8_t)((rgb >> 8) & 0xFF);
  c.b = (uint8_t)(rgb & 0xFF);
  return c;
}

typedef struct {
  GhosttyTerminal terminal;
  GhosttyRenderState render_state;
  GhosttyRenderStateRowIterator row_iter;
  GhosttyRenderStateRowCells row_cells;
  GhosttyKeyEncoder key_encoder;
  GhosttyKeyEvent key_event;
  ByteBuf pty_out;
  ByteBuf frame_buf;
  bool suppress_pty_writes;
  bool bell_rung;
  bool title_changed;
} GhosttyHandle;

static void on_write_pty(GhosttyTerminal terminal,
                         void* userdata,
                         const uint8_t* data,
                         size_t len) {
  (void)terminal;
  GhosttyHandle* h = (GhosttyHandle*)userdata;
  if (h == NULL || h->suppress_pty_writes || data == NULL || len == 0) return;
  (void)bytebuf_append(&h->pty_out, data, len);
}

static void on_bell(GhosttyTerminal terminal, void* userdata) {
  (void)terminal;
  GhosttyHandle* h = (GhosttyHandle*)userdata;
  if (h != NULL) h->bell_rung = true;
}

static void on_title_changed(GhosttyTerminal terminal, void* userdata) {
  (void)terminal;
  GhosttyHandle* h = (GhosttyHandle*)userdata;
  if (h != NULL) h->title_changed = true;
}

static void configure_terminal_callbacks(GhosttyHandle* h, size_t max_scrollback_bytes) {
  if (max_scrollback_bytes > 0) {
    (void)ghostty_terminal_set(
        h->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &max_scrollback_bytes);
  }
  const size_t cont_max = 4096;
  (void)ghostty_terminal_set(
      h->terminal, GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &cont_max);
  (void)ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, h);
  (void)ghostty_terminal_set(
      h->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void*)on_write_pty);
  (void)ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_BELL, (const void*)on_bell);
  (void)ghostty_terminal_set(
      h->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void*)on_title_changed);
}

static GhosttyHandle* from_jlong(jlong ptr) {
  if (ptr == 0) return NULL;
  return (GhosttyHandle*)(uintptr_t)ptr;
}

static jbyteArray bytes_to_jbytearray(JNIEnv* env, const uint8_t* data, size_t len) {
  if (data == NULL || len == 0) return NULL;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
  if (arr == NULL) return NULL;
  (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte*)data);
  return arr;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv* env,
                                             jclass clazz,
                                             jint cols,
                                             jint rows,
                                             jlong max_scrollback_bytes) {
  (void)env;
  (void)clazz;
  if (cols <= 0 || rows <= 0 || cols > 1024 || rows > 512) return 0;

  GhosttyHandle* h = (GhosttyHandle*)calloc(1, sizeof(GhosttyHandle));
  if (h == NULL) return 0;

  if (ghostty_terminal_new(NULL, &h->terminal, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_new(NULL, &h->render_state) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &h->row_iter) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &h->row_cells) != GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(NULL, &h->key_encoder) != GHOSTTY_SUCCESS ||
      ghostty_key_event_new(NULL, &h->key_event) != GHOSTTY_SUCCESS) {
    if (h->key_event) ghostty_key_event_free(h->key_event);
    if (h->key_encoder) ghostty_key_encoder_free(h->key_encoder);
    if (h->row_cells) ghostty_render_state_row_cells_free(h->row_cells);
    if (h->row_iter) ghostty_render_state_row_iterator_free(h->row_iter);
    if (h->render_state) ghostty_render_state_free(h->render_state);
    if (h->terminal) ghostty_terminal_free(h->terminal);
    free(h);
    return 0;
  }

  size_t sb = max_scrollback_bytes > 0 ? (size_t)max_scrollback_bytes : (4u * 1024u * 1024u);
  configure_terminal_callbacks(h, sb);
  return (jlong)(uintptr_t)h;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv* env, jclass clazz, jlong handle_ptr) {
  (void)env;
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL) return;
  if (h->key_event) ghostty_key_event_free(h->key_event);
  if (h->key_encoder) ghostty_key_encoder_free(h->key_encoder);
  if (h->row_cells) ghostty_render_state_row_cells_free(h->row_cells);
  if (h->row_iter) ghostty_render_state_row_iterator_free(h->row_iter);
  if (h->render_state) ghostty_render_state_free(h->render_state);
  if (h->terminal) ghostty_terminal_free(h->terminal);
  free(h->pty_out.data);
  free(h->frame_buf.data);
  free(h);
}

JNIEXPORT void JNICALL JNI_FN(nativeReset)(JNIEnv* env, jclass clazz, jlong handle_ptr) {
  (void)env;
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL) return;
  ghostty_terminal_reset(h->terminal);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeResize)(JNIEnv* env,
                                                jclass clazz,
                                                jlong handle_ptr,
                                                jint cols,
                                                jint rows,
                                                jint cell_width_px,
                                                jint cell_height_px) {
  (void)env;
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL || cols <= 0 || rows <= 0) return JNI_FALSE;
  uint32_t cw = cell_width_px > 0 ? (uint32_t)cell_width_px : 10u;
  uint32_t ch = cell_height_px > 0 ? (uint32_t)cell_height_px : 20u;
  GhosttyResult res =
      ghostty_terminal_resize(h->terminal, (uint16_t)cols, (uint16_t)rows, cw, ch);
  return res == GHOSTTY_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetColors)(JNIEnv* env,
                                               jclass clazz,
                                               jlong handle_ptr,
                                               jint fg_rgb,
                                               jint bg_rgb,
                                               jint cursor_rgb,
                                               jintArray palette_rgb) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL) return;

  GhosttyColorRgb fg = rgb_from_int(fg_rgb);
  GhosttyColorRgb bg = rgb_from_int(bg_rgb);
  GhosttyColorRgb cur = rgb_from_int(cursor_rgb);
  (void)ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
  (void)ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);
  (void)ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cur);

  if (palette_rgb != NULL) {
    jsize n = (*env)->GetArrayLength(env, palette_rgb);
    if (n > 0) {
      GhosttyColorRgb palette[256];
      ghostty_color_palette_default(palette);
      jsize copy_n = n < 256 ? n : 256;
      jint* elems = (*env)->GetIntArrayElements(env, palette_rgb, NULL);
      if (elems != NULL) {
        for (jsize i = 0; i < copy_n; i++) {
          palette[i] = rgb_from_int(elems[i]);
        }
        (*env)->ReleaseIntArrayElements(env, palette_rgb, elems, JNI_ABORT);
        if (copy_n <= 16) {
          ghostty_color_palette_generate(palette, NULL, &bg, &fg, false, palette);
        }
        (void)ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
      }
    }
  }
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeWriteVt)(JNIEnv* env,
                                                   jclass clazz,
                                                   jlong handle_ptr,
                                                   jbyteArray data,
                                                   jint offset,
                                                   jint length,
                                                   jboolean suppress_replies) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL || data == NULL || length <= 0 || offset < 0) {
    return NULL;
  }
  jsize total = (*env)->GetArrayLength(env, data);
  if (offset + length > total) return NULL;

  jbyte* ptr = (*env)->GetByteArrayElements(env, data, NULL);
  if (ptr == NULL) return NULL;

  h->pty_out.len = 0;
  h->suppress_pty_writes = (suppress_replies == JNI_TRUE);
  ghostty_terminal_vt_write(h->terminal, (const uint8_t*)(ptr + offset), (size_t)length);
  h->suppress_pty_writes = false;

  (*env)->ReleaseByteArrayElements(env, data, ptr, JNI_ABORT);

  if (h->pty_out.len == 0) return NULL;
  jbyteArray out = bytes_to_jbytearray(env, h->pty_out.data, h->pty_out.len);
  h->pty_out.len = 0;
  return out;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncodeKey)(JNIEnv* env,
                                                     jclass clazz,
                                                     jlong handle_ptr,
                                                     jint key,
                                                     jint mods,
                                                     jint action,
                                                     jbyteArray utf8_bytes,
                                                     jint unshifted_codepoint) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL || h->key_encoder == NULL || h->key_event == NULL) {
    return NULL;
  }

  (void)ghostty_key_encoder_set_from_terminal(h->key_encoder, h->terminal);
  (void)ghostty_key_event_set_action(h->key_event, (GhosttyKeyAction)action);
  (void)ghostty_key_event_set_key(h->key_event, (GhosttyKey)key);
  (void)ghostty_key_event_set_mods(h->key_event, (GhosttyMods)mods);
  (void)ghostty_key_event_set_consumed_mods(h->key_event, 0);
  (void)ghostty_key_event_set_composing(h->key_event, false);
  (void)ghostty_key_event_set_unshifted_codepoint(
      h->key_event, unshifted_codepoint >= 0 ? (uint32_t)unshifted_codepoint : 0u);

  jbyte* utf8_ptr = NULL;
  jsize utf8_len = 0;
  if (utf8_bytes != NULL) {
    utf8_len = (*env)->GetArrayLength(env, utf8_bytes);
    if (utf8_len > 0) {
      utf8_ptr = (*env)->GetByteArrayElements(env, utf8_bytes, NULL);
    }
  }
  GhosttyString gs = {
      .ptr = (const uint8_t*)utf8_ptr,
      .len = utf8_ptr != NULL ? (size_t)utf8_len : 0u,
  };
  (void)ghostty_key_event_set_utf8(h->key_event, gs);

  uint8_t out_buf[256];
  size_t written = 0;
  GhosttyResult res =
      ghostty_key_encoder_encode(h->key_encoder, h->key_event, out_buf, sizeof(out_buf), &written);

  if (utf8_ptr != NULL) {
    (*env)->ReleaseByteArrayElements(env, utf8_bytes, utf8_ptr, JNI_ABORT);
  }
  if (res != GHOSTTY_SUCCESS || written == 0) return NULL;
  return bytes_to_jbytearray(env, out_buf, written);
}

typedef struct {
  const uint8_t* ptr;
  size_t len;
} PasteReaderCtx;

static bool paste_mime_read(void* userdata, GhosttyString mime, GhosttyWriter writer) {
  (void)mime;
  PasteReaderCtx* ctx = (PasteReaderCtx*)userdata;
  if (ctx == NULL || ctx->ptr == NULL || ctx->len == 0) return true;
  return writer.write(writer.userdata, ctx->ptr, ctx->len);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsPasteSafe)(JNIEnv* env, jclass clazz, jbyteArray data) {
  (void)clazz;
  if (data == NULL) return JNI_TRUE;
  jsize len = (*env)->GetArrayLength(env, data);
  if (len <= 0) return JNI_TRUE;
  jbyte* ptr = (*env)->GetByteArrayElements(env, data, NULL);
  if (ptr == NULL) return JNI_FALSE;
  bool safe = ghostty_paste_is_safe((const char*)ptr, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, data, ptr, JNI_ABORT);
  return safe ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncodePaste)(JNIEnv* env,
                                                       jclass clazz,
                                                       jlong handle_ptr,
                                                       jbyteArray data,
                                                       jboolean allow_unsafe) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL || data == NULL) return NULL;
  jsize len = (*env)->GetArrayLength(env, data);
  if (len <= 0) return NULL;

  jbyte* ptr = (*env)->GetByteArrayElements(env, data, NULL);
  if (ptr == NULL) return NULL;

  PasteReaderCtx ctx = {.ptr = (const uint8_t*)ptr, .len = (size_t)len};
  GhosttyString mime = {.ptr = (const uint8_t*)"text/plain", .len = 10};
  GhosttyPaste paste = {
      .size = sizeof(paste),
      .location = GHOSTTY_CLIPBOARD_LOCATION_STANDARD,
      .source = GHOSTTY_PASTE_SOURCE_CLIPBOARD,
      .mimes = &mime,
      .mimes_len = 1,
      .reader = {.read = paste_mime_read, .userdata = &ctx},
      .allow_unsafe = (allow_unsafe == JNI_TRUE),
  };

  h->pty_out.len = 0;
  h->suppress_pty_writes = false;
  bool pasted = false;
  GhosttyResult res = ghostty_terminal_paste(h->terminal, &paste, &pasted);
  (*env)->ReleaseByteArrayElements(env, data, ptr, JNI_ABORT);

  if (res != GHOSTTY_SUCCESS || !pasted || h->pty_out.len == 0) {
    h->pty_out.len = 0;
    return NULL;
  }
  jbyteArray out = bytes_to_jbytearray(env, h->pty_out.data, h->pty_out.len);
  h->pty_out.len = 0;
  return out;
}

JNIEXPORT void JNICALL JNI_FN(nativeScrollViewport)(JNIEnv* env,
                                                    jclass clazz,
                                                    jlong handle_ptr,
                                                    jint tag,
                                                    jint delta) {
  (void)env;
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL) return;
  GhosttyTerminalScrollViewport sv;
  memset(&sv, 0, sizeof(sv));
  if (tag == 0) {
    sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_TOP;
  } else if (tag == 1) {
    sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM;
  } else {
    sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA;
    sv.value.delta = (intptr_t)delta;
  }
  (void)ghostty_terminal_scroll_viewport(h->terminal, sv);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeGetTitleUtf8)(JNIEnv* env,
                                                        jclass clazz,
                                                        jlong handle_ptr) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL) return NULL;
  GhosttyString title = {0};
  if (ghostty_terminal_get(h->terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title) != GHOSTTY_SUCCESS) {
    return NULL;
  }
  h->title_changed = false;
  if (title.ptr == NULL || title.len == 0) return NULL;
  return bytes_to_jbytearray(env, title.ptr, title.len);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSnapshotEncode)(JNIEnv* env,
                                                          jclass clazz,
                                                          jlong handle_ptr) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL) return NULL;
  uint8_t* snap = NULL;
  size_t snap_len = 0;
  if (ghostty_snapshot_encode_alloc(h->terminal, NULL, &snap, &snap_len) != GHOSTTY_SUCCESS ||
      snap == NULL || snap_len == 0) {
    return NULL;
  }
  jbyteArray arr = bytes_to_jbytearray(env, snap, snap_len);
  ghostty_free(NULL, snap, snap_len);
  return arr;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSnapshotDecode)(JNIEnv* env,
                                                        jclass clazz,
                                                        jlong handle_ptr,
                                                        jbyteArray data) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || data == NULL) return JNI_FALSE;
  jsize len = (*env)->GetArrayLength(env, data);
  if (len <= 0) return JNI_FALSE;
  jbyte* ptr = (*env)->GetByteArrayElements(env, data, NULL);
  if (ptr == NULL) return JNI_FALSE;

  GhosttySnapshotDecoder decoder = NULL;
  GhosttyResult res =
      ghostty_snapshot_decoder_new_buf(NULL, &decoder, (const uint8_t*)ptr, (size_t)len);
  GhosttyTerminal decoded = NULL;
  if (res == GHOSTTY_SUCCESS) {
    res = ghostty_snapshot_decoder_decode(decoder, &decoded);
  }
  if (decoder != NULL) ghostty_snapshot_decoder_free(decoder);
  (*env)->ReleaseByteArrayElements(env, data, ptr, JNI_ABORT);

  if (res != GHOSTTY_SUCCESS || decoded == NULL) return JNI_FALSE;

  if (h->terminal != NULL) {
    ghostty_terminal_free(h->terminal);
  }
  h->terminal = decoded;
  configure_terminal_callbacks(h, 0);
  return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeCaptureFrame)(JNIEnv* env,
                                                        jclass clazz,
                                                        jlong handle_ptr,
                                                        jboolean force_full) {
  (void)clazz;
  GhosttyHandle* h = from_jlong(handle_ptr);
  if (h == NULL || h->terminal == NULL || h->render_state == NULL) return NULL;

  if (ghostty_render_state_update(h->render_state, h->terminal) != GHOSTTY_SUCCESS) {
    return NULL;
  }

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  (void)ghostty_render_state_get(h->render_state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  if (force_full == JNI_TRUE) {
    dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
  } else if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) {
    return NULL;
  }

  uint16_t cols = 0;
  uint16_t rows = 0;
  (void)ghostty_render_state_get(h->render_state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  (void)ghostty_render_state_get(h->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  (void)ghostty_render_state_get(h->render_state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  (void)ghostty_render_state_get(h->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

  GhosttyTerminalScreen active_screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  (void)ghostty_terminal_get(h->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &active_screen);

  GhosttyTerminalScrollbar scrollbar = {0, 0, 0};
  (void)ghostty_terminal_get(h->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scrollbar);

  ByteBuf* out = &h->frame_buf;
  out->len = 0;

  uint8_t cursor_flags = 0;
  if (cursor.visible) cursor_flags |= (1u << 0);
  if (cursor.viewport_has_value) cursor_flags |= (1u << 1);
  if (cursor.blinking) cursor_flags |= (1u << 2);
  if (cursor.password_input) cursor_flags |= (1u << 3);
  if (cursor.wide_tail) cursor_flags |= (1u << 4);
  if (active_screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) cursor_flags |= (1u << 5);
  if (h->bell_rung) cursor_flags |= (1u << 6);
  if (h->title_changed) cursor_flags |= (1u << 7);
  h->bell_rung = false;

  if (!bytebuf_u16_be(out, cols) ||
      !bytebuf_u16_be(out, rows) ||
      !bytebuf_u8(out, (uint8_t)dirty) ||
      !bytebuf_u32_be(out, rgb_to_argb(colors.foreground)) ||
      !bytebuf_u32_be(out, rgb_to_argb(colors.background)) ||
      !bytebuf_u32_be(out, colors.cursor_has_value ? rgb_to_argb(colors.cursor) : 0u) ||
      !bytebuf_u8(out, cursor_flags) ||
      !bytebuf_u8(out, (uint8_t)cursor.visual_style) ||
      !bytebuf_u16_be(out, cursor.viewport_has_value ? cursor.viewport_x : 0u) ||
      !bytebuf_u16_be(out, cursor.viewport_has_value ? cursor.viewport_y : 0u) ||
      !bytebuf_u32_be(out, (uint32_t)scrollbar.total) ||
      !bytebuf_u32_be(out, (uint32_t)scrollbar.offset) ||
      !bytebuf_u32_be(out, (uint32_t)scrollbar.len)) {
    return NULL;
  }

  size_t dirty_count_pos = out->len;
  if (!bytebuf_u16_be(out, 0)) return NULL;

  if (ghostty_render_state_get(
          h->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &h->row_iter) !=
      GHOSTTY_SUCCESS) {
    return NULL;
  }

  uint16_t dirty_row_count = 0;
  uint16_t row_y = 0;
  bool use_all_rows = (dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL);

  while (true) {
    if (use_all_rows) {
      if (!ghostty_render_state_row_iterator_next(h->row_iter)) break;
    } else {
      if (!ghostty_render_state_row_iterator_next_dirty(h->row_iter, &row_y)) break;
    }

    if (ghostty_render_state_row_get(
            h->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &h->row_cells) != GHOSTTY_SUCCESS) {
      if (use_all_rows) row_y++;
      continue;
    }

    if (!bytebuf_u16_be(out, row_y)) return NULL;
    size_t cell_count_pos = out->len;
    if (!bytebuf_u16_be(out, 0)) return NULL;

    uint16_t cell_count = 0;
    uint8_t utf8_tmp[64];

    while (ghostty_render_state_row_cells_next(h->row_cells)) {
      GhosttyCell raw_cell = 0;
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      if (ghostty_render_state_row_cells_get(
              h->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw_cell) ==
          GHOSTTY_SUCCESS) {
        (void)ghostty_cell_get(raw_cell, GHOSTTY_CELL_DATA_WIDE, &wide);
      }

      GhosttyStyle style;
      ghostty_style_default(&style);
      bool has_styling = false;
      (void)ghostty_render_state_row_cells_get(
          h->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &has_styling);
      if (has_styling) {
        (void)ghostty_render_state_row_cells_get(
            h->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
      }

      GhosttyColorRgb fg_rgb = colors.foreground;
      bool has_fg = (ghostty_render_state_row_cells_get(
                         h->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg_rgb) ==
                     GHOSTTY_SUCCESS);
      if (!has_fg) fg_rgb = colors.foreground;

      GhosttyColorRgb bg_rgb = colors.background;
      bool has_bg = (ghostty_render_state_row_cells_get(
                         h->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg_rgb) ==
                     GHOSTTY_SUCCESS);

      if (style.flags & GHOSTTY_STYLE_FLAG_INVERSE) {
        GhosttyColorRgb orig_fg = fg_rgb;
        fg_rgb = has_bg ? bg_rgb : colors.background;
        bg_rgb = orig_fg;
        has_bg = true;
      }

      uint32_t fg_argb = rgb_to_argb(fg_rgb);
      uint32_t bg_argb = has_bg ? rgb_to_argb(bg_rgb) : 0u;

      GhosttyBuffer utf8_buf = {.ptr = utf8_tmp, .cap = sizeof(utf8_tmp), .len = 0};
      GhosttyResult ures = ghostty_render_state_row_cells_get(
          h->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &utf8_buf);
      size_t ulen = (ures == GHOSTTY_SUCCESS) ? utf8_buf.len : 0u;
      if (ulen > sizeof(utf8_tmp)) ulen = 0u;

      if (!bytebuf_u32_be(out, fg_argb) ||
          !bytebuf_u32_be(out, bg_argb) ||
          !bytebuf_u16_be(out, style.flags) ||
          !bytebuf_u8(out, (uint8_t)wide) ||
          !bytebuf_u8(out, (uint8_t)ulen) ||
          !bytebuf_append(out, utf8_tmp, ulen)) {
        return NULL;
      }
      cell_count++;
    }

    out->data[cell_count_pos] = (uint8_t)(cell_count >> 8);
    out->data[cell_count_pos + 1] = (uint8_t)(cell_count & 0xFFu);
    dirty_row_count++;
    if (use_all_rows) row_y++;
  }

  out->data[dirty_count_pos] = (uint8_t)(dirty_row_count >> 8);
  out->data[dirty_count_pos + 1] = (uint8_t)(dirty_row_count & 0xFFu);

  (void)ghostty_render_state_clean(h->render_state);
  return bytes_to_jbytearray(env, out->data, out->len);
}
