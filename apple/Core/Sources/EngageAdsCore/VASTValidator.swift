import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

enum VASTValidator {
    private static let maximumMarkupBytes = 1_024 * 1_024

    static func validate(_ markup: String) throws {
        guard let data = markup.data(using: .utf8) else { throw EngageError.malformedResponse("VAST is not UTF-8") }
        guard data.count <= maximumMarkupBytes else { throw EngageError.malformedResponse("VAST markup exceeds the size limit") }
        guard markup.range(of: "<!DOCTYPE", options: .caseInsensitive) == nil,
              markup.range(of: "<!ENTITY", options: .caseInsensitive) == nil else {
            throw EngageError.malformedResponse("VAST document type and entity declarations are not supported")
        }
        let delegate = RootDelegate(); let parser = XMLParser(data: data); parser.delegate = delegate
        guard parser.parse(), let root = delegate.root, root.caseInsensitiveCompare("VAST") == .orderedSame || root.caseInsensitiveCompare("VMAP") == .orderedSame else {
            throw EngageError.malformedResponse("Video markup must be a well-formed VAST or VMAP document")
        }
        guard delegate.containsAd else { throw EngageError.noFill }
    }
}

private final class RootDelegate: NSObject, XMLParserDelegate {
    private static let maximumNesting = 64
    var root: String?
    var containsAd = false
    private var depth = 0
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        depth += 1
        if depth > Self.maximumNesting { parser.abortParsing(); return }
        if root == nil { root = elementName }
        if elementName.caseInsensitiveCompare("Ad") == .orderedSame || elementName.caseInsensitiveCompare("AdSource") == .orderedSame { containsAd = true }
    }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        depth = max(0, depth - 1)
    }
}
