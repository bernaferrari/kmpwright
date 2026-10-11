package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class A11ySnapshotOptions(
    val includeHidden: Boolean = false,
    val maxDepth: Int? = null,
    val interactiveOnly: Boolean = false,
    val compact: Boolean = false,
    val scopeSelector: String? = null,
    val cursorInteractive: Boolean = false
)

@Serializable
data class A11yNode(
    val role: String,
    val name: String,
    val value: String? = null,
    val states: Set<String> = emptySet(),
    val children: List<A11yNode> = emptyList(),
    val refId: String? = null,
    val depth: Int = 0,
    val level: Int? = null
)

@Serializable
data class RefEntry(
    val refId: String,
    val role: String,
    val name: String,
    val cssSelector: String? = null,
    val nth: Int? = null
)

@Serializable
data class A11ySnapshot(
    val nodes: List<A11yNode>,
    val refMap: Map<String, RefEntry>,
    val textTree: String,
    val timestamp: Long
)

data class SnapshotDiffResult(
    val unifiedDiff: String,
    val additions: Int,
    val removals: Int,
    val unchanged: Int,
    val changed: Boolean
)

internal object AccessibilitySnapshotEngine {

    private val json = Json { ignoreUnknownKeys = true }

    fun snapshotJs(options: A11ySnapshotOptions): String {
        return """
        (function() {
            ${snapshotOptionsJs(options)}
            var registry = window.__kmpwright_snapshotRegistry;
            if (!registry || registry.document !== document) {
                registry = { document: document, ids: new WeakMap(), nextId: 0 };
                window.__kmpwright_snapshotRegistry = registry;
            }
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

            ${generateSelectorJs()}

            ${walkJs()}

            ${walkNativeJs()}

            var root;
            try { root = scopeSelector ? document.querySelector(scopeSelector) : (document.body || document.documentElement); }
            catch (e) { return JSON.stringify({ error: 'Invalid snapshot selector: ' + scopeSelector }); }
            if (scopeSelector && !root) return JSON.stringify({ error: 'Snapshot selector did not match: ' + scopeSelector });
            if (root) {
                if (!includeHidden && window.__kmpwright_browserAXTree && window.__kmpwright_browserAXNodes) walkNative(root);
                else walk(root, 0);
            }
            window.__kmpwright_snapshotNodes = snapshotNodes;
            return JSON.stringify({ flatNodes: flatNodes, refMap: refMap });
        })()
        """.trimIndent()
    }

    private fun snapshotOptionsJs(options: A11ySnapshotOptions): String {
        val includeHidden = options.includeHidden
        val maxDepthExpr = options.maxDepth?.toString() ?: "null"
        val interactiveOnly = options.interactiveOnly
        val cursorInteractive = options.cursorInteractive
        val scopeExpr = options.scopeSelector?.let { "'${escapeJsString(it)}'" } ?: "null"
        return """
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
                'option': true, 'switch': true, 'slider': true, 'spinbutton': true
            };
    """.trimIndent()

    private fun getRoleJs(): String = """
            function getRole(el) {
                var native = window.__kmpwright_browserAX && window.__kmpwright_browserAX.get(el);
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
                if (tag === 'TABLE') return 'table';
                if (tag === 'FORM') return 'form';
                if (tag === 'LABEL') return 'label';
                if (tag === 'DETAILS') return 'group';
                if (tag === 'SUMMARY') return 'button';
                if (tag === 'PROGRESS') return 'progressbar';
                if (tag === 'METER') return 'meter';
                return 'generic';
            }
    """.trimIndent()

    private fun getNameJs(): String = """
            function getName(el) {
                var native = window.__kmpwright_browserAX && window.__kmpwright_browserAX.get(el);
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
                var title = el.getAttribute('title');
                if (title && title.trim()) return title.trim();
                var text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                if (text) {
                    var explicitRole = (el.getAttribute('role') || '').toLowerCase();
                    var isSemanticText =
                        tag === 'A' || tag === 'BUTTON' || tag === 'SUMMARY' || tag === 'LABEL' ||
                        tag === 'OPTION' || tag === 'LI' || tag === 'TD' || tag === 'TH' ||
                        /^H[1-6]$/.test(tag) || INTERACTIVE_TAGS[tag] || INTERACTIVE_ROLES[explicitRole];
                    if (isSemanticText) return text;
                }
                return '';
            }
    """.trimIndent()

    private fun getStatesJs(): String = """
            function getStates(el) {
                var native = window.__kmpwright_browserAX && window.__kmpwright_browserAX.get(el);
                var states = native ? native.states.slice() : [];
                if (el.getAttribute('aria-checked') === 'true') states.push('checked');
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
                    var style = window.getComputedStyle(el);
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
                var style = window.getComputedStyle(el);
                if (style.display === 'none') return false;
                if (style.visibility === 'hidden') return false;
                if (parseFloat(style.opacity) === 0) return false;
                if (el.getAttribute('aria-hidden') === 'true') return false;
                return true;
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
                if (interactiveOnly && !isInteractive(el) && !el.children.length) return -1;

                var role = getRole(el);
                if (role === null) return -1;

                var name = getName(el);
                var value = getValue(el);
                var states = getStates(el);
                var level = getLevel(el);
                var refId = null;

                if (isInteractive(el)) {
                    refId = registry.ids.get(el);
                    if (!refId) {
                        refId = '@e' + (++registry.nextId);
                        registry.ids.set(el, refId);
                    }
                    snapshotNodes[refId] = el;
                    refMap[refId] = {
                        refId: refId,
                        role: role,
                        name: name,
                        cssSelector: generateSelector(el)
                    };
                }

                var nodeIdx = flatNodes.length;
                var childIndices = [];
                flatNodes.push({
                    role: role,
                    name: name,
                    value: value,
                    states: states,
                    children: childIndices,
                    refId: refId,
                    depth: depth,
                    level: level
                });
                var children = Array.from(el.children);
                if (el.shadowRoot) children = children.concat(Array.from(el.shadowRoot.children));
                for (var i = 0; i < children.length; i++) {
                    var childIdx = walk(children[i], depth + 1);
                    if (childIdx >= 0) childIndices.push(childIdx);
                }
                return nodeIdx;
            }
    """.trimIndent()

    private fun walkNativeJs(): String = """
            function walkNative(scope) {
                var tree = window.__kmpwright_browserAXTree;
                var dom = window.__kmpwright_browserAXNodes;
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
                    if (scopeSelector && isInteractive(scope)) {
                        scopeRef = registry.ids.get(scope);
                        if (!scopeRef) {scopeRef='@e'+(++registry.nextId);registry.ids.set(scope,scopeRef);}
                        snapshotNodes[scopeRef]=scope;
                        refMap[scopeRef]={refId:scopeRef,role:getRole(scope),name:getName(scope),cssSelector:generateSelector(scope)};
                    }
                    flatNodes.push({role:scopeSelector ? getRole(scope) : 'generic',name:scopeSelector ? getName(scope) : '',value:getValue(scope),states:getStates(scope),children:[],refId:scopeRef,depth:0,level:getLevel(scope)});
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
                    if (interactiveOnly && !(el && el.nodeType === 1 && isInteractive(el))) include = false;
                    if (depth > 256 || (maxDepth !== null && depth > maxDepth)) continue;
                    if (include) {
                        var name = node.name && node.name.value || '', states = [], level = null;
                        (node.properties || []).forEach(function(p) {
                            if (p.name === 'level') level = p.value.value;
                            if (['checked','expanded','disabled','required','selected','readonly','busy','focused'].indexOf(p.name)>=0 && p.value.value===true) states.push(p.name);
                            if (p.name==='checked' && p.value.value==='mixed') states.push('mixed');
                        });
                        var refId = null;
                        if (el && el.nodeType === 1 && isInteractive(el)) {
                            refId=registry.ids.get(el);
                            if (!refId) {refId='@e'+(++registry.nextId);registry.ids.set(el,refId);}
                            snapshotNodes[refId]=el;
                            refMap[refId]={refId:refId,role:role,name:name,cssSelector:generateSelector(el)};
                        }
                        var index=flatNodes.length;
                        flatNodes.push({role:role,name:name,value:node.value ? String(node.value.value) : null,states:states,children:[],refId:refId,depth:depth,level:level});
                        if (parent>=0) flatNodes[parent].children.push(index);
                        parent=index;depth++;
                    }
                    var children=node.childIds || [];
                    for (var i=children.length-1;i>=0;i--) {
                        var child=byId.get(children[i]);
                        if (child) stack.push({node:child,parent:parent,depth:depth});
                    }
                }
            }
    """.trimIndent()

    fun parseSnapshotJson(raw: String): A11ySnapshot {
        val cleaned = raw.trim()
        if (cleaned == "null" || cleaned == "undefined" || cleaned.isEmpty()) {
            return A11ySnapshot(emptyList(), emptyMap(), "", currentTimeMillis())
        }

        return try {
            val jsonObj = json.parseToJsonElement(cleaned).jsonObject
            val flatNodesArray = jsonObj["flatNodes"]!!.jsonArray
            val refMapObj = jsonObj["refMap"]

            val flatNodes = flatNodesArray.map { it.jsonObject }
            val refMap = mutableMapOf<String, RefEntry>()

            if (refMapObj != null) {
                val refMapJson = refMapObj.jsonObject
                for ((key, value) in refMapJson) {
                    val entry = value.jsonObject
                    refMap[key] = RefEntry(
                        refId = entry["refId"]?.jsonPrimitive?.content ?: key,
                        role = entry["role"]?.jsonPrimitive?.content ?: "",
                        name = entry["name"]?.jsonPrimitive?.content ?: "",
                        cssSelector = entry["cssSelector"]?.jsonPrimitive?.content
                    )
                }
            }

            // Reassemble tree
            fun buildNode(index: Int): A11yNode {
                val raw = flatNodes[index]
                val childrenIndices = raw["children"]?.jsonArray?.map {
                    it.jsonPrimitive.content.toIntOrNull() ?: -1
                }?.filter { it >= 0 } ?: emptyList()

                val states = raw["states"]?.jsonArray?.map {
                    it.jsonPrimitive.content
                }?.toSet() ?: emptySet()

                return A11yNode(
                    role = raw["role"]?.jsonPrimitive?.content ?: "generic",
                    name = raw["name"]?.jsonPrimitive?.content ?: "",
                    value = raw["value"]?.jsonPrimitive?.content,
                    states = states,
                    children = childrenIndices.map { buildNode(it) },
                    refId = raw["refId"]?.jsonPrimitive?.content,
                    depth = raw["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    level = raw["level"]?.jsonPrimitive?.content?.toIntOrNull()
                )
            }

            val nodes = if (flatNodes.isNotEmpty()) listOf(buildNode(0)) else emptyList()
            val textTree = buildTextTree(nodes)

            A11ySnapshot(
                nodes = nodes,
                refMap = refMap,
                textTree = textTree,
                timestamp = currentTimeMillis()
            )
        } catch (e: Exception) {
            A11ySnapshot(emptyList(), emptyMap(), "", currentTimeMillis())
        }
    }

    private fun buildTextTree(nodes: List<A11yNode>, indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        for (node in nodes) {
            val ref = if (node.refId != null) " ${node.refId}" else ""
            val level = if (node.level != null) " [h${node.level}]" else ""
            val states = if (node.states.isNotEmpty()) " ${node.states.sorted().joinToString(",")}" else ""
            val value = if (node.value != null) " = \"${node.value}\"" else ""
            val name = if (node.name.isNotEmpty()) " \"${node.name}\"" else ""
            sb.appendLine("${prefix}${node.role}${level}${name}${value}${states}${ref}")
            sb.append(buildTextTree(node.children, indent + 1))
        }
        return sb.toString().trimEnd()
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
