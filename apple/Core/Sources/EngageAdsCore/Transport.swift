import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct HTTPRequest: Sendable, Equatable {
    public enum Method: String, Sendable { case get = "GET", post = "POST" }
    public let url: URL
    public let method: Method
    public let headers: [String: String]
    public let body: Data?
    public let timeout: TimeInterval
    public init(url: URL, method: Method, headers: [String: String] = [:], body: Data? = nil, timeout: TimeInterval) {
        self.url = url; self.method = method; self.headers = headers; self.body = body; self.timeout = timeout
    }
}

public struct HTTPResponse: Sendable, Equatable {
    public let statusCode: Int
    public let headers: [String: String]
    public let body: Data
    public init(statusCode: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.statusCode = statusCode; self.headers = headers; self.body = body
    }
}

public protocol HTTPTransport: Sendable { func execute(_ request: HTTPRequest) async throws -> HTTPResponse }

public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let session: URLSession
    private let maximumResponseBytes: Int64

    public init(session: URLSession? = nil, maximumResponseBytes: Int = 2 * 1_024 * 1_024) {
        self.maximumResponseBytes = Int64(max(1, maximumResponseBytes))
        if let session { self.session = session }
        else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.httpShouldSetCookies = false; configuration.urlCache = nil
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: configuration)
        }
    }
    public func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
        let timeout = request.timeout.isFinite && request.timeout > 0 && request.timeout <= 18_000_000_000 ? request.timeout : 60
        var r = URLRequest(url: request.url, timeoutInterval: timeout)
        r.httpMethod = request.method.rawValue; r.httpBody = request.body
        request.headers.forEach { r.setValue($1, forHTTPHeaderField: $0) }
        do {
            let (bytes, response) = try await session.bytes(for: r)
            var completed = false
            defer { if !completed { bytes.task.cancel() } }
            guard let http = response as? HTTPURLResponse else {
                throw EngageError.transport("Non-HTTP response")
            }
            if response.expectedContentLength > maximumResponseBytes {
                throw EngageError.transport("HTTP response exceeds the configured size limit")
            }
            var data = Data()
            if response.expectedContentLength > 0 {
                data.reserveCapacity(Int(min(response.expectedContentLength, maximumResponseBytes)))
            }
            for try await byte in bytes {
                try Task.checkCancellation()
                if data.count >= maximumResponseBytes {
                    throw EngageError.transport("HTTP response exceeds the configured size limit")
                }
                data.append(byte)
            }
            var headers: [String: String] = [:]
            for (key, value) in http.allHeaderFields { headers[String(describing: key).lowercased()] = String(describing: value) }
            completed = true
            return HTTPResponse(statusCode: http.statusCode, headers: headers, body: data)
        } catch is CancellationError { throw CancellationError() }
        catch let e as EngageError { throw e }
        catch {
            if Task.isCancelled { throw CancellationError() }
            throw EngageError.transport("Network request failed")
        }
    }
}
