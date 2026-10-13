package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Options for [Page.snapshot], mirroring agent-browser's `snapshot` flags.
 *
 * @property interactive `-i`: only elements with refs (buttons, links, inputs, iframes and named
 *   headings/landmarks), as a flat list; refs nested in a ref (such as iframe content) are indented.
 * @property compact `-c`: drop structural nodes that contain no refs or values.
 * @property maxDepth `-d`: limit the tree depth.
 * @property selector `-s`: snapshot only the subtree of this selector.
 * @property cursor `-C`: also include elements that are only interactive through `cursor: pointer`/`onclick`.
 * @property includeHidden include elements hidden from assistive technology.
 * @property urls `-u`: print each link's resolved `href` as `url="..."`.
 */
@Serializable
public data class SnapshotOptions(
    val interactive: Boolean = false,
    val compact: Boolean = false,
    val maxDepth: Int? = null,
    val selector: String? = null,
    val cursor: Boolean = false,
    val includeHidden: Boolean = false,
    val urls: Boolean = false
)

/**
 * A node of a [Snapshot] tree; [ref] (such as `@e3`) is set for elements that can be targeted.
 *
 * [inputType] is set for text-entry `<input>` elements (empty when the page gave no `type`
 * attribute), [placeholder] for inputs and text areas that have one, and [url] for links when
 * [SnapshotOptions.urls] is on.
 */
@Serializable
public data class SnapshotNode(
    val role: String,
    val name: String,
    val value: String? = null,
    val states: Set<String> = emptySet(),
    val children: List<SnapshotNode> = emptyList(),
    val ref: String? = null,
    val depth: Int = 0,
    val level: Int? = null,
    val inputType: String? = null,
    val placeholder: String? = null,
    val url: String? = null
)

/** A snapshot ref (agent-browser `@eN`) and the element it named when the snapshot was taken. */
@Serializable
public data class RefEntry(
    val ref: String,
    val role: String,
    val name: String,
    val cssSelector: String? = null
)

/**
 * An accessibility snapshot (agent-browser `snapshot`). [tree] is the text form an LLM reads, [refs]
 * maps each ref (`@e3`) to its element, and [nodes] is the structured tree.
 *
 * [tree] uses agent-browser's snapshot format, one element per line:
 * ```
 * @e1 [heading] "Example Domain" [level=1]
 * @e2 [button] "Submit"
 * @e3 [input type="email"] placeholder="Email"
 * @e4 [checkbox] "Remember me" [checked]
 * @e5 [link] "Learn more" url="https://example.com/more"
 * @e6 [Iframe] "payment-frame"
 *   @e7 [button] "Pay"
 * ```
 * Elements without a ref print without the `@eN` prefix, children are indented two spaces, and
 * page-controlled text is printed as JSON string literals.
 */
@Serializable
public data class Snapshot(
    val tree: String,
    val refs: Map<String, RefEntry>,
    val nodes: List<SnapshotNode>
)

/** A line diff between two snapshots (agent-browser `diff`). */
public data class SnapshotDiffResult(
    val unifiedDiff: String,
    val additions: Int,
    val removals: Int,
    val unchanged: Int,
    val changed: Boolean
)

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

internal object AccessibilitySnapshotEngine {

    private val json = Json { ignoreUnknownKeys = true }

    /** Function body for [PageHelpers.envelope]; `H` is the page runtime. Returns the snapshot JSON string. */
    fun snapshotBody(options: SnapshotOptions): String = "return " + snapshotJs(options) + ";"

    /** Snapshot script; must run with the page runtime bound to `H` (see [snapshotBody]). */
    fun snapshotJs(options: SnapshotOptions): String {
        return """
        (function() {
            ${snapshotOptionsJs(options)}
            // Stable ref ids and the ref -> element registry live in the sealed runtime, never on window.
            var registry = H.snapshotIds(${jsStringLiteral(PageHelpers.seal)});
            // Browser accessibility data (CDP) lives in the sealed runtime, never in page-visible globals.
            var nativeAX = H.nativeAX(${jsStringLiteral(PageHelpers.seal)}, 'get');
            function nativeMeta(el) { return nativeAX ? nativeAX.meta(el) : null; }
            var snapshotNodes = {};
            var refMap = {};
            var flatNodes = [];

            ${interactiveTagsJs()}

            ${getRoleJs()}

            ${getNameJs()}

            ${getStatesJs()}

            ${getValueJs()}

            ${getLevelJs()}

            ${isInteractiveJs()}

            ${isVisibleJs()}

            ${snapshotExtrasJs()}

            ${generateSelectorJs()}

            ${walkJs()}

            ${walkNativeJs()}

            var root;
            // -s accepts any KMPwright selector (css, text=, role=, refs, `>>` chains) via the locator engine.
            try { root = scopeSelector ? H.resolveFirst(scopeParts) : (document.body || document.documentElement); }
            catch (e) { return JSON.stringify({ error: /^(Unknown ref|Ref @)/.test(String(e && e.message)) ? String(e.message) : 'Invalid snapshot selector: ' + scopeSelector + ' (' + String(e && e.message) + ')' }); }
            if (scopeSelector && !root) return JSON.stringify({ error: 'Snapshot selector did not match: ' + scopeSelector });
            if (root) {
                if (!includeHidden && nativeAX) walkNative(root);
                else walk(root, 0);
            }
            H.commitSnapshot(${jsStringLiteral(PageHelpers.seal)}, snapshotNodes);
            return JSON.stringify({ flatNodes: flatNodes, refMap: refMap });
        })()
        """.trimIndent()
    }

    private fun snapshotOptionsJs(options: SnapshotOptions): String {
        val includeHidden = options.includeHidden
        val maxDepthExpr = options.maxDepth?.toString() ?: "null"
        val interactiveOnly = options.interactive
        val cursorInteractive = options.cursor
        val scopeExpr = options.selector?.let { "'${escapeJsString(it)}'" } ?: "null"
        val scopeParts = options.selector?.let { selector ->
            selector.split(">>").map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(",", "[", "]") { part -> jsStringLiteral(parseRef(part)?.let { "ref=$it" } ?: part) }
        } ?: "null"
        return """
            var scopeParts = $scopeParts;
            var includeUrls = ${options.urls};
            var includeHidden = $includeHidden;
            var maxDepth = $maxDepthExpr;
            var interactiveOnly = $interactiveOnly;
            var cursorInteractive = $cursorInteractive;
            var scopeSelector = $scopeExpr;
        """.trimIndent()
    }

    private fun interactiveTagsJs(): String = """
            var INTERACTIVE_TAGS = {
                'A': true, 'BUTTON': true, 'INPUT': true, 'SELECT': true,
                'TEXTAREA': true, 'DETAILS': true, 'SUMMARY': true
            };
            var INTERACTIVE_ROLES = {
                'button': true, 'link': true, 'checkbox': true, 'radio': true,
                'textbox': true, 'combobox': true, 'menuitem': true, 'tab': true,
                'option': true, 'switch': true, 'slider': true, 'spinbutton': true,
                'searchbox': true, 'listbox': true, 'menuitemcheckbox': true, 'menuitemradio': true, 'treeitem': true
            };
    """.trimIndent()

    private fun getRoleJs(): String = """
            function getRole(el) {
                var native = nativeMeta(el);
                if (native) return native.role;
                var explicit = (el.getAttribute('role') || '').toLowerCase();
                if (explicit) return explicit;
                var tag = el.tagName;
                if (tag === 'H1') return 'heading';
                if (tag === 'H2') return 'heading';
                if (tag === 'H3') return 'heading';
                if (tag === 'H4') return 'heading';
                if (tag === 'H5') return 'heading';
                if (tag === 'H6') return 'heading';
                if (tag === 'A') return el.getAttribute('href') ? 'link' : 'generic';
                if (tag === 'BUTTON') return 'button';
                if (tag === 'INPUT') {
                    var type = (el.type || 'text').toLowerCase();
                    if (type === 'checkbox') return 'checkbox';
                    if (type === 'radio') return 'radio';
                    if (type === 'button' || type === 'submit' || type === 'reset') return 'button';
                    if (type === 'hidden') return null;
                    if (type === 'search') return 'searchbox';
                    if (type === 'number') return 'spinbutton';
                    if (type === 'range') return 'slider';
                    return 'textbox';
                }
                if (tag === 'TEXTAREA') return 'textbox';
                if (tag === 'SELECT') return 'combobox';
                if (tag === 'IMG') return 'img';
                if (tag === 'NAV') return 'navigation';
                if (tag === 'MAIN') return 'main';
                if (tag === 'HEADER') return 'banner';
                if (tag === 'FOOTER') return 'contentinfo';
                if (tag === 'DIALOG') return 'dialog';
                if (tag === 'UL' || tag === 'OL') return 'list';
                if (tag === 'LI') return 'listitem';
                if (tag === 'P') return 'paragraph';
                if (tag === 'ARTICLE') return 'article';
                if (tag === 'ASIDE') return 'complementary';
                if (tag === 'TR') return 'row';
                if (tag === 'TD') return 'cell';
                if (tag === 'TH') return 'columnheader';
                if (tag === 'OPTION') return 'option';
                if (tag === 'TABLE') return 'table';
                if (tag === 'FORM') return 'form';
                // A label is its control's name, not a node of its own (the browser ignores it too).
                if (tag === 'LABEL') return 'generic';
                if (tag === 'DETAILS') return 'group';
                if (tag === 'IFRAME' || tag === 'FRAME') return 'Iframe';
                if (tag === 'SUMMARY') return 'button';
                if (tag === 'PROGRESS') return 'progressbar';
                if (tag === 'METER') return 'meter';
                return 'generic';
            }
    """.trimIndent()

    private fun getNameJs(): String = """
            function getName(el) {
                var native = nativeMeta(el);
                if (native) return native.name;
                var label = el.getAttribute('aria-label');
                if (label && label.trim()) return label.trim();
                var labelledBy = el.getAttribute('aria-labelledby');
                if (labelledBy) {
                    var ids = labelledBy.trim().split(/\s+/);
                    var parts = [];
                    for (var i = 0; i < ids.length; i++) {
                        var ref = document.getElementById(ids[i]);
                        if (ref) parts.push((ref.textContent || ref.innerText || '').trim());
                    }
                    var joined = parts.join(' ').trim();
                    if (joined) return joined;
                }
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                    var associatedLabel = el.closest('label');
                    if (associatedLabel) {
                        var lt = (associatedLabel.innerText || associatedLabel.textContent || '').trim();
                        if (lt) return lt;
                    }
                    var forId = el.getAttribute('id');
                    if (forId) {
                        var labelEl = Array.from(el.labels || [])[0];
                        if (labelEl) {
                            var lt2 = (labelEl.innerText || labelEl.textContent || '').trim();
                            if (lt2) return lt2;
                        }
                    }
                    var ph = el.getAttribute('placeholder');
                    if (ph && ph.trim()) return ph.trim();
                }
                if (tag === 'IMG') {
                    var alt = el.getAttribute('alt') || '';
                    if (alt.trim()) return alt.trim();
                }
                if (tag === 'IFRAME' || tag === 'FRAME') {
                    var frameName = el.getAttribute('title') || el.getAttribute('name') || '';
                    if (frameName.trim()) return frameName.trim();
                }
                var title = el.getAttribute('title');
                if (title && title.trim()) return title.trim();
                var text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                if (text) {
                    var explicitRole = (el.getAttribute('role') || '').toLowerCase();
                    var isSemanticText =
                        tag === 'A' || tag === 'BUTTON' || tag === 'SUMMARY' ||
                        tag === 'OPTION' || tag === 'LI' || tag === 'TD' || tag === 'TH' ||
                        /^H[1-6]$/.test(tag) || INTERACTIVE_TAGS[tag] || INTERACTIVE_ROLES[explicitRole];
                    if (isSemanticText) return text;
                }
                return '';
            }
    """.trimIndent()

    private fun getStatesJs(): String = """
            function getStates(el) {
                var native = nativeMeta(el);
                var states = native ? native.states.slice() : [];
                if (el.getAttribute('aria-checked') === 'true') states.push('checked');
                if (el.getAttribute('aria-checked') === 'mixed') states.push('mixed');
                // Native control state, so WebView snapshots match the browser accessibility tree.
                if (el.tagName === 'OPTION' && el.selected) states.push('selected');
                if (el.tagName === 'SUMMARY' && el.parentElement && el.parentElement.tagName === 'DETAILS' && el.parentElement.open) states.push('expanded');
                if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) {
                    if (el.indeterminate) states.push('mixed');
                    else if (el.checked) states.push('checked');
                }
                if (el.getAttribute('aria-expanded') === 'true') states.push('expanded');
                if (el.disabled) states.push('disabled');
                if (el.getAttribute('aria-disabled') === 'true') states.push('disabled');
                if (el.getAttribute('aria-required') === 'true') states.push('required');
                if (el.required) states.push('required');
                if (el.getAttribute('aria-selected') === 'true') states.push('selected');
                if (el.getAttribute('aria-hidden') === 'true') states.push('hidden');
                if (el.readOnly || el.getAttribute('aria-readonly') === 'true') states.push('readonly');
                if (el.getAttribute('aria-busy') === 'true') states.push('busy');
                return Array.from(new Set(states));
            }
    """.trimIndent()

    private fun getValueJs(): String = """
            function getValue(el) {
                var tag = el.tagName;
                var inputType = String(el.type || 'text').toLowerCase();
                if (tag === 'INPUT' && !TEXT_INPUT_TYPES[inputType] && inputType !== 'range') return null;
                if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                    var v = el.value;
                    if (v && v.trim()) return v.trim();
                }
                return null;
            }
    """.trimIndent()

    private fun getLevelJs(): String = """
            function getLevel(el) {
                var tag = el.tagName;
                if (tag && tag.match(/^H([1-6])$/)) return parseInt(tag[1]);
                var levelAttr = el.getAttribute('aria-level');
                if (levelAttr) return parseInt(levelAttr) || null;
                return null;
            }
    """.trimIndent()

    private fun isInteractiveJs(): String = """
            function isInteractive(el) {
                var tag = el.tagName;
                if (INTERACTIVE_TAGS[tag]) return true;
                var role = (el.getAttribute('role') || '').toLowerCase();
                if (INTERACTIVE_ROLES[role]) return true;
                if (el.getAttribute('tabindex') != null) return true;
                if (cursorInteractive) {
                    var style = ((el.ownerDocument && el.ownerDocument.defaultView) || window).getComputedStyle(el);
                    if (style.cursor === 'pointer') return true;
                    if (el.getAttribute('onclick') != null) return true;
                    if (el.getAttribute('onmousedown') != null) return true;
                }
                return false;
            }
    """.trimIndent()

    private fun isVisibleJs(): String = """
            function isVisible(el) {
                if (!el) return false;
                var view = (el.ownerDocument && el.ownerDocument.defaultView) || window;
                var style = view.getComputedStyle(el);
                if (style.display === 'none') return false;
                if (style.visibility === 'hidden') return false;
                if (parseFloat(style.opacity) === 0) return false;
                if (el.getAttribute('aria-hidden') === 'true') return false;
                return true;
            }
    """.trimIndent()

    /**
     * Ref assignment and per-node fields shared by the DOM and native walkers. Like agent-browser,
     * interactive elements and iframes always get a ref, and named content landmarks (headings,
     * cells, list items, regions...) get one too, so `snapshot -i` lists them.
     */
    private fun snapshotExtrasJs(): String = """
            var CONTENT_ROLES = {
                'heading': true, 'cell': true, 'gridcell': true, 'columnheader': true, 'rowheader': true,
                'listitem': true, 'article': true, 'region': true, 'main': true, 'navigation': true
            };
            var TEXT_INPUT_TYPES = {
                'text': true, 'email': true, 'password': true, 'search': true, 'tel': true, 'url': true,
                'number': true, 'date': true, 'datetime-local': true, 'month': true, 'week': true, 'time': true
            };
            function refWorthy(el, role, name) {
                if (!el || el.nodeType !== 1) return false;
                if (isInteractive(el) || role === 'Iframe' || INTERACTIVE_ROLES[role]) return true;
                return !!(CONTENT_ROLES[role] && name && String(name).trim());
            }
            function assignRef(el, role, name) {
                var refId = registry.ids.get(el);
                if (!refId) {
                    refId = '@e' + (++registry.counter.next);
                    registry.ids.set(el, refId);
                }
                snapshotNodes[refId] = el;
                // A CSS path is only meaningful in the main document; iframe elements resolve by ref.
                refMap[refId] = {refId: refId, role: role, name: name, cssSelector: el.ownerDocument === document ? generateSelector(el) : null};
                return refId;
            }
            function childFrameDocument(el) {
                // Only one level of same-origin iframes is inlined, matching agent-browser.
                if (!el || (el.tagName !== 'IFRAME' && el.tagName !== 'FRAME') || el.ownerDocument !== document) return null;
                try { var d = el.contentDocument; return d && (d.body || d.documentElement) ? d : null; } catch (e) { return null; }
            }
            function nodeRecord(el, role, name, refId, depth, value, states, level, children) {
                var record = {role: role, name: name, value: value, states: states, children: children, refId: refId, depth: depth, level: level};
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA') {
                    var placeholder = el.getAttribute('placeholder');
                    if (placeholder && placeholder.trim()) record.placeholder = placeholder;
                }
                if (tag === 'INPUT') {
                    var type = String(el.type || 'text').toLowerCase();
                    if (TEXT_INPUT_TYPES[type]) record.inputType = el.hasAttribute('type') ? type : '';
                }
                if (includeUrls && refId && role === 'link' && typeof el.href === 'string' && el.href) record.url = el.href;
                return record;
            }
    """.trimIndent()

    private fun generateSelectorJs(): String = """
            function generateSelector(el) {
                if (el.id) return '#' + CSS.escape(el.id);
                var path = [];
                var current = el;
                while (current && current !== document.body && current !== document.documentElement) {
                    var tag = current.tagName.toLowerCase();
                    var parent = current.parentElement;
                    if (!parent) break;
                    var siblings = Array.from(parent.children).filter(function(c) { return c.tagName === current.tagName; });
                    if (siblings.length > 1) {
                        var idx = siblings.indexOf(current) + 1;
                        path.unshift(tag + ':nth-of-type(' + idx + ')');
                    } else {
                        path.unshift(tag);
                    }
                    current = parent;
                }
                return path.join(' > ');
            }
    """.trimIndent()

    private fun walkJs(): String = """
            function walk(el, depth) {
                if (!el || el.nodeType !== 1) return -1;
                if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'NOSCRIPT') return -1;
                if (el.tagName === 'HEAD') return -1;
                if (!includeHidden && !isVisible(el)) return -1;
                if (depth > 256 || (maxDepth !== null && depth > maxDepth)) return -1;

                var role = getRole(el);
                if (role === null) return -1;
                // The document body is the page itself, like the browser's RootWebArea.
                if (el.ownerDocument && el === el.ownerDocument.body && !(scopeSelector && el.ownerDocument === document)) role = 'RootWebArea';

                var name = getName(el);
                var refId = refWorthy(el, role, name) ? assignRef(el, role, name) : null;
                var frameDoc = childFrameDocument(el);
                if (interactiveOnly && !refId && !el.children.length && !(el.shadowRoot && el.shadowRoot.children.length)) return -1;

                var nodeIdx = flatNodes.length;
                var childIndices = [];
                flatNodes.push(nodeRecord(el, role, name, refId, depth, getValue(el), getStates(el), getLevel(el), childIndices));
                var children = Array.from(el.childNodes);
                if (el.shadowRoot) children = children.concat(Array.from(el.shadowRoot.childNodes));
                if (frameDoc) children = [frameDoc.body || frameDoc.documentElement];
                for (var i = 0; i < children.length; i++) {
                    var child = children[i];
                    if (child.nodeType === 3) {
                        // Text becomes StaticText, like the browser accessibility tree.
                        // Form controls report their own value; their text children are not content.
                        var text = el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || (el.tagName === 'LABEL' && el.control) ? '' : (child.data || '').replace(/\s+/g, ' ');
                        if (text.trim() && !interactiveOnly && (maxDepth === null || depth + 1 <= maxDepth)) {
                            childIndices.push(flatNodes.length);
                            flatNodes.push({role: 'StaticText', name: text, value: null, states: [], children: [], refId: null, depth: depth + 1, level: null});
                        }
                        continue;
                    }
                    var childIdx = walk(child, depth + 1);
                    if (childIdx >= 0) childIndices.push(childIdx);
                }
                return nodeIdx;
            }
    """.trimIndent()

    private fun walkNativeJs(): String = """
            function walkNative(scope) {
                var tree = nativeAX.tree;
                var dom = {get: function(id) { var el = nativeAX.node(id); return el ? {deref: function() { return el; }} : null; }};
                var byId = new Map(tree.map(function(node) { return [node.nodeId, node]; }));
                var scopedRoot = scopeSelector && tree.find(function(node) { var weak=dom.get(node.nodeId); return weak && weak.deref()===scope; });
                var roots = scopedRoot ? [scopedRoot] : tree.filter(function(node) { return !node.parentId || !byId.has(node.parentId); });
                var stack = roots.reverse().map(function(node) { return {node:node,parent:-1,depth:0}; });
                var visited = new Set();
                function inside(el) {
                    if (!scopeSelector || scopedRoot) return true;
                    if (el && el.nodeType !== 1) el = el.parentElement;
                    while (el) {
                        if (el === scope || scope.contains(el)) return true;
                        var root = el.getRootNode(); el = root && root.host;
                    }
                    return false;
                }
                if (scopeSelector || interactiveOnly) {
                    var scopeRef = null;
                    var scopeRole = scopeSelector ? getRole(scope) : 'generic', scopeName = scopeSelector ? getName(scope) : '';
                    if (scopeSelector && refWorthy(scope, scopeRole, scopeName)) scopeRef = assignRef(scope, scopeRole, scopeName);
                    flatNodes.push(scopeSelector
                        ? nodeRecord(scope, scopeRole, scopeName, scopeRef, 0, getValue(scope), getStates(scope), getLevel(scope), [])
                        : {role:'generic',name:'',value:null,states:[],children:[],refId:null,depth:0,level:null});
                    stack.forEach(function(item) {item.parent=0;item.depth=1;});
                }
                while (stack.length) {
                    var item = stack.pop(), node = item.node;
                    if (visited.has(node.nodeId)) continue;
                    visited.add(node.nodeId);
                    var weak = dom.get(node.nodeId), el = weak && weak.deref();
                    var role = node.role && node.role.value || 'generic';
                    var parent = item.parent, depth = item.depth;
                    var include = !node.ignored && role !== 'InlineTextBox' && inside(el) && el !== (scopeSelector ? scope : null);
                    var name = node.name && node.name.value || '';
                    var isElement = !!(el && el.nodeType === 1);
                    var worthy = isElement && refWorthy(el, role, name);
                    if (interactiveOnly && !worthy) include = false;
                    if (depth > 256 || (maxDepth !== null && depth > maxDepth)) continue;
                    if (include) {
                        var states = [], level = null;
                        (node.properties || []).forEach(function(p) {
                            if (p.name === 'level') level = p.value.value;
                            if (['checked','expanded','disabled','required','selected','readonly','busy','focused'].indexOf(p.name)>=0 && (p.value.value===true || p.value.value==='true')) states.push(p.name);
                            if (p.name==='checked' && p.value.value==='mixed') states.push('mixed');
                        });
                        var refId = worthy ? assignRef(el, role, name) : null;
                        var value = node.value && node.value.value != null && node.value.value !== '' ? String(node.value.value) : null;
                        var index=flatNodes.length;
                        flatNodes.push(isElement
                            ? nodeRecord(el, role, name, refId, depth, value, states, level, [])
                            : {role:role,name:name,value:value,states:states,children:[],refId:null,depth:depth,level:level});
                        if (parent>=0) flatNodes[parent].children.push(index);
                        parent=index;depth++;
                    }
                    // Same-origin iframe documents are not part of the main frame's accessibility tree:
                    // inline them (one level, like agent-browser) with the DOM walker.
                    var frameDoc = isElement && include && !(node.childIds && node.childIds.length) ? childFrameDocument(el) : null;
                    if (frameDoc) {
                        var frameChild = walk(frameDoc.body || frameDoc.documentElement, depth);
                        if (frameChild >= 0 && parent >= 0) flatNodes[parent].children.push(frameChild);
                    }
                    var children=node.childIds || [];
                    for (var i=children.length-1;i>=0;i--) {
                        var child=byId.get(children[i]);
                        if (child) stack.push({node:child,parent:parent,depth:depth});
                    }
                }
            }
    """.trimIndent()

    fun parseSnapshotJson(raw: String, options: SnapshotOptions = SnapshotOptions()): Snapshot {
        val cleaned = raw.trim()
        if (cleaned == "null" || cleaned == "undefined" || cleaned.isEmpty()) {
            return Snapshot(tree = "", refs = emptyMap(), nodes = emptyList())
        }

        return try {
            val jsonObj = json.parseToJsonElement(cleaned).jsonObject
            val flatNodesArray = jsonObj["flatNodes"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            val refMapObj = jsonObj["refMap"]

            val flatNodes = flatNodesArray.map { it.jsonObject }
            val refMap = mutableMapOf<String, RefEntry>()

            if (refMapObj != null) {
                val refMapJson = refMapObj.jsonObject
                for ((key, value) in refMapJson) {
                    if (!REF_PATTERN.matches(key)) continue
                    val entry = value.jsonObject
                    refMap[key] = RefEntry(
                        ref = key,
                        role = entry["role"]?.jsonPrimitive?.content ?: "",
                        name = entry["name"]?.jsonPrimitive?.content ?: "",
                        cssSelector = entry["cssSelector"]?.jsonPrimitive?.contentOrNullSafe()
                    )
                }
            }

            // Reassemble tree
            val visited = HashSet<Int>()
            fun buildNode(index: Int): SnapshotNode {
                if (!visited.add(index)) throw KmpwrightException("Accessibility snapshot contains a cycle")
                val raw = flatNodes[index]
                val childrenIndices = raw["children"]?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull() ?: -1
                }?.filter { it >= 0 && it < flatNodes.size } ?: emptyList()

                val states = raw["states"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                }?.toSet() ?: emptySet()

                return SnapshotNode(
                    role = raw["role"]?.jsonPrimitive?.content ?: "generic",
                    name = raw["name"]?.jsonPrimitive?.content ?: "",
                    value = raw["value"]?.jsonPrimitive?.contentOrNullSafe(),
                    states = states,
                    children = childrenIndices.map { buildNode(it) },
                    ref = raw["refId"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { REF_PATTERN.matches(it) },
                    depth = raw["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    level = raw["level"]?.jsonPrimitive?.content?.toIntOrNull(),
                    inputType = raw["inputType"]?.jsonPrimitive?.contentOrNullSafe(),
                    placeholder = raw["placeholder"]?.jsonPrimitive?.contentOrNullSafe(),
                    url = raw["url"]?.jsonPrimitive?.contentOrNullSafe()
                )
            }

            val nodes = if (flatNodes.isNotEmpty()) listOf(buildNode(0)) else emptyList()
            val textTree = formatTree(nodes, options)

            Snapshot(tree = textTree, refs = refMap, nodes = nodes)
        } catch (e: Exception) {
            throw KmpwrightException("Could not parse the accessibility snapshot: ${e.message}", e)
        }
    }

    private const val MAX_TEXT_LENGTH = 200
    private val REF_PATTERN = Regex("@e[0-9]+")
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")

    /**
     * Page-controlled strings are rendered as JSON string literals (whitespace collapsed, truncated),
     * and roles/states/refs are restricted to token characters, so a page cannot forge extra lines
     * or refs in the text tree an LLM reads.
     */
    internal fun quote(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        val bounded = if (collapsed.length > MAX_TEXT_LENGTH) collapsed.take(MAX_TEXT_LENGTH) + "…" else collapsed
        return kotlinx.serialization.json.JsonPrimitive(bounded).toString()
    }

    private fun token(text: String, fallback: String): String = text.takeIf { TOKEN_PATTERN.matches(it) } ?: fallback

    /** Collapses whitespace like [quote], without quoting or truncating. */
    private fun collapse(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** State tokens in agent-browser order; `focused` is omitted so the tree does not change on focus. */
    private val STATE_ORDER = listOf("checked", "expanded", "selected", "disabled", "required", "readonly", "busy", "hidden")
    private val HIDDEN_STATES = setOf("focused", "mixed")

    /**
     * Renders [nodes] in agent-browser's snapshot format (see [Snapshot]). Mirrors agent-browser's
     * renderer: the root web area, blank text and unnamed single-child `generic` wrappers are
     * transparent (their children print in their place); [SnapshotOptions.interactive] keeps only ref lines (indented under ref ancestors,
     * such as an iframe); [SnapshotOptions.compact] keeps only lines with refs or values and their
     * ancestors.
     */
    internal fun formatTree(nodes: List<SnapshotNode>, options: SnapshotOptions = SnapshotOptions()): String =
        nodes.flatMap { renderNode(it, 0, options, root = true) }.joinToString("\n")

    private fun renderNode(node: SnapshotNode, indent: Int, options: SnapshotOptions, root: Boolean): List<String> {
        val role = token(node.role, "generic")
        val ref = node.ref?.takeIf { REF_PATTERN.matches(it) }
        val name = collapse(node.name)
        val children = textChildren(node, name)
        val transparent = role == "RootWebArea" || role == "WebArea" ||
            (role == "generic" && ref == null && name.isEmpty() && (children.size <= 1 || root)) ||
            (role == "StaticText" && name.isEmpty()) ||
            (options.interactive && ref == null)
        if (transparent) return children.flatMap { renderNode(it, indent, options, root = false) }

        val value = node.value?.let(::collapse)?.takeIf { it.isNotEmpty() && it != name }
        val childLines = children.flatMap { renderNode(it, indent + 1, options, root = false) }
        if (options.compact && ref == null && value == null && childLines.isEmpty()) return emptyList()

        val line = StringBuilder("  ".repeat(indent))
        if (ref != null) line.append(ref).append(' ')
        val inputType = node.inputType?.let { type -> if (type.isEmpty()) "" else token(type, "") }
        line.append('[').append(if (inputType == null) role else if (inputType.isEmpty()) "input" else "input type=\"$inputType\"").append(']')
        val placeholder = node.placeholder?.let(::collapse)?.takeIf { it.isNotEmpty() }
        if (name.isNotEmpty() && name != placeholder) line.append(' ').append(quote(name))
        if (placeholder != null) line.append(" placeholder=").append(quote(placeholder))
        node.level?.let { line.append(" [level=").append(it).append(']') }
        val states = node.states.filter { TOKEN_PATTERN.matches(it) }.toSet()
        for (state in STATE_ORDER) {
            if (state !in states) continue
            if (state == "checked" && "mixed" in states) continue
            line.append(" [").append(state).append(']')
        }
        if ("mixed" in states) line.append(" [checked=mixed]")
        states.filter { it !in STATE_ORDER && it !in HIDDEN_STATES }.sorted().forEach { line.append(" [").append(it).append(']') }
        if (value != null) line.append(" value=").append(quote(value))
        if (options.urls) node.url?.let(::collapse)?.takeIf { it.isNotEmpty() && ref != null }?.let { line.append(" url=").append(quote(it)) }
        return listOf(line.toString()) + childLines
    }

    /**
     * agent-browser's text cleanup: adjacent `StaticText` children merge into one, and a lone
     * `StaticText` child that repeats its parent's name is dropped.
     */
    private fun textChildren(node: SnapshotNode, parentName: String): List<SnapshotNode> {
        // Unnamed inline wrappers that only hold text (`<b>`, `<span>`) do not split a run of text.
        val children = node.children.flatMap { child ->
            if (child.role == "generic" && child.ref == null && child.name.isBlank() && child.value == null &&
                child.children.isNotEmpty() && child.children.all { it.role == "StaticText" && it.ref == null && it.children.isEmpty() }
            ) child.children else listOf(child)
        }
        if (children.none { it.role == "StaticText" }) return children
        val merged = ArrayList<SnapshotNode>(children.size)
        for (child in children) {
            val last = merged.lastOrNull()
            if (child.role == "StaticText" && last != null && last.role == "StaticText" && last.ref == null && child.ref == null && last.children.isEmpty() && child.children.isEmpty()) {
                merged[merged.lastIndex] = last.copy(name = last.name + child.name)
            } else {
                merged += child
            }
        }
        val only = merged.singleOrNull()
        return if (only != null && only.role == "StaticText" && only.ref == null && collapse(only.name) == parentName) only.children else merged
    }

    /**
     * Compute a line-level diff between two accessibility snapshot text trees.
     * Uses a patience-based algorithm to produce a unified diff string.
     */
    fun diffSnapshots(before: String, after: String): SnapshotDiffResult {
        val beforeLines = before.lines()
        val afterLines = after.lines()

        if (before == after) {
            return SnapshotDiffResult(
                unifiedDiff = "",
                additions = 0,
                removals = 0,
                unchanged = beforeLines.size,
                changed = false
            )
        }

        val lcs = longestCommonSubsequence(beforeLines, afterLines)
        val diff = StringBuilder()
        var additions = 0
        var removals = 0
        var unchanged = 0

        var bi = 0
        var ai = 0
        var li = 0
        while (li < lcs.size) {
            val line = lcs[li]
            // Emit removals before this match
            while (bi < beforeLines.size && beforeLines[bi] != line) {
                diff.appendLine("-${beforeLines[bi]}")
                bi++
                removals++
            }
            // Emit additions before this match
            while (ai < afterLines.size && afterLines[ai] != line) {
                diff.appendLine("+${afterLines[ai]}")
                ai++
                additions++
            }
            // Emit the matching line
            diff.appendLine(" ${line}")
            bi++
            ai++
            unchanged++
            li++
        }
        // Remaining removals
        while (bi < beforeLines.size) {
            diff.appendLine("-${beforeLines[bi]}")
            bi++
            removals++
        }
        // Remaining additions
        while (ai < afterLines.size) {
            diff.appendLine("+${afterLines[ai]}")
            ai++
            additions++
        }

        return SnapshotDiffResult(
            unifiedDiff = diff.toString().trimEnd(),
            additions = additions,
            removals = removals,
            unchanged = unchanged,
            changed = additions > 0 || removals > 0
        )
    }

    /**
     * Patience-based longest common subsequence algorithm.
     * Efficient for detecting moved blocks in diffs.
     *
     * Only lines occurring once in each input are used as patience anchors.
     * Repeated lines are handled by [lcsFallback], whose large-input path is
     * linear in the two input sizes and keeps matches in source order.
     */
    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()

        val countsA = a.groupingBy { it }.eachCount()
        val countsB = b.groupingBy { it }.eachCount()
        val positionsB = b.withIndex().associate { it.value to it.index }

        // Use only genuinely unique common lines as patience anchors. The
        // anchors must also be monotonic in b; reordered anchors are excluded
        // by the longest-increasing-subsequence selection below.
        val commonPoints = a.withIndex()
            .filter { (_, line) -> countsA[line] == 1 && countsB[line] == 1 }
            .map { (index, line) -> Pair(index, positionsB[line]!!) }
        val patiencePoints = longestIncreasingBySecond(commonPoints)

        return lcsFromPatience(a, b, patiencePoints)
    }

    /** Select a monotonic set of patience points using LIS on b indexes. */
    private fun longestIncreasingBySecond(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (points.isEmpty()) return emptyList()

        val tails = mutableListOf<Int>()
        val predecessors = IntArray(points.size) { -1 }
        for (index in points.indices) {
            val value = points[index].second
            var low = 0
            var high = tails.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (points[tails[middle]].second < value) low = middle + 1 else high = middle
            }
            if (low > 0) predecessors[index] = tails[low - 1]
            if (low == tails.size) tails.add(index) else tails[low] = index
        }

        val result = ArrayList<Pair<Int, Int>>(tails.size)
        var index = tails.last()
        while (index >= 0) {
            result.add(points[index])
            index = predecessors[index]
        }
        result.reverse()
        return result
    }

    private fun lcsFromPatience(
        a: List<String>,
        b: List<String>,
        patiencePoints: List<Pair<Int, Int>>
    ): List<String> {
        if (patiencePoints.isEmpty()) {
            return lcsFallback(a, b)
        }

        val result = mutableListOf<String>()
        var prevA = 0
        var prevB = 0

        for ((ai, bi) in patiencePoints) {
            // LCS of the sub-array between previous and current patience point
            val subLcs = lcsFallback(a.subList(prevA, ai), b.subList(prevB, bi))
            result.addAll(subLcs)
            result.add(a[ai])
            prevA = ai + 1
            prevB = bi + 1
        }

        // LCS after last patience point
        val tailLcs = lcsFallback(a.subList(prevA, a.size), b.subList(prevB, b.size))
        result.addAll(tailLcs)

        return result
    }

    /**
     * Standard dynamic programming LCS for small sequences.
     */
    private fun lcsFallback(a: List<String>, b: List<String>): List<String> {
        val m = a.size
        val n = b.size
        if (m == 0 || n == 0) return emptyList()

        // Limit to avoid O(m*n) memory on large inputs. The fallback is
        // intentionally linear in m + n, with O(n) auxiliary storage.
        val maxLen = 200
        if (m > maxLen || n > maxLen) {
            // For large sequences, use an indexed occurrence-queue approach.
            return lcsGreedy(a, b)
        }

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    dp[i - 1][j].coerceAtLeast(dp[i][j - 1])
                }
            }
        }

        // Backtrack to recover the LCS
        val lcs = mutableListOf<String>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            if (a[i - 1] == b[j - 1]) {
                lcs.add(0, a[i - 1])
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        return lcs
    }

    /**
     * Bounded large-input LCS approximation.
     *
     * Every b index is enqueued once and consumed at most once. Skipping an
     * occurrence that precedes the last match keeps the result a valid ordered
     * subsequence, while the per-line cursors avoid indexOf/removeAt's O(n^2)
     * scan and shifting behavior. Runtime is O(a + b), with O(b) storage.
     */
    private fun lcsGreedy(a: List<String>, b: List<String>): List<String> {
        val result = mutableListOf<String>()
        val occurrences = mutableMapOf<String, MutableList<Int>>()
        b.forEachIndexed { index, line ->
            occurrences.getOrPut(line) { mutableListOf() }.add(index)
        }
        val cursors = mutableMapOf<String, Int>()
        var lastMatchedB = -1

        for (line in a) {
            val positions = occurrences[line] ?: continue
            var cursor = cursors[line] ?: 0
            while (cursor < positions.size && positions[cursor] <= lastMatchedB) {
                cursor++
            }
            if (cursor < positions.size) {
                result.add(line)
                lastMatchedB = positions[cursor]
                cursor++
            }
            cursors[line] = cursor
        }
        return result
    }
}
