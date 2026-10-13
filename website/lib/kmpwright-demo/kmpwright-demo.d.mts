type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare interface KtSet<out E> /* extends Collection<E> */ {
    asJsReadonlySetView(): ReadonlySet<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtSet": unique symbol;
    };
}
export declare namespace KtSet {
    function fromJsSet<E>(set: ReadonlySet<E>): KtSet<E>;
}
export declare interface KtList<out E> /* extends Collection<E> */ {
    asJsReadonlyArrayView(): ReadonlyArray<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtList": unique symbol;
    };
}
export declare namespace KtList {
    function fromJsArray<E>(array: ReadonlyArray<E>): KtList<E>;
}
export declare interface KtMap<K, out V> {
    asJsReadonlyMapView(): ReadonlyMap<K, V>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMap": unique symbol;
    };
}
export declare namespace KtMap {
    function fromJsMap<K, V>(map: ReadonlyMap<K, V>): KtMap<K, V>;
}
export declare interface KtMutableSet<E> extends KtSet<E>/*, MutableCollection<E> */ {
    asJsSetView(): Set<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableSet": unique symbol;
    } & KtSet<any>["__doNotUseOrImplementIt"];
}
export declare namespace KtMutableSet {
    function fromJsSet<E>(set: ReadonlySet<E>): KtMutableSet<E>;
}
export declare interface KtMutableList<E> extends KtList<E>/*, MutableCollection<E> */ {
    asJsArrayView(): Array<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableList": unique symbol;
    } & KtList<any>["__doNotUseOrImplementIt"];
}
export declare namespace KtMutableList {
    function fromJsArray<E>(array: ReadonlyArray<E>): KtMutableList<E>;
}
export declare interface KtMutableMap<K, V> extends KtMap<K, V> {
    asJsMapView(): Map<K, V>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtMutableMap": unique symbol;
    } & KtMap<any, any>["__doNotUseOrImplementIt"];
}
export declare namespace KtMutableMap {
    function fromJsMap<K, V>(map: ReadonlyMap<K, V>): KtMutableMap<K, V>;
}
export declare const initHook: {
    get(): any;
};
export declare abstract class Ksoup {
    static readonly getInstance: () => typeof Ksoup.$metadata$.type;
    private constructor();
}
export declare namespace Ksoup {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        abstract class type extends KtSingleton<constructor>() {
            private constructor();
        }
        abstract class constructor {
            parse(html: string, baseUri?: string): Document;
            parseWithParser(html: string, parser?: Parser, baseUri?: string): Document;
            parseXml(xml: string, baseUri?: string): Document;
            parseBodyFragment(bodyHtml: string, baseUri?: string): Document;
            clean(bodyHtml: string, safelist?: any/* Safelist */, baseUri?: string, outputSettings?: Nullable<Document.OutputSettings>): string;
            isValid(bodyHtml: string, safelist?: any/* Safelist */): boolean;
            parseMetaData(element: Element): any/* MetaData */;
            parseMetaDataOfHtml(html: string, baseUri?: string, interceptor?: Nullable<(head: Element, metaData: any/* MetaData */) => void>): any/* MetaData */;
            parseMetaDataInternal(baseUri: string, title: Nullable<string>, selectFirst: (query: string) => Nullable<Element>): any/* MetaData */;
            private constructor();
        }
    }
}
export declare class Attribute /* implements KtMap.Entry<string, Nullable<string>>, KCloneable<Attribute> */ {
    constructor(key: string, value: Nullable<string>, parent: Nullable<Attributes>);
    get attributeKey(): string;
    set attributeKey(value: string);
    get attributeValue(): Nullable<string>;
    set attributeValue(value: Nullable<string>);
    get parent(): Nullable<Attributes>;
    set parent(value: Nullable<Attributes>);
    setKey(key: string): void;
    hasDeclaredValue(): boolean;
    setValue(newValue: Nullable<string>): string;
    prefix(): string;
    localName(): string;
    namespace(): string;
    html(): string;
    sourceRange(): Range.AttributeRange;
    toString(): string;
    equals(other: Nullable<any>): boolean;
    hashCode(): number;
    isDataAttribute(): boolean;
    isInternal(): boolean;
}
export declare namespace Attribute {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Attribute;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                htmlNoValidate(key: string, value: Nullable<string>, accum: any/* QuietAppendable */, out: Document.OutputSettings): void;
                getValidKey(key: string, syntax: Document.OutputSettings.Syntax): Nullable<string>;
                createFromEncoded(unencodedKey: string, encodedValue: string): Attribute;
                isBooleanAttribute(key: string): boolean;
                private constructor();
            }
        }
    }
}
export declare class Attributes /* implements Iterable<Attribute>, KCloneable<Attributes> */ {
    constructor();
    get _size(): number;
    set _size(value: number);
    indexOfKey(key: string): number;
    get(key: string): string;
    attribute(key: Nullable<string>): Nullable<Attribute>;
    getIgnoreCase(key: string): string;
    add(key: string, value: Nullable<string>): Attributes;
    put(key: string, value: Nullable<string>): Attributes;
    userData(): KtMutableMap<string, any>;
    hasUserData(): boolean;
    getUserDataByKey(key: string): Nullable<any>;
    putUserData(key: string, value: Nullable<any>): Attributes;
    putIgnoreCase(key: string, value: Nullable<string>): void;
    putBoolean(key: string, value: boolean): Attributes;
    putAttribute(attribute: Attribute): Attributes;
    remove(key: string): void;
    removeIgnoreCase(key: string): void;
    hasKey(key: string): boolean;
    hasKeyIgnoreCase(key: string): boolean;
    hasDeclaredValueForKey(key: string): boolean;
    hasDeclaredValueForKeyIgnoreCase(key: string): boolean;
    size(): number;
    isEmpty(): boolean;
    addAll(incoming: Attributes): void;
    sourceRange(key: string): Range.AttributeRange;
    getRanges(): Nullable<KtMutableMap<string, Range.AttributeRange>>;
    asList(): KtList<Attribute>;
    dataset(): Attributes.Dataset;
    html(): string;
    toString(): string;
    equals(other: Nullable<any>): boolean;
    hashCode(): number;
    normalize(): void;
    deduplicate(settings: ParseSettings): number;
}
export declare namespace Attributes {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Attributes;
    }
    class Dataset {
        constructor(attributes: Attributes);
        get size(): number;
        set(key: string, value: string): Nullable<string>;
        get(key: string): Nullable<string>;
        remove(key: string): void;
    }
    namespace Dataset {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Dataset;
        }
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                get dataPrefix(): string;
                checkNotNull(value: Nullable<any>): string;
                internalKey(key: string): string;
                isInternalKey(key: string): boolean;
                private constructor();
            }
        }
    }
}
export declare class Document extends Element.$metadata$.constructor {
    constructor(namespace: string, location: Nullable<string>, parser?: Parser);
    location(): Nullable<string>;
    documentType(): Nullable<Node>/* Nullable<DocumentType> */;
    head(): Element;
    headOrNull(): Nullable<Element>;
    body(): Element;
    forms(): KtList<Element/* FormElement */>;
    expectForm(cssQuery: string): Nullable<Element>/* Nullable<FormElement> */;
    title(): string;
    setTitle(title: string): void;
    createElement(tagName: string): Element;
    outerHtml(): string;
    setText(text: string): Element;
    nodeName(): string;
    setCharset(charset: any/* Charset */): void;
    charset(): any/* Charset */;
    shallowClone(): Document;
    outputSettings(): Document.OutputSettings;
    setOutputSettings(outputSettings: Document.OutputSettings): Document;
    quirksMode(): Document.QuirksMode;
    setQuirksMode(quirksMode: Document.QuirksMode): Document;
    parser(): Parser;
    setParser(parser: Parser): Document;
}
export declare namespace Document {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Document;
    }
    class OutputSettings /* implements KCloneable<Document.OutputSettings> */ {
        constructor(escapeMode?: any/* Entities.$metadata$.type.EscapeMode */, charset?: any/* Charset */, prettyPrint?: boolean, outline?: boolean, indentAmount?: number, maxPaddingWidth?: number, syntax?: Document.OutputSettings.Syntax);
        escapeMode(): any/* Entities.$metadata$.type.EscapeMode */;
        setEscapeMode(escapeMode: any/* Entities.$metadata$.type.EscapeMode */): Document.OutputSettings;
        charset(): any/* Charset */;
        setCharset(charset: string): Document.OutputSettings;
        syntax(): Document.OutputSettings.Syntax;
        setSyntax(syntax: Document.OutputSettings.Syntax): Document.OutputSettings;
        prettyPrint(): boolean;
        setPrettyPrint(pretty: boolean): Document.OutputSettings;
        outline(): boolean;
        setOutline(outlineMode: boolean): Document.OutputSettings;
        indentAmount(): number;
        setIndentAmount(indentAmount: number): Document.OutputSettings;
        maxPaddingWidth(): number;
        setMaxPaddingWidth(maxPaddingWidth: number): Document.OutputSettings;
        copy(escapeMode?: any/* Entities.$metadata$.type.EscapeMode */, charset?: any/* Charset */, prettyPrint?: boolean, outline?: boolean, indentAmount?: number, maxPaddingWidth?: number, syntax?: Document.OutputSettings.Syntax): Document.OutputSettings;
        toString(): string;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace OutputSettings {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => OutputSettings;
        }
        abstract class Syntax {
            private constructor();
            static get html(): Document.OutputSettings.Syntax & {
                get name(): "html";
                get ordinal(): 0;
            };
            static get xml(): Document.OutputSettings.Syntax & {
                get name(): "xml";
                get ordinal(): 1;
            };
            static values(): [typeof Document.OutputSettings.Syntax.html, typeof Document.OutputSettings.Syntax.xml];
            static valueOf(value: string): Document.OutputSettings.Syntax;
            get name(): "html" | "xml";
            get ordinal(): 0 | 1;
        }
        namespace Syntax {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => Syntax;
            }
        }
    }
    abstract class QuirksMode {
        private constructor();
        static get noQuirks(): Document.QuirksMode & {
            get name(): "noQuirks";
            get ordinal(): 0;
        };
        static get quirks(): Document.QuirksMode & {
            get name(): "quirks";
            get ordinal(): 1;
        };
        static get limitedQuirks(): Document.QuirksMode & {
            get name(): "limitedQuirks";
            get ordinal(): 2;
        };
        static values(): [typeof Document.QuirksMode.noQuirks, typeof Document.QuirksMode.quirks, typeof Document.QuirksMode.limitedQuirks];
        static valueOf(value: string): Document.QuirksMode;
        get name(): "noQuirks" | "quirks" | "limitedQuirks";
        get ordinal(): 0 | 1 | 2;
    }
    namespace QuirksMode {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => QuirksMode;
        }
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                createShell(baseUri: string): Document;
                private constructor();
            }
        }
    }
}
export declare class Element extends Node.$metadata$.constructor /* implements Iterable<Element> */ {
    private constructor();
    get _tag(): Tag;
    set _tag(value: Tag);
    get _childNodes(): Element.Companion.NodeList;
    set _childNodes(value: Element.Companion.NodeList);
    hasChildNodes(): boolean;
    ensureChildNodes(): KtMutableList<Node>;
    hasAttributes(): boolean;
    attributes(): Attributes;
    baseUri(): string;
    doSetBaseUri(baseUri: Nullable<string>): void;
    childNodeSize(): number;
    nodeName(): string;
    tagName(): string;
    normalName(): string;
    elementIs(normalName: Nullable<string>, namespace: string): boolean;
    tag(): Tag;
    setTag(tag: Tag): Element;
    isBlock(): boolean;
    id(): string;
    setId(id: string): Element;
    setAttribute(attributeKey: string, attributeValue: Nullable<string>): Element;
    setBooleanAttr(attributeKey: string, attributeValue: boolean): Element;
    attribute(key: Nullable<string>): Nullable<Attribute>;
    dataset(): Attributes.Dataset;
    parent(): Nullable<Element>;
    parents(): Elements;
    child(index: number): Element;
    childrenSize(): number;
    children(): Elements;
    childElementsList(): KtList<Element>;
    cachedChildren(): Nullable<KtList<Element>>;
    stream(): any/* Sequence<Element> */;
    textNodes(): KtList<Node/* TextNode */>;
    dataNodes(): KtList<Node/* DataNode */>;
    select(cssQuery: string): Elements;
    selectStream(cssQuery: string): any/* Sequence<Element> */;
    selectFirst(cssQuery: string): Nullable<Element>;
    expectFirst(cssQuery: string): Element;
    selectNodes(cssQuery: string): Nodes<Node>;
    selectFirstNode<T extends Node>(cssQuery: string, type: any/* KClass<T> */): Nullable<T>;
    expectFirstNode<T extends Node>(cssQuery: string, type: any/* KClass<T> */): T;
    is(cssQuery: string): boolean;
    closest(cssQuery: string): Nullable<Element>;
    appendChild(child: Node): Element;
    appendChildren(children: any/* Collection<Node> */): Element;
    appendTo(parent: Element): Element;
    prependChild(child: Node): Element;
    prependChildren(children: any/* Collection<Node> */): Element;
    insertChildren(index: number, children: any/* Collection<Node> */): Element;
    appendElement(tagName: string, namespace?: string): Element;
    prependElement(tagName: string, namespace?: string): Element;
    appendText(text: string): Element;
    prependText(text: string): Element;
    append(html: string): Element;
    prepend(html: string): Element;
    before(html: string): Element;
    beforeNode(node: Node): Element;
    after(html: string): Element;
    afterNode(node: Node): Element;
    empty(): Element;
    wrap(html: string): Element;
    cssSelector(): string;
    siblingElements(): Elements;
    nextElementSiblings(): Elements;
    previousElementSiblings(): Elements;
    firstElementSibling(): Nullable<Element>;
    elementSiblingIndex(): number;
    lastElementSibling(): Nullable<Element>;
    firstElementChild(): Nullable<Element>;
    lastElementChild(): Nullable<Element>;
    getElementsByTag(tagName: Nullable<string>): Elements;
    getElementById(id: string): Nullable<Element>;
    getElementsByClass(className: string): Elements;
    getElementsByAttribute(key: string): Elements;
    getElementsByAttributeStarting(keyPrefix: string): Elements;
    getElementsByAttributeValue(key: string, value: string): Elements;
    getElementsByAttributeValueNot(key: string, value: string): Elements;
    getElementsByAttributeValueStarting(key: string, valuePrefix: string): Elements;
    getElementsByAttributeValueEnding(key: string, valueSuffix: string): Elements;
    getElementsByAttributeValueContaining(key: string, match: string): Elements;
    getElementsByAttributeValueMatching(key: string, regex: any/* Regex */): Elements;
    getElementsByIndexLessThan(index: number): Elements;
    getElementsByIndexGreaterThan(index: number): Elements;
    getElementsByIndexEquals(index: number): Elements;
    getElementsContainingText(searchText: string): Elements;
    getElementsMatchingText(regex: any/* Regex */): Elements;
    getElementsMatchingOwnText(regex: any/* Regex */): Elements;
    getAllElements(): Elements;
    text(): string;
    wholeText(): string;
    nodeValue(): string;
    wholeOwnText(): string;
    ownText(): string;
    setText(text: string): Element;
    hasText(): boolean;
    data(): string;
    className(): string;
    classNames(): KtMutableSet<string>;
    setClassNames(classNames: KtSet<string>): Element;
    hasClass(className: string): boolean;
    addClass(className: string): Element;
    removeClass(className: string): Element;
    toggleClass(className: string): Element;
    value(): string;
    setValue(value: string): Element;
    endSourceRange(): Range;
    html(): string;
    setHtml(html: string): Element;
    protected doClone(parent: Nullable<Node>): Element;
    shallowClone(): Element;
    clearAttributes(): Element;
    removeAttr(attributeKey: string): Element;
    root(): Element;
    traverse(nodeVisitor: any/* NodeVisitor */): Element;
    forEachNode(action: any/* Consumer<Node> */): Element;
    forEach(action: (p0: Element) => void): void;
    filter(nodeFilter: any/* NodeFilter */): Element;
    reindexChildren(): void;
    invalidateChildren(): void;
    hasValidChildren(): boolean;
}
export declare namespace Element {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Element;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        class NodeList implements KtMutableList<Node> {
            constructor(initialCapacity: number);
            get validChildren(): boolean;
            set validChildren(value: boolean);
            modCount(): number;
            incrementMod(): void;
            asJsArrayView(): Array<Node>;
            asJsReadonlyArrayView(): ReadonlyArray<Node>;
            readonly __doNotUseOrImplementIt: KtMutableList<any>["__doNotUseOrImplementIt"];
        }
        namespace NodeList {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => NodeList;
            }
        }
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                get BaseUriKey(): string;
                searchUpForAttribute(start: Element, key: string): Nullable<string>;
                preserveWhitespace(node: Nullable<Node>): boolean;
                private constructor();
            }
        }
    }
}
export declare abstract class Node /* implements KCloneable<Node> */ {
    protected constructor();
    get _parentNode(): Nullable<Element>;
    set _parentNode(value: Nullable<Element>);
    get _siblingIndex(): number;
    set _siblingIndex(value: number);
    abstract nodeName(): string;
    normalName(): string;
    nodeValue(): string;
    nameIs(normalName: Nullable<string>): boolean;
    parentNameIs(normalName: string): boolean;
    parentElementIs(normalName: string, namespace: string): boolean;
    abstract hasAttributes(): boolean;
    hasParent(): boolean;
    attr(attributeKey: string): string;
    abstract attributes(): Attributes;
    attributesSize(): number;
    setAttribute(attributeKey: string, attributeValue: Nullable<string>): Node;
    hasAttr(attributeKey: string): boolean;
    removeAttr(attributeKey: string): Node;
    clearAttributes(): Node;
    abstract baseUri(): string;
    protected abstract doSetBaseUri(baseUri: Nullable<string>): void;
    setBaseUri(baseUri: string): void;
    absUrl(attributeKey: string): string;
    protected abstract ensureChildNodes(): KtMutableList<Node>;
    childNode(index: number): Node;
    childNodes(): KtList<Node>;
    childNodesCopy(): KtList<Node>;
    abstract childNodeSize(): number;
    protected childNodesAsArray(): Array<Node>;
    abstract empty(): Nullable<Node>;
    parent(): Nullable<Node>;
    parentElement(): Nullable<Element>;
    parentNode(): Nullable<Node>;
    root(): Node;
    ownerDocument(): Nullable<Document>;
    remove(): void;
    before(html: string): Node;
    beforeNode(node: Node): Node;
    after(html: string): Node;
    afterNode(node: Node): Node;
    wrap(html: string): Node;
    unwrap(): Nullable<Node>;
    replaceWith(inNode: Node): void;
    protected setParentNode(parentNode: Node): void;
    replaceChild(out: Node, inNode: Node): void;
    protected removeChild(out: Node): void;
    addChildren(children: Array<Node>): void;
    addChildrenAt(index: number, children: Array<Node>): void;
    protected reparentChild(child: Node): void;
    siblingNodes(): KtList<Node>;
    nextSibling(): Nullable<Node>;
    previousSibling(): Nullable<Node>;
    siblingIndex(): number;
    firstChild(): Nullable<Node>;
    lastChild(): Nullable<Node>;
    firstSibling(): Nullable<Node>;
    lastSibling(): Nullable<Node>;
    nextElementSibling(): Nullable<Element>;
    previousElementSibling(): Nullable<Element>;
    traverse(nodeVisitor: any/* NodeVisitor */): Node;
    forEachNode(action: any/* Consumer<Node> */): Node;
    filter(nodeFilter: any/* NodeFilter */): Node;
    nodeStream(): any/* Sequence<Node> */;
    outerHtml(): string;
    sourceRange(): Range;
    toString(): string;
    equals(other: Nullable<any>): boolean;
    hashCode(): number;
    hasSameValue(o: Nullable<any>): boolean;
    shallowClone(): Node;
    protected doClone(parent: Nullable<Node>): Node;
}
export declare namespace Node {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Node;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                get EmptyNodes(): KtMutableList<Node>;
                get EmptyString(): string;
                private constructor();
            }
        }
    }
}
export declare class Range {
    constructor(start: Range.Position, end: Range.Position);
    start(): Range.Position;
    end(): Range.Position;
    startPos(): number;
    endPos(): number;
    isTracked(): boolean;
    isImplicit(): boolean;
    toString(): string;
    copy(start?: Range.Position, end?: Range.Position): Range;
    hashCode(): number;
    equals(other: Nullable<any>): boolean;
}
export declare namespace Range {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Range;
    }
    class Position {
        constructor(pos: number, lineNumber: number, columnNumber: number);
        pos(): number;
        lineNumber(): number;
        columnNumber(): number;
        isTracked(): boolean;
        toString(): string;
        copy(pos?: number, lineNumber?: number, columnNumber?: number): Range.Position;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace Position {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => Position;
        }
    }
    class AttributeRange {
        constructor(nameRange: Range, valueRange: Range);
        nameRange(): Range;
        valueRange(): Range;
        toString(): string;
        copy(nameRange?: Range, valueRange?: Range): Range.AttributeRange;
        hashCode(): number;
        equals(other: Nullable<any>): boolean;
    }
    namespace AttributeRange {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            const constructor: abstract new () => AttributeRange;
        }
        abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
            private constructor();
        }
        namespace Companion {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                abstract class constructor {
                    get UntrackedAttr(): Range.AttributeRange;
                    private constructor();
                }
            }
        }
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                of(node: Node, start: boolean): Range;
                private constructor();
            }
        }
    }
}
export declare class ParseSettings {
    constructor(preserveTagCase: boolean, preserveAttributeCase: boolean);
    preserveTagCase(): boolean;
    preserveAttributeCase(): boolean;
    normalizeTag(name: string): string;
    normalizeAttribute(name: string): string;
    normalizeAttributes(attributes: Nullable<Attributes>): void;
}
export declare namespace ParseSettings {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ParseSettings;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                get htmlDefault(): ParseSettings;
                get preserveCase(): ParseSettings;
                normalName(name: Nullable<string>): string;
                private constructor();
            }
        }
    }
}
export declare class Parser /* implements KCloneable<Parser> */ {
    private constructor();
    get isTrackPosition(): boolean;
    set isTrackPosition(value: boolean);
    static withTreeBuilder(treeBuilder: any/* TreeBuilder */): Parser;
    newInstance(): Parser;
    parseInput(input: string, baseUri: string): Document;
    parseReaderInput(reader: any/* Reader */, baseUri: string): Document;
    parseFragmentInput(fragment: string, context: Nullable<Element>, baseUri: string): KtList<Node>;
    parseReaderFragmentInput(fragment: any/* Reader */, context: Nullable<Element>, baseUri: string): KtList<Node>;
    getTreeBuilder(): any/* TreeBuilder */;
    isTrackErrors(): boolean;
    setTrackErrors(maxErrors: number): Parser;
    getErrors(): KtMutableList<any/* ParseError */>/* ParseErrorList */;
    setTrackPosition(trackPosition: boolean): Parser;
    setSettings(settings: ParseSettings): Parser;
    settings(): ParseSettings;
    setMaxDepth(maxDepth: number): Parser;
    getMaxDepth(): number;
    setTagSet(tagSet: any/* TagSet */): Parser;
    tagSet(): any/* TagSet */;
    unescape(string: string, inAttribute: boolean): string;
    defaultNamespace(): string;
}
export declare namespace Parser {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Parser;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                get NamespaceHtml(): string;
                get NamespaceXml(): string;
                get NamespaceMathml(): string;
                get NamespaceSvg(): string;
                parse(html: string, baseUri: string): Document;
                parseFragment(fragmentHtml: string, context: Nullable<Element>, baseUri: string, errorList?: Nullable<KtMutableList<any/* ParseError */>>/* Nullable<ParseErrorList> */): KtList<Node>;
                parseXmlFragment(fragmentXml: string, baseUri: string): KtList<Node>;
                parseBodyFragment(bodyHtml: string, baseUri: string): Document;
                unescapeEntities(html: string, inAttribute: boolean): string;
                htmlParser(): Parser;
                xmlParser(): Parser;
                private constructor();
            }
        }
    }
}
export declare class Tag /* implements KCloneable<Tag> */ {
    constructor(tagName: string, normalName: string, namespace: string);
    get tagName(): string;
    set tagName(value: string);
    get normalName(): string;
    set normalName(value: string);
    get namespace(): string;
    set namespace(value: string);
    get options(): number;
    set options(value: number);
    name(): string;
    hashCode(): number;
    setName(tagName: string): Tag;
    prefix(): string;
    localName(): string;
    isBlock(): boolean;
    isEmpty(): boolean;
    isFormSubmittable(): boolean;
    setSeenSelfClose(): void;
    textState(): Nullable<any>/* Nullable<TokeniserState> */;
    getNormalName(): string;
    getNamespace(): string;
    setNamespace(namespace: string): Tag;
    set(option: number): Tag;
    is(option: number): boolean;
    clear(option: number): Tag;
    /** @deprecated setting is only used within the Printer. Will be removed in a future release. */
    formatAsBlock(): boolean;
    isInline(): boolean;
    isSelfClosing(): boolean;
    isKnownTag(): boolean;
    preserveWhitespace(): boolean;
    toString(): string;
    equals(other: Nullable<any>): boolean;
    copy(tagName?: string, normalName?: string, namespace?: string): Tag;
}
export declare namespace Tag {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Tag;
    }
    abstract class Companion extends KtSingleton<Companion.$metadata$.constructor>() {
        private constructor();
    }
    namespace Companion {
        /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
        namespace $metadata$ {
            abstract class constructor {
                get Known(): number;
                get Void(): number;
                get Block(): number;
                get InlineContainer(): number;
                get SelfClose(): number;
                get SeenSelfClose(): number;
                get PreserveWhitespace(): number;
                get RcData(): number;
                get Data(): number;
                get FormSubmittable(): number;
                valueOf(tagName: string, settings: ParseSettings): Tag;
                isKnownTag(tagName: string): boolean;
                private constructor();
            }
        }
    }
}
export declare class Elements extends Nodes.$metadata$.constructor<Element> {
    constructor(delegateList?: KtMutableList<Element>/* ArrayList<Element> */);
    clone(): Elements;
    asList(): KtMutableList<Element>/* ArrayList<Element> */;
    attr(attributeKey: string): string;
    hasAttr(attributeKey: string): boolean;
    eachAttr(attributeKey: string): KtList<string>;
    setAttr(attributeKey: string, attributeValue: Nullable<string>): Elements;
    removeAttr(attributeKey: string): Elements;
    addClass(className: string): Elements;
    removeClass(className: string): Elements;
    toggleClass(className: string): Elements;
    hasClass(className: string): boolean;
    value(): string;
    setValue(value: string): Elements;
    text(): string;
    hasText(): boolean;
    eachText(): KtList<string>;
    html(): string;
    tagName(tagName: string): Elements;
    setHtml(html: string): Elements;
    prepend(html: string): Elements;
    append(html: string): Elements;
    before(html: string): Elements;
    after(html: string): Elements;
    wrap(html: string): Elements;
    unwrap(): Elements;
    empty(): Elements;
    remove(): Elements;
    select(query: string): Elements;
    selectFirst(cssQuery: string): Nullable<Element>;
    expectFirst(cssQuery: string): Element;
    not(query: string): Elements;
    eq(index: number): Elements;
    is(query: string): boolean;
    parents(): Elements;
    first(): Nullable<Element>;
    last(): Nullable<Element>;
    traverse(nodeVisitor: any/* NodeVisitor */): Elements;
    filter(nodeFilter: any/* NodeFilter */): Elements;
    forms(): KtList<Element/* FormElement */>;
    comments(): KtList<Node/* Comment */>;
    textNodes(): KtList<Node/* TextNode */>;
    dataNodes(): KtList<Node/* DataNode */>;
}
export declare namespace Elements {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => Elements;
    }
}
export declare class Nodes<T extends Node> implements KtMutableList<T> {
    constructor(delegateList?: KtMutableList<T>);
    equals(other: Nullable<any>): boolean;
    hashCode(): number;
    clone(): Nodes<T>;
    asList(): KtMutableList<T>/* ArrayList<T> */;
    remove(): Nodes<T>;
    outerHtml(): string;
    toString(): string;
    before(html: string): Nodes<T>;
    after(html: string): Nodes<T>;
    wrap(html: string): Nodes<T>;
    first(): Nullable<T>;
    last(): Nullable<T>;
    deselect(index: number): T;
    deselectAll(): void;
    removeIf(predicate: (p0: T) => boolean): boolean;
    replaceAll(operator: (p0: T) => T): void;
    asJsArrayView(): Array<T>;
    asJsReadonlyArrayView(): ReadonlyArray<T>;
    readonly __doNotUseOrImplementIt: KtMutableList<any>["__doNotUseOrImplementIt"];
}
export declare namespace Nodes {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new <T extends Node>() => Nodes<T>;
    }
}
export declare class CommandOutcome {
    constructor(ok: boolean, output: string, durationMs: number, changedPage: boolean);
    get ok(): boolean;
    get output(): string;
    get durationMs(): number;
    get changedPage(): boolean;
}
export declare namespace CommandOutcome {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CommandOutcome;
    }
}
export declare class RefBox {
    constructor(ref: string, role: string, name: string, x: number, y: number, width: number, height: number);
    get ref(): string;
    get role(): string;
    get name(): string;
    get x(): number;
    get y(): number;
    get width(): number;
    get height(): number;
}
export declare namespace RefBox {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RefBox;
    }
}
export declare class KmpwrightDemo {
    constructor(frame: HTMLIFrameElement);
    run(command: string): Promise<CommandOutcome>;
    refs(): Promise<Array<RefBox>>;
    dispose(): void;
}
export declare namespace KmpwrightDemo {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KmpwrightDemo;
    }
}