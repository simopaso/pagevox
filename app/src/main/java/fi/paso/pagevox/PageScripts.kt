package fi.paso.pagevox

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "PageScripts"

// Never read aloud, in either mode: menus, footers, sidebars, dialogs (the cookie
// banner, as a rule) and reference lists. On a phone-sized Wikipedia article the
// footer and navigation alone are nine blocks in ten, and the references are
// hundreds of citations. <header> stays out of this list because article titles
// live in it; reader mode, which wants the article only, adds it.
private const val NOT_READ_SELECTOR =
    "nav, footer, aside, dialog, [role=navigation], [role=contentinfo], [role=complementary], " +
        "[role=dialog], [aria-modal=true], .references, .reflist, " +
        "[role=doc-endnotes], [role=doc-bibliography]"

// Opens what the page has folded away, so that it can be read and followed on
// screen. Mobile Wikipedia folds every section with hidden="until-found", which
// keeps the text out of innerText (unlike display:none it does have a layout box,
// with content-visibility:hidden on it), so only the intro and the headings were
// read. beforematch is how a page hears that the browser is revealing such an
// element, and is what keeps its own expand arrows right. Runs when reading starts,
// not on load, so a page that's only being looked at stays as its author left it.
private const val REVEAL_COLLAPSED_JS = """
    (function() {
        var skip = '$NOT_READ_SELECTOR';
        var folded = document.querySelectorAll('[hidden="until-found"]');
        for (var f = 0; f < folded.length; f++) {
            if (folded[f].closest(skip)) continue;
            try { folded[f].dispatchEvent(new Event('beforematch', { bubbles: true })); } catch (e) {}
            folded[f].removeAttribute('hidden');
        }
        var closed = document.querySelectorAll('details:not([open])');
        for (var d = 0; d < closed.length; d++) {
            if (!closed[d].closest(skip)) closed[d].open = true;
        }
    })();
"""

// Extracts innerText of semantic elements (falls back to body for .txt pages).
// Returns a JSON object string {lang, texts, tags} via evaluateJavascript, where
// tags[i] is the lowercased tag name of the element texts[i] came from. The tag
// is what makes heading-aware navigation possible: h1–h6 blocks become section
// boundaries for the scrubber's tick marks and long-press section skip.
//
// Two things are never read, reader mode or not: site chrome and citation lists
// ([NOT_READ_SELECTOR]), and text with no layout box. display:none text has to be
// left out by hand, because innerText falls back to textContent for an element
// that isn't rendered: hidden menus and dialogs used to be read aloud, and could
// never be highlighted. If those rules leave nothing at all, the page is read
// unfiltered: better too much than a Play button that says nothing, and a page
// hiding its body until its scripts have run reads as it always did.
//
// When [readerMode] is on, extraction is also scoped to the main article: it picks
// <article>/<main>/[role=main], or the densest text container, and skips the
// header too, so only the article itself is read.
internal fun extractTextJs(readerMode: Boolean) = """
(function() {
    var reader = $readerMode;
    $REVEAL_COLLAPSED_JS
    var root = null;
    if (reader) {
        // The one article holding most of the page's text, when there is one.
        // querySelector('article, main') returns whichever comes first in the
        // document, and a main wrapping the article comes first: everything
        // around the story was read along with it. A page of many small articles
        // (a front page) has no such article, and falls through to main.
        var arts = document.querySelectorAll('article'), topArt = null, topLen = 0, allLen = 0;
        var paras = document.querySelectorAll('p');
        for (var q = 0; q < paras.length; q++) allLen += (paras[q].innerText || '').length;
        for (var a0 = 0; a0 < arts.length; a0++) {
            var aps = arts[a0].querySelectorAll('p'), aLen = 0;
            for (var q2 = 0; q2 < aps.length; q2++) aLen += (aps[q2].innerText || '').length;
            if (aLen > topLen) { topLen = aLen; topArt = arts[a0]; }
        }
        root = (topArt && topLen >= allLen * 0.6) ? topArt : document.querySelector('main, [role=main], article');
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
    var skip = '$NOT_READ_SELECTOR' + (reader ? ', header, [role=banner]' : '');
    var blockSel = 'p,h1,h2,h3,h4,h5,h6,li,pre';
    var els = root.querySelectorAll(blockSel);
    var texts = [], tags = [];
    if (els.length === 0) {
        // Plain-text document: one undifferentiated block, so no headings.
        var b = (root.innerText || '').trim();
        if (b) { texts.push(b); tags.push(''); }
    } else {
        for (var i = 0; i < els.length; i++) {
            if (els[i].closest(skip) || els[i].getClientRects().length === 0) continue;
            var t;
            if (els[i].querySelector(blockSel)) {
                // A block holding other blocks (a list item around a paragraph or a
                // nested list): those are read in their own turn, so take only the
                // text that is this block's own, or theirs would be read twice.
                var own = els[i].cloneNode(true), inner = own.querySelectorAll(blockSel);
                for (var n0 = 0; n0 < inner.length; n0++) inner[n0].remove();
                t = (own.textContent || '').trim();
            } else {
                t = (els[i].innerText || '').trim();
            }
            if (t) { texts.push(t); tags.push(els[i].tagName.toLowerCase()); }
        }
        var filteredAway = texts.length === 0;
        for (var u = 0; filteredAway && u < els.length; u++) {
            var ut = (els[u].innerText || '').trim();
            if (ut) { texts.push(ut); tags.push(els[u].tagName.toLowerCase()); }
        }
    }
    // Declared content language: <html lang> first, then a content-language meta.
    var lang = (document.documentElement.getAttribute('lang') || '').trim();
    if (!lang) {
        var m = document.querySelector('meta[http-equiv="content-language" i]');
        if (m && m.content) lang = m.content.trim().split(',')[0].trim();
    }
    // The page's lead image, for the media notification's artwork. Resolved
    // against the document so a relative og:image still works; the Kotlin side
    // accepts only http(s) whatever comes back from here.
    var image = '';
    var im = document.querySelector('meta[property="og:image" i], meta[name="og:image" i], ' +
        'meta[name="twitter:image" i], meta[property="twitter:image" i]');
    if (im && im.content) {
        try { image = new URL(im.content.trim(), document.baseURI).href; } catch (e) {}
    }
    // The next chapter, if this page has one: a rel=next link; else this URL
    // with the last number bumped (/story/7 -> /story/8), but only when the page
    // links to it, which is what chapter navigation looks like; else a link
    // labelled "Next" or "Next chapter" in one of the app's languages. Same site
    // only — and the Kotlin side re-checks whatever comes back.
    var next = '';
    try {
        var here = location.href.split('#')[0];
        var abs = function(h) {
            try {
                var u = new URL(h, document.baseURI);
                if ((u.protocol !== 'https:' && u.protocol !== 'http:') || u.host !== location.host) return '';
                u.hash = '';
                return u.href === here ? '' : u.href;
            } catch (e) { return ''; }
        };
        var rel = document.querySelector('link[rel~="next" i][href], a[rel~="next" i][href]');
        if (rel) next = abs(rel.getAttribute('href'));
        var links = document.querySelectorAll('a[href]');
        var m = next ? null : location.pathname.match(/^(.*?)(\d+)(\/?)$/);
        if (m) {
            var bumped = String(Number(m[2]) + 1);
            while (m[2].charAt(0) === '0' && bumped.length < m[2].length) bumped = '0' + bumped;
            var want = new URL(m[1] + bumped + m[3], location.href).href.replace(/\/$/, '');
            for (var i = 0; i < links.length && !next; i++) {
                var c = abs(links[i].getAttribute('href'));
                if (c && c.split('?')[0].replace(/\/$/, '') === want) next = c;
            }
        }
        var label = /^[\s‹«←<›»→>]*(next|next chapter|next page|next part|seuraava|seuraava luku|seuraava osa|seuraava sivu|nästa|nästa kapitel|nästa del|nästa sida|weiter|nächstes kapitel|nächster teil|nächste seite|suivant|chapitre suivant|partie suivante|page suivante)[\s›»→>]*(?:[:\n][\s\S]*)?$/i;
        for (var j = 0; j < links.length && !next; j++) {
            var a = links[j];
            var t = (a.innerText || a.textContent || '').trim();
            var al = (a.getAttribute('aria-label') || '').trim();
            if (label.test(t) || label.test(al)) next = abs(a.getAttribute('href'));
        }
    } catch (e) { next = ''; }
    return JSON.stringify({ lang: lang, texts: texts, tags: tags, image: image, next: next });
})();
""".trimIndent()

// Returns page text starting at the tapped point, so playback can seek to the
// tapped sentence. The primary path uses caretRangeFromPoint to find the exact
// character under the finger — essential on plain-text pages, <pre> stories, or
// single-container articles, where the whole readable body is ONE block and
// element-level detection would return the entire document (always resolving to
// the first sentence). Falls back to nearest-block text only if no caret API.
internal fun tapDetectionJs(pxX: Float, pxY: Float) = """
(function() {
    var cx = $pxX / window.devicePixelRatio;
    var cy = $pxY / window.devicePixelRatio;

    // A tap on a link or a control navigates or acts; it isn't a request to
    // read from there. Without this, opening a menu link on a page that was
    // being read also jumped the reading to the link's text.
    var hit = document.elementFromPoint(cx, cy);
    if (hit && hit.closest && hit.closest(
        'a[href], button, [role="button"], [role="link"], [role="tab"], [role="menuitem"], ' +
        'input, select, textarea, label, summary, [contenteditable="true"]')) return '';

    var textNode = null, offset = 0;
    if (document.caretRangeFromPoint) {
        var r = document.caretRangeFromPoint(cx, cy);
        if (r) { textNode = r.startContainer; offset = r.startOffset; }
    } else if (document.caretPositionFromPoint) {
        var p = document.caretPositionFromPoint(cx, cy);
        if (p) { textNode = p.offsetNode; offset = p.offset; }
    }

    if (textNode && textNode.nodeType === 3) {
        // Collect ~200 chars from the caret forward, in document order, so the
        // snippet begins exactly where the user tapped (usually mid-sentence).
        var out = textNode.nodeValue.substring(offset);
        if (out.length < 200) {
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
            walker.currentNode = textNode;
            var n;
            while (out.length < 200 && (n = walker.nextNode())) {
                out += ' ' + n.nodeValue;
            }
        }
        out = out.replace(/\s+/g, ' ').trim();
        if (out.length > 3) return out;
    }

    // Fallback: nearest block element's text (engines without a caret API).
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
    // A page reloaded under a reading that's already going hasn't been through
    // extraction: its sentences would be found, but inside a section that can't paint.
    $REVEAL_COLLAPSED_JS

    // Walking every text node is O(page); doing it per sentence made long
    // articles O(n²) and janky. Build the concatenated-text index once, cache
    // it on window, and invalidate via a MutationObserver when the DOM changes.
    function buildIndex() {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        var full = '', map = [], prevSpace = true, node;
        while (node = walker.nextNode()) {
            // Never on screen, and a page's own data can repeat its text.
            var parent = node.parentNode ? node.parentNode.nodeName : '';
            if (parent === 'SCRIPT' || parent === 'STYLE' || parent === 'NOSCRIPT') continue;
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

    // A short sentence is rarely the only place its words appear: a heading's word
    // is in the introduction too, and "Yes." is said all through a chapter. The
    // first match used to win, so the highlight, and the scrolling with it, jumped
    // back up the page for that sentence. Reading moves on from the sentence before,
    // so the match nearest to where that one ended is the right one.
    function nearest(hay, needle) {
        var first = hay.indexOf(needle), hint = window.__pagevox_last;
        if (first < 0 || typeof hint !== 'number') return first;
        var best = first, i = first;
        while (i >= 0 && i <= hint) { best = i; i = hay.indexOf(needle, i + 1); }
        if (i >= 0 && (best > hint || i - hint < hint - best)) best = i;
        return best;
    }

    function locate(c) {
        var i = nearest(c.full, target);
        return i >= 0 ? i : nearest(c.lower, target.toLowerCase());
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
    window.__pagevox_last = idx + target.length;

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

/** One extracted block of page text and the element it came from. */
data class PageBlock(val text: String, val tag: String) {
    /** h1–h6 — the blocks that open a new section of the page. */
    val isHeading: Boolean get() = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'
}

// How often an unsettled page is re-read, and how long to keep trying before
// taking whatever is there.
private const val SETTLE_POLL_MS = 350L
private const val SETTLE_TIMEOUT_MS = 10_000L
// A loaded page with no text at all stops being worth waiting for much sooner.
private const val EMPTY_PAGE_TIMEOUT_MS = 3_000L
// A page this young may still be setting itself up: script-built pages keep
// rewriting their text just after load, without the text having "changed" between
// two quick readings. A page that has been open a while answers straight away.
private const val MIN_PAGE_AGE_MS = 1_500.0
// A page still loading (progress below 100, often only ads and trackers) counts
// as settled once this many readings in a row agree and it holds real text.
private const val UNFINISHED_PAGE_READINGS = 5
private const val UNFINISHED_PAGE_MIN_CHARS = 500

/**
 * Extract the page once its text has stopped changing, re-reading it until two
 * readings agree.
 *
 * A single reading isn't safe: onPageFinished can fire at 32% loaded, and pages
 * stream and rewrite themselves after that. Reading too early got a handful of
 * fragments, which were then read aloud in place of the page, and even a complete
 * page changes its sentence numbers while it sets itself up, which is what saved
 * reading positions are counted in. Always calls back, at the latest after
 * [SETTLE_TIMEOUT_MS], with whatever the last reading found, unless [isActive]
 * has gone false, which is how a caller that may destroy the WebView opts out.
 *
 * Re-reads through its own Handler rather than webView.postDelayed: a view that
 * isn't attached to a window, as the playback service's isn't, queues posted work
 * until it is attached, which would be never.
 */
internal fun extractWhenSettled(
    webView: WebView,
    readerMode: Boolean,
    isActive: () -> Boolean = { true },
    onResult: (lang: String?, blocks: List<PageBlock>, imageUrl: String?, nextUrl: String?) -> Unit
) {
    val handler = Handler(Looper.getMainLooper())
    val startedAt = SystemClock.uptimeMillis()
    var lastChars = -1
    var agreeing = 0
    fun read() {
        if (!isActive()) return
        extractTexts(webView, readerMode) { lang, blocks, image, next ->
            if (!isActive()) return@extractTexts
            webView.evaluateJavascript("performance.now()") { age ->
                if (!isActive()) return@evaluateJavascript
                val chars = blocks.sumOf { it.text.length }
                agreeing = if (chars > 0 && chars == lastChars) agreeing + 1 else 0
                lastChars = chars
                val elapsed = SystemClock.uptimeMillis() - startedAt
                val mature = (age?.toDoubleOrNull() ?: Double.MAX_VALUE) >= MIN_PAGE_AGE_MS
                val settled = when {
                    elapsed >= SETTLE_TIMEOUT_MS -> true
                    webView.progress >= 100 ->
                        (agreeing >= 1 && mature) || (chars == 0 && elapsed >= EMPTY_PAGE_TIMEOUT_MS)
                    else -> agreeing >= UNFINISHED_PAGE_READINGS - 1 && chars >= UNFINISHED_PAGE_MIN_CHARS
                }
                if (settled) {
                    Log.d(TAG, "Extracted ${blocks.size} blocks, $chars chars, after ${elapsed}ms")
                    onResult(lang, blocks, image, next)
                } else {
                    handler.postDelayed({ read() }, SETTLE_POLL_MS)
                }
            }
        }
    }
    read()
}

internal fun extractTexts(
    webView: WebView?,
    readerMode: Boolean,
    onResult: (lang: String?, blocks: List<PageBlock>, imageUrl: String?, nextUrl: String?) -> Unit
) {
    webView?.evaluateJavascript(extractTextJs(readerMode)) { result ->
        try {
            val jsonStr = JSONTokener(result).nextValue() as String
            val obj = JSONObject(jsonStr)
            val lang = obj.optString("lang").ifBlank { null }
            val image = obj.optString("image").ifBlank { null }
            val next = obj.optString("next").ifBlank { null }
            val arr = obj.getJSONArray("texts")
            // tags is absent only if a cached script from an older build ran;
            // treat a missing entry as an untagged block rather than failing.
            val tags = obj.optJSONArray("tags")
            onResult(lang, (0 until arr.length()).map { i ->
                PageBlock(arr.getString(i), tags?.optString(i).orEmpty())
            }, image, next)
        } catch (e: Exception) {
            // A script run mid-navigation returns "null" rather than our JSON.
            // Report it as a page with no text yet, so the caller can retry
            // instead of the press silently doing nothing.
            Log.w(TAG, "Text extraction failed", e)
            onResult(null, emptyList(), null, null)
        }
    }
}
