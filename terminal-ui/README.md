# NovaScale terminal surface

The Apache-2.0 implementation in this module consumes immutable
`TerminalRenderSnapshot` values. It does not parse terminal output or own SSH,
Herdr or Ghostty sessions. It replaces the historical Termux-derived view.

The implementation was written from NovaScale's snapshot/input contracts,
terminal protocol behavior, existing NovaScale-owned behavioral tests and Android
API documentation. The old view was reviewed for provenance and public API
requirements, not used as a translation template. No formal clean-room claim is
made. The old implementation and its attribution remain in historical GPL tags.

- `NovaTerminalView`: application callbacks, viewport sizing/clipping, clipboard,
  selection, accessibility and lifecycle coordination.
- `TerminalInputConnection`: local IME preedit and explicit commit/cancellation.
  Printable Ctrl/Alt shortcuts are dispatched immediately even when an IME
  composes the key. Retiring that editor prevents duplicate completion; a new
  input connection resumes normal typing. Previously composed text is committed
  before arming a shortcut, using its original modifier state.
- `TerminalTouch`: touch/mouse/scale state, local scroll and remote wheel routing.
- `TerminalFonts`: four cached font styles/metrics and a per-view text advance
  LRU, bounded to 2,048 entries and 256 KiB of accounted storage.
- `TerminalRowRenderer`: immutable row layouts and Android 10+ `RenderNode`
  display lists. Up to 256 rows and 4 MiB of accounted Java/native storage are
  retained per view. Native display-list usage is Android's approximate measure.
  Android 9 and software Canvas use the same cached layout/measurements without
  RenderNode. Android retains the platform glyph rasterization cache.
- `TerminalImageRenderer` and `TerminalMouse`: retained NovaScale-owned image
  decoding/layer composition and xterm protocol helpers; neither comes from
  Termux. PNG/media limits remain enforced by the existing native decoder.

Equal row contents reuse their layout and GPU commands even when the native
snapshot creates fresh Kotlin objects. Changed text, styles, cell layout or
column count invalidate that row. Font/density changes clear row and measurement
caches. Cursor and selection are separate overlays; images are composed between
the relevant background/text layers. Cached measurements and text never cross
view/session boundaries or reach disk. Disposal and memory-pressure callbacks
release caches; the authoritative session snapshot can redraw them.

The `renderer` Android instrumentation suite compares cached/uncached pixels,
changed-row and font invalidation, actual hardware display-list reuse, IME
composition, Unicode selection and memory bounds. The existing `keyboard`,
`mouse`, `graphics` and isolated live Herdr suites cover shared behavior.

API references:

- https://developer.android.com/develop/ui/views/graphics/hardware-accel
- https://developer.android.com/reference/android/graphics/RenderNode
- https://developer.android.com/reference/android/graphics/Paint
- https://developer.android.com/reference/android/view/inputmethod/InputConnection
- https://developer.android.com/reference/android/view/GestureDetector
- https://developer.android.com/reference/android/view/ScaleGestureDetector
