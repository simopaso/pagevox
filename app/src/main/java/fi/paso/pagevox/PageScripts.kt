package fi.paso.pagevox

import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "PageScripts"

// Extracts innerText of semantic elements (falls back to body for .txt pages).
// Returns a JSON object string {lang, texts} via evaluateJavascript.
//
// When [readerMode] is on, extraction is scoped to the main article: it picks
// <article>/<main>/[role=main], or the densest text container, and skips
// chrome (nav/header/footer/aside) so the TTS doesn't read menus and ads.
internal fun extractTextJs(readerMode: Boolean) = """
(function() {
    var reader = $readerMode;
    var root = null;
    if (reader) {
        root = document.querySelector('article, main, [role=main]');
        if (!root) {
            var best = null, bestLen = 0;
            var conts = document.querySelectorAll('div, section, article, main');
            for (var c = 0; c < conts.length; c++) {
                var ps = conts[c].querySelectorAll('p');
                var len = 0;
                for (var p = 0; p < ps.length; p++) len += (ps[p].innerText || '').length;
                if (len > bestLen) { bestLen = len; best = conts[c]; }
            }
            root = best;
        }
    }
    root = root || document.body;
    var skip = 'nav, header, footer, aside, [role=navigation], [role=banner], [role=complementary]';
    var els = root.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,pre');
    var texts = [];
    if (els.length === 0) {
        var b = (root.innerText || '').trim();
        if (b) texts.push(b);
    } else {
        for (var i = 0; i < els.length; i++) {
            if (reader && els[i].closest(skip)) continue;
            var t = (els[i].innerText || '').trim();
            if (t) texts.push(t);
        }
    }
    // Declared content language: <html lang> first, then a content-language meta.
    var lang = (document.documentElement.getAttribute('lang') || '').trim();
    if (!lang) {
        var m = document.querySelector('meta[http-equiv="content-language" i]');
        if (m && m.content) lang = m.content.trim().split(',')[0].trim();
    }
    return JSON.stringify({ lang: lang, texts: texts });
})();
""".trimIndent()

// Returns the text of the element at the tapped pixel coordinates.
// Uses document.elementFromPoint, which works regardless of click handlers
// and is reliable for plain text where the DOM click event often doesn't fire
// in Android WebView.
internal fun tapDetectionJs(pxX: Float, pxY: Float) = """
(function() {
    var cx = $pxX / window.devicePixelRatio;
    var cy = $pxY / window.devicePixelRatio;
    var el = document.elementFromPoint(cx, cy);
    if (!el) return '';
    var blockTags = ['P','H1','H2','H3','H4','H5','H6','LI','PRE','BLOCKQUOTE','ARTICLE'];
    var node = el;
    for (var i = 0; i < 10 && node && node !== document.body; i++) {
        if (blockTags.indexOf(node.tagName) >= 0) {
            var text = (node.innerText || node.textContent || '').trim();
            if (text.length > 5) return text;
        }
        node = node.parentElement;
    }
    // Fallback: any ancestor with substantial text content.
    node = el;
    for (var j = 0; j < 5 && node && node !== document.body; j++) {
        var t = (node.innerText || node.textContent || '').trim();
        if (t.length > 20) return t;
        node = node.parentElement;
    }
    return '';
})();
""".trimIndent()

private const val READER_STYLE_ID = "__pagevox_reader__"

// Reader-mode visual de-clutter: hide common chrome/ads and give the main
// content a comfortable measure and line height. Removable by its <style> id.
// (Deliberately conservative — never hides <header>, where article titles live.)
internal val APPLY_READER_JS = """
(function() {
    var id = '$READER_STYLE_ID';
    if (document.getElementById(id)) return;
    var s = document.createElement('style');
    s.id = id;
    s.textContent =
        'nav, aside, footer, [role=navigation], [role=complementary], .ad, .ads,' +
        '.advert, .advertisement, ins.adsbygoogle { display: none !important; }' +
        'html, body { background: initial; }' +
        'body { max-width: 720px !important; margin: 0 auto !important;' +
        ' padding: 16px !important; line-height: 1.6 !important; }' +
        'p, li { font-size: 1.05em !important; }' +
        'img, video, table { max-width: 100% !important; height: auto !important; }';
    (document.head || document.documentElement).appendChild(s);
})();
""".trimIndent()

internal val REMOVE_READER_JS = """
(function() {
    var e = document.getElementById('$READER_STYLE_ID');
    if (e) e.remove();
})();
""".trimIndent()

private const val HIGHLIGHT_NAME = "pagevox"

// Follow-along: highlight the sentence currently being read and scroll it into
// view (karaoke style). Uses the CSS Custom Highlight API so the page DOM is
// left untouched — important because we also extract text from that DOM. The
// sentence is located by walking text nodes and matching against a
// whitespace-collapsed concatenation, so matches that span inline tags (<a>,
// <em>, …) still work. Degrades gracefully: if the Highlight API is missing the
// page still scrolls; if the text isn't found nothing happens.
internal fun highlightSentenceJs(sentenceJson: String) = """
(function(sent){
    var NAME = '$HIGHLIGHT_NAME';
    try { if (window.CSS && CSS.highlights) CSS.highlights.delete(NAME); } catch (e) {}
    if (!sent) return;
    var target = sent.replace(/\s+/g, ' ').trim();
    var root = document.body;
    if (!target || !root) return;

    // Walking every text node is O(page); doing it per sentence made long
    // articles O(n²) and janky. Build the concatenated-text index once, cache
    // it on window, and invalidate via a MutationObserver when the DOM changes.
    function buildIndex() {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        var full = '', map = [], prevSpace = true, node;
        while (node = walker.nextNode()) {
            var v = node.nodeValue;
            for (var i = 0; i < v.length; i++) {
                var c = v[i];
                if (/\s/.test(c)) {
                    if (prevSpace) continue;
                    full += ' '; map.push([node, i]); prevSpace = true;
                } else {
                    full += c; map.push([node, i]); prevSpace = false;
                }
            }
        }
        return { full: full, lower: full.toLowerCase(), map: map };
    }

    var cache = window.__pagevox_index;
    if (!cache) {
        cache = window.__pagevox_index = buildIndex();
        if (!window.__pagevox_observer && window.MutationObserver) {
            window.__pagevox_observer = new MutationObserver(function(){
                window.__pagevox_index = null;
            });
            window.__pagevox_observer.observe(root,
                { childList: true, subtree: true, characterData: true });
        }
    }

    function locate(c) {
        var i = c.full.indexOf(target);
        return i >= 0 ? i : c.lower.indexOf(target.toLowerCase());
    }

    var idx = locate(cache);
    if (idx < 0) {
        // Possibly a stale cache the observer hasn't flushed yet — rebuild once.
        cache = window.__pagevox_index = buildIndex();
        idx = locate(cache);
        if (idx < 0) return;
    }
    var s = cache.map[idx], e = cache.map[idx + target.length - 1];
    if (!s || !e) return;

    var range = document.createRange();
    try { range.setStart(s[0], s[1]); range.setEnd(e[0], e[1] + 1); }
    catch (err) { window.__pagevox_index = null; return; }

    if (window.Highlight && window.CSS && CSS.highlights) {
        try {
            CSS.highlights.set(NAME, new Highlight(range));
            if (!document.getElementById('__pagevox_hl_style__')) {
                var st = document.createElement('style');
                st.id = '__pagevox_hl_style__';
                st.textContent = '::highlight($HIGHLIGHT_NAME){background-color:rgba(255,213,79,0.55);color:inherit;}';
                (document.head || document.documentElement).appendChild(st);
            }
        } catch (e2) {}
    }

    var rect = range.getBoundingClientRect();
    if (rect && (rect.height || rect.width)) {
        var vh = window.innerHeight || document.documentElement.clientHeight;
        // Only scroll when the sentence isn't comfortably in view, then center it.
        if (rect.top < 80 || rect.bottom > vh - 80) {
            var y = window.scrollY + rect.top - (vh / 2) + (rect.height / 2);
            window.scrollTo({ top: Math.max(0, y), behavior: 'smooth' });
        }
    }
})($sentenceJson);
""".trimIndent()

internal val CLEAR_HIGHLIGHT_JS = """
(function(){ try { if (window.CSS && CSS.highlights) CSS.highlights.delete('$HIGHLIGHT_NAME'); } catch (e) {} })();
""".trimIndent()

internal fun extractTexts(webView: WebView?, readerMode: Boolean, onResult: (lang: String?, texts: List<String>) -> Unit) {
    webView?.evaluateJavascript(extractTextJs(readerMode)) { result ->
        try {
            val jsonStr = JSONTokener(result).nextValue() as String
            val obj = JSONObject(jsonStr)
            val lang = obj.optString("lang").ifBlank { null }
            val arr = obj.getJSONArray("texts")
            onResult(lang, (0 until arr.length()).map { arr.getString(it) })
        } catch (e: Exception) {
            Log.e(TAG, "Text extraction failed", e)
        }
    }
}
