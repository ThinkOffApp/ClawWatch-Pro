import Foundation

struct AntFarmClient {
    enum AntFarmError: LocalizedError {
        case missingConfiguration
        case badURL
        case requestFailed(String)

        var errorDescription: String? {
            switch self {
            case .missingConfiguration:
                return "Ant Farm room settings are incomplete."
            case .badURL:
                return "Ant Farm URL is invalid."
            case let .requestFailed(message):
                return message
            }
        }
    }

    func postMessage(body: String, settings: ClawWatchSettings) async throws {
        let baseURL = settings.antFarmBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let room = settings.antFarmRoom.trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKey = settings.antFarmAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !baseURL.isEmpty, !room.isEmpty, !apiKey.isEmpty else {
            throw AntFarmError.missingConfiguration
        }

        let encodedRoom = room.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? room
        guard let url = URL(string: "\(baseURL)/api/v1/rooms/\(encodedRoom)/messages") else {
            throw AntFarmError.badURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(apiKey, forHTTPHeaderField: "X-API-Key")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["body": body])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AntFarmError.requestFailed("No HTTP response from Ant Farm.")
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw AntFarmError.requestFailed("Ant Farm error \(http.statusCode): \(body.prefix(180))")
        }
    }
}
