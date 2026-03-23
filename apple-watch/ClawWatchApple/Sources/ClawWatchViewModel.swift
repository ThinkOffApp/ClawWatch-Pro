import Foundation

@MainActor
final class ClawWatchViewModel: ObservableObject {
    @Published var prompt = ""
    @Published var roomDraft = ""
    @Published private(set) var transcript: [ConversationItem] = []
    @Published private(set) var state: ClawState = .idle
    @Published private(set) var mood: ClawMood = .neutral
    @Published private(set) var statusText = "Ready"
    @Published private(set) var lastResponse = ""
    @Published private(set) var lastError: String?
    @Published var isSettingsPresented = false

    private let anthropicClient = AnthropicClient()
    private let antFarmClient = AntFarmClient()

    func sendPrompt() {
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let settings = ClawWatchSettings.load()
        transcript.append(ConversationItem(role: .user, text: trimmed))
        prompt = ""
        lastError = nil
        state = needsSearch(for: trimmed) ? .searching : .thinking
        statusText = state == .searching ? "Searching" : "Thinking"

        Task {
            do {
                let reply = try await anthropicClient.send(prompt: trimmed, settings: settings)
                await MainActor.run {
                    finishReply(reply)
                }
            } catch {
                await MainActor.run {
                    fail(error.localizedDescription)
                }
            }
        }
    }

    func useQuickPrompt(_ promptKind: WatchQuickPrompt) {
        prompt = promptKind.prompt
        sendPrompt()
    }

    func postDraftToRoom() {
        let trimmed = roomDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        postToRoom(trimmed)
        roomDraft = ""
    }

    func postLastReplyToRoom() {
        let reply = lastResponse.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !reply.isEmpty else { return }
        postToRoom(reply)
    }

    func openSettings() {
        isSettingsPresented = true
    }

    func bubbleStyle() -> BubbleStyle {
        switch state {
        case .listening, .thinking, .searching:
            return .thought
        case .speaking:
            return .speech
        case .idle, .error:
            return .thought
        }
    }

    private func postToRoom(_ text: String) {
        let settings = ClawWatchSettings.load()
        lastError = nil
        state = .thinking
        statusText = "Posting"
        Task {
            do {
                try await antFarmClient.postMessage(body: text, settings: settings)
                await MainActor.run {
                    transcript.append(ConversationItem(role: .system, text: "Posted to \(settings.antFarmRoom)."))
                    statusText = "Posted"
                    state = .idle
                }
            } catch {
                await MainActor.run {
                    fail(error.localizedDescription)
                }
            }
        }
    }

    private func finishReply(_ reply: String) {
        lastResponse = reply
        transcript.append(ConversationItem(role: .claw, text: reply))
        mood = inferMood(from: reply)
        state = .speaking
        statusText = "Speaking"

        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.4))
            await MainActor.run {
                guard let self else { return }
                if self.state == .speaking {
                    self.state = .idle
                    self.statusText = "Ready"
                }
            }
        }
    }

    private func fail(_ message: String) {
        lastError = message
        transcript.append(ConversationItem(role: .system, text: message))
        state = .error
        statusText = "Problem"
    }

    private func inferMood(from text: String) -> ClawMood {
        let lower = text.lowercased()
        if lower.contains("!") || lower.contains("amazing") || lower.contains("great") {
            return .excited
        }
        if ["love", "glad", "nice", "wonderful", "happy"].contains(where: lower.contains) {
            return .cheerful
        }
        if ["maybe", "curious", "hmm", "play", "fun"].contains(where: lower.contains) {
            return .playful
        }
        if ["sorry", "need", "important", "careful", "warning"].contains(where: lower.contains) {
            return .serious
        }
        if ["gentle", "calm", "rest", "steady", "quiet"].contains(where: lower.contains) {
            return .calm
        }
        return .neutral
    }

    private func needsSearch(for text: String) -> Bool {
        let lower = text.lowercased()
        return [
            "today", "latest", "current", "news", "weather",
            "price", "recent", "score", "search"
        ].contains(where: lower.contains)
    }
}
