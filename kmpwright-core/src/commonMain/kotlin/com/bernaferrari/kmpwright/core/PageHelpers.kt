package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The in-page automation runtime (selector engine, actionability checks, synthetic input and the
 * snapshot ref registry).
 *
 * Threat model: the automated page may be hostile. The runtime therefore
 * - lives in a closure and is published under a random, per-process property name defined with
 *   `Object.defineProperty` as non-writable, non-enumerable and non-configurable, so a page can
 *   neither replace it nor pre-empt a well-known global name;
 * - captures the DOM primitives it depends on when it is installed;
 * - keeps the snapshot ref registry private; mutating it requires [seal], a secret that only ever
 *   appears in scripts evaluated by KMPwright and is compared inside the frozen runtime;
 * - never trusts a pre-existing global: a slot that exists but is not a sealed runtime of the
 *   expected version is reported as tampering.
 *
 * Residual risk: after installation a page can discover the property name (for example through
 * `Object.getOwnPropertyNames(window)`) and read — but not modify — the runtime. A page that
 * learned the name could pre-define it in a *later* document before KMPwright installs there; the
 * install script detects that the slot is not a sealed runtime and fails loudly instead of using it.
 */
internal object PageHelpers {
    const val VERSION = 5
    private fun hex(size: Int) = secureRandomBytes(size).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** Random global slot name of the runtime in every document automated by this process. */
    val key: String = "__kmpwright_rt_" + hex(9)

    /** Secret required for privileged runtime operations (ref registry writes). */
    val seal: String = hex(16)

    const val MISSING = "__kmpwright_helpers_missing__"
    const val TAMPERED = "__kmpwright_helpers_tampered__"

    /** JS expression that evaluates to the runtime or `null`. */
    val runtimeExpression: String get() = "(function(){var h=window[${jsStringLiteral(key)}];return h&&h.version===$VERSION&&h.sealed===true?h:null;})()"

    /**
     * Wraps [body] (a function body that may use `H`, the runtime) into a self-contained script whose
     * result is always a JSON envelope `{"__kw":1,...}`: `v` holds the value, `e` an error message,
     * and `missing`/`tampered` report runtime state.
     */
    fun envelope(body: String): String = """
        (function(){
          var H = window[${jsStringLiteral(key)}];
          if (!H || H.version !== $VERSION || H.sealed !== true) return H ? '{"__kw":1,"tampered":1}' : '{"__kw":1,"missing":1}';
          try {
            var __v = (function(){ $body
            })();
            return H.enc({__kw:1, v: __v === undefined ? null : __v});
          } catch (e) {
            return H.enc({__kw:1, e: String((e && e.message) || e).slice(0, 2000)});
          }
        })()
    """.trimIndent()

    sealed class Outcome {
        data class Value(val value: JsonElement) : Outcome()
        data class Error(val message: String) : Outcome()
        data object Missing : Outcome()
        data object Tampered : Outcome()
    }

    /**
     * Decodes an [envelope] result. Bridges that cannot report values (or test doubles) return
     * plain strings; those are treated as the value itself so custom controllers keep working.
     */
    fun decode(raw: String?): Outcome {
        if (raw == null) return Outcome.Value(JsonNull)
        val trimmed = raw.trim()
        if (trimmed.startsWith("{\"__kw\"")) {
            val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            if (obj != null && obj.containsKey("__kw")) {
                return when {
                    obj.containsKey("missing") -> Outcome.Missing
                    obj.containsKey("tampered") -> Outcome.Tampered
                    obj.containsKey("e") -> Outcome.Error(obj["e"]?.jsonPrimitive?.content ?: "Unknown page error")
                    else -> Outcome.Value(obj["v"] ?: JsonNull)
                }
            }
        }
        if (trimmed == MISSING) return Outcome.Missing
        if (trimmed == TAMPERED) return Outcome.Tampered
        return Outcome.Value(JsonPrimitive(raw))
    }

    /** Converts a decoded value back into the legacy string representation used by locator APIs. */
    fun valueAsString(value: JsonElement): String? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    fun asObject(value: JsonElement): JsonObject? = value as? JsonObject
        ?: (value as? JsonPrimitive)?.takeIf { it.isString && it.content.startsWith("{") }
            ?.let { runCatching { Json.parseToJsonElement(it.content).jsonObject }.getOrNull() }

    /** Script that installs the runtime in the current document (idempotent). */
    fun installScript(): String = """
(function(){
  var KEY = ${jsStringLiteral(key)}, SEAL = ${jsStringLiteral(seal)}, VERSION = $VERSION;
  var existing = Object.getOwnPropertyDescriptor(window, KEY);
  if (existing) {
    var current = existing.value;
    return (!existing.get && !existing.writable && !existing.configurable && current && current.version === VERSION && current.sealed === true) ? 'ok' : '$TAMPERED';
  }
  var win = window, doc = document;
  var O = Object, AP = Array.prototype, slice = AP.slice;
  var stringify = JSON.stringify, WM = win.WeakMap, WR = win.WeakRef, MapC = win.Map;
  var EP = win.Element.prototype, DP = win.Document.prototype, FP = win.DocumentFragment.prototype;
  var eQSA = EP.querySelectorAll, dQSA = DP.querySelectorAll, fQSA = FP.querySelectorAll;
  var gBCR = EP.getBoundingClientRect, gCS = win.getComputedStyle, dispatch = win.EventTarget.prototype.dispatchEvent;
  var getAttr = EP.getAttribute, hasAttr = EP.hasAttribute, contains = win.Node.prototype.contains;
  function desc(proto, name) { var d = proto && O.getOwnPropertyDescriptor(proto, name); return d && d.set ? d.set : null; }
  var setInputValue = desc(win.HTMLInputElement && win.HTMLInputElement.prototype, 'value');
  var setTextAreaValue = desc(win.HTMLTextAreaElement && win.HTMLTextAreaElement.prototype, 'value');
  var setSelectValue = desc(win.HTMLSelectElement && win.HTMLSelectElement.prototype, 'value');
  var setChecked = desc(win.HTMLInputElement && win.HTMLInputElement.prototype, 'checked');
  // Browser accessibility metadata (CDP only). Private to this closure: written only through the sealed
  // nativeAX entry point, so a page cannot forge roles/names or remap AX node ids to other elements.
  var wmGet = WM.prototype.get, wmSet = WM.prototype.set, mapGet = MapC.prototype.get, mapSet = MapC.prototype.set;
  var axMeta = new WM(), axNodes = new MapC(), axTree = null;
  function nativeMeta(el) { try { return wmGet.call(axMeta, el) || null; } catch (e) { return null; } }
  function nativeNode(id) { var ref = mapGet.call(axNodes, id); return ref ? (typeof WR === 'function' ? ref.deref() : ref) : null; }

  function attr(el, name) { try { return getAttr.call(el, name); } catch (e) { return null; } }
  function has(el, name) { try { return hasAttr.call(el, name); } catch (e) { return false; } }
  function norm(s) { return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }
  function qsa(root, sel) {
    var fn = root.nodeType === 9 ? dQSA : root.nodeType === 11 ? fQSA : eQSA;
    try { return slice.call(fn.call(root, sel)); }
    catch (e) { throw new Error('Invalid selector "' + sel + '": ' + (e && e.message || e)); }
  }
  function rect(el) { return gBCR.call(el); }
  function style(el) { return gCS.call(win, el); }
  function parentOf(el) { return el.parentElement || (el.parentNode && el.parentNode.host) || null; }
  function within(root, el) { if (!root || root === doc || root.nodeType === 9) return true; var n = el; while (n) { if (n === root) return true; n = parentOf(n); } return false; }
  function allElements(root) {
    var out = [], stack = [root];
    while (stack.length) {
      var node = stack.pop();
      var kids = node.children ? slice.call(node.children) : [];
      if (node.shadowRoot && node.shadowRoot.mode === 'open') kids = kids.concat(slice.call(node.shadowRoot.children));
      for (var i = kids.length - 1; i >= 0; i--) stack.push(kids[i]);
      if (node.nodeType === 1) out.push(node);
    }
    return out;
  }
  function shadowRoots(root) {
    var out = [];
    allElements(root).forEach(function(el) { if (el.shadowRoot && el.shadowRoot.mode === 'open') out.push(el.shadowRoot); });
    return out;
  }
  function unique(list) { var seen = new win.Set(), out = []; list.forEach(function(el) { if (!seen.has(el)) { seen.add(el); out.push(el); } }); return out; }
  function unquote(s) { return s.slice(1, -1).replace(/\\(.)/g, '${'$'}1'); }
  function regexOf(spec) {
    var m = /^\/(.*)\/([dimsuy]*)$/.exec(spec);
    if (!m) return null;
    try { return new RegExp(m[1], m[2]); } catch (e) { throw new Error('Invalid selector: bad regular expression ' + spec); }
  }
  // text matcher: "quoted" = exact (whitespace-normalised, case-sensitive); /re/flags = regex; plain = case-insensitive substring.
  function matcher(spec, flag) {
    spec = String(spec);
    var re = regexOf(spec);
    if (re) return function(s) { return re.test(norm(s)); };
    if (spec.length >= 2 && spec.charAt(0) === '"' && spec.charAt(spec.length - 1) === '"') {
      var exact = norm(unquote(spec));
      if (flag === 'i') { exact = exact.toLowerCase(); return function(s) { return norm(s).toLowerCase() === exact; }; }
      return function(s) { return norm(s) === exact; };
    }
    var needle = norm(spec).toLowerCase();
    return function(s) { return norm(s).toLowerCase().indexOf(needle) !== -1; };
  }
  function textOf(el) {
    var tag = el.tagName;
    if (tag === 'INPUT' && /^(button|submit|reset)${'$'}/i.test(el.type)) return el.value || '';
    var t = el.innerText;
    return t == null ? (el.textContent || '') : t;
  }
  var SKIP_TEXT = {SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, HEAD: 1, TEMPLATE: 1, HTML: 1, TITLE: 1};
  function byText(root, test) {
    var hits = allElements(root.nodeType === 9 ? (root.body || root.documentElement) : root).filter(function(el) {
      return !SKIP_TEXT[el.tagName] && test(textOf(el));
    });
    var marked = new win.Set();
    hits.forEach(function(el) { var p = parentOf(el); while (p) { marked.add(p); p = parentOf(p); } });
    return hits.filter(function(el) { return !marked.has(el); });
  }
  function isHiddenForAT(el) {
    for (var n = el; n; n = parentOf(n)) { if (attr(n, 'aria-hidden') === 'true') return true; }
    if (typeof el.checkVisibility === 'function') return !el.checkVisibility({visibilityProperty: true});
    var s = style(el);
    return s.display === 'none' || s.visibility === 'hidden';
  }
  function roleOf(el) {
    var native = nativeMeta(el);
    if (native && native.role) return String(native.role);
    var explicit = (attr(el, 'role') || '').trim().split(/\s+/)[0].toLowerCase();
    if (explicit) return explicit;
    var tag = el.tagName, type = String(el.type || '').toLowerCase();
    switch (tag) {
      case 'A': case 'AREA': return has(el, 'href') ? 'link' : '';
      case 'BUTTON': return 'button';
      case 'INPUT':
        if (type === 'hidden') return '';
        if (/^(button|submit|reset|image)${'$'}/.test(type)) return 'button';
        if (type === 'checkbox') return 'checkbox';
        if (type === 'radio') return 'radio';
        if (type === 'range') return 'slider';
        if (type === 'number') return 'spinbutton';
        if (has(el, 'list')) return 'combobox';
        if (type === 'search') return 'searchbox';
        return /^(|text|email|tel|url|password|date|datetime-local|month|time|week)${'$'}/.test(type) ? 'textbox' : '';
      case 'TEXTAREA': return 'textbox';
      case 'SELECT': return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
      case 'OPTION': return 'option';
      case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6': return 'heading';
      case 'IMG': return attr(el, 'alt') === '' ? 'presentation' : 'img';
      case 'NAV': return 'navigation';
      case 'MAIN': return 'main';
      case 'ASIDE': return 'complementary';
      case 'HEADER': return el.closest && el.closest('article,aside,main,nav,section') ? '' : 'banner';
      case 'FOOTER': return el.closest && el.closest('article,aside,main,nav,section') ? '' : 'contentinfo';
      case 'UL': case 'OL': case 'MENU': return 'list';
      case 'LI': return 'listitem';
      case 'TABLE': return 'table';
      case 'TR': return 'row';
      case 'TD': return 'cell';
      case 'TH': return 'columnheader';
      case 'FORM': return 'form';
      case 'DIALOG': return 'dialog';
      case 'PROGRESS': return 'progressbar';
      case 'METER': return 'meter';
      case 'DETAILS': return 'group';
      case 'SUMMARY': return 'button';
      case 'FIELDSET': return 'group';
      case 'ARTICLE': return 'article';
      case 'HR': return 'separator';
      case 'OUTPUT': return 'status';
      case 'SECTION': return (attr(el, 'aria-label') || attr(el, 'aria-labelledby')) ? 'region' : '';
    }
    return '';
  }
  var NAME_FROM_CONTENT = {button: 1, link: 1, heading: 1, cell: 1, columnheader: 1, rowheader: 1, option: 1, tab: 1, menuitem: 1,
    menuitemcheckbox: 1, menuitemradio: 1, checkbox: 1, radio: 1, switch: 1, treeitem: 1, tooltip: 1, listitem: 1, row: 1};
  function nameOf(el) {
    var native = nativeMeta(el);
    if (native && native.name != null) return norm(native.name);
    var labelledBy = attr(el, 'aria-labelledby');
    if (labelledBy) {
      var root = el.getRootNode ? el.getRootNode() : doc;
      var joined = norm(labelledBy.trim().split(/\s+/).map(function(id) {
        var ref = (root.getElementById ? root.getElementById(id) : null) || doc.getElementById(id);
        return ref ? (ref.innerText || ref.textContent || '') : '';
      }).join(' '));
      if (joined) return joined;
    }
    var label = attr(el, 'aria-label');
    if (label && label.trim()) return norm(label);
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'METER' || tag === 'PROGRESS' || tag === 'OUTPUT') {
      var labels = el.labels ? slice.call(el.labels) : [];
      var fromLabels = norm(labels.map(function(l) { return l.innerText || l.textContent || ''; }).join(' '));
      if (fromLabels) return fromLabels;
      if (tag === 'INPUT' && /^(button|submit|reset)${'$'}/i.test(el.type)) return norm(el.value || (el.type === 'submit' ? 'Submit' : el.type === 'reset' ? 'Reset' : ''));
      if (tag === 'INPUT' && el.type === 'image') return norm(attr(el, 'alt') || 'Submit');
      var ph = attr(el, 'placeholder');
      if (ph && ph.trim()) return norm(ph);
    }
    if (tag === 'IMG' || tag === 'AREA') { var alt = attr(el, 'alt'); if (alt && alt.trim()) return norm(alt); }
    if (tag === 'FIELDSET') { var legend = el.querySelector('legend'); if (legend) return norm(legend.textContent); }
    if (tag === 'TABLE') { var cap = el.querySelector('caption'); if (cap) return norm(cap.textContent); }
    var role = roleOf(el);
    if (NAME_FROM_CONTENT[role] || tag === 'SUMMARY' || tag === 'LABEL' || tag === 'LEGEND') {
      var text = norm(el.innerText || el.textContent || '');
      if (text) return text;
    }
    var title = attr(el, 'title');
    if (title && title.trim()) return norm(title);
    return '';
  }
  function checkedState(el) {
    var tag = el.tagName, type = String(el.type || '').toLowerCase();
    if (tag === 'INPUT' && (type === 'checkbox' || type === 'radio')) return el.indeterminate ? 'mixed' : !!el.checked;
    var aria = attr(el, 'aria-checked');
    if (aria === 'true') return true;
    if (aria === 'mixed') return 'mixed';
    if (aria === 'false') return false;
    return null;
  }
  function isDisabled(el) {
    try { if (typeof el.matches === 'function' && el.matches(':disabled')) return true; } catch (e) {}
    for (var n = el; n; n = parentOf(n)) { if (attr(n, 'aria-disabled') === 'true') return true; }
    return false;
  }
  function parseRole(spec) {
    var m = /^([A-Za-z][\w-]*)([\s\S]*)${'$'}/.exec(String(spec).trim());
    if (!m) throw new Error('Invalid selector: role=' + spec);
    var out = {role: m[1].toLowerCase().replace(/_/g, '-'), attrs: {}}, rest = m[2];
    var re = /^\s*\[\s*([A-Za-z-]+)\s*(?:=\s*("(?:[^"\\]|\\.)*"|\/(?:[^\/\\]|\\.)+\/[dimsuy]*|[^\]\s]+)\s*([is])?\s*)?\]/;
    while (rest.trim()) {
      var a = re.exec(rest);
      if (!a) throw new Error('Invalid selector: cannot parse role attributes in "role=' + spec + '"');
      out.attrs[a[1].toLowerCase()] = {value: a[2] === undefined ? 'true' : a[2], flag: a[3]};
      rest = rest.slice(a[0].length);
    }
    return out;
  }
  function boolAttr(v) { return v === 'true' ? true : v === 'false' ? false : v; }
  function byRole(root, spec) {
    var parsed = parseRole(spec), wanted = parsed.role, attrs = parsed.attrs;
    var includeHidden = attrs['include-hidden'] && attrs['include-hidden'].value !== 'false';
    var name = attrs.name ? (function(a) {
      var v = a.value;
      if (v.charAt(0) === '"') return a.flag ? matcher(v, a.flag === 'i' ? 'i' : 's') : matcher(norm(unquote(v)));
      return matcher(v);
    })(attrs.name) : null;
    return allElements(root.nodeType === 9 ? root.documentElement : root).filter(function(el) {
      var role = roleOf(el);
      var ok = role === wanted || (wanted === 'input' && (/^(textbox|searchbox|combobox|spinbutton|listbox)${'$'}/.test(role) || /^(INPUT|TEXTAREA|SELECT)${'$'}/.test(el.tagName)));
      if (!ok) return false;
      if (!includeHidden && isHiddenForAT(el)) return false;
      if (name && !name(nameOf(el))) return false;
      if (attrs.checked && String(checkedState(el)) !== String(boolAttr(attrs.checked.value))) return false;
      if (attrs.disabled && isDisabled(el) !== (attrs.disabled.value !== 'false')) return false;
      if (attrs.expanded && String(attr(el, 'aria-expanded') === 'true' || !!el.open) !== String(attrs.expanded.value !== 'false')) return false;
      if (attrs.selected && String(attr(el, 'aria-selected') === 'true' || !!el.selected) !== String(attrs.selected.value !== 'false')) return false;
      if (attrs.pressed && String(attr(el, 'aria-pressed')) !== String(attrs.pressed.value)) return false;
      if (attrs.level) { var lv = /^H([1-6])${'$'}/.exec(el.tagName); var level = lv ? +lv[1] : +(attr(el, 'aria-level') || 0); if (String(level) !== String(attrs.level.value)) return false; }
      return true;
    });
  }
  function byAttribute(root, attribute, test) {
    return allElements(root.nodeType === 9 ? root.documentElement : root).filter(function(el) { var v = attr(el, attribute); return v != null && test(v); });
  }
  function byLabel(root, spec) {
    var test = matcher(spec), out = [], base = root.nodeType === 9 ? root.documentElement : root;
    var els = allElements(base);
    els.forEach(function(el) {
      if (el.tagName === 'LABEL' && test(el.innerText || el.textContent || '')) {
        var control = el.control || (attr(el, 'for') ? (el.ownerDocument || doc).getElementById(attr(el, 'for')) : null);
        if (control) out.push(control);
      }
    });
    els.forEach(function(el) {
      var aria = attr(el, 'aria-label');
      if (aria != null && test(aria)) out.push(el);
      else if (attr(el, 'aria-labelledby') && test(nameOf(el))) out.push(el);
    });
    return unique(out);
  }
  var refs = new MapC(), ids = new WM(), counter = {next: 0};
  function refElement(ref) {
    var held = refs.get(ref);
    var el = held && (typeof held.deref === 'function' ? held.deref() : held);
    return el && el.isConnected ? el : null;
  }
  function queryAll(selector, root) {
    var base = root || doc;
    selector = String(selector).trim();
    var m = /^(ref|role|label|text|exact_text|placeholder|alt|title|testid|xpath|css)[=:]([\s\S]*)${'$'}/.exec(selector);
    var engine = m ? m[1] : 'css', body = m ? m[2] : selector;
    if (m && selector.charAt(m[1].length) === ':' && engine !== 'text') { engine = 'css'; body = selector; }
    switch (engine) {
      case 'ref':
        // Refs never auto-wait: an unknown or detached ref is an error, not an empty match.
        var refKey = body.charAt(0) === '@' ? body : '@' + body;
        var target = refElement(refKey);
        if (!target) throw new Error(refs.get(refKey) === undefined
          ? 'Unknown ref ' + refKey + ' \u2014 run snapshot again'
          : 'Ref ' + refKey + ' is no longer attached \u2014 run snapshot again');
        return within(base, target) ? [target] : [];
      case 'role': return byRole(base, body);
      case 'label': return byLabel(base, body);
      case 'text': return byText(base, matcher(body));
      case 'exact_text': return byText(base, matcher('"' + body.replace(/["\\]/g, '\\${'$'}&') + '"'));
      case 'placeholder': return byAttribute(base, 'placeholder', matcher(body));
      case 'title': return byAttribute(base, 'title', matcher(body));
      case 'testid': return byAttribute(base, 'data-testid', regexOf(body) ? matcher(body) : function(v) { return v === body; });
      case 'alt':
        var altTest = matcher(body);
        return byAttribute(base, 'alt', altTest).filter(function(el) { return el.tagName === 'IMG' || el.tagName === 'AREA' || el.tagName === 'INPUT'; })
          .concat(byAttribute(base, 'aria-label', altTest).filter(function(el) { return (attr(el, 'role') || '').toLowerCase() === 'img'; }));
      case 'xpath':
        var owner = base.nodeType === 9 ? base : (base.ownerDocument || doc), snap;
        try { snap = owner.evaluate(body, base, null, 7, null); }
        catch (e) { throw new Error('Invalid selector "xpath=' + body + '": ' + (e && e.message || e)); }
        var arr = [];
        for (var i = 0; i < snap.snapshotLength; i++) { var n = snap.snapshotItem(i); if (n && n.nodeType === 1) arr.push(n); }
        return arr;
      default:
        var found = qsa(base, body);
        shadowRoots(base.nodeType === 9 ? base.documentElement : base).forEach(function(sr) { found = found.concat(qsa(sr, body)); });
        return unique(found);
    }
  }
  // parts: selector strings and filter objects; root (optional) scopes the query to an element's descendants.
  function resolveAll(parts, root) {
    var current = null;
    for (var i = 0; i < parts.length; i++) {
      var step = parts[i];
      if (typeof step !== 'string') {
        current = (current || []).filter(function(el) {
          var text = textOf(el);
          if (step.hasText != null && !matcher(step.hasText)(text)) return false;
          if (step.hasNotText != null && matcher(step.hasNotText)(text)) return false;
          function descendants(selector) {
            if (typeof selector !== 'string') return resolveAll(selector, el).length > 0;
            var result = [el];
            String(selector).split('>>').forEach(function(part) {
              var next = [];
              result.forEach(function(r) { next = next.concat(queryAll(part.trim(), r).filter(function(x) { return x !== r; })); });
              result = next;
            });
            return result.length > 0;
          }
          if (step.has != null && !descendants(step.has)) return false;
          if (step.hasNot != null && descendants(step.hasNot)) return false;
          return true;
        });
        if (step.and != null) { var both = resolveAll(step.and); current = current.filter(function(x) { return both.indexOf(x) !== -1; }); }
        if (step.or != null) {
          current = unique(current.concat(resolveAll(step.or)));
          current.sort(function(a, b) { return a === b ? 0 : (a.compareDocumentPosition(b) & 4 ? -1 : 1); });
        }
        if (step.nth != null) {
          var index = step.nth < 0 ? current.length + step.nth : step.nth;
          current = current[index] ? [current[index]] : [];
        }
        if (!current.length) return [];
        continue;
      }
      var next = [];
      var bases = current || [root || doc];
      for (var j = 0; j < bases.length; j++) next = next.concat(queryAll(step, bases[j]).filter(function(x) { return x !== bases[j]; }));
      current = unique(next);
      if (!current.length) return [];
    }
    return current || [];
  }
  function describe(el) {
    if (!el || el.nodeType !== 1) return String(el);
    var tag = el.tagName.toLowerCase(), out = '<' + tag, attrs = el.attributes || [], shown = 0;
    for (var i = 0; i < attrs.length && shown < 5; i++) {
      var a = attrs[i];
      if (a.name === 'style') continue;
      out += ' ' + a.name + (a.value ? '="' + String(a.value).slice(0, 40) + '"' : '');
      shown++;
    }
    var text = norm(textOf(el)).slice(0, 50);
    return (out + '>' + text + '</' + tag + '>').slice(0, 200);
  }
  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    var r = rect(el);
    if (!(r.width > 0 && r.height > 0)) return false;
    var s = style(el);
    return s.visibility !== 'hidden' && s.visibility !== 'collapse';
  }
  var NOT_FILLABLE = {checkbox: 1, radio: 1, file: 1, button: 1, submit: 1, reset: 1, image: 1, hidden: 1, range: 1, color: 1};
  // returns null when editable, {fatal:true,..} when the element type can never be edited
  function editability(el) {
    var tag = el.tagName;
    if (tag === 'INPUT') {
      var type = String(el.type || 'text').toLowerCase();
      if (NOT_FILLABLE[type]) return {fatal: true, reason: 'Input of type "' + type + '" cannot be filled'};
    } else if (tag !== 'TEXTAREA' && tag !== 'SELECT' && !el.isContentEditable) {
      return {fatal: true, reason: 'Element is not an <input>, <textarea>, <select> or [contenteditable] element'};
    }
    if (el.readOnly || attr(el, 'aria-readonly') === 'true') return {fatal: false, reason: 'Element is read-only'};
    return null;
  }
  function inViewport(r) { return r.top >= 0 && r.left >= 0 && r.bottom <= win.innerHeight && r.right <= win.innerWidth; }
  function scrollIntoViewIfNeeded(el, block, inline) {
    var r = rect(el);
    if (!inViewport(r)) {
      try { el.scrollIntoView({block: block || 'center', inline: inline || 'center', behavior: 'instant'}); }
      catch (e) { el.scrollIntoView(true); }
    }
  }
  function hitTarget(el, x, y) {
    var root = el.getRootNode ? el.getRootNode() : doc;
    var hit = root.elementFromPoint ? root.elementFromPoint(x, y) : doc.elementFromPoint(x, y);
    if (!hit) return {ok: false, hit: 'nothing (point is outside the viewport)'};
    if (hit === el || contains.call(el, hit)) return {ok: true, target: hit};
    var label = hit.closest ? hit.closest('label') : null;
    if (label && label.control === el) return {ok: true, target: hit};
    for (var host = el; host && host.getRootNode && host.getRootNode().host; ) { host = host.getRootNode().host; if (hit === host) return {ok: true, target: el}; }
    return {ok: false, hit: describe(hit)};
  }
  function targetAt(el, x, y) {
    var root = el.getRootNode ? el.getRootNode() : doc;
    var hit = root.elementFromPoint ? root.elementFromPoint(x, y) : null;
    return hit && (hit === el || contains.call(el, hit)) ? hit : el;
  }
  function probe(el, o) {
    if (!el.isConnected) return {state: 'detached'};
    var d = describe(el);
    if (o.visible && !isVisible(el)) return {state: 'notvisible', d: d};
    if (o.enabled && isDisabled(el)) return {state: 'disabled', d: d};
    if (o.editable) { var ed = editability(el); if (ed && (ed.fatal || !o.force)) return {state: ed.fatal ? 'fatal' : 'noteditable', d: d, reason: ed.reason}; }
    if (o.scroll) scrollIntoViewIfNeeded(el);
    var r = rect(el), box = {x: r.left, y: r.top, w: r.width, h: r.height};
    if (o.stable) {
      var p = o.prev;
      if (!p || Math.abs(p.x - box.x) > 0.5 || Math.abs(p.y - box.y) > 0.5 || Math.abs(p.w - box.w) > 0.5 || Math.abs(p.h - box.h) > 0.5)
        return {state: 'unstable', d: d, rect: box};
    }
    var x, y;
    if (o.position) { x = r.left + o.position.x; y = r.top + o.position.y; }
    else {
      var l = Math.max(0, r.left), t = Math.max(0, r.top), rr = Math.min(win.innerWidth, r.right), b = Math.min(win.innerHeight, r.bottom);
      x = rr > l ? (l + rr) / 2 : r.left + r.width / 2; y = b > t ? (t + b) / 2 : r.top + r.height / 2;
    }
    if (o.hit) {
      var h = hitTarget(el, x, y);
      if (!h.ok) return {state: 'intercepted', d: d, hit: h.hit, rect: box};
    }
    return {state: 'ok', d: d, rect: box, x: x, y: y};
  }

  // ---- synthetic input ----
  var hover = {el: null};
  function view() { return win; }
  function mouseInit(x, y, extra) {
    var init = {bubbles: true, cancelable: true, composed: true, view: view(), clientX: x, clientY: y, screenX: x, screenY: y,
      button: 0, buttons: 0, detail: 0, ctrlKey: false, shiftKey: false, altKey: false, metaKey: false};
    for (var k in extra) init[k] = extra[k];
    return init;
  }
  function fire(target, type, ctor, init) {
    var C = win[ctor] || win.MouseEvent, ev;
    try { ev = new C(type, init); } catch (e) { ev = new win.MouseEvent(type, init); }
    return dispatch.call(target, ev);
  }
  function mouse(target, type, x, y, extra) { return fire(target, type, 'MouseEvent', mouseInit(x, y, extra || {})); }
  function pointer(target, type, x, y, extra) {
    if (typeof win.PointerEvent !== 'function') return true;
    var init = mouseInit(x, y, extra || {});
    init.pointerId = init.pointerType === 'touch' ? 2 : 1; init.pointerType = (extra && extra.pointerType) || 'mouse';
    init.isPrimary = true; init.width = 1; init.height = 1; init.pressure = init.buttons ? 0.5 : 0;
    return fire(target, type, 'PointerEvent', init);
  }
  function moveTo(t, x, y, extra) {
    extra = extra || {};
    var prev = hover.el;
    if (prev !== t) {
      if (prev && prev.isConnected) {
        pointer(prev, 'pointerout', x, y, extra); mouse(prev, 'mouseout', x, y, extra);
        var leave = mouseInit(x, y, extra); leave.bubbles = false;
        if (!contains.call(prev, t)) { fire(prev, 'pointerleave', 'PointerEvent', leave); fire(prev, 'mouseleave', 'MouseEvent', leave); }
      }
      // Pointer Events order: pointerover, pointerenter, mouseover, mouseenter.
      var enter = mouseInit(x, y, extra); enter.bubbles = false;
      var entering = !prev || !contains.call(t, prev);
      pointer(t, 'pointerover', x, y, extra);
      if (entering && typeof win.PointerEvent === 'function') fire(t, 'pointerenter', 'PointerEvent', enter);
      mouse(t, 'mouseover', x, y, extra);
      if (entering) fire(t, 'mouseenter', 'MouseEvent', enter);
      hover.el = t;
    }
    pointer(t, 'pointermove', x, y, extra); mouse(t, 'mousemove', x, y, extra);
  }
  function focusable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (isDisabled(el) && /^(BUTTON|INPUT|SELECT|TEXTAREA|OPTION|FIELDSET)${'$'}/.test(el.tagName)) return false;
    return el.tabIndex >= 0 || has(el, 'tabindex') || el.isContentEditable;
  }
  function focusFor(t) {
    var f = t;
    while (f && !focusable(f)) f = parentOf(f);
    var active = (t.getRootNode && t.getRootNode().activeElement) || doc.activeElement;
    if (f) { if (active !== f) { try { f.focus({preventScroll: true}); } catch (e) { f.focus(); } } }
    else if (active && active !== doc.body && active.blur) active.blur();
  }
  function modifiers(m) { m = m || {}; return {ctrlKey: !!m.ctrl, shiftKey: !!m.shift, altKey: !!m.alt, metaKey: !!m.meta}; }
  // A pressed (not yet released) synthetic mouse button, so a delayed click can span evaluations.
  var pendingClick = null;
  function clickDown(el, x, y, a, i) {
    var t = pendingClick && pendingClick.el === el && pendingClick.t.isConnected ? pendingClick.t : targetAt(el, x, y);
    var btn = a.button || 0, mask = btn === 0 ? 1 : btn === 1 ? 4 : 2, mods = modifiers(a.modifiers);
    var ex = function(o) { for (var k in mods) o[k] = mods[k]; return o; };
    if (i === 1) moveTo(t, x, y, mods);
    var pd = pointer(t, 'pointerdown', x, y, ex({button: btn, buttons: mask}));
    var md = pd ? mouse(t, 'mousedown', x, y, ex({button: btn, buttons: mask, detail: i})) : true;
    if (md && i === 1 && btn === 0) focusFor(t);
    if (btn === 2) mouse(t, 'contextmenu', x, y, ex({button: 2, buttons: mask, detail: i}));
    pendingClick = {el: el, t: t, pd: pd};
    return true;
  }
  function clickUp(el, x, y, a, i) {
    var p = pendingClick && pendingClick.el === el ? pendingClick : {t: targetAt(el, x, y), pd: true};
    var t = p.t, btn = a.button || 0, mods = modifiers(a.modifiers);
    var ex = function(o) { for (var k in mods) o[k] = mods[k]; return o; };
    pendingClick = {el: el, t: t, pd: true};
    pointer(t, 'pointerup', x, y, ex({button: btn, buttons: 0}));
    if (p.pd) mouse(t, 'mouseup', x, y, ex({button: btn, buttons: 0, detail: i}));
    if (!t.isConnected) { pendingClick = null; return false; }
    if (btn === 0) mouse(t, 'click', x, y, ex({button: 0, detail: i}));
    else fire(t, 'auxclick', 'PointerEvent', mouseInit(x, y, ex({button: btn, detail: i})));
    if (i === 2 && btn === 0) mouse(t, 'dblclick', x, y, ex({button: 0, detail: 2}));
    return true;
  }
  function clickSeq(el, x, y, a) {
    var count = a.clickCount || 1;
    pendingClick = null;
    for (var i = 1; i <= count; i++) {
      clickDown(el, x, y, a, i);
      if (!clickUp(el, x, y, a, i)) break;
    }
    pendingClick = null;
    return true;
  }
  function touchEvent(t, type, x, y) {
    if (typeof win.TouchEvent !== 'function' || typeof win.Touch !== 'function') return true;
    try {
      var point = new win.Touch({identifier: 1, target: t, clientX: x, clientY: y, screenX: x, screenY: y, pageX: x + win.scrollX, pageY: y + win.scrollY, radiusX: 1, radiusY: 1, force: 1});
      var active = type === 'touchend' ? [] : [point];
      return dispatch.call(t, new win.TouchEvent(type, {bubbles: true, cancelable: true, composed: true, view: win, touches: active, targetTouches: active, changedTouches: [point]}));
    } catch (e) { return true; }
  }
  function tapSeq(el, x, y) {
    var t = targetAt(el, x, y), touch = {pointerType: 'touch'};
    pointer(t, 'pointerover', x, y, touch);
    pointer(t, 'pointerdown', x, y, {pointerType: 'touch', buttons: 1});
    var started = touchEvent(t, 'touchstart', x, y);
    pointer(t, 'pointerup', x, y, touch);
    var ended = touchEvent(t, 'touchend', x, y);
    pointer(t, 'pointerout', x, y, touch);
    if (started && ended && t.isConnected) {
      mouse(t, 'mouseover', x, y); mouse(t, 'mousemove', x, y);
      if (mouse(t, 'mousedown', x, y, {buttons: 1, detail: 1})) focusFor(t);
      mouse(t, 'mouseup', x, y, {detail: 1});
      mouse(t, 'click', x, y, {detail: 1});
    }
    return true;
  }
  function inputEvent(el, type, inputType, data, cancelable) {
    var init = {bubbles: true, cancelable: !!cancelable, composed: true, inputType: inputType, data: data == null ? null : data};
    var ev;
    try { ev = new win.InputEvent(type, init); } catch (e) { ev = new win.Event(type, init); }
    return dispatch.call(el, ev);
  }
  function plain(el, type) { return dispatch.call(el, new win.Event(type, {bubbles: true})); }
  $NATIVE_VALUE_SETTER_JS
  // Setters captured at install time win over later prototype tampering; the shared helper covers frames.
  function setValue(el, value) {
    var setter = el.tagName === 'TEXTAREA' ? setTextAreaValue : el.tagName === 'SELECT' ? setSelectValue : setInputValue;
    if (setter && el.ownerDocument === doc) setter.call(el, value); else kmpwrightSetNativeValue(el, value);
  }
  function focusEl(el) {
    var active = (el.getRootNode && el.getRootNode().activeElement) || doc.activeElement;
    if (active !== el && !(el.isContentEditable && active && contains.call(el, active))) { try { el.focus({preventScroll: true}); } catch (e) { el.focus(); } }
  }
  function selectAllIn(el) {
    if (el.isContentEditable) {
      var range = doc.createRange(); range.selectNodeContents(el);
      var sel = win.getSelection(); sel.removeAllRanges(); sel.addRange(range);
    } else if (typeof el.select === 'function') { try { el.select(); } catch (e) {} }
  }
  var DATE_TYPES = {date: 1, time: 1, 'datetime-local': 1, month: 1, week: 1, color: 1, range: 1};
  function fillSeq(el, value) {
    focusEl(el);
    if (el.isContentEditable) {
      selectAllIn(el);
      var done = false;
      try { done = value === '' ? doc.execCommand('delete', false, null) : doc.execCommand('insertText', false, value); } catch (e) {}
      if (!done || norm(el.textContent) !== norm(value)) {
        if (inputEvent(el, 'beforeinput', 'insertReplacementText', value, true)) { el.textContent = value; inputEvent(el, 'input', 'insertReplacementText', value, false); }
      }
      return true;
    }
    if (el.tagName === 'SELECT') {
      var options = slice.call(el.options), match = null;
      for (var i = 0; i < options.length && !match; i++) if (options[i].value === value) match = options[i];
      for (var j = 0; j < options.length && !match; j++) if (norm(options[j].label) === norm(value)) match = options[j];
      if (!match) throw new Error('No <option> with value or label "' + value + '"');
      setValue(el, match.value);
      plain(el, 'input'); plain(el, 'change');
      return true;
    }
    var type = String(el.type || '').toLowerCase();
    if (type === 'number' && value !== '' && isNaN(Number(value))) throw new Error('Cannot type text into input[type=number]');
    selectAllIn(el);
    var inputType = value === '' ? 'deleteContentBackward' : 'insertText';
    if (!DATE_TYPES[type] && !inputEvent(el, 'beforeinput', inputType, value || null, true)) return true;
    setValue(el, value);
    if (DATE_TYPES[type] && value !== '' && el.value !== value) throw new Error('Malformed value "' + value + '" for input[type=' + type + ']');
    inputEvent(el, 'input', inputType, value || null, false);
    plain(el, 'change');
    return true;
  }
  function selectionOf(el) {
    try { if (typeof el.selectionStart === 'number') return [el.selectionStart, el.selectionEnd]; } catch (e) {}
    var len = String(el.value || '').length; return [len, len];
  }
  function setCaret(el, pos) { try { el.setSelectionRange(pos, pos); } catch (e) {} }
  function insertText(el, text) {
    if (el.isContentEditable) {
      var ok = false;
      try { ok = doc.execCommand('insertText', false, text); } catch (e) {}
      if (!ok && inputEvent(el, 'beforeinput', 'insertText', text, true)) {
        var sel = win.getSelection();
        if (sel && sel.rangeCount) { var r = sel.getRangeAt(0); r.deleteContents(); r.insertNode(doc.createTextNode(text)); r.collapse(false); }
        else el.appendChild(doc.createTextNode(text));
        inputEvent(el, 'input', 'insertText', text, false);
      }
      return;
    }
    if (!('value' in el) || el.readOnly || isDisabled(el)) return;
    if (!inputEvent(el, 'beforeinput', 'insertText', text, true)) return;
    var v = String(el.value || ''), s = selectionOf(el), next = v.slice(0, s[0]) + text + v.slice(s[1]);
    if (el.maxLength >= 0 && next.length > el.maxLength) return;
    setValue(el, next);
    setCaret(el, s[0] + text.length);
    inputEvent(el, 'input', 'insertText', text, false);
  }
  function deleteText(el, forward) {
    if (el.isContentEditable) { try { doc.execCommand(forward ? 'forwardDelete' : 'delete', false, null); } catch (e) {} return; }
    if (!('value' in el) || el.readOnly) return;
    var v = String(el.value || ''), s = selectionOf(el), a = s[0], b = s[1];
    if (a === b) { if (forward) b = Math.min(v.length, b + 1); else a = Math.max(0, a - 1); }
    if (a === b) return;
    var inputType = forward ? 'deleteContentForward' : 'deleteContentBackward';
    if (!inputEvent(el, 'beforeinput', inputType, null, true)) return;
    setValue(el, v.slice(0, a) + v.slice(b)); setCaret(el, a);
    inputEvent(el, 'input', inputType, null, false);
  }
  function keyEvent(t, type, d, m) {
    var init = {key: d.key, code: d.code, keyCode: d.keyCode, which: d.keyCode, charCode: type === 'keypress' ? d.charCode : 0,
      bubbles: true, cancelable: true, composed: true, view: win, ctrlKey: !!m.ctrl, shiftKey: !!m.shift, altKey: !!m.alt, metaKey: !!m.meta};
    var ev = new win.KeyboardEvent(type, init);
    try {
      if (ev.keyCode !== init.keyCode) O.defineProperty(ev, 'keyCode', {get: function() { return init.keyCode; }});
      if (ev.which !== init.which) O.defineProperty(ev, 'which', {get: function() { return init.which; }});
      if (type === 'keypress' && ev.charCode !== init.charCode) O.defineProperty(ev, 'charCode', {get: function() { return init.charCode; }});
    } catch (e) {}
    return dispatch.call(t, ev);
  }
  function tabbables(root) {
    return allElements(root.documentElement || root).filter(function(el) {
      return focusable(el) && el.tabIndex >= 0 && isVisible(el) && !(el.tagName === 'INPUT' && el.type === 'hidden');
    }).map(function(el, i) { return {el: el, i: i}; }).sort(function(a, b) {
      var ta = a.el.tabIndex, tb = b.el.tabIndex;
      if (ta > 0 && tb > 0 && ta !== tb) return ta - tb;
      if (ta > 0 && tb === 0) return -1;
      if (tb > 0 && ta === 0) return 1;
      return a.i - b.i;
    }).map(function(x) { return x.el; });
  }
  function submitForm(form) {
    var buttons = slice.call(form.elements || []).filter(function(b) {
      return (b.tagName === 'BUTTON' && (b.type || 'submit').toLowerCase() === 'submit') || (b.tagName === 'INPUT' && /^(submit|image)${'$'}/i.test(b.type));
    });
    var submitter = buttons[0];
    if (submitter) { if (!isDisabled(submitter)) submitter.click(); return; }
    if (typeof form.requestSubmit === 'function') form.requestSubmit();
    else if (dispatch.call(form, new win.Event('submit', {bubbles: true, cancelable: true}))) form.submit();
  }
  function defaultKeyAction(t, d, m) {
    var key = d.key, tag = t.tagName, isText = tag === 'TEXTAREA' || t.isContentEditable || (tag === 'INPUT' && !NOT_FILLABLE[String(t.type || 'text').toLowerCase()]);
    if ((m.ctrl || m.meta) && !m.alt && key.toLowerCase() === 'a') { selectAllIn(t); return; }
    if (m.ctrl || m.meta || m.alt) return;
    if (key === 'Enter') {
      if (tag === 'TEXTAREA') insertText(t, '\n');
      else if (t.isContentEditable) { try { doc.execCommand('insertParagraph', false, null); } catch (e) {} }
      else if (tag === 'INPUT' && t.form && isText) submitForm(t.form);
      else if (tag === 'BUTTON' || (tag === 'A' && has(t, 'href')) || tag === 'SUMMARY' || (tag === 'INPUT' && /^(submit|button|reset|image)${'$'}/i.test(t.type))) t.click();
      return;
    }
    if (key === 'Backspace') { if (isText) deleteText(t, false); return; }
    if (key === 'Delete') { if (isText) deleteText(t, true); return; }
    if (key === 'Tab') {
      var list = tabbables(t.ownerDocument || doc), index = list.indexOf(t);
      var next = list[m.shift ? (index <= 0 ? list.length - 1 : index - 1) : (index + 1) % Math.max(1, list.length)];
      if (next) { try { next.focus({preventScroll: false}); } catch (e) { next.focus(); } }
      return;
    }
    if (isText && !t.isContentEditable && (key === 'ArrowLeft' || key === 'ArrowRight' || key === 'Home' || key === 'End')) {
      var s = selectionOf(t), len = String(t.value || '').length;
      setCaret(t, key === 'Home' ? 0 : key === 'End' ? len : key === 'ArrowLeft' ? Math.max(0, s[0] - (s[0] === s[1] ? 1 : 0)) : Math.min(len, s[1] + (s[0] === s[1] ? 1 : 0)));
    }
  }
  function modifierDescriptor(name) { return {key: name, code: name === 'Meta' ? 'MetaLeft' : name + 'Left', keyCode: {Shift: 16, Control: 17, Alt: 18, Meta: 91}[name] || 0, charCode: 0}; }
  // A pressed (not yet released) synthetic key, so a delayed press can span evaluations.
  var pendingPress = null;
  function pressSeq(el, d, m, held) {
    pressDown(el, d, m, held);
    return pressUp(el, d, m);
  }
  function deepActive(root) {
    var a = root.activeElement;
    while (a) {
      if (a.shadowRoot && a.shadowRoot.activeElement) { a = a.shadowRoot.activeElement; continue; }
      if (/^I?FRAME${'$'}/.test(a.tagName)) {
        var cd = null;
        try { cd = a.contentDocument; } catch (e) {}
        if (cd && cd.activeElement) { a = cd.activeElement; continue; }
      }
      break;
    }
    return a || root.body || root.documentElement;
  }
  function pressDown(el, d, m, already) {
    focusEl(el);
    var t = (el.getRootNode && el.getRootNode().activeElement) || doc.activeElement || el;
    if (t === doc.body && el !== doc.body) t = el;
    var mods = [], held = {}, skip = already || [];
    if (m.ctrl) mods.push('Control'); if (m.shift) mods.push('Shift'); if (m.alt) mods.push('Alt'); if (m.meta) mods.push('Meta');
    mods = mods.filter(function(name) { if (skip.indexOf(name) === -1) return true; held[{Control: 'ctrl', Shift: 'shift', Alt: 'alt', Meta: 'meta'}[name]] = true; return false; });
    mods.forEach(function(name) { held[{Control: 'ctrl', Shift: 'shift', Alt: 'alt', Meta: 'meta'}[name]] = true; keyEvent(t, 'keydown', modifierDescriptor(name), held); });
    var down = keyEvent(t, 'keydown', d, m);
    pendingPress = {t: t, down: down, mods: mods, held: held};
    if (down) {
      var printable = d.text != null && d.text !== '' && !m.ctrl && !m.meta && !m.alt;
      if (printable || d.key === 'Enter') {
        if (keyEvent(t, 'keypress', d, m)) {
          if (printable && !(d.text === ' ' && /^(BUTTON|SUMMARY)${'$'}|^INPUT${'$'}/.test(t.tagName) && (t.tagName !== 'INPUT' || /^(checkbox|radio|button|submit|reset)${'$'}/i.test(t.type)))) insertText(t, d.text);
          else if (d.key === 'Enter') defaultKeyAction(t, d, m);
        }
      } else defaultKeyAction(t, d, m);
    }
    return true;
  }
  function pressUp(el, d, m) {
    var p = pendingPress || {t: el, down: true, mods: [], held: {}};
    pendingPress = null;
    var t = p.t, down = p.down, mods = p.mods, held = p.held;
    var up = keyEvent(t, 'keyup', d, m);
    if (down && up && d.key === ' ' && (t.tagName === 'BUTTON' || t.tagName === 'SUMMARY' || (t.tagName === 'INPUT' && /^(checkbox|radio|button|submit|reset)${'$'}/i.test(t.type)) || /^(button|checkbox|radio|switch)${'$'}/.test(attr(t, 'role') || ''))) t.click();
    mods.reverse().forEach(function(name) { held[{Control: 'ctrl', Shift: 'shift', Alt: 'alt', Meta: 'meta'}[name]] = false; keyEvent(t, 'keyup', modifierDescriptor(name), held); });
    return true;
  }
  function typeSeq(el, chars) {
    focusEl(el);
    for (var i = 0; i < chars.length; i++) {
      var t = (el.getRootNode && el.getRootNode().activeElement) || doc.activeElement || el;
      if (t === doc.body) t = el;
      var c = chars[i], d = c.d, m = {};
      var down = keyEvent(t, 'keydown', d, m);
      if (down && keyEvent(t, 'keypress', d, m)) {
        if (d.key === 'Enter') defaultKeyAction(t, d, m);
        else insertText(t, c.text);
      }
      keyEvent(t, 'keyup', d, m);
    }
    return true;
  }
  function draggableOf(t) {
    for (var n = t; n; n = parentOf(n)) { if (n.draggable === true) return n; if (attr(n, 'draggable') === 'false') return null; }
    return null;
  }
  function dragEvent(t, type, dt, x, y) {
    var init = mouseInit(x, y, {buttons: 1}); init.dataTransfer = dt;
    var ev;
    try { ev = new win.DragEvent(type, init); }
    catch (e) { ev = new win.MouseEvent(type, init); try { O.defineProperty(ev, 'dataTransfer', {value: dt}); } catch (_) {} }
    return dispatch.call(t, ev);
  }
  function dragSeq(src, tgt, sp, tp, steps) {
    var s = targetAt(src, sp.x, sp.y);
    moveTo(s, sp.x, sp.y);
    var pd = pointer(s, 'pointerdown', sp.x, sp.y, {buttons: 1});
    if (pd && mouse(s, 'mousedown', sp.x, sp.y, {buttons: 1, detail: 1})) focusFor(s);
    var draggable = draggableOf(s);
    function at(i) { return {x: sp.x + (tp.x - sp.x) * i / steps, y: sp.y + (tp.y - sp.y) * i / steps}; }
    if (draggable && typeof win.DataTransfer === 'function') {
      var dt = new win.DataTransfer();
      if (dragEvent(draggable, 'dragstart', dt, sp.x, sp.y)) {
        var over = null, accepted = false;
        for (var i = 1; i <= steps; i++) {
          var p = at(i);
          var under = i === steps ? targetAt(tgt, tp.x, tp.y) : (doc.elementFromPoint(p.x, p.y) || tgt);
          dragEvent(draggable, 'drag', dt, p.x, p.y);
          if (under !== over) { if (over) dragEvent(over, 'dragleave', dt, p.x, p.y); dragEvent(under, 'dragenter', dt, p.x, p.y); over = under; }
          accepted = !dragEvent(under, 'dragover', dt, p.x, p.y);
        }
        if (accepted) dragEvent(over, 'drop', dt, tp.x, tp.y); else if (over) dragEvent(over, 'dragleave', dt, tp.x, tp.y);
        dragEvent(draggable, 'dragend', dt, tp.x, tp.y);
        pointer(s, 'pointercancel', tp.x, tp.y, {buttons: 0});
        return {mode: 'html5', dropped: accepted};
      }
    }
    var last = s;
    for (var j = 1; j <= steps; j++) {
      var q = at(j);
      var e = j === steps ? targetAt(tgt, tp.x, tp.y) : (doc.elementFromPoint(q.x, q.y) || tgt);
      moveTo(e, q.x, q.y, {buttons: 1});
      last = e;
    }
    pointer(last, 'pointerup', tp.x, tp.y, {buttons: 0});
    if (pd) mouse(last, 'mouseup', tp.x, tp.y, {buttons: 0, detail: 1});
    return {mode: 'mouse'};
  }
  function selectOptions(el, values) {
    if (el.tagName !== 'SELECT') throw new Error('Element is not a <select> element');
    if (!el.multiple && values.length > 1) throw new Error('Select does not allow multiple values');
    var all = slice.call(el.options), selected = [];
    for (var i = 0; i < values.length; i++) {
      var v = values[i];
      var option = all.filter(function(o) { return o.value === v; })[0] || all.filter(function(o) { return norm(o.label) === norm(v); })[0];
      if (!option) return {missing: v, available: all.map(function(o) { return o.value + ' (' + norm(o.label) + ')'; }).join(', ')};
      if (option.disabled || (option.parentElement && option.parentElement.tagName === 'OPTGROUP' && option.parentElement.disabled))
        throw new Error('Option is disabled: ' + v);
      selected.push(option);
    }
    all.forEach(function(o) { o.selected = selected.indexOf(o) !== -1; });
    if (!selected.length) el.selectedIndex = -1;
    plain(el, 'input'); plain(el, 'change');
    return true;
  }
  function perform(el, kind, a, p) {
    switch (kind) {
      case 'click': return clickSeq(el, p.x, p.y, a);
      case 'clickDown': clickDown(el, p.x, p.y, a, a.i || 1); return {x: p.x, y: p.y};
      case 'point': return {x: p.x, y: p.y};
      case 'clickUp': return clickUp(el, p.x, p.y, a, a.i || 1);
      case 'pressDown': return pressDown(el, a.key, a.modifiers || {}, a.held);
      case 'pressUp': return pressUp(el, a.key, a.modifiers || {});
      case 'tap': return tapSeq(el, p.x, p.y);
      case 'hover': moveTo(targetAt(el, p.x, p.y), p.x, p.y); return true;
      case 'fill': return fillSeq(el, a.value);
      case 'type': return typeSeq(el, a.chars);
      case 'press': return pressSeq(el, a.key, a.modifiers || {}, a.held);
      case 'focus': focusEl(el); return true;
      case 'noop': return true;
      case 'scroll': try { el.scrollIntoView({block: a.block, inline: a.inline, behavior: 'instant'}); } catch (e) { el.scrollIntoView(); } return true;
      case 'scrollIfNeeded':
        // Playwright semantics: an element that is already entirely in the viewport is not scrolled.
        if (inViewport(rect(el))) return false;
        try { el.scrollIntoView({block: a.block, inline: a.inline, behavior: 'instant'}); } catch (e) { el.scrollIntoView(); } return true;
      case 'select': return selectOptions(el, a.values);
      case 'fileInput':
        if (el.tagName !== 'INPUT') return {error: 'Node is not an HTMLInputElement'};
        if (String(el.type).toLowerCase() !== 'file') return {error: 'Not an input[type=file] element'};
        if (a.count > 1 && !el.multiple) return {error: 'Non-multiple file input can only accept single file'};
        return true;
      case 'setChecked':
        if (setChecked && el.tagName === 'INPUT') { setChecked.call(el, !!a.checked); plain(el, 'input'); plain(el, 'change'); }
        return true;
    }
    throw new Error('Unknown action ' + kind);
  }

  var H = {
    version: VERSION,
    sealed: true,
    enc: function(v) { return stringify(v); },
    norm: norm,
    queryAll: queryAll,
    resolveAll: resolveAll,
    resolveFirst: function(parts) { return resolveAll(parts)[0] || null; },
    roleOf: roleOf,
    nameOf: nameOf,
    textOf: textOf,
    describe: describe,
    isVisible: isVisible,
    isDisabled: isDisabled,
    isHiddenForAT: isHiddenForAT,
    checkedState: checkedState,
    editability: editability,
    probe: probe,
    perform: perform,
    activeElement: function() { return deepActive(doc); },
    drag: function(src, tgt, sp, tp, steps) { return dragSeq(src, tgt, sp, tp, steps || 8); },
    refElement: refElement,
    // Privileged: only scripts that know SEAL may read or rewrite the ref registry.
    snapshotIds: function(seal) { if (seal !== SEAL) throw new Error('Forbidden'); return {ids: ids, counter: counter}; },
    // Privileged: browser accessibility metadata. 'reset' clears it, 'attach' binds AX data to a node,
    // 'tree' stores the AX hierarchy, 'get' returns read accessors (or null when no tree is stored).
    nativeAX: function(seal, op, a, b) {
      if (seal !== SEAL) throw new Error('Forbidden');
      if (op === 'reset') { axMeta = new WM(); axNodes = new MapC(); axTree = null; return true; }
      if (op === 'attach') {
        if (!a || typeof a.nodeType !== 'number' || !b) return false;
        var data = O.freeze({id: b.id, ignored: !!b.ignored, role: b.role == null ? 'generic' : String(b.role),
          name: b.name == null ? '' : String(b.name), states: O.freeze(slice.call(b.states || []).map(String))});
        mapSet.call(axNodes, data.id, typeof WR === 'function' ? new WR(a) : a);
        if (a.nodeType === 1 && !data.ignored) wmSet.call(axMeta, a, data);
        return true;
      }
      if (op === 'tree') { axTree = a || null; return true; }
      if (op === 'get') return axTree ? O.freeze({tree: axTree, node: nativeNode, meta: nativeMeta}) : null;
      throw new Error('Unknown nativeAX operation ' + op);
    },
    commitSnapshot: function(seal, nodes) {
      if (seal !== SEAL) throw new Error('Forbidden');
      refs = new MapC();
      for (var k in nodes) { if (O.prototype.hasOwnProperty.call(nodes, k) && /^@e[0-9]+${'$'}/.test(k)) refs.set(k, typeof WR === 'function' ? new WR(nodes[k]) : nodes[k]); }
      return true;
    }
  };
  O.freeze(H);
  O.defineProperty(win, KEY, {value: H, writable: false, enumerable: false, configurable: false});
  return 'ok';
})()
    """.trimIndent()
}
